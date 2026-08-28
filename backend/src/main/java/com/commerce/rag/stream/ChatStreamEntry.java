package com.commerce.rag.stream;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatRunVO;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.worker.ChatRequestWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Chat 对话编排入口（SSE 生命周期/Redis 入队/心跳/归属校验），供 ChatController 与 StudentController 共用。
 *
 * <p>负责 Chat SSE 对话的完整编排，供以下端点调用：
 * <ol>
 *   <li>POST /api/v1/student/chat — 发起对话，返回 SseEmitter</li>
 *   <li>POST /api/v1/student/chat/{runId}/cancel — 取消正在执行的 run</li>
 *   <li>GET /api/v1/student/chat/{runId}/reconnect — 断线重连</li>
 * </ol>
 *
 * <p>鉴权：通过 AuthInterceptor 注入的 request attribute 获取已认证用户 ID。
 * 角色门禁（P2-4 用户裁决）由暴露端点的 Controller 声明——允许 C 端学生与 B 端角色
 * （TEACHER/SUPER_ADMIN）使用对话能力。
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
@Component
@RequiredArgsConstructor
public class ChatStreamEntry {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamEntry.class);

    /** SseEmitter 超时时间：30 分钟 */
    private static final long EMITTER_TIMEOUT = 30 * 60 * 1000L;

    private final ChatRequestWorker worker;
    private final MemoryStreamBridge bridge;
    private final IChatRunService chatRunService;
    private final IChatSessionService chatSessionService;
    private final IChatMessageService chatMessageService;
    private final StringRedisTemplate redisTemplate;
    private final StreamProperties streamProperties;
    private final ObjectMapper objectMapper;

    /** 心跳定时器线程池 */
    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        // L-7: 心跳线程数 2→4——慢客户端 send 阻塞时避免饿死全部连接心跳
        // （根治方案=心跳发送走投递线程/加超时，线程扩容为低成本缓解）
        scheduler = new ScheduledThreadPoolExecutor(4, r -> {
            Thread t = new Thread(r, "chat-heartbeat");
            t.setDaemon(true);
            return t;
        });
        log.info("ChatStreamEntry 心跳调度器已启动: interval={}s", streamProperties.heartbeatInterval());
    }

    @PreDestroy
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            log.info("ChatStreamEntry 心跳调度器已关闭");
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
     *   <li>chatRunService.createRun — 并发守卫（ConcurrentRunException → 全局 409）</li>
     *   <li>创建 SseEmitter + bridge.createRing + bridge.subscribe（先订阅再入队，确保不丢事件）</li>
     *   <li>XADD 消息到 Redis Stream：{runId, sessionId, userId, query}</li>
     *   <li>启动心跳定时器（每 heartbeatInterval 秒发 heartbeat 事件）</li>
     * </ol>
     *
     * <p>⚠️ 顺序关键：createRing → subscribe → XADD。
     * 先创建 ring 确保 subscribe 不失败，先 subscribe 再 XADD 确保不丢事件。
     * Worker 的 createRing 用 computeIfAbsent，幂等。
     */
    public SseEmitter chat(HttpServletRequest httpRequest, ChatRequest request) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);

        // 0. 参数校验
        if (request.query() == null || request.query().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "query 不能为空");
        }

        // 1. 会话处理（含归属校验：sessionId 非空时必须是当前用户的会话）
        Long sessionId = request.sessionId();
        if (sessionId == null) {
            SessionVO session = chatSessionService.createSession(userId, truncateTitle(request.query()));
            sessionId = session.id();
        } else {
            // P0-3: sessionId 归属校验——传入他人会话 ID 直接拒绝
            ChatSessionVO session = chatSessionService.findById(sessionId);
            if (session == null || !session.userId().equals(userId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "无权操作此会话");
            }
        }

        // 2. 创建 run（并发守卫 → ConcurrentRunException 由全局异常处理器统一转 409）
        ChatRunVO run = chatRunService.createRun(sessionId, userId);
        String runId = run.id().toString();

        // 3. 创建 SseEmitter（30 分钟超时）
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

        // 4. 创建 ring + 订阅（先创建 ring 再订阅，确保不丢事件）
        //    Worker 的 createRing 用 computeIfAbsent，ChatStreamEntry 先创建是幂等操作
        bridge.createRing(runId);
        bridge.subscribe(runId, emitter);

        // 5. XADD 入队（subscribe 之后再入队，确保 Worker 推送的事件能到达 emitter）
        // 本次输入附件 → JSON 数组字符串（worker 消费后落 chat_run/chat_message 的 attachments_json，spec §5.1）
        String attachmentsJson = request.attachments() == null ? "[]" : new Gson().toJson(request.attachments());
        Map<String, String> message = new LinkedHashMap<>();
        message.put("runId", runId);
        message.put("sessionId", sessionId.toString());
        message.put("userId", userId.toString());
        message.put("query", request.query());
        message.put("attachments", attachmentsJson);
        try {
            redisTemplate.opsForStream().add(streamProperties.requestStream(), message);
        } catch (Exception e) {
            // P0-4c 修复：入队失败回滚 run 状态（解除 uniq_active_run_per_session 唯一索引锁死）
            // + 清理 ring。复合故障（Redis+DB 双挂）下 updateStatus 抛异常也必须清理 ring，
            // 故 removeRing 放 finally（修复审查 finding：锁死未解除 + ring 泄漏）
            log.error("XADD 入队失败，回滚 run: runId={}", runId, e);
            try {
                chatRunService.updateStatus(run.id(), "ERROR");
            } catch (Exception dbEx) {
                log.error("XADD 失败后回滚 run 状态失败: runId={}", runId, dbEx);
            } finally {
                bridge.removeRing(runId);
            }
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "消息队列暂不可用，请稍后重试");
        }

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
     *
     * <p>B2-7 终态校验：已终态（COMPLETED/CANCELLED/ERROR）的 run 无可取消对象，
     * 直接 409 拒绝——否则 worker.cancel 会在 cancelFlags 无条件新建条目，而其唯一
     * 清理路径（processRequest.finally）早已执行，条目将永久残留（内存泄漏）。
     */
    public ResponseEntity<Void> cancel(String runId, HttpServletRequest httpRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        ChatRunVO run = checkRunOwnership(runId, userId);
        // B2-7: 终态 run 拒绝取消（409 语义），不再写 cancelFlags
        if (run != null && isTerminalStatus(run.status())) {
            throw new BizException(ErrorCode.CONFLICT, "Run 已结束，无法取消");
        }
        worker.cancel(runId);
        log.info("取消请求已发送: runId={}", runId);
        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // GET /api/v1/student/chat/{runId}/reconnect — 断线重连
    // ========================================================================

    /**
     * 断线重连：原子「回放 + 订阅」恢复事件流。
     *
     * <p>归属校验：run 必须属于当前用户（P0-3，不匹配 404 不泄露存在性）。
     *
     * <p>流程：
     * <ol>
     *   <li>创建新 SseEmitter</li>
     *   <li>bridge.replayAndSubscribe(runId, lastEventId, emitter)
     *       → true：回放 lastEventId 之后的事件并注册 emitter（P1-2，回放与订阅原子，
     *       与 Worker 推送并发下不丢不重），启动心跳即可，无需再 subscribe</li>
     *   <li>返回 false：ring 不存在或 lastEventId 已被覆盖
     *       → F2-9: 降级查 PG chat_message 表，回放历史消息（§3.6）</li>
     *   <li>PG 回放成功 + run 已终态（COMPLETED/CANCELLED/ERROR，ring 已被 Worker 移除）
     *       → 补发 end 事件 + complete（P1-2，避免前端状态机永久停在"生成中"）</li>
     *   <li>PG 回放成功 + run 仍活跃 → 继续 subscribe 接收后续事件 + 启动心跳</li>
     * </ol>
     */
    public SseEmitter reconnect(String runId, long lastEventId, HttpServletRequest httpRequest) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

        // P1-2: 原子「回放 + 订阅」——回放 lastEventId 之后的事件并注册 emitter，
        // 与 Worker 推送并发下不丢不重（消除旧 replay→subscribe 两步之间的窗口竞态）
        boolean success = bridge.replayAndSubscribe(runId, lastEventId, emitter);
        if (!success) {
            // F2-9: ring buffer 已覆盖/不存在 → 降级查 PG chat_message 表，replay 历史消息（§3.6）
            log.warn("ring 回放失败，降级查 PG: runId={}, lastEventId={}", runId, lastEventId);
            long lastSeq = replayFromPg(runId, lastEventId, emitter);
            if (lastSeq < 0) {
                // P2-10 修复: run 仍在执行时（ring 覆盖、PG 尚无消息——持久化在 run 结束）
                // 不得误报 REPLAY_FAILED 终态——仅订阅继续收实时事件，历史缺失可接受
                ChatRunVO run = chatRunService.findById(Long.parseLong(runId));
                if (run != null && !isTerminalStatus(run.status())) {
                    // P1-4: 订阅返回 false 说明 ring 恰在此时被关闭（run 完成）——补查终态补发 end
                    if (!bridge.subscribe(runId, emitter)) {
                        ChatRunVO closedRun = chatRunService.findById(Long.parseLong(runId));
                        if (closedRun != null && isTerminalStatus(closedRun.status())) {
                            // R2 补口 B：COMPLETED 终态补 assistant 正文行 messageId（M2：解析方法复查
                            // run 状态）；CANCELLED/ERROR 不带 messageId（半截内容不作反馈目标）
                            String payload = "COMPLETED".equals(closedRun.status())
                                    ? buildEndPayload(runId, closedRun.status(), resolveAssistantMessageId(runId))
                                    : buildEndPayload(runId, closedRun.status());
                            try {
                                emitter.send(SseEmitter.event()
                                        .name(SseEventType.END.getEventName())
                                        .data(payload));
                            } catch (IOException ex) {
                                // emitter 已关闭，忽略
                            }
                        }
                        emitter.complete();
                        return emitter;
                    }
                    startHeartbeat(emitter);
                    log.info("ring 已覆盖且 run 仍活跃，降级为仅订阅实时事件: runId={}", runId);
                    return emitter;
                }
                // run 终态且 PG 也无数据 → 历史不可恢复，返回 error 事件（真失败）
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
            // P1-2 终态判定：run 已完成（ring 已移除）→ 补发 end 事件 + complete，
            // 否则新 emitter 收不到 end，前端状态机永久停在"生成中"
            ChatRunVO run = chatRunService.findById(Long.parseLong(runId));
            if (run != null && isTerminalStatus(run.status())) {
                // R2 补口 B：COMPLETED 终态补 assistant 正文行 messageId（resolveAssistantMessageId
                // 内部复查 M2 状态过滤）；CANCELLED/ERROR 不带 messageId 键
                String payload = "COMPLETED".equals(run.status())
                        ? buildEndPayload(runId, run.status(), resolveAssistantMessageId(runId))
                        : buildEndPayload(runId, run.status());
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(lastSeq + 1))
                            .name(SseEventType.END.getEventName())
                            .data(payload));
                    emitter.complete();
                } catch (IOException e) {
                    // emitter 已关闭，忽略
                }
                log.info("run 已终态，补发 end 事件收尾: runId={}, status={}", runId, run.status());
                return emitter;
            }
            // 非终态：run 仍在执行，继续订阅接收后续事件
            // P1-4: subscribe 返回 false 说明 ring 恰在判定与订阅之间被关闭（run 已完成）——
            // 补查终态并补发 end，避免新 emitter 无事件无 end 永久"生成中"
            if (!bridge.subscribe(runId, emitter)) {
                ChatRunVO closedRun = chatRunService.findById(Long.parseLong(runId));
                if (closedRun != null && isTerminalStatus(closedRun.status())) {
                    // R2 补口 B：COMPLETED 终态补 assistant 正文行 messageId（M2 状态过滤）；
                    // CANCELLED/ERROR 不带 messageId 键
                    String payload = "COMPLETED".equals(closedRun.status())
                            ? buildEndPayload(runId, closedRun.status(), resolveAssistantMessageId(runId))
                            : buildEndPayload(runId, closedRun.status());
                    try {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(lastSeq + 1))
                                .name(SseEventType.END.getEventName())
                                .data(payload));
                    } catch (IOException ex) {
                        // emitter 已关闭，忽略
                    }
                }
                emitter.complete();
                log.info("订阅时 ring 已关闭，补发终态收尾: runId={}", runId);
                return emitter;
            }
            startHeartbeat(emitter);
            log.info("PG 降级回放成功: runId={}, lastEventId={}", runId, lastEventId);
            return emitter;
        }

        // 回放成功：replayAndSubscribe 已注册 emitter，无需再 subscribe；启动心跳
        startHeartbeat(emitter);

        log.info("断线重连成功: runId={}, lastEventId={}", runId, lastEventId);
        return emitter;
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
     * <p>当 ring buffer 已覆盖（lastEventId 太旧）或 ring 不存在时，查 PG chat_message 表
     * 按 runId 获取历史消息，转换为 SSE 事件推送到 emitter。
     * 降级不终止，记 warn。
     *
     * @param runId       Run 唯一标识（字符串）
     * @param lastEventId 客户端最后收到的 eventId（用于 seq 续编号）
     * @param emitter     SSE 订阅者
     * @return 最后回放的 seq（回放成功）；-1=PG 无数据或回放失败
     */
    private long replayFromPg(String runId, long lastEventId, SseEmitter emitter) {
        try {
            Long runIdLong = Long.parseLong(runId);
            List<ChatMessageVO> messages = chatMessageService.findByRunId(runIdLong);
            if (messages == null || messages.isEmpty()) {
                log.warn("PG 降级回放: runId={} 无历史消息", runId);
                return -1;
            }

            long seq = lastEventId;
            for (ChatMessageVO msg : messages) {
                // 跳过用户消息（客户端已有用户查询）
                if ("USER".equals(msg.role())) {
                    continue;
                }

                seq++;
                String eventType;
                String payload;

                if ("thinking".equals(msg.messageType())) {
                    eventType = SseEventType.THINKING.getEventName();
                    payload = "{\"delta\":\"" + escapeJson(msg.content()) + "\"}";
                } else if ("TOOL_CALL".equals(msg.messageType())) {
                    // P1-2: 与实时 TOOL_CALL 事件 schema 对齐（toolCallId/toolName/input）——
                    // 新格式直接透传；历史旧格式（{"tool","args"}）重建，保证前端按 toolCallId 配对
                    eventType = SseEventType.TOOL_CALL.getEventName();
                    payload = normalizeToolPayload(msg.content(), true);
                } else if ("TOOL_RESULT".equals(msg.messageType())) {
                    // P1-2: 与实时 TOOL_RESULT 事件 schema 对齐（toolCallId/status/output）
                    eventType = SseEventType.TOOL_RESULT.getEventName();
                    payload = normalizeToolPayload(msg.content(), false);
                } else if ("query_plan".equals(msg.messageType())) {
                    // 2026-08-28 时间线改版：query_plan 行 content 即实时 query_plan 事件同款 JSON
                    // （单一构造点 SseEventTransformer.buildQueryPlanPayload）——同名事件原样透传
                    // 保持回放与实时契约一致；不得落入 else 分支被当正文 DELTA 泄漏 JSON
                    eventType = SseEventType.QUERY_PLAN.getEventName();
                    payload = msg.content();
                } else {
                    // 普通助手消息 → DELTA 事件
                    eventType = SseEventType.DELTA.getEventName();
                    payload = "{\"text\":\"" + escapeJson(msg.content()) + "\"}";
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
            return seq;
        } catch (NumberFormatException e) {
            log.warn("PG 降级回放: runId 解析失败 runId={}", runId);
            return -1;
        } catch (Exception e) {
            log.warn("PG 降级回放失败: runId={}", runId, e);
            return -1;
        }
    }

    /**
     * 判断 run 是否已处于终态（COMPLETED/CANCELLED/ERROR）。
     *
     * <p>终态 run 的 ring 已被 Worker 移除，重连时无法通过事件流收到 end 事件，
     * 需由服务端按 run 状态补发（P1-2）。
     */
    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "ERROR".equals(status);
    }

    /**
     * 解析 run 最终 assistant 正文行的消息 ID（R2 补口 B，reconnect 补发 end 事件的 messageId 来源）。
     *
     * <p>按 run_id 查消息表，反向扫描最后一条 role=ASSISTANT 且 messageType==null 的正文行
     * （最终回答，跳过 thinking/TOOL_* 行），ID 字符串化返回（与 runId 字符串风格一致）。
     *
     * <p>审核修正 M2（强制）：必须校验 run.status==COMPLETED 才返回 ID——取消/异常路径的
     * 半截 assistant 正文行虽已落库，但不得作为反馈目标（与实时路径「CANCELLED/ERROR 终态
     * 不带 messageId」语义对齐）。异常/未落库窗口返回 null（正常降级，前端 {@code messageId?} 可空容忍）。
     *
     * @param runId Run 唯一标识（字符串，归属校验已通过）
     * @return assistant 正文行消息 ID 字符串；run 非 COMPLETED / 无正文行 / 查询异常时返回 null
     */
    private String resolveAssistantMessageId(String runId) {
        try {
            Long runIdLong = Long.parseLong(runId);
            // M2 状态过滤：仅 COMPLETED run 的 assistant 正文行可作反馈目标
            ChatRunVO run = chatRunService.findById(runIdLong);
            if (run == null || !"COMPLETED".equals(run.status())) {
                return null;
            }
            List<ChatMessageVO> messages = chatMessageService.findByRunId(runIdLong);
            if (messages == null || messages.isEmpty()) {
                return null;
            }
            // 反向扫描：消息按 seq 升序返回，取最后一条正文行即「最终回答」
            for (int i = messages.size() - 1; i >= 0; i--) {
                ChatMessageVO msg = messages.get(i);
                if ("ASSISTANT".equals(msg.role()) && msg.messageType() == null && msg.id() != null) {
                    return msg.id().toString();
                }
            }
            return null;
        } catch (Exception e) {
            // 解析/查询失败不得阻断 end 补发——messageId 降级 null
            log.warn("解析 assistant 消息 ID 失败，end 事件 messageId 降级为 null: runId={}", runId, e);
            return null;
        }
    }

    /**
     * 构建 end 事件 payload（R2 补口 B，CANCELLED/ERROR 终态变体——不带 messageId 键）。
     *
     * <p>runId/status 均来自服务端白名单值（数字 ID + 枚举状态），手工拼接安全。
     *
     * @param runId  Run 唯一标识（字符串）
     * @param status run 终态（CANCELLED/ERROR）
     * @return {@code {"runId":"...","status":"..."}}（无 messageId 键——半截内容不作反馈目标）
     */
    private String buildEndPayload(String runId, String status) {
        return "{\"runId\":\"" + runId + "\",\"status\":\"" + status + "\"}";
    }

    /**
     * 构建 end 事件 payload（R2 补口 B，COMPLETED 终态变体——追加 messageId 字段）。
     *
     * <p>messageId 恒字符串或显式 null（异常/未落库窗口无法解析时 null，前端可空容忍）。
     *
     * @param runId      Run 唯一标识（字符串）
     * @param status     run 终态（COMPLETED）
     * @param messageId assistant 正文行消息 ID 字符串（可为 null）
     * @return {@code {"runId":"...","status":"...","messageId":"..."|null}}
     */
    private String buildEndPayload(String runId, String status, String messageId) {
        return "{\"runId\":\"" + runId + "\",\"status\":\"" + status + "\",\"messageId\":"
                + (messageId == null ? "null" : "\"" + messageId + "\"") + "}";
    }

    /**
     * Run 归属校验 —— runId 必须属于当前用户（P0-3 对话端点 IDOR 修复）
     *
     * <p>runId 非法或不存在或不属于当前用户 → 404（不泄露存在性）。
     *
     * @param runId  Run 唯一标识（字符串）
     * @param userId 当前登录用户 ID
     * @return 校验通过的 Run 视图对象（调用方可复用其状态等字段，避免二次查询）
     */
    private ChatRunVO checkRunOwnership(String runId, Long userId) {
        Long runIdLong;
        try {
            runIdLong = Long.parseLong(runId);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.NOT_FOUND, "Run 不存在");
        }
        ChatRunVO run = chatRunService.findById(runIdLong);
        if (run == null || !run.userId().equals(userId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "Run 不存在");
        }
        return run;
    }

    /**
     * 将落库的工具消息内容归一化为实时事件 schema（P1-2）
     *
     * <p>新格式（含 toolCallId）直接透传；历史旧格式（TOOL_CALL: {"tool","args"} /
     * TOOL_RESULT: {"tool","result"}）重建为实时字段（toolCallId 缺失用空串）。
     * 解析失败时原样返回（不中断回放）。
     *
     * @param content    落库的 content JSON 字符串
     * @param isToolCall true=TOOL_CALL 事件；false=TOOL_RESULT 事件
     * @return 实时 schema 的 payload JSON 字符串
     */
    private String normalizeToolPayload(String content, boolean isToolCall) {
        if (content == null || content.isBlank()) {
            return "{}";
        }
        try {
            JsonNode node = objectMapper.readTree(content);
            // 新格式已含 toolCallId，直接透传
            if (node.has("toolCallId")) {
                return content;
            }
            // 旧格式重建：{"tool":"<name>","args":...} / {"tool":"<name>","result":"..."}
            String toolName = node.path("tool").asText("");
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            rebuilt.put("toolCallId", "");
            if (isToolCall) {
                rebuilt.put("toolName", toolName);
                rebuilt.put("input", node.has("args") ? node.get("args") : Map.of());
            } else {
                rebuilt.put("status", "success");
                rebuilt.put("output", node.path("result").asText(""));
            }
            return objectMapper.writeValueAsString(rebuilt);
        } catch (Exception e) {
            log.warn("工具消息 payload 归一化失败，原样返回: {}", content, e);
            return content;
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
