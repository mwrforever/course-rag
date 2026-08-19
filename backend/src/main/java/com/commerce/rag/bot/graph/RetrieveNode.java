package com.commerce.rag.bot.graph;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.hook.DocumentAssemblerInterceptor;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import com.commerce.rag.bot.tool.TypedQuery;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.retrieval.ContextBuilderService;
import com.commerce.rag.retrieval.CourseNameMapper;
import com.commerce.rag.service.AttachmentLocalSearchService;
import com.commerce.rag.service.AttachmentOrchestrator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

/**
 * 检索编排节点 —— S1 三节点图的第二节点（spec §1 链路图）
 *
 * <p>职责（仅 knowledge_question 分支触发）：
 * <ol>
 *   <li>从 State 读取 QueryPlan（queryUnderstandingNode 写入）</li>
 *   <li>filters.course_names → CourseNameMapper 确定性映射 course_id（同名全注入；
 *       匹配失败/为空 → null 全局检索，spec §2.3）</li>
 *   <li>每条重写查询构建 TypedQuery 并行混合检索（SearchKnowledgeTool 内完成：
 *       预取 → RRF 融合 → SHA256 内容去重 → Rerank 精排）</li>
 *   <li>ContextBuilderService 组装 &lt;document&gt;（system-document，空候选返回 null）</li>
 *   <li>读取 config.metadata() 中 worker 写入的附件上下文（键
 *       {@link AttachmentOrchestrator#KEY_ATTACHMENT_CONTEXT}，spec §5.1）：图片 caption
 *       直接注入、文档附件以用户原问题为查询向量做局部检索（AttachmentLocalSearchService，
 *       spec §5.4），经 buildUserDocument + appendUserDocument 合并为 &lt;user-document&gt;
 *       子块（本类方法 {@link #mergeUserDocument}）；系统检索为空但有附件上下文时，以仅含
 *       &lt;user-document&gt; 的 &lt;document&gt; shell 注入（{@link #buildEmptySystemDocument}，
 *       spec §5.4 两者合并注入）</li>
 *   <li>合并后的 document 文本写入 config.metadata()["document_context"]（{@link
 *       AttachmentOrchestrator#KEY_ATTACHMENT_CONTEXT} 同通道）——不写 State、不进 checkpoint
 *       （临时上下文，DocumentAssemblerInterceptor 瞬时注入）</li>
 * </ol>
 *
 * <p>失败降级：检索异常 → 不写 document，ReactAgent 直接回答并记日志；系统检索空结果 →
 * 无附件上下文时不写 document（ReactAgent 直接回答），有附件上下文时仍注入仅含
 * user-document 的 document shell（spec §5.4 附件语料不丢）。
 *
 * <p>本类为手写单构造器（5 依赖：SearchKnowledgeTool / CourseNameMapper / ContextBuilderService
 * / AttachmentLocalSearchService / EmbeddingModel，Spring 按单构造器自动注入，无需 @Autowired）。
 *
 * <p>注：本类与项目接口 {@link com.commerce.rag.bot.graph.OverAllState}（KEY_QUERY_PLAN
 * 定义处）同包，但 apply 签名需用框架的 {@code com.alibaba.cloud.ai.graph.OverAllState}
 * （显式 import 遮蔽同包同名类型，JLS 6.4.1），故常量以静态 import（{@code KEY_QUERY_PLAN}）
 * 方式引用项目接口成员。
 *
 * @author commerce-rag
 */
@Component
public class RetrieveNode implements AsyncNodeActionWithConfig {

    private static final Logger log = LoggerFactory.getLogger(RetrieveNode.class);

    /** 文档附件局部检索返回条数上限（spec §5.4 局部检索 Top-K） */
    private static final int LOCAL_SEARCH_TOP_K = 3;

    private final SearchKnowledgeTool searchKnowledgeTool;
    private final CourseNameMapper courseNameMapper;
    private final ContextBuilderService contextBuilderService;
    private final AttachmentLocalSearchService localSearchService;
    private final EmbeddingModel embeddingModel;

    public RetrieveNode(
            SearchKnowledgeTool searchKnowledgeTool,
            CourseNameMapper courseNameMapper,
            ContextBuilderService contextBuilderService,
            AttachmentLocalSearchService localSearchService,
            EmbeddingModel embeddingModel) {
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.courseNameMapper = courseNameMapper;
        this.contextBuilderService = contextBuilderService;
        this.localSearchService = localSearchService;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 节点执行 —— 检索编排并写入 document_context（不写 state）
     *
     * @param state  图状态（含 queryPlan）
     * @param config RunnableConfig（metadata 与 GraphRunner 贯穿全图共享，AgentLlmNode 读同一 Map）
     * @return 空增量 Map（检索结果不经 state）
     */
    @Override
    public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
        Optional<Object> planOpt = state.value(KEY_QUERY_PLAN);
        if (planOpt.isEmpty() || !(planOpt.get() instanceof QueryPlan plan)) {
            log.debug("retrieveNode: 无 QueryPlan，跳过检索");
            return CompletableFuture.completedFuture(Map.of());
        }
        // 仅 knowledge_question 触发检索；chat/unknown 直接对话（spec §1）
        if (plan.intent() != IntentType.KNOWLEDGE_QUESTION) {
            log.debug("retrieveNode: intent={}，不检索", plan.intent().name());
            return CompletableFuture.completedFuture(Map.of());
        }

        // 1. 原问题（检索说明 + 回答基准）
        String originalQuery = extractLastUserQuery(state);

        // 2. filters.course_names → course_id（降级全局：空列表 → null 不设过滤）
        List<String> courseIds = null;
        if (plan.filters() != null
                && plan.filters().courseNames() != null
                && !plan.filters().courseNames().isEmpty()) {
            List<String> mapped = courseNameMapper.mapCourseNames(plan.filters().courseNames());
            if (!mapped.isEmpty()) {
                courseIds = mapped;
            }
        }

        // 3. 每条重写查询构建 TypedQuery（courseIds 为 null 即全局检索）
        List<TypedQuery> queries = new ArrayList<>();
        if (plan.rewrittenQueries() != null) {
            for (String query : plan.rewrittenQueries()) {
                if (query != null && !query.isBlank()) {
                    queries.add(new TypedQuery(plan.intent(), query, courseIds));
                }
            }
        }
        if (queries.isEmpty()) {
            log.warn("retrieveNode: 无可用重写查询，跳过检索（ReactAgent 直接回答）");
            return CompletableFuture.completedFuture(Map.of());
        }

        // 4. 检索（并行 + RRF 融合 + SHA256 去重 + Rerank 在 SearchKnowledgeTool 内完成）
        List<KnowledgeChunk> chunks =
                searchKnowledgeTool.searchKnowledge(queries).chunks();
        // 附件上下文（worker 写入 metadata）提前读取：系统检索并入前即拿到，
        // 系统库无命中时仍须注入 <user-document>（spec §5.4 两者合并注入）
        AttachmentContext attachmentContext = readAttachmentContext(config);

        // 系统检索为空：仅当有附件上下文才组装仅含 <user-document> 的 document shell 注入
        // （ReactAgent 仍直接回答，但附件局部语料不丢，spec §5.4）
        if (chunks.isEmpty()) {
            String emptyShell = buildEmptySystemDocument(attachmentContext, originalQuery);
            if (emptyShell != null && !emptyShell.isBlank()) {
                config.metadata().ifPresent(m -> m.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, emptyShell));
            }
            log.info(
                    "retrieveNode: 检索结果为空（ReactAgent 直接回答）: intent={}, 重写={}条, 附件上下文注入={}",
                    plan.intent().name(),
                    queries.size(),
                    emptyShell != null);
            return CompletableFuture.completedFuture(Map.of());
        }

        // 5. 组装 <document>（system-document）并合并 <user-document>（附件上下文，spec §5.3/§5.4）
        String document = contextBuilderService.buildDocument(originalQuery, plan.rewrittenQueries(), chunks);
        if (document == null || document.isBlank()) {
            log.info("retrieveNode: document 组装为空，跳过注入");
            return CompletableFuture.completedFuture(Map.of());
        }
        // 合并附件 user-document 子块（无附件上下文/无命中则原样返回 systemDocument）
        // final 合并结果，供下方 lambda 捕获（document 已被上面赋值，非 effectively final）
        final String mergedDocument = mergeUserDocument(document, originalQuery, attachmentContext);
        config.metadata().ifPresent(m -> m.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, mergedDocument));
        log.info(
                "retrieveNode 完成: intent={}, 候选={}条, 注入 document（{} 字符）",
                plan.intent().name(),
                chunks.size(),
                mergedDocument.length());

        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * 合并附件 user-document 子块 —— 系统 document 非空时在 `</document>` 前并入（spec §5.3/§5.4）
     *
     * <p>无附件上下文/无命中 → 原样返回 systemDocument（不产生 user-document 子块）。
     *
     * @param document          既有 systemDocument（buildDocument 输出，非 null）
     * @param originalQuery     用户原问题（局部检索查询向量来源）
     * @param attachmentContext 附件上下文（null/无附件 → 原样返回既有 document）
     * @return 合并后的 document 文本
     */
    private String mergeUserDocument(String document, String originalQuery, AttachmentContext attachmentContext) {
        String userDocument = buildUserDocumentText(attachmentContext, originalQuery);
        // 无 user-document 子块（无附件上下文/无命中）→ 原样返回系统 document，不调用 appendUserDocument
        // （保持既有合并行为：无附件时不触发合并调用）
        if (userDocument == null || userDocument.isBlank()) {
            return document;
        }
        return contextBuilderService.appendUserDocument(document, userDocument);
    }

    /**
     * 计算 {@code <user-document>} 子块文本 —— 图片 caption 注入 + 文档附件局部检索（spec §5.3/§5.4）
     *
     * <p>流程：按附件 objectKey 逐个以用户原问题向量做局部检索（内存余弦 Top-K，
     * spec §5.4：附件文档不参与系统检索，仅作局部语料）→ buildUserDocument 组装。
     *
     * <p>查询向量 = 用户原问题（originalQuery）：图输入 UserMessage 即用户问题正式形态，含附件
     * caption 语境的「图片N:[caption]」前缀（spec §3.3 回答以原问题为准），纯图片走 chat/unknown
     * 不触发检索、纯文档无前缀、图片+文档混合时 caption 是有效附件语境，故不属污染。
     *
     * <p>容错：某附件分片列表为空 → search 返回空列表，docHits 不 put（组装容忍）；
     * captions 与 docHits 均无内容 → buildUserDocument 返回 null。
     *
     * @param attachmentContext 附件上下文（null/无任何附件 → 返回 null）
     * @param originalQuery     用户原问题（局部检索查询向量来源）
     * @return user-document 文本；无附件上下文/无任何可注入内容返回 null
     */
    private String buildUserDocumentText(AttachmentContext attachmentContext, String originalQuery) {
        if (attachmentContext == null || !attachmentContext.hasAny()) {
            return null;
        }
        // 存储附件 objectKey → 命中段落文本列表（检索有命中的 key 才放入）
        Map<String, List<String>> docHits = new LinkedHashMap<>();
        if (attachmentContext.documents() != null
                && !attachmentContext.documents().isEmpty()) {
            // 用户原问题作局部检索查询向量（spec §5.4：附件文档内容不参与系统检索查询）
            float[] queryVector = embeddingModel.embed(originalQuery);
            for (Map.Entry<String, List<DocumentLocalChunk>> entry :
                    attachmentContext.documents().entrySet()) {
                // 某附件分片列表可能为空 → search 返回空列表，docHits 不 put（组装容忍）
                List<DocumentLocalChunk> hits =
                        localSearchService.search(entry.getValue(), queryVector, LOCAL_SEARCH_TOP_K);
                if (!hits.isEmpty()) {
                    docHits.put(
                            entry.getKey(),
                            hits.stream().map(DocumentLocalChunk::text).toList());
                }
            }
        }
        // 组装 user-document 子块（captions 与 docHits 均空 → buildUserDocument 返回 null）
        return contextBuilderService.buildUserDocument(attachmentContext.captions(), docHits);
    }

    /**
     * 系统检索为空时组装仅含 {@code <user-document>} 的 {@code <document>} shell（spec §5.4）
     *
     * <p>场景：knowledge_question + 文档附件 + 系统库无命中 —— 系统 document 不存在，但仍须把附件
     * 局部语料以 &lt;document&gt; 壳注入，否则文档语料整体丢失。以空 shell 为底调用
     * {@link ContextBuilderService#appendUserDocument} 把 user-document 合并进 `</document>` 前。
     *
     * @param attachmentContext 附件上下文（null/无附件 → 返回 null，不注入）
     * @param originalQuery     用户原问题（局部检索查询向量来源）
     * @return 仅含 user-document 的 document shell；无任何可注入内容返回 null
     */
    private String buildEmptySystemDocument(AttachmentContext attachmentContext, String originalQuery) {
        String userDocument = buildUserDocumentText(attachmentContext, originalQuery);
        if (userDocument == null || userDocument.isBlank()) {
            return null;
        }
        // 以空 <document> 壳为底，把 user-document 合并进 </document> 前（spec §3.2 装配顺序）
        return contextBuilderService.appendUserDocument("<document>\n</document>", userDocument);
    }

    /**
     * 读取附件处理上下文（worker 写入 config.metadata，键见 {@link AttachmentOrchestrator#KEY_ATTACHMENT_CONTEXT}）
     *
     * @param config RunnableConfig（metadata 贯穿全图共享）
     * @return 附件上下文载体；metadata 无该键/类型不符返回 null
     */
    private static AttachmentContext readAttachmentContext(RunnableConfig config) {
        return config.metadata()
                .map(m -> m.get(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT))
                .filter(AttachmentContext.class::isInstance)
                .map(AttachmentContext.class::cast)
                .orElse(null);
    }

    /**
     * 提取最后一条用户消息原文（检索说明的「用户原问题」，回答以原问题为准）
     */
    private static String extractLastUserQuery(OverAllState state) {
        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) state.value("messages").orElse(Collections.emptyList());
        if (messages == null) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof UserMessage && m.getText() != null && !m.getText().isBlank()) {
                return m.getText();
            }
        }
        return null;
    }
}
