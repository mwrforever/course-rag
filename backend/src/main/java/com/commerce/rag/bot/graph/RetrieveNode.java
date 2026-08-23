package com.commerce.rag.bot.graph;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.hook.DocumentAssemblerInterceptor;
import com.commerce.rag.bot.hook.EpisodicInterceptor;
import com.commerce.rag.bot.hook.PreferenceInterceptor;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import com.commerce.rag.bot.tool.TypedQuery;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.record.RetrievalSource;
import com.commerce.rag.retrieval.ContextBuilderService;
import com.commerce.rag.retrieval.CourseNameMapper;
import com.commerce.rag.service.AttachmentLocalSearchService;
import com.commerce.rag.service.AttachmentOrchestrator;
import com.commerce.rag.service.IEpisodicMemoryService;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>knowledge_question 分支公共段（queries 判空后、系统检索空/非空两分支返回前）编排经历记忆
 *       召回（{@link IEpisodicMemoryService#recall}，spec §8.7）：user_id 硬隔离（metadata 读
 *       userId，Long）、recall_history 动态 validity 过滤，命中把引用列表写入
 *       metadata["episodic_context"]（{@link EpisodicInterceptor#KEY_EPISODIC_CONTEXT}，与
 *       document 同通道——不落 state/checkpoint，EpisodicInterceptor 尾部注入）；召回异常/无命中/
 *       无 userId → 降级不写，不影响主文档检索与回答</li>
 *   <li>并行编排（2026-08-22 性能优化报告 2-3）：episodic 召回、知识检索、附件局部检索向量 embed
 *       三段相互独立的远程 IO 经本节点独立小线程池（默认 3 线程，P2-3）并行执行（与 SearchKnowledgeTool 内部
 *       searchExecutor 隔离，避免自阻塞）；document_context 由 join 后主线程写入、episodic_context
 *       由 recallEpisodic 任务内写入（join 建立 happens-before，后续读取可见）——检索节点延迟从
 *       「三段之和」降为「约等于最慢一段」，高并发对话时不再白占 runPool worker</li>
 *   <li>首条重写查询预嵌入（性能优化报告 3-1 方案 a）：embed 一次首条重写查询文本，
 *       向量同时供经历记忆召回与知识检索首条复用（同文本两次远程 embed 收敛为一次）；
 *       预嵌入失败/空向量 → 召回跳过（降级不注入）、知识检索内部自嵌兜底，不中断主流程</li>
 * </ol>
 *
 * <p>失败降级：检索异常 → 不写 document，ReactAgent 直接回答并记日志；系统检索空结果 →
 * 无附件上下文时不写 document（ReactAgent 直接回答），有附件上下文时仍注入仅含
 * user-document 的 document shell（spec §5.4 附件语料不丢）；经历记忆召回异常/无命中 →
 * 不写 episodic_context（EpisodicInterceptor 原样透传），记忆缺失不影响回答。
 *
 * <p>本类为手写单构造器（8 依赖：SearchKnowledgeTool / CourseNameMapper / ContextBuilderService
 * / AttachmentLocalSearchService / EmbeddingModel / IEpisodicMemoryService / MemoryProperties /
 * 检索并行度配置，Spring 按单构造器自动注入，无需 @Autowired）。
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

    /**
     * 检索来源列表 metadata 键（B3-5：SOURCES 事件与 chat_message.sourcesJson 的数据通道）。
     * 检索命中非空时写入 {@code List<RetrievalSource>}，ChatRequestWorker 读取后推 SOURCES
     * 事件并持久化 sourcesJson；与 document_context 同通道——不写 State、不进 checkpoint
     * （瞬时上下文，仅本轮 run 生命周期内有效）。
     */
    public static final String KEY_RETRIEVAL_SOURCES = "retrieval_sources";

    private final SearchKnowledgeTool searchKnowledgeTool;
    private final CourseNameMapper courseNameMapper;
    private final ContextBuilderService contextBuilderService;
    private final AttachmentLocalSearchService localSearchService;
    private final EmbeddingModel embeddingModel;
    private final IEpisodicMemoryService episodicMemoryService;
    private final MemoryProperties properties;
    /** 检索节点并行执行器（独立小线程池，与 SearchKnowledgeTool 内部池隔离，报告 2-3；P2-3 默认 3 线程） */
    private final ExecutorService retrieveExecutor;

    public RetrieveNode(
            SearchKnowledgeTool searchKnowledgeTool,
            CourseNameMapper courseNameMapper,
            ContextBuilderService contextBuilderService,
            AttachmentLocalSearchService localSearchService,
            EmbeddingModel embeddingModel,
            IEpisodicMemoryService episodicMemoryService,
            MemoryProperties properties,
            @Value("${retrieval.retrieve-node-parallelism:3}") int parallelism) {
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.courseNameMapper = courseNameMapper;
        this.contextBuilderService = contextBuilderService;
        this.localSearchService = localSearchService;
        this.embeddingModel = embeddingModel;
        this.episodicMemoryService = episodicMemoryService;
        this.properties = properties;
        // 独立小线程池（不与 SearchKnowledgeTool 内部 searchExecutor 共用，避免检索任务与召回任务
        // 互抢 4 线程形成自阻塞）；P2-3 默认 3 线程——三段任务（附件局部检索/经历召回/知识检索）各有
        // 线程可占，最重的知识检索不再因 2 线程池排队延后（有附件场景退化修复）；
        // daemon 线程随 JVM 退出，带前缀+序号便于 thread dump 排查
        AtomicInteger seq = new AtomicInteger(1);
        this.retrieveExecutor = Executors.newFixedThreadPool(Math.max(1, parallelism), r -> {
            Thread t = new Thread(r, "retrieve-node-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
    }

    /** 释放检索并行执行器线程（应用关闭时；daemon 线程不阻塞 JVM 退出） */
    @PreDestroy
    public void destroy() {
        retrieveExecutor.shutdownNow();
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

        // 附件上下文提前读取（纯内存 metadata 读，供并行任务与非空分支共用）
        AttachmentContext attachmentContext = readAttachmentContext(config);

        // 附件 user-document 子块任务先行（与下方预 embed 并行：互不依赖）
        CompletableFuture<String> userDocFuture = CompletableFuture.supplyAsync(
                () -> buildUserDocumentText(attachmentContext, originalQuery), retrieveExecutor);

        // 方案 3-1-a：首条重写查询预嵌入一次（远程调用），向量同时供经历记忆召回与知识检索首条
        // 复用——同文本两次 embed 收敛为一次（费用/配额省半）；失败/空向量返回 null，
        // recall/检索各自走既有降级（回退为内部自嵌，行为与改造前等价）
        float[] preVector = embedSafely(queries.get(0).queryText());

        // 2-3 并行编排（性能优化报告）：episodic 召回 ∥ 知识检索 ∥ 附件局部检索 embed 三段相互独立的
        // 远程 IO 并行执行——检索节点延迟从「三段之和」降为「约等于最慢一段」；document_context 由
        // join 后主线程写入（episodic_context 由 recallEpisodic 任务内写入，不同键 + join 建立
        // happens-before，无可见性问题）
        CompletableFuture<Void> episodicFuture =
                CompletableFuture.runAsync(() -> recallEpisodic(config, plan, preVector), retrieveExecutor);
        CompletableFuture<List<KnowledgeChunk>> chunksFuture = CompletableFuture.supplyAsync(
                () -> searchKnowledgeTool.searchKnowledge(queries, preVector).chunks(), retrieveExecutor);

        // recallEpisodic 内部已全异常降级（不会抛出）；附件局部检索 embed 异常在
        // buildUserDocumentText 内降级为 null（跳过局部检索，B3-4），三段 join 均不向上传播
        episodicFuture.join();
        List<KnowledgeChunk> chunks = chunksFuture.join();
        String userDocument = userDocFuture.join();

        // 系统检索为空：仅当有附件上下文才组装仅含 <user-document> 的 document shell 注入
        // （ReactAgent 仍直接回答，但附件局部语料不丢，spec §5.4）
        if (chunks.isEmpty()) {
            String emptyShell = buildEmptySystemDocument(userDocument);
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

        // B3-5：检索命中非空 → 来源列表（chunkId/docTitle/headingPath/score）写入 metadata，
        // ChatRequestWorker 读取后推 SOURCES 事件并持久化 chat_message.sourcesJson（与
        // document_context 同通道，不落 state/checkpoint）；chat/unknown 意图与空检索不写
        // （worker 侧不推 SOURCES、sourcesJson 保持 "[]"）
        config.metadata().ifPresent(m -> m.put(KEY_RETRIEVAL_SOURCES, buildSources(chunks)));

        // 5. 组装 <document>（system-document）并合并 <user-document>（附件上下文，spec §5.3/§5.4）
        String document = contextBuilderService.buildDocument(originalQuery, plan.rewrittenQueries(), chunks);
        if (document == null || document.isBlank()) {
            log.info("retrieveNode: document 组装为空，跳过注入");
            return CompletableFuture.completedFuture(Map.of());
        }
        // 合并附件 user-document 子块（无 user-document 子块 → 原样返回 systemDocument）
        // final 合并结果，供下方 lambda 捕获（document 已被上面赋值，非 effectively final）
        final String mergedDocument = mergeUserDocument(document, userDocument);
        config.metadata().ifPresent(m -> m.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, mergedDocument));
        log.info(
                "retrieveNode 完成: intent={}, 候选={}条, 注入 document（{} 字符）",
                plan.intent().name(),
                chunks.size(),
                mergedDocument.length());

        return CompletableFuture.completedFuture(Map.of());
    }

    /**
     * 经历记忆召回编排（spec §8.7）：user_id（metadata 硬隔离）→ recall → 命中写入
     * {@link EpisodicInterceptor#KEY_EPISODIC_CONTEXT} metadata（EpisodicInterceptor 尾部注入）。
     *
     * <p>规则：recall_history=true → 不带 validity 过滤（全量召回）；false → 仅召 active，
     * 由 {@link IEpisodicMemoryService#recall} 内部实现（spec §8.7 动态 validity）。
     *
     * <p>降级：无 userId / userId 非法字符串 / 预嵌入向量为空（召回失败）/ 无命中 → 不写 metadata
     * （EpisodicInterceptor 原样透传），记忆缺失不影响主文档检索与回答。
     *
     * @param config      RunnableConfig（metadata：userId 读取 + episodic_context 写入通道）
     * @param plan        QueryPlan（recallHistory 动态过滤上游）
     * @param queryVector 召回查询向量（{@link #embedSafely} 预嵌入的首条重写查询向量，复用不重复 embed；
     *                    null/空 → 跳过召回）
     */
    private void recallEpisodic(RunnableConfig config, QueryPlan plan, float[] queryVector) {
        if (queryVector == null || queryVector.length == 0) {
            log.debug("recallEpisodic: 查询向量为空，跳过经历记忆召回");
            return;
        }
        // 全链路 user_id 硬隔离（spec §10-6）：从 metadata 读 PreferenceInterceptor.KEY_USER_ID（String）
        Object uid = config.metadata()
                .map(m -> m.get(PreferenceInterceptor.KEY_USER_ID))
                .orElse(null);
        if (!(uid instanceof String userId) || userId.isBlank()) {
            log.debug("recallEpisodic: 无 userId，跳过经历记忆召回");
            return;
        }
        try {
            Long parsedUserId = Long.parseLong(userId);
            List<EpisodicMemoryRef> refs = episodicMemoryService.recall(
                    parsedUserId,
                    queryVector,
                    plan.recallHistory(),
                    properties.getEpisodic().getRecallTopK());
            if (refs == null || refs.isEmpty()) {
                log.debug("recallEpisodic: 无命中经历记忆 userId={}, recallHistory={}", parsedUserId, plan.recallHistory());
                return;
            }
            // 命中 → 写入 episodic_context metadata（与 document 同通道，不落 state/checkpoint，
            // refs 为 effectively final，可供 lambda 直接捕获）
            config.metadata().ifPresent(m -> m.put(EpisodicInterceptor.KEY_EPISODIC_CONTEXT, refs));
            log.info(
                    "recallEpisodic: 召回 {} 条经历记忆 userId={}, recallHistory={}",
                    refs.size(),
                    parsedUserId,
                    plan.recallHistory());
        } catch (NumberFormatException e) {
            log.warn("recallEpisodic: userId 非法字符串，跳过经历记忆召回: {}", userId);
        } catch (RuntimeException e) {
            log.warn("recallEpisodic: 召回异常，降级不注入（不影响主流程）: userId={}", userId, e);
        }
    }

    /**
     * 首条重写查询预嵌入（方案 3-1-a：一次远程调用供经历记忆召回与知识检索首条共用）。
     *
     * <p>降级语义：embedding 异常/空向量返回 null——recallEpisodic 跳过召回（无向量不检索），
     * 知识检索内部回退自嵌（其自身还有空向量降级），与改造前行为等价，不中断主流程。
     *
     * @param text 首条重写查询文本（非空白）
     * @return 查询向量；失败/空向量返回 null
     */
    private float[] embedSafely(String text) {
        try {
            float[] vector = embeddingModel.embed(text);
            return (vector == null || vector.length == 0) ? null : vector;
        } catch (RuntimeException e) {
            log.warn("retrieveNode: 查询预嵌入失败（降级两链路内部兜底）: query={}, error={}", truncateQuery(text), e.getMessage());
            return null;
        }
    }

    /** 日志用查询文本摘要（超长截断，避免日志膨胀） */
    private static String truncateQuery(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 30 ? text : text.substring(0, 30) + "...";
    }

    /**
     * 组装检索来源列表（B3-5：SOURCES 事件与 sourcesJson 的载荷）
     *
     * <p>按精排顺序映射为 {@link RetrievalSource}（最小可用字段：chunkId/docTitle/headingPath/score，
     * 依据前端设计 §1.6.4「sources → 列表渲染」——契约文档未细化 payload 结构）。
     *
     * @param chunks 精排后的检索命中（非空）
     * @return 来源列表（与入参同序）
     */
    private static List<RetrievalSource> buildSources(List<KnowledgeChunk> chunks) {
        return chunks.stream()
                .map(c -> new RetrievalSource(c.chunkId(), c.docTitle(), c.headingPath(), c.score()))
                .toList();
    }

    /**
     * 合并附件 user-document 子块 —— 系统 document 非空时在 `</document>` 前并入（spec §5.3/§5.4）。
     * user-document 由并行任务预计算（{@link #buildUserDocumentText}），此处仅做空值判断与合并。
     *
     * @param document    既有 systemDocument（buildDocument 输出，非 null）
     * @param userDocument 预计算的 user-document 子块文本（无附件上下文/无命中 → 原样返回既有 document）
     * @return 合并后的 document 文本
     */
    private String mergeUserDocument(String document, String userDocument) {
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
            // B3-4 降级：embed 异常/空向量 → 查询向量置 null 跳过文档附件局部检索（无向量不检索），
            // caption 照常注入、知识检索与回答不中断（与 embedSafely 同款降级语义，
            // 兑现类注释「检索异常不写 document 即直答」的降级承诺）
            float[] queryVector = embedSafely(originalQuery);
            if (queryVector == null) {
                log.warn("retrieveNode: 附件局部检索查询向量获取失败，跳过文档附件局部检索（不影响回答）");
            } else {
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
     * @param userDocument 预计算的 user-document 子块文本（null/空白 → 返回 null，不注入）
     * @return 仅含 user-document 的 document shell；无可注入内容返回 null
     */
    private String buildEmptySystemDocument(String userDocument) {
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
