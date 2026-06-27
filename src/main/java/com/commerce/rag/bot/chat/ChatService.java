package com.commerce.rag.bot.chat;

import com.commerce.rag.bot.chat.worker.ChatStreamUtils;
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
 * 对话服务编排（同步接口）
 *
 * 提供同步对话能力，串联完整 RAG 链路：
 * 意图路由 → Query 重写 → 混合检索 → 模式处理 → 消息持久化。
 *
 * 流式对话已迁移至 ChatRequestWorker（Redis Streams 消费），
 * 本类仅保留同步 processMessage 向后兼容。
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
     * 处理用户消息并生成 AI 回答（同步，向后兼容）
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
        String historySummary = ChatStreamUtils.buildHistorySummary(history);

        // 3. 意图路由
        IntentType intent = intentRouter.route(userMessage);
        log.info("意图路由结果: intent={}, sessionId={}", intent, sessionId);

        // 4. 根据意图模式处理
        String answer;
        List<SearchResult> sources = new ArrayList<>();

        switch (intent) {
            case COURSE_INFO -> {
                sources = hybridSearch.search(userMessage, null, 3, "quick");
                String context = ChatStreamUtils.buildContextFromResults(sources);
                answer = courseMode.answer(userMessage, context);
            }
            case HEURISTIC -> {
                sources = hybridSearch.search(userMessage, null, 5, "thinking");
                String context = ChatStreamUtils.buildContextFromResults(sources);
                answer = heuristicMode.answer(userMessage, context, historySummary);
            }
            case QA -> {
                List<TypedQuery> typedQueries = queryRewriter.rewrite(userMessage, historySummary);
                if (typedQueries.isEmpty()) {
                    answer = qaMode.answer(userMessage, List.of(), historySummary);
                } else {
                    String searchQuery = typedQueries.get(0).query();
                    sources = hybridSearch.search(searchQuery, null, 5, "thinking");
                    answer = qaMode.answer(userMessage, sources, historySummary);
                }
            }
            default -> answer = qaMode.answer(userMessage, List.of(), historySummary);
        }

        // 5. 构建来源信息
        List<Map<String, Object>> sourcesMeta = ChatStreamUtils.buildSourcesMeta(sources);

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
     * 对话响应（同步接口返回值）
     */
    public record ChatResponse(
            UUID messageId,
            UUID sessionId,
            String content,
            String intentType,
            List<Map<String, Object>> sources
    ) {}
}
