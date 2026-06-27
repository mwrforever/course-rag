package com.commerce.rag.bot.chat.worker;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatRepository;
import com.commerce.rag.bot.chat.ChatSession;
import com.commerce.rag.bot.chat.stream.SseEventType;
import com.commerce.rag.bot.chat.stream.StreamSessionManager;
import com.commerce.rag.bot.context.ContextManager;
import com.commerce.rag.bot.context.TokenEstimator;
import com.commerce.rag.bot.mode.CourseInquiryMode;
import com.commerce.rag.bot.mode.HeuristicMode;
import com.commerce.rag.bot.mode.QaMode;
import com.commerce.rag.bot.prompt.PromptTemplateService;
import com.commerce.rag.bot.router.IntentRouter;
import com.commerce.rag.bot.router.IntentType;
import com.commerce.rag.retrieval.query.QueryRewriter;
import com.commerce.rag.retrieval.search.HybridSearchService;
import com.commerce.rag.retrieval.search.SearchResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * 请求队列消费者（Worker）
 *
 * 使用 Redis Consumer Group 从 chat:request 消费用户请求，
 * 执行完整 RAG 链路（意图路由 → 检索 → 上下文构建 → LLM 流式），
 * 将生成的 token 逐条 XADD 到 chat:response:{runId}。
 * PG 持久化贯穿全流程：INSERT → 增量 UPDATE → 终态 UPDATE。
 *
 * 支持多实例横向扩展：每个实例使用唯一 consumerId，
 * 通过 XREADGROUP 自动负载均衡。崩溃后 pending 消息自动重试。
 */
@Slf4j
@Component
public class ChatRequestWorker {

    private final ChatRepository chatRepository;
    private final IntentRouter intentRouter;
    private final QueryRewriter queryRewriter;
    private final HybridSearchService hybridSearch;
    private final CourseInquiryMode courseMode;
    private final HeuristicMode heuristicMode;
    private final QaMode qaMode;
    private final ContextManager contextManager;
    private final StreamSessionManager streamManager;
    private final PromptTemplateService promptTemplateService;

    /** 当前 Worker 实例的唯一标识 */
    private final String consumerId = generateConsumerId();

    /** Worker 消费循环运行标志 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Worker 守护线程 */
    private Thread workerThread;

    @Value("${stream.batch-size:10}")
    private int batchSize;

    @Value("${stream.poll-timeout:2000}")
    private long pollTimeout;

    @Value("${stream.incremental-save-threshold:50}")
    private int incrementalSaveThreshold;

    public ChatRequestWorker(ChatRepository chatRepository,
                              IntentRouter intentRouter,
                              QueryRewriter queryRewriter,
                              HybridSearchService hybridSearch,
                              CourseInquiryMode courseMode,
                              HeuristicMode heuristicMode,
                              QaMode qaMode,
                              ContextManager contextManager,
                              StreamSessionManager streamManager,
                              PromptTemplateService promptTemplateService) {
        this.chatRepository = chatRepository;
        this.intentRouter = intentRouter;
        this.queryRewriter = queryRewriter;
        this.hybridSearch = hybridSearch;
        this.courseMode = courseMode;
        this.heuristicMode = heuristicMode;
        this.qaMode = qaMode;
        this.contextManager = contextManager;
        this.streamManager = streamManager;
        this.promptTemplateService = promptTemplateService;
    }

    @PostConstruct
    void start() {
        streamManager.initConsumerGroup();
        running.set(true);
        workerThread = new Thread(this::consumeLoop, "chat-worker-" + consumerId);
        workerThread.setDaemon(true);
        workerThread.start();
        log.info("ChatRequestWorker 已启动: consumerId={}", consumerId);
    }

    @PreDestroy
    void stop() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        log.info("ChatRequestWorker 已停止: consumerId={}", consumerId);
    }

    /**
     * 消费主循环
     *
     * 1. 优先处理 pending 消息（崩溃恢复）
     * 2. 阻塞等待新消息（XREADGROUP BLOCK）
     * 3. 逐条处理 + ACK
     */
    private void consumeLoop() {
        while (running.get()) {
            try {
                // 1. 先处理 pending（上次崩溃未 ACK 的消息）
                List<MapRecord<String, Object, Object>> pending = streamManager.getRetryPending(consumerId);
                for (MapRecord<String, Object, Object> record : pending) {
                    processRequest(record);
                    streamManager.ackRequest(record.getId().getValue());
                }

                // 2. 获取新消息（长轮询）
                List<MapRecord<String, Object, Object>> newMsgs =
                        streamManager.getPendingRequests(consumerId, batchSize, pollTimeout);
                for (MapRecord<String, Object, Object> record : newMsgs) {
                    processRequest(record);
                    streamManager.ackRequest(record.getId().getValue());
                }
            } catch (Exception e) {
                log.error("Worker 消费循环异常，1s 后重试", e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("Worker 消费循环退出: consumerId={}", consumerId);
    }

    /**
     * 处理单条请求（核心业务逻辑）
     *
     * 流程：保存用户消息 → 意图路由 → 检索 → 上下文构建 →
     * LLM 流式输出 → 逐 token 写入响应流 + PG 增量持久化
     */
    private void processRequest(MapRecord<String, Object, Object> record) {
        Map<Object, Object> payload = record.getValue();
        UUID runId = UUID.fromString(String.valueOf(payload.get("runId")));
        UUID sessionId = UUID.fromString(String.valueOf(payload.get("sessionId")));
        String userMessage = String.valueOf(payload.get("userMessage"));

        long startTime = System.currentTimeMillis();
        log.info("Worker 开始处理请求: runId={}, sessionId={}", runId, sessionId);

        // 0. 检查是否已被取消（用户可能在 Worker 拿到消息前就取消了）
        if (streamManager.isCancelled(runId)) {
            log.info("请求已被取消，跳过: runId={}", runId);
            streamManager.markCancelled(runId);
            return;
        }

        UUID messageId = null;
        StringBuilder accumulatedText = new StringBuilder();

        try {
            // 1. [PG] 保存用户消息
            ChatMessage userMsg = ChatMessage.builder()
                    .sessionId(sessionId).role("USER").content(userMessage).build();
            chatRepository.saveMessage(userMsg);

            // 2. 意图路由
            IntentType intent = intentRouter.route(userMessage);
            log.info("Worker 意图路由: intent={}, runId={}", intent, runId);

            // 3. 混合检索
            List<SearchResult> sources = ChatStreamUtils.executeSearch(
                    chatRepository, queryRewriter, hybridSearch, userMessage, intent, sessionId);
            List<Map<String, Object>> sourcesMeta = ChatStreamUtils.buildSourcesMeta(sources);

            // 4. 构建上下文（含压缩判断）
            ChatSession session = chatRepository.findSession(sessionId);
            String systemPrompt = promptTemplateService.getSystemPrompt();
            int contextTokens = TokenEstimator.estimate(
                    ChatStreamUtils.buildContextFromResults(sources));
            String historySummary = contextManager.buildContext(session, systemPrompt, contextTokens);

            // 5. [PG] 预创建 ASSISTANT 消息（status=streaming）
            ChatMessage assistantMsg = ChatMessage.builder()
                    .sessionId(sessionId).role("ASSISTANT").content("")
                    .intentType(intent.name()).sourcesJson(sourcesMeta)
                    .runId(runId).streamStatus("streaming").build();
            chatRepository.saveMessage(assistantMsg);
            messageId = assistantMsg.getId();

            // 更新 meta 中的 messageId
            streamManager.updateMessageId(runId, messageId);

            // 6. [Redis] 发布 START 事件
            streamManager.publishResponseEvent(runId, SseEventType.START,
                    Map.of("runId", runId.toString(), "messageId", messageId.toString()));

            // 7. 获取 LLM 流式输出，阻塞消费
            Flux<String> llmFlux = getLlmStream(intent, userMessage, sources, historySummary);
            int[] deltaCount = {0};

            // 使用 .toStream() 将 Flux 转为阻塞 Java Stream（Worker 线程内阻塞是安全的）
            try (Stream<String> tokenStream = llmFlux.toStream()) {
                Iterator<String> tokens = tokenStream.iterator();
                while (tokens.hasNext()) {
                    // 检查取消标记
                    if (streamManager.isCancelled(runId)) {
                        handleCancellation(runId, messageId, accumulatedText.toString());
                        return;
                    }

                    String token = tokens.next();
                    accumulatedText.append(token);
                    deltaCount[0]++;

                    // [Redis] 发布 DELTA 事件
                    streamManager.publishResponseEvent(runId, SseEventType.DELTA,
                            Map.of("text", token));

                    // [PG] 增量持久化：每 N 个 delta 写入一次
                    if (deltaCount[0] % incrementalSaveThreshold == 0) {
                        chatRepository.updateStreamMessage(messageId,
                                accumulatedText.toString(), "streaming", null);
                    }
                }
            }

            // 8. 流正常完成
            String finalText = accumulatedText.toString();

            streamManager.publishResponseEvent(runId, SseEventType.CITATION,
                    Map.of("sources", sourcesMeta));
            streamManager.publishResponseEvent(runId, SseEventType.DONE,
                    Map.of("messageId", messageId.toString()));

            int tokenCount = TokenEstimator.estimate(finalText);
            chatRepository.updateStreamMessage(messageId, finalText, "completed", tokenCount);
            streamManager.markCompleted(runId);

            log.info("Worker 处理完成: runId={}, delta数={}, 耗时={}ms",
                    runId, deltaCount[0], System.currentTimeMillis() - startTime);

        } catch (Exception e) {
            // 流异常
            log.error("Worker 处理请求异常: runId={}", runId, e);
            String partial = accumulatedText.toString();
            if (messageId != null) {
                chatRepository.updateStreamMessage(messageId, partial, "error", null);
            }
            streamManager.markError(runId, e.getMessage());
            streamManager.publishResponseEvent(runId, SseEventType.ERROR,
                    Map.of("code", "WORKER_ERROR",
                            "message", e.getMessage() != null ? e.getMessage() : "未知异常"));
        }
    }

    /**
     * 处理取消：保存部分内容 + 标记取消
     */
    private void handleCancellation(UUID runId, UUID messageId, String partialText) {
        log.info("Worker 检测到取消: runId={}, 已生成字符数={}", runId, partialText.length());
        chatRepository.updateStreamMessage(messageId, partialText, "cancelled", null);
        streamManager.markCancelled(runId);
        streamManager.publishResponseEvent(runId, SseEventType.CANCELLED,
                Map.of("messageId", messageId.toString()));
    }

    /**
     * 根据意图获取 LLM 流式输出
     */
    private Flux<String> getLlmStream(IntentType intent, String userMessage,
                                       List<SearchResult> sources, String historySummary) {
        return switch (intent) {
            case COURSE_INFO -> {
                String context = ChatStreamUtils.buildContextFromResults(sources);
                yield courseMode.answerStream(userMessage, context);
            }
            case HEURISTIC -> {
                String context = ChatStreamUtils.buildContextFromResults(sources);
                yield heuristicMode.answerStream(userMessage, context, historySummary);
            }
            case QA -> qaMode.answerStream(userMessage, sources, historySummary);
            default -> qaMode.answerStream(userMessage, List.of(), historySummary);
        };
    }

    /**
     * 生成 Worker 实例唯一标识：worker-{hostname}-{pid}
     */
    private static String generateConsumerId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        return "worker-" + hostname + "-" + pid;
    }
}
