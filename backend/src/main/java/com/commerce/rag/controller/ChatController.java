package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.config.StreamProperties;
import com.commerce.rag.controller.dto.ChatRequest;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.service.ChatMessageService;
import com.commerce.rag.service.ChatRunService;
import com.commerce.rag.service.ChatSessionService;
import com.commerce.rag.service.ConcurrentRunException;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEventType;
import com.commerce.rag.worker.ChatRequestWorker;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat SSE Controller —— 对话流式端点
 *
 * <p>提供 3 个端点：
 * <ol>
 *   <li>POST /api/v1/student/chat — 发起对话，返回 SseEmitter</li>
 *   <li>POST /api/v1/student/chat/{runId}/cancel — 取消正在执行的 run</li>
 *   <li>GET /api/v1/student/chat/{runId}/reconnect — 断线重连</li>
 * </ol>
 *
 * <p>鉴权：通过 AuthInterceptor 注入的 request attribute 获取已认证用户 ID。
 *
 * <p>线程模型：
 * <ul>
 *   <li>请求线程（Tomcat）→ 创建 SseEmitter 后立即返回，不阻塞</li>
 *   <li>心跳线程（chat-heartbeat）→ 定时发送 heartbeat 事件</li>
 *   <li>Worker 线程（chat-worker-N）→ 通过 bridge.push 推送事件到 SseEmitter</li>
 * </ul>
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/student/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** SseEmitter 超时时间：30 分钟 */
    private static final long EMITTER_TIMEOUT = 30 * 60 * 1000L;

    private final ChatRequestWorker worker;
    private final MemoryStreamBridge bridge;
    private final ChatRunService chatRunService;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final StringRedisTemplate redisTemplate;
    private final StreamProperties streamProperties;

    /** 心跳定时器线程池 */
    private ScheduledExecutorService scheduler;

    public ChatController(
            ChatRequestWorker worker,
            MemoryStreamBridge bridge,
            ChatRunService chatRunService,
            ChatSessionService chatSessionService,
            ChatMessageService chatMessageService,
            StringRedisTemplate redisTemplate,
            StreamProperties streamProperties) {
        this.worker = worker;
        this.bridge = bridge;
        this.chatRunService = chatRunService;
        this.chatSessionService = chatSessionService;
        this.chatMessageService = chatMessageService;
        this.redisTemplate = redisTemplate;
        this.streamProperties = streamProperties;
    }

    @PostConstruct
    public void init() {
        scheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "chat-heartbeat");
            t.setDaemon(true);
            return t;
        });
        log.info("ChatController 心跳调度器已启动: interval={}s", streamProperties.heartbeatInterval());
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("ChatController 心跳调度器已关闭");
        }
    }

    // ========================================================================
    // POST /api/v1/student/chat — 发起对话
    // ========================================================================

    /**
     * 发起对话，返回 SseEmitter。
     *
     * <p>流程：
     * <ol>
     *   <li>从 AuthInterceptor 注入的 request attribute 获取 userId</li>
     *   <li>如果 sessionId 为 null → 创建新会话</li>
     *   <li>chatRunService.createRun — 并发守卫（ConcurrentRunException → 409）</li>
     *   <li>创建 SseEmitter + bridge.createRing + bridge.subscribe（先订阅再入队，确保不丢事件）</li>
     *   <li>XADD 消息到 Redis Stream：{runId, sessionId, userId, query}</li>
     *   <li>启动心跳定时器（每 heartbeatInterval 秒发 heartbeat 事件）</li>
     * </ol>
     *
     * <p>⚠️ 顺序关键：createRing → subscribe → XADD。
     * 先创建 ring 确保 subscribe 不失败，先 subscribe 再 XADD 确保不丢事件。
     * Worker 的 createRing 用 computeIfAbsent，幂等。
     */
    @PostMapping
    public SseEmitter chat(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);

        // 0. 参数校验
        if (request.query() == null || request.query().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }

        // 1. 会话处理（含归属校验：sessionId 非空时必须是当前用户的会话）
        Long sessionId = request.sessionId();
        if (sessionId == null) {
            ChatSession session = chatSessionService.createSession(userId, truncateTitle(request.query()));
            sessionId = session.getId();
        } else {
            // P0-3: sessionId 归属校验——传入他人会话 ID 直接拒绝
            ChatSession session = chatSessionService.findById(sessionId);
            if (session == null || !session.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此会话");
            }
        }

        // 2. 创建 run（并发守卫 → ConcurrentRunException 由 @ExceptionHandler 处理）
        ChatRun run = chatRunService.createRun(sessionId, userId);
        String runId = run.getId().toString();

        // 3. 创建 SseEmitter（30 分钟超时）
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

        // 4. 创建 ring + 订阅（先创建 ring 再订阅，确保不丢事件）
        //    Worker 的 createRing 用 computeIfAbsent，Controller 先创建是幂等操作
        bridge.createRing(runId);
        bridge.subscribe(runId, emitter);

        // 5. XADD 入队（subscribe 之后再入队，确保 Worker 推送的事件能到达 emitter）
        Map<String, String> message = Map.of(
                "runId", runId,
                "sessionId", sessionId.toString(),
                "userId", userId.toString(),
                "query", request.query());
        redisTemplate.opsForStream().add(streamProperties.requestStream(), message);

        // 6. 心跳定时器
        startHeartbeat(emitter);

        // 7. 更新 session 最后消息时间
        chatSessionService.updateLastMessageAt(sessionId);

        log.info("发起对话: runId={}, sessionId={}, userId={}", runId, sessionId, userId);
        return emitter;
    }

    // ========================================================================
    // POST /api/v1/student/chat/{runId}/cancel — 取消 run
    // ========================================================================

    /**
     * 取消正在执行的 run。
     *
     * <p>归属校验：run 必须属于当前用户（P0-3，不匹配 404 不泄露存在性）。
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String runId, HttpServletRequest httpRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);
        worker.cancel(runId);
        log.info("取消请求已发送: runId={}", runId);
        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // GET /api/v1/student/chat/{runId}/reconnect — 断线重连
    // ========================================================================

    /**
     * 断线重连：从 ring buffer 回放 lastEventId 之后的事件。
     *
     * <p>归属校验：run 必须属于当前用户（P0-3，不匹配 404 不泄露存在性）。
     *
     * <p>流程：
     * <ol>
     *   <li>创建新 SseEmitter</li>
     *   <li>bridge.replay(runId, lastEventId, emitter)
     *       → true：回放成功，继续 subscribe 接收后续事件</li>
     *   <li>bridge.replay → false：lastEventId 太旧（ring buffer 已覆盖）
     *       → F2-9: 降级查 PG chat_message 表，replay 历史消息到 emitter（§3.6）
     *       → 降级不终止，继续 subscribe 接收后续事件</li>
     * </ol>
     */
    @GetMapping("/{runId}/reconnect")
    public SseEmitter reconnect(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long lastEventId,
            HttpServletRequest httpRequest) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

        boolean success = bridge.replay(runId, lastEventId, emitter);
        if (!success) {
            // F2-9: ring buffer 已覆盖 → 降级查 PG chat_message 表，replay 历史消息（§3.6）
            // 降级不终止 run，记 warn，继续 subscribe 接收后续事件
            log.warn("ring buffer 回放失败，降级查 PG: runId={}, lastEventId={}", runId, lastEventId);
            boolean pgOk = replayFromPg(runId, lastEventId, emitter);
            if (!pgOk) {
                // PG 也无数据，返回 error 事件
                try {
                    emitter.send(SseEmitter.event()
                            .name(SseEventType.ERROR.getEventName())
                            .data("{\"message\":\"会话历史不可用，请重新提问\",\"code\":\"REPLAY_FAILED\"}"));
                    emitter.complete();
                } catch (IOException e) {
                    // emitter 已关闭，忽略
                }
                return emitter;
            }
            // PG 降级回放成功，继续订阅后续事件
            bridge.subscribe(runId, emitter);
            startHeartbeat(emitter);
            log.info("PG 降级回放成功: runId={}, lastEventId={}", runId, lastEventId);
            return emitter;
        }

        // 回放成功，继续订阅后续事件
        bridge.subscribe(runId, emitter);
        startHeartbeat(emitter);

        log.info("断线重连成功: runId={}, lastEventId={}", runId, lastEventId);
        return emitter;
    }

    // ========================================================================
    // Exception Handlers
    // ========================================================================

    /**
     * 并发 Run 冲突 → 409 Conflict + JSON 错误体
     */
    @ExceptionHandler(ConcurrentRunException.class)
    public ResponseEntity<Map<String, String>> handleConcurrentRun(ConcurrentRunException e) {
        log.warn("并发 Run 冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "CONFLICT", "message", "该会话已有正在进行的对话"));
    }

    /**
     * 数据库访问异常 → 503 Service Unavailable
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> handleDataAccess(DataAccessException e) {
        log.error("数据库访问异常", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "SERVICE_UNAVAILABLE", "message", "数据库暂时不可用，请稍后重试"));
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 启动心跳定时器。
     *
     * <p>每 heartbeatInterval 秒发送一个 SSE 注释行（{@code :heartbeat}），保持连接活跃。
     * 使用 {@code SseEmitter.event().comment("heartbeat")} 产生标准 SSE 注释行，
     * 而非命名事件（SseEmitter.event().name() 设置的是 event: 字段，非注释行）。
     * SseEmitter 支持多个 onCompletion/onTimeout/onError 回调（List 存储），
     * 因此 bridge.subscribe 注册的回调与这里的 heartbeat.cancel 回调可共存。
     */
    private void startHeartbeat(SseEmitter emitter) {
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        // 发送 SSE 注释行 :heartbeat（非命名事件），符合 SSE 协议保活规范
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException e) {
                        // emitter 已关闭，取消定时器
                    }
                },
                streamProperties.heartbeatInterval(),
                streamProperties.heartbeatInterval(),
                TimeUnit.SECONDS);

        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> heartbeat.cancel(false));
        emitter.onError(e -> heartbeat.cancel(false));
    }

    /**
     * 截取查询前 30 字符作为会话标题。
     */
    private String truncateTitle(String query) {
        if (query == null || query.isBlank()) return "新对话";
        return query.length() > 30 ? query.substring(0, 30) + "..." : query;
    }

    /**
     * F2-9: 从 PG chat_message 表降级回放历史消息到 emitter（§3.6）。
     *
     * <p>当 ring buffer 已覆盖（lastEventId 太旧）时，查 PG chat_message 表
     * 按 runId 获取历史消息，转换为 SSE 事件推送到 emitter。
     * 降级不终止，记 warn。
     *
     * @param runId       Run 唯一标识（字符串）
     * @param lastEventId 客户端最后收到的 eventId（用于 seq 续编号）
     * @param emitter     SSE 订阅者
     * @return true=至少回放了一条消息；false=PG 无数据或回放失败
     */
    private boolean replayFromPg(String runId, long lastEventId, SseEmitter emitter) {
        try {
            Long runIdLong = Long.parseLong(runId);
            List<ChatMessage> messages = chatMessageService.findByRunId(runIdLong);
            if (messages == null || messages.isEmpty()) {
                log.warn("PG 降级回放: runId={} 无历史消息", runId);
                return false;
            }

            long seq = lastEventId;
            for (ChatMessage msg : messages) {
                // 跳过用户消息（客户端已有用户查询）
                if ("USER".equals(msg.getRole())) {
                    continue;
                }

                seq++;
                String eventType;
                String payload;

                if ("thinking".equals(msg.getMessageType())) {
                    eventType = SseEventType.THINKING.getEventName();
                    payload = "{\"delta\":\"" + escapeJson(msg.getContent()) + "\"}";
                } else if ("TOOL_CALL".equals(msg.getMessageType())) {
                    eventType = SseEventType.TOOL_CALL.getEventName();
                    payload = msg.getContent() != null ? msg.getContent() : "{}";
                } else if ("TOOL_RESULT".equals(msg.getMessageType())) {
                    eventType = SseEventType.TOOL_RESULT.getEventName();
                    payload = msg.getContent() != null ? msg.getContent() : "{}";
                } else {
                    // 普通助手消息 → DELTA 事件
                    eventType = SseEventType.DELTA.getEventName();
                    payload = "{\"text\":\"" + escapeJson(msg.getContent()) + "\"}";
                }

                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(seq))
                            .name(eventType)
                            .data(payload));
                } catch (IOException e) {
                    log.warn("PG 降级回放: emitter 发送失败 runId={} seq={}", runId, seq);
                    break;
                }
            }

            log.info("PG 降级回放完成: runId={}, 消息数={}", runId, messages.size());
            return true;
        } catch (NumberFormatException e) {
            log.warn("PG 降级回放: runId 解析失败 runId={}", runId);
            return false;
        } catch (Exception e) {
            log.warn("PG 降级回放失败: runId={}", runId, e);
            return false;
        }
    }

    /**
     * Run 归属校验 —— runId 必须属于当前用户（P0-3 对话端点 IDOR 修复）
     *
     * <p>runId 非法或不存在或不属于当前用户 → 404（不泄露存在性）。
     *
     * @param runId  Run 唯一标识（字符串）
     * @param userId 当前登录用户 ID
     */
    private void checkRunOwnership(String runId, Long userId) {
        Long runIdLong;
        try {
            runIdLong = Long.parseLong(runId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在");
        }
        ChatRun run = chatRunService.findById(runIdLong);
        if (run == null || !run.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在");
        }
    }

    /**
     * 简单 JSON 字符串转义（用于 PG 降级回放时构造 payload）。
     */
    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
