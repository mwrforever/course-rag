package com.commerce.rag.bot.chat.worker;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatRepository;
import com.commerce.rag.bot.router.IntentType;
import com.commerce.rag.retrieval.query.QueryRewriter;
import com.commerce.rag.retrieval.query.TypedQuery;
import com.commerce.rag.retrieval.search.HybridSearchService;
import com.commerce.rag.retrieval.search.SearchResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 流式处理共享工具类
 *
 * 提取 ChatService 中 Worker 和业务层共用的辅助方法：
 * - 检索结果格式化
 * - 历史摘要构建
 * - 来源元数据构建
 * - 按意图执行检索
 */
public final class ChatStreamUtils {

    private ChatStreamUtils() {}

    /**
     * 将检索结果格式化为上下文文本（供 LLM prompt 注入）
     *
     * @param results 检索结果列表
     * @return 格式化后的文本，空结果返回 null
     */
    public static String buildContextFromResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return null;
        return results.stream()
                .map(r -> String.format("[%s] %s", r.headingPath(), r.content()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 构建对话历史摘要文本（简单拼接，供意图路由和上下文使用）
     *
     * @param messages 历史消息列表
     * @return 拼接后的摘要文本，空列表返回 null
     */
    public static String buildHistorySummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        return messages.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 构建来源元数据列表（用于 SSE citation 事件和 PG sourcesJson）
     *
     * @param sources 检索结果
     * @return 来源元数据列表
     */
    public static List<Map<String, Object>> buildSourcesMeta(List<SearchResult> sources) {
        if (sources == null || sources.isEmpty()) return List.of();
        return sources.stream()
                .map(r -> Map.<String, Object>of(
                        "chunkId", r.chunkId(),
                        "headingPath", r.headingPath() != null ? r.headingPath() : "",
                        "score", r.score()
                ))
                .collect(Collectors.toList());
    }

    /**
     * 根据意图类型执行对应的检索策略
     *
     * @param chatRepository 消息仓储（QA 模式需要历史做 query rewrite）
     * @param queryRewriter  查询重写器
     * @param hybridSearch   混合检索服务
     * @param userMessage    用户消息
     * @param intent         意图类型
     * @param sessionId      会话 ID
     * @return 检索结果列表
     */
    public static List<SearchResult> executeSearch(ChatRepository chatRepository,
                                                     QueryRewriter queryRewriter,
                                                     HybridSearchService hybridSearch,
                                                     String userMessage,
                                                     IntentType intent,
                                                     UUID sessionId) {
        return switch (intent) {
            case COURSE_INFO -> hybridSearch.search(userMessage, null, 3, "quick");
            case HEURISTIC -> hybridSearch.search(userMessage, null, 5, "thinking");
            case QA -> {
                List<ChatMessage> history = chatRepository.findRecentMessages(sessionId);
                String historySummary = buildHistorySummary(history);
                List<TypedQuery> typedQueries = queryRewriter.rewrite(userMessage, historySummary);
                if (typedQueries.isEmpty()) {
                    yield List.of();
                }
                yield hybridSearch.search(typedQueries.get(0).query(), null, 5, "thinking");
            }
            default -> List.of();
        };
    }
}
