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
import com.commerce.rag.retrieval.ContextBuilderService;
import com.commerce.rag.retrieval.CourseNameMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
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
 *   <li>ContextBuilderService 组装 &lt;document&gt;（空候选返回 null）</li>
 *   <li>document 文本写入 config.metadata()["document_context"]——不写 State、
 *       不进 checkpoint（临时上下文，DocumentAssemblerInterceptor 瞬时注入）</li>
 * </ol>
 *
 * <p>失败降级：检索异常/空结果 → 不写 document，ReactAgent 直接回答并记日志
 * （spec §1：retrieveNode 失败/空结果 → document 为空）。
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

    private final SearchKnowledgeTool searchKnowledgeTool;
    private final CourseNameMapper courseNameMapper;
    private final ContextBuilderService contextBuilderService;

    public RetrieveNode(
            SearchKnowledgeTool searchKnowledgeTool,
            CourseNameMapper courseNameMapper,
            ContextBuilderService contextBuilderService) {
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.courseNameMapper = courseNameMapper;
        this.contextBuilderService = contextBuilderService;
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
        if (chunks.isEmpty()) {
            log.info(
                    "retrieveNode: 检索结果为空（ReactAgent 直接回答）: intent={}, 重写={}条",
                    plan.intent().name(),
                    queries.size());
            return CompletableFuture.completedFuture(Map.of());
        }

        // 5. 组装 <document> 并写入 metadata（临时上下文，不落 state/checkpoint）
        String document = contextBuilderService.buildDocument(originalQuery, plan.rewrittenQueries(), chunks);
        if (document == null || document.isBlank()) {
            log.info("retrieveNode: document 组装为空，跳过注入");
            return CompletableFuture.completedFuture(Map.of());
        }
        config.metadata().ifPresent(m -> m.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, document));
        log.info(
                "retrieveNode 完成: intent={}, 候选={}条, 注入 document（{} 字符）",
                plan.intent().name(),
                chunks.size(),
                document.length());

        return CompletableFuture.completedFuture(Map.of());
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
