package com.commerce.rag.bot.chat;

import com.commerce.rag.bot.mode.CourseInquiryMode;
import com.commerce.rag.bot.mode.HeuristicMode;
import com.commerce.rag.bot.mode.QaMode;
import com.commerce.rag.bot.router.IntentRouter;
import com.commerce.rag.bot.router.IntentType;
import com.commerce.rag.retrieval.query.QueryRewriter;
import com.commerce.rag.retrieval.query.TypedQuery;
import com.commerce.rag.retrieval.search.HybridSearchService;
import com.commerce.rag.retrieval.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 对话服务编排
 *
 * 串联完整的对话流程：
 * 1. 意图路由（判断 COURSE_INFO / HEURISTIC / QA）
 * 2. Query 重写（拆解子查询）
 * 3. 混合检索（Dense + Keyword + RRF）
 * 4. 模式处理（根据意图选择对应模式）
 * 5. 消息持久化
 */
@Slf4j
@Service
public class ChatService {

    private final ChatRepository chatRepository;
    private final IntentRouter intentRouter;
    private final QueryRewriter queryRewriter;
    private final HybridSearchService hybridSearch;
    private final CourseInquiryMode courseMode;
    private final HeuristicMode heuristicMode;
    private final QaMode qaMode;

    public ChatService(ChatRepository chatRepository,
                       IntentRouter intentRouter,
                       QueryRewriter queryRewriter,
                       HybridSearchService hybridSearch,
                       CourseInquiryMode courseMode,
                       HeuristicMode heuristicMode,
                       QaMode qaMode) {
        this.chatRepository = chatRepository;
        this.intentRouter = intentRouter;
        this.queryRewriter = queryRewriter;
        this.hybridSearch = hybridSearch;
        this.courseMode = courseMode;
        this.heuristicMode = heuristicMode;
        this.qaMode = qaMode;
    }

    /**
     * 创建新会话
     *
     * @param userId 用户标识
     * @return 新创建的会话
     */
    public ChatSession createSession(String userId) {
        ChatSession session = ChatSession.builder()
                .userId(userId)
                .build();
        return chatRepository.saveSession(session);
    }

    /**
     * 处理用户消息并生成 AI 回答
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @return 包含 AI 回答和来源信息的响应
     */
    public ChatResponse processMessage(UUID sessionId, String userMessage) {
        long startTime = System.currentTimeMillis();

        // 1. 保存用户消息
        ChatMessage userMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .role("USER")
                .content(userMessage)
                .build();
        chatRepository.saveMessage(userMsg);

        // 2. 获取历史上下文
        List<ChatMessage> history = chatRepository.findRecentMessages(sessionId);
        String historySummary = buildHistorySummary(history);

        // 3. 意图路由
        IntentType intent = intentRouter.route(userMessage);
        log.info("意图路由结果: intent={}, sessionId={}", intent, sessionId);

        // 4. 根据意图模式处理
        String answer;
        List<SearchResult> sources = new ArrayList<>();

        switch (intent) {
            case COURSE_INFO -> {
                // 课程询问：简单检索 + 课程模式
                sources = hybridSearch.search(userMessage, null, 3, "quick");
                String context = buildContextFromResults(sources);
                answer = courseMode.answer(userMessage, context);
            }
            case HEURISTIC -> {
                // 启发式解答：检索相关文档 + 引导式回答
                sources = hybridSearch.search(userMessage, null, 5, "thinking");
                String context = buildContextFromResults(sources);
                answer = heuristicMode.answer(userMessage, context, historySummary);
            }
            case QA -> {
                // QA 问答：完整 RAG 链路
                List<TypedQuery> typedQueries = queryRewriter.rewrite(userMessage, historySummary);

                if (typedQueries.isEmpty()) {
                    // 无需检索（闲聊场景），直接用 LLM 对话
                    answer = qaMode.answer(userMessage, List.of(), historySummary);
                } else {
                    // 取最高优先级的子查询执行检索
                    String searchQuery = typedQueries.get(0).query();
                    sources = hybridSearch.search(searchQuery, null, 5, "thinking");
                    answer = qaMode.answer(userMessage, sources, historySummary);
                }
            }
            default -> answer = qaMode.answer(userMessage, List.of(), historySummary);
        }

        // 5. 构建来源信息
        List<Map<String, Object>> sourcesMeta = sources.stream()
                .map(r -> Map.<String, Object>of(
                        "chunkId", r.chunkId(),
                        "headingPath", r.headingPath() != null ? r.headingPath() : "",
                        "score", r.score()
                ))
                .collect(Collectors.toList());

        // 6. 保存 AI 回复
        ChatMessage assistantMsg = ChatMessage.builder()
                .sessionId(sessionId)
                .role("ASSISTANT")
                .content(answer)
                .intentType(intent.name())
                .sourcesJson(sourcesMeta)
                .build();
        chatRepository.saveMessage(assistantMsg);

        log.info("对话处理完成: sessionId={}, intent={}, 耗时={}ms",
                sessionId, intent, System.currentTimeMillis() - startTime);

        return new ChatResponse(
                assistantMsg.getId(),
                sessionId,
                answer,
                intent.name(),
                sourcesMeta
        );
    }

    /**
     * 获取会话历史消息
     */
    public List<ChatMessage> getHistory(UUID sessionId) {
        return chatRepository.findAllMessages(sessionId);
    }

    /**
     * 关闭会话
     */
    public void closeSession(UUID sessionId) {
        chatRepository.closeSession(sessionId);
    }

    /**
     * 将检索结果格式化为上下文文本
     */
    private String buildContextFromResults(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return null;
        return results.stream()
                .map(r -> String.format("[%s] %s", r.headingPath(), r.content()))
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 构建对话历史摘要
     */
    private String buildHistorySummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;
        return messages.stream()
                .sorted(Comparator.comparing(ChatMessage::getCreatedAt))
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 对话响应
     */
    public record ChatResponse(
            UUID messageId,
            UUID sessionId,
            String content,
            String intentType,
            List<Map<String, Object>> sources
    ) {}
}
