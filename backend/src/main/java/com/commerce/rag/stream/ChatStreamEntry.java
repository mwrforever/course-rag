package com.commerce.rag.stream;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ChatReplayRequest;
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
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
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
 *   <li>POST /api/v1/student/chat/session/{sessionId}/replay — 消息级重放（M5 编辑/重新生成）</li>
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
    /** checkpoint saver（M5 replay 定位与回滚：__START__ 锚点枚举 + 回滚写入；单例 Bean） */
    private final BaseCheckpointSaver saver;

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
     *   <li>创建 SseEmitter + 设置防代理缓冲响应头（Cache-Control: no-cache, no-transform /
     *       X-Accel-Buffering: no，宪法 C.1.9）+ bridge.createRing + bridge.subscribe（先订阅再入队，确保不丢事件）</li>
     *   <li>XADD 消息到 Redis Stream：{runId, sessionId, userId, query}</li>
     *   <li>启动心跳定时器（每 heartbeatInterval 秒发 heartbeat 事件）</li>
     * </ol>
     *
     * <p>⚠️ 顺序关键：createRing → subscribe → XADD。
     * 先创建 ring 确保 subscribe 不失败，先 subscribe 再 XADD 确保不丢事件。
     * Worker 的 createRing 用 computeIfAbsent，幂等。
     */
    public SseEmitter chat(HttpServletRequest httpRequest, HttpServletResponse httpResponse, ChatRequest request) {

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

        // 3. 创建 SseEmitter（30 分钟超时）；创建即设置防代理缓冲响应头——必须在 emitter
        //    首次写出前（本方法返回前）经原始 HttpServletResponse 设置，SseEmitter 自身
        //    不提供设置 HTTP 头的入口（宪法 C.1.9：流式端点必须禁代理缓冲）
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        applyNoProxyBufferHeaders(httpResponse);

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
            // BUG-14：XADD 成功后注册取消条目（生命周期「入队 → processRequest.finally 清理」），
            // 使 worker.cancel 的 computeIfPresent 在排队期与执行期均能命中；runPool 拒绝路径
            // 与 finally 均负责清理，注册不引入残留
            worker.registerPendingRun(runId);
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
     * 直接 409 拒绝。BUG-14 后 worker.cancel 改 computeIfPresent 仅对已注册条目置位
     * （条目生命周期「入队注册 → processRequest.finally 清理」），即使本校验与置位
     * 之间 run 恰好完成（条目已被 finally 清理），置位也为 no-op，不再产生残留条目。
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
    public SseEmitter reconnect(
            String runId, long lastEventId, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);

        // 创建新 SseEmitter 并设置防代理缓冲响应头（归属校验已通过，即将进入流式回放；
        // 404 校验失败路径不会走到这里，错误 JSON 响应不携带流式专用头）
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        applyNoProxyBufferHeaders(httpResponse);

        // P1-2: 原子「回放 + 订阅」——回放 lastEventId 之后的事件并注册 emitter，
        // 与 Worker 推送并发下不丢不重（消除旧 replay→subscribe 两步之间的窗口竞态）
        boolean success = bridge.replayAndSubscribe(runId, lastEventId, emitter);
        if (!success) {
            // F2-9: ring buffer 已覆盖/不存在 → 降级查 PG chat_message 表，replay 历史消息（§3.6）
            log.warn("ring 回放失败，降级查 PG: runId={}, lastEventId={}", runId, lastEventId);
            PgReplayOutcome replay = replayFromPg(runId, lastEventId, emitter);
            if (replay.lastSeq() < 0) {
                // P2-10 修复: run 仍在执行时（ring 覆盖、PG 尚无消息——持久化在 run 结束）
                // 不得误报 REPLAY_FAILED 终态——仅订阅继续收实时事件，历史缺失可接受
                ChatRunVO run = chatRunService.findById(Long.parseLong(runId));
                if (run != null && !isTerminalStatus(run.status())) {
                    // P1-4: 订阅返回 false 说明 ring 恰在此时被关闭（run 完成）——补查终态补发 end
                    if (!bridge.subscribe(runId, emitter)) {
                        ChatRunVO closedRun = chatRunService.findById(Long.parseLong(runId));
                        if (closedRun != null && isTerminalStatus(closedRun.status())) {
                            // R2 补口 B + 2026-09-03 停止态改版：COMPLETED/CANCELLED 终态补
                            // assistant 正文行 messageId（M2：解析方法复查 run 状态——PERF-13
                            // 复用已查 closedRun，免重复查询；CANCELLED 半截正文行同样可反馈）；
                            // ERROR 不带 messageId（失败内容不作反馈目标）
                            String payload = "ERROR".equals(closedRun.status())
                                    ? buildEndPayload(runId, closedRun.status())
                                    : buildEndPayload(
                                            runId, closedRun.status(), resolveAssistantMessageId(runId, closedRun));
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
                // R2 补口 B + 2026-09-03 停止态改版：COMPLETED/CANCELLED 终态补 assistant 正文行
                // messageId（PERF-13：M2 状态过滤与正文行扫描均在已查 run + PG 回放消息上比对，
                // 免重复查询；CANCELLED 半截正文行同样可反馈）；ERROR 不带 messageId
                String payload = "ERROR".equals(run.status())
                        ? buildEndPayload(runId, run.status())
                        : buildEndPayload(runId, run.status(), resolveAssistantMessageId(run, replay.messages()));
                try {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(replay.lastSeq() + 1))
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
                    // R2 补口 B + 2026-09-03 停止态改版：COMPLETED/CANCELLED 终态补 assistant
                    // 正文行 messageId（M2 状态过滤——PERF-13 复用已查 closedRun；CANCELLED
                    // 半截正文行同样可反馈）；ERROR 不带 messageId
                    String payload = "ERROR".equals(closedRun.status())
                            ? buildEndPayload(runId, closedRun.status())
                            : buildEndPayload(runId, closedRun.status(), resolveAssistantMessageId(runId, closedRun));
                    try {
                        emitter.send(SseEmitter.event()
                                .id(String.valueOf(replay.lastSeq() + 1))
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
    // POST /api/v1/student/chat/session/{sessionId}/replay — 消息级重放（M5）
    // ========================================================================

    /**
     * 消息级重放入口（M5，spec D2/D5）：编辑最后一条用户消息重答（EDIT）/
     * 重新生成最后一条回答（REGENERATE）。
     *
     * <p>编排流程（校验前置、副作用事务化、任何校验失败都在软删发生前中止）：
     * <ol>
     *   <li>会话归属校验（404 不存在 / 403 非本人，与 chat() 先例一致）</li>
     *   <li>mode 白名单校验（400）；EDIT 时 query 非空校验（400）</li>
     *   <li>targetRun 归属与软删校验：run 属于该 session 且未软删
     *       （{@code findById} 过 @TableLogic，重复 replay 已软删 → null → 409 防重）</li>
     *   <li>位置校验（D5）：目标 run 之后无 deleted=0 run（否则 409）</li>
     *   <li>活跃校验：session 无 QUEUED/ACTIVE run（否则 409「正在回答中」；
     *       uniq_active_run_per_session 兜底并发窗口）</li>
     *   <li>checkpoint 定位与回滚（R2 实证 {@code __START__} 锚点方案）：
     *       写新 checkpoint 载目标状态（thread_id 不变、纯 INSERT 不删历史，红线①）；
     *       定位失败抛 409——锚点不可得时不执行软删（防数据与 checkpoint 脱节）</li>
     *   <li>软删事务：消息行软删（softDeleteFromRun）+ run 行软删与新 QUEUED run
     *       创建（prepareReplayRun，事务内原子）</li>
     *   <li>复用 chat() 的流式链路：createRing → subscribe → XADD（消息体携带
     *       replayMode 语义标记；EDIT 带新 query，REGENERATE 带原问题文本供消息行
     *       持久化）→ 心跳 → 更新会话最后消息时间</li>
     * </ol>
     *
     * @param httpRequest  请求（AuthInterceptor 注入的用户属性，非 null）
     * @param httpResponse 响应（SSE 防代理缓冲头，见 applyNoProxyBufferHeaders）
     * @param sessionId    会话 ID（路径参数）
     * @param request      重放请求（mode/query/targetRunId，Bean Validation 已过）
     * @return SSE 流（新 run 的事件流，复用 chat() 的 ring→subscribe→XADD 链路）
     * @throws BizException 400 参数非法（mode 白名单外 / EDIT 空 query）；
     *         403 非本人会话；404 会话不存在；409 目标 run 失效 / 位置校验失败 /
     *         正在回答中 / checkpoint 锚点不可得
     */
    public SseEmitter replay(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse,
            Long sessionId,
            ChatReplayRequest request) {
        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        // ① 会话归属校验（与 chat()/R1 历史消息先例一致：404 不存在 / 403 非本人）
        ChatSessionVO session = chatSessionService.findById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        if (!session.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作此会话");
        }
        // ② 模式与参数校验（Bean Validation 之外的业务条件：EDIT 必带非空 query）
        boolean isEdit = "EDIT".equals(request.mode());
        if (!isEdit && !"REGENERATE".equals(request.mode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "mode 仅支持 EDIT / REGENERATE");
        }
        if (isEdit && (request.query() == null || request.query().isBlank())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "EDIT 模式必须携带新问题文本");
        }
        // ③ 目标 run 归属与软删校验：findById 过 @TableLogic——已软删（重复 replay）返回
        //    null，非本会话 run 同样拒绝（409 语义：目标消息已失效，提示刷新）
        ChatRunVO targetRun = chatRunService.findById(request.targetRunId());
        if (targetRun == null || !targetRun.sessionId().equals(sessionId)) {
            throw new BizException(ErrorCode.CONFLICT, "目标消息已失效，请刷新后重试");
        }
        // ④ 位置校验（D5）：目标 run 之后无未删除 run（EDIT/REGENERATE 均限最后一条）
        if (chatRunService.existsRunAfter(sessionId, request.targetRunId())) {
            throw new BizException(ErrorCode.CONFLICT, "仅支持对最后一条消息执行编辑或重新生成");
        }
        // ⑤ 活跃校验：正在回答中拒绝（校验后并发窗口由 uniq_active_run_per_session 兜底）
        if (chatRunService.existsActiveRun(sessionId)) {
            throw new BizException(ErrorCode.CONFLICT, "正在回答中，请稍后操作");
        }
        // ⑥ checkpoint 定位与回滚（红线①：写入 config 不带 checkPointId，纯 INSERT 不删历史）；
        //    定位/校验失败抛 409，此时软删尚未发生，会话数据无损
        String targetUserQuery = rollbackCheckpointBeforeRun(sessionId, isEdit, request.targetRunId());
        // ⑦ 软删事务：消息行先行软删（EDIT 与 REGENERATE 统一 runId>=targetRunId 范围——
        //    REGENERATE 时目标即最后一个 run，范围等价仅目标 run），run 软删与新 run 同事务
        chatMessageService.softDeleteFromRun(sessionId, request.targetRunId());
        ChatRunVO run = chatRunService.prepareReplayRun(sessionId, userId, request.mode(), request.targetRunId());
        String runId = run.id().toString();
        // ⑧ 复用 chat() 的流式链路（ring → subscribe → XADD → 心跳；先订阅再入队不丢事件）
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        applyNoProxyBufferHeaders(httpResponse);
        bridge.createRing(runId);
        bridge.subscribe(runId, emitter);
        Map<String, String> message = new LinkedHashMap<>();
        message.put("runId", runId);
        message.put("sessionId", sessionId.toString());
        message.put("userId", userId.toString());
        // EDIT：新问题文本作为 query（worker 组装 UserMessage 图输入）；
        // REGENERATE：携带目标 run 落库的原问题文本——图输入 messages 置空（checkpoint 已含
        // 原用户消息，由 replayMode 标记驱动 worker 特判），query 仅供 chat_message USER 行持久化
        message.put("query", isEdit ? request.query() : targetUserQuery);
        message.put("attachments", "[]");
        // replay 语义标记：worker 据此把图输入组装为空 messages（从回滚 checkpoint 续跑重新生成）
        message.put("replayMode", request.mode());
        try {
            redisTemplate.opsForStream().add(streamProperties.requestStream(), message);
            // BUG-14 同款：XADD 成功后注册取消条目（生命周期「入队 → processRequest.finally 清理」）
            worker.registerPendingRun(runId);
        } catch (Exception e) {
            // 入队失败回滚 run 状态（解除 uniq_active_run_per_session 锁死）+ 清理 ring（P0-4c）
            log.error("replay XADD 入队失败，回滚 run: runId={}", runId, e);
            try {
                chatRunService.updateStatus(run.id(), "ERROR");
            } catch (Exception dbEx) {
                log.error("replay XADD 失败后回滚 run 状态失败: runId={}", runId, dbEx);
            } finally {
                bridge.removeRing(runId);
            }
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "消息队列暂不可用，请稍后重试");
        }
        // ⑨ 心跳 + 会话最后消息时间（与 chat() 收尾一致）
        startHeartbeat(emitter);
        chatSessionService.updateLastMessageAt(sessionId);
        log.info(
                "replay 发起: sessionId={}, mode={}, targetRunId={}, newRunId={}",
                sessionId,
                request.mode(),
                request.targetRunId(),
                runId);
        return emitter;
    }

    /**
     * checkpoint 定位与回滚（M5，R2 实证 {@code __START__} 锚点方案，spec D2）
     *
     * <p><b>依赖单实例部署</b>（多实例待 TASK.md §1 拍板）：PostgresSaver 继承
     * MemorySaver 的进程内缓存（loadedCheckpoints 非空不再读 PG），跨实例 replay
     * 定位失效——本方法假定 saver 单例与本 JVM 内一致。
     *
     * <p>定位算法（Checkpoint 类无时间戳字段，时间戳二分不可行——R2 实证）：
     * <ol>
     *   <li>按 threadId（=sessionId）经 {@code saver.list(config)} 枚举历史 checkpoint，
     *       顺序新→旧（MemorySaver addFirst / PG 载入 ORDER BY saved_at DESC）；</li>
     *   <li>从头向旧走遇到的<b>第一条 {@code nodeId="__START__"}</b> 即目标 run 的 START
     *       （每个新 run 开始时 MainGraphExecutor 写一条；D5 位置校验已保证目标 = 最后一个
     *       run，无更晚 run 的 checkpoint 混入）。START checkpoint 为写入时深拷贝快照
     *       （addCheckpoint 经 cloneState），state = pre-run 状态 + 已合并本轮用户消息
     *       （quQuery 形态即 caption 前缀形态），不受后续运行原地 append 污染；</li>
     *   <li>锚点归属校验：START state 的最后一条消息应为 UserMessage 且文本以目标 run 落库的
     *       原问题结尾（quQuery = caption 前缀 + 原问题，endsWith 成立）——不匹配说明目标
     *       run 图启动前失败（无 __START__，定位到的是上一 run 的 START），抛 409 拒绝
     *       （防错误锚点导致回滚到错误轮次）；</li>
     *   <li>构造回滚 checkpoint（红线①：写入 config <b>严禁带 checkPointId</b>——带
     *       checkPointId 的 put 走替换分支会 DELETE 旧 checkpoint 行毁审计历史；无
     *       checkPointId 的 put 为 push 分支纯 INSERT）：
     *       <ul>
     *         <li>REGENERATE：新 checkpoint 载 START 快照原样（messages 含原用户消息），
     *             新 run 图输入 {@code {messages: []}} 从该点续跑重新生成；</li>
     *         <li>EDIT：新 checkpoint 载「START 快照去掉末位用户消息」的状态（= pre-run
     *             上下文，不含目标用户消息；首条消息 EDIT 时 messages 为空，等价全新会话），
     *             新 run 图输入 {@code {messages: [UserMessage(新问题)]}} 经 worker 全链路组装。
     *             不直接取「START 之前更旧的一条 checkpoint」——该条作为 run 启动基线时其
     *             messages 列表被 AppendStrategy 原地追加污染（进程内缓存视图含本轮消息），
     *             START 深拷贝快照去除末位才是干净等价物；</li>
     *       </ul>新 checkpoint 新 UUID、nodeId/nextNodeId 沿用 START 值（worker 新 run 的
     *       config 无 checkPointId/HUMAN_FEEDBACK（红线②），仅以 state 为初始上下文）。 </li>
     * </ol>
     *
     * @param sessionId   会话 ID（归属校验已通过）
     * @param isEdit      true=EDIT（锚点去掉末位用户消息）；false=REGENERATE（锚点原样）
     * @param targetRunId 目标 run ID（位置/软删校验已通过）
     * @return 目标 run 落库的原问题文本（REGENERATE 时供 XADD query 供消息行持久化；
     *         EDIT 场景调用方使用新 query，返回值不消费）
     * @throws BizException 409 无历史 checkpoint / 无 {@code __START__} / 锚点归属校验
     *         失败（目标 run 图启动前失败）/ saver 访问异常——锚点不可得时不执行软删
     */
    private String rollbackCheckpointBeforeRun(Long sessionId, boolean isEdit, Long targetRunId) {
        // 目标 run 落库的 USER 行原文（归属校验事实源 + REGENERATE 的 query 回填）
        String targetUserQuery = findRunUserQuery(targetRunId);
        try {
            // 仅 threadId 定位（红线①：不带 checkPointId/HUMAN_FEEDBACK metadata，红线②同源）
            RunnableConfig config =
                    RunnableConfig.builder().threadId(sessionId.toString()).build();
            // 按 threadId 枚举历史 checkpoint（顺序新→旧，R2 实证）
            Checkpoint startCp = null;
            for (Checkpoint cp : saver.list(config)) {
                // 从最新向旧走：第一条 __START__ 即目标 run 的 START（D5 保证无更晚 run）
                if ("__START__".equals(cp.getNodeId())) {
                    startCp = cp;
                    break;
                }
            }
            if (startCp == null) {
                // 无 __START__：目标 run 图启动前失败或会话无 checkpoint——锚点不可得，拒绝
                log.warn(
                        "replay checkpoint 定位失败（无 __START__ 锚点）: sessionId={}, targetRunId={}", sessionId, targetRunId);
                throw new BizException(ErrorCode.CONFLICT, "消息上下文不可用，无法执行编辑或重新生成");
            }
            // 锚点归属校验 + 末位用户消息提取（startMessages 为 START 深拷贝快照，安全读取）
            List<Object> startMessages = readStateMessages(startCp);
            if (startMessages.isEmpty()
                    || !(startMessages.get(startMessages.size() - 1) instanceof UserMessage lastUser)) {
                throw new BizException(ErrorCode.CONFLICT, "消息上下文不可用，无法执行编辑或重新生成");
            }
            // quQuery = caption 前缀 + 原问题 → endsWith 成立即归属目标 run；不匹配 = 定位到
            // 上一 run 的 START（目标 run 未写 __START__），拒绝防错误回滚
            if (targetUserQuery == null || !lastUser.getText().endsWith(targetUserQuery)) {
                log.warn(
                        "replay checkpoint 锚点归属校验失败（疑似目标 run 图启动前失败）: sessionId={}, targetRunId={}",
                        sessionId,
                        targetRunId);
                throw new BizException(ErrorCode.CONFLICT, "消息上下文不可用，无法执行编辑或重新生成");
            }
            // 构造回滚 state：容器级拷贝 + messages 列表拷贝（EDIT 去除末位用户消息 = pre-run 上下文）
            Map<String, Object> rollbackState = new HashMap<>(startCp.getState());
            List<Object> rollbackMessages = new ArrayList<>(startMessages);
            if (isEdit) {
                // 首条消息 EDIT：去除后 messages 为空，等价全新会话（R2 边界）
                rollbackMessages.remove(rollbackMessages.size() - 1);
            }
            rollbackState.put("messages", rollbackMessages);
            // 新 checkpoint 新 UUID 载锚点 state/nodeId/nextNodeId；put 走 push 分支纯 INSERT
            // 不删历史（红线①，沿用 forwardRollCheckpoint 写入模式）；thread_id 不变
            Checkpoint rollbackCp = Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .state(rollbackState)
                    .nodeId(startCp.getNodeId())
                    .nextNodeId(startCp.getNextNodeId())
                    .build();
            saver.put(config, rollbackCp);
            log.info(
                    "replay checkpoint 回滚: sessionId={}, targetRunId={}, mode={}, startCheckpointId={}, rollbackCheckpointId={}, rollbackMessages={}",
                    sessionId,
                    targetRunId,
                    isEdit ? "EDIT" : "REGENERATE",
                    startCp.getId(),
                    rollbackCp.getId(),
                    rollbackMessages.size());
            return targetUserQuery;
        } catch (BizException e) {
            // 业务 409 原样上抛（锚点不可得，软删未发生）
            throw e;
        } catch (Exception e) {
            // saver 访问异常（PG/序列化故障）：锚点不可得，拒绝执行（防数据与 checkpoint 脱节）
            log.error("replay checkpoint 回滚失败: sessionId={}, targetRunId={}", sessionId, targetRunId, e);
            throw new BizException(ErrorCode.CONFLICT, "消息上下文不可用，无法执行编辑或重新生成");
        }
    }

    /**
     * 读取 checkpoint state 的 messages 列表（防御性降级空列表）
     *
     * @param checkpoint checkpoint（非 null）
     * @return messages 列表（state 无 messages 键或类型不符时为空列表）
     */
    private List<Object> readStateMessages(Checkpoint checkpoint) {
        Object messages = checkpoint.getState().get("messages");
        if (messages instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return new ArrayList<>();
    }

    /**
     * 查目标 run 落库的 USER 行原文（锚点归属校验 + REGENERATE 的 query 回填）
     *
     * @param targetRunId 目标 run ID
     * @return 用户问题原文（quQuery 的 caption 后缀部分）；无 USER 行时 null
     */
    private String findRunUserQuery(Long targetRunId) {
        // findByRunId 按 seq 升序返回 run 全部行，USER 行 seq 恒 0（persistMessages 首行）
        return chatMessageService.findByRunId(targetRunId).stream()
                .filter(msg -> "USER".equals(msg.role()))
                .map(ChatMessageVO::content)
                .findFirst()
                .orElse(null);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 为 SSE 流式响应设置防代理缓冲响应头（宪法 C.1.9：流式端点必须禁代理缓冲）。
     *
     * <p>背景（2026-08-30 流式链路根因调研，docs/progress/2026-08-30-流式链路根因调研.md）：
     * SSE 响应经反向代理转发时，gzip 压缩中间人（如 Next dev 默认开启的 compression 中间件）
     * 会把无 Content-Length 的几十字节小帧攒进 zlib 缓冲直到流结束才 flush，浏览器表现为
     * 「长时间空白→最终一次性全量渲染」；nginx 类反代默认也会聚合缓冲响应。以下两个头从
     * 协议层声明「本响应不可压缩、不可缓冲」，使后端在任何中间代理前不再裸奔：
     * <ul>
     *   <li>Cache-Control: no-cache, no-transform —— no-transform 为 HTTP/1.1 标准指令
     *       （RFC 9111），任何中间代理不得压缩/变换响应体，compression 中间件遇之跳过 gzip</li>
     *   <li>X-Accel-Buffering: no —— nginx 及兼容反代禁用响应缓冲，事件帧到达即转发</li>
     * </ul>
     *
     * <p>调用时序：必须在 emitter 首次写出前（controller 返回 emitter 之前）经原始
     * {@link HttpServletResponse} 设置——SseEmitter 不提供设置 HTTP 头的入口，故由
     * controller 透传响应对象，在本类创建 emitter 处统一调用；且仅在参数校验/归属校验
     * 通过后调用，避免 4xx 错误 JSON 响应携带流式专用头。
     *
     * @param httpResponse 当前请求的原始响应（由 controller 透传，非 null）
     */
    private void applyNoProxyBufferHeaders(HttpServletResponse httpResponse) {
        // no-transform：禁止中间代理压缩/变换 SSE 响应体——gzip 中间人遇此指令跳过压缩，
        // 小帧实时透传而不再滞留 zlib 缓冲到流结束（no-cache 兼防客户端缓存事件流）
        httpResponse.setHeader("Cache-Control", "no-cache, no-transform");
        // 禁用 nginx 类反向代理的响应缓冲，帧到即转发（宪法 C.1.9 部署面要求）
        httpResponse.setHeader("X-Accel-Buffering", "no");
    }

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
        // BUG-37（2026-08-31）：心跳 future 先落 holder 再供任务体引用——send 失败时在任务内
        // <b>显式取消</b>，不依赖未捕获异常的隐式取消：scheduleAtFixedRate 对未捕获
        // RuntimeException 会静默取消后续所有执行且异常被吞进 Future 无人消费（心跳无任何
        // 日志停跳，连接假死难定位）；对齐 sendEvent 投递侧防御风格（MemoryStreamBridge
        // 单条投递异常不外抛）。任务首跑在 ≥1 个周期之后，holder 赋值先行，无竞态。
        ScheduledFuture<?>[] heartbeatHolder = new ScheduledFuture<?>[1];
        heartbeatHolder[0] = scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        // 发送 SSE 注释行 :heartbeat（非命名事件），符合 SSE 协议保活规范
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException | RuntimeException e) {
                        // emitter 已关闭（IOException）/ 已完成（complete 并发导致的
                        // IllegalStateException）：连接不可用，显式取消心跳任务停跳
                        log.debug("心跳发送失败，显式取消心跳任务: {}", e.getMessage());
                        ScheduledFuture<?> task = heartbeatHolder[0];
                        if (task != null) {
                            task.cancel(false);
                        }
                    }
                },
                streamProperties.heartbeatInterval(),
                streamProperties.heartbeatInterval(),
                TimeUnit.SECONDS);
        ScheduledFuture<?> heartbeat = heartbeatHolder[0];

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
     * 2026-08-29 消息实体化：assistant 实体行由
     * {@code IChatMessageService#findByRunId} 统一拆行还原事件序行（thinking/query_plan/
     * TOOL_CALL/正文），本方法消费的 VO 形态与实体化前一致——thinking/query_plan 事件由
     * 拆出的行重建，事件协议不变（前端零改动）。
     * 降级不终止，记 warn。
     *
     * @param runId       Run 唯一标识（字符串）
     * @param lastEventId 客户端最后收到的 eventId（用于 seq 续编号）
     * @param emitter     SSE 订阅者
     * @return 回放结果（PERF-13）：lastSeq=最后回放的 seq（-1=PG 无数据或回放失败）；
     *         messages=本次已查消息列表（供终态 messageId 解析复用，免二次查询）
     */
    private PgReplayOutcome replayFromPg(String runId, long lastEventId, SseEmitter emitter) {
        try {
            Long runIdLong = Long.parseLong(runId);
            List<ChatMessageVO> messages = chatMessageService.findByRunId(runIdLong);
            if (messages == null || messages.isEmpty()) {
                log.warn("PG 降级回放: runId={} 无历史消息", runId);
                return new PgReplayOutcome(-1, List.of());
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
                    // 2026-08-28 时间线改版：THINKING 回放 payload 与实时事件同构 {delta, stage}——
                    // stage 取落库 thinking_stage 原样透传；历史存量行该列为 null 时输出 JSON null
                    // （锁定决策：null 语义 = 前端降级按 generating 渲染，回放不报错、后端不代填）
                    eventType = SseEventType.THINKING.getEventName();
                    String stage = msg.thinkingStage();
                    payload = "{\"delta\":\"" + escapeJson(msg.content()) + "\",\"stage\":"
                            + (stage == null ? "null" : "\"" + escapeJson(stage) + "\"") + "}";
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
            return new PgReplayOutcome(seq, messages);
        } catch (NumberFormatException e) {
            log.warn("PG 降级回放: runId 解析失败 runId={}", runId);
            return new PgReplayOutcome(-1, List.of());
        } catch (Exception e) {
            log.warn("PG 降级回放失败: runId={}", runId, e);
            return new PgReplayOutcome(-1, List.of());
        }
    }

    /** PG 降级回放结果（PERF-13）：seq 游标 + 已查消息列表（复用给终态 messageId 解析，消重复查询） */
    private record PgReplayOutcome(long lastSeq, List<ChatMessageVO> messages) {}

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
     * <p>审核修正 M2（2026-09-03 停止态改版放宽）：必须校验 run 终态才返回 ID——
     * COMPLETED/CANCELLED run 的 assistant 正文行（含取消路径的半截正文行）可作反馈目标
     * （停止后点赞点踩保留，图 4 拍板）；ERROR 终态不得作为反馈目标。异常/未落库窗口
     * 返回 null（正常降级，前端 {@code messageId?} 可空容忍）。
     *
     * <p>PERF-13（2026-08-31）：降级路径 run 已在手时复用传入（免 run 复查），messages 仍自查
     * （无既有可复用列表的调用点）；run/messages 均在手时直接走纯比对重载，全程零额外查询
     * （降级路径 4 次 DB 往返收敛为 2 次）。
     *
     * @param runId    Run 唯一标识（字符串，归属校验已通过）
     * @param knownRun 调用方已查询的 run（非 null 时复用，终态过滤在该对象上比对；null 时自查）
     * @return assistant 正文行消息 ID 字符串；run 非 COMPLETED/CANCELLED / 无正文行 / 查询异常时返回 null
     */
    private String resolveAssistantMessageId(String runId, ChatRunVO knownRun) {
        try {
            Long runIdLong = Long.parseLong(runId);
            // M2 状态过滤（2026-09-03 放宽）：COMPLETED/CANCELLED run 的正文行可作反馈目标
            // （CANCELLED 半截回答同样开放反馈；ERROR 排除）
            ChatRunVO run = knownRun != null ? knownRun : chatRunService.findById(runIdLong);
            if (!isFeedbackEligibleStatus(run)) {
                return null;
            }
            List<ChatMessageVO> messages = chatMessageService.findByRunId(runIdLong);
            return resolveAssistantMessageId(run, messages);
        } catch (Exception e) {
            // 解析/查询失败不得阻断 end 补发——messageId 降级 null
            log.warn("解析 assistant 消息 ID 失败，end 事件 messageId 降级为 null: runId={}", runId, e);
            return null;
        }
    }

    /**
     * 解析 assistant 正文行消息 ID 的纯比对重载（PERF-13）——在既有 run/messages 上完成
     * 终态过滤与反向扫描，不产生任何 DB 查询；调用方保证对象新鲜度
     * （同一降级流程内已查数据，run 终态为最终态无并发漂移）。
     *
     * @param run      已查询的 run（终态过滤对象；null 或非 COMPLETED/CANCELLED 返回 null）
     * @param messages 已查询的消息列表（按 seq 升序；null/空返回 null）
     * @return assistant 正文行消息 ID 字符串；无正文行时返回 null
     */
    private String resolveAssistantMessageId(ChatRunVO run, List<ChatMessageVO> messages) {
        if (!isFeedbackEligibleStatus(run)) {
            return null;
        }
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
    }

    /**
     * 反馈目标终态判定（M2 2026-09-03 放宽）：COMPLETED/CANCELLED run 的 assistant 正文行
     * 可作反馈目标（取消路径半截正文行开放反馈，图 4 停止态拍板）；ERROR/非终态/null 排除。
     *
     * @param run run 视图（可 null）
     * @return true=COMPLETED/CANCELLED（可反馈）
     */
    private boolean isFeedbackEligibleStatus(ChatRunVO run) {
        return run != null && ("COMPLETED".equals(run.status()) || "CANCELLED".equals(run.status()));
    }

    /**
     * 构建 end 事件 payload（R2 补口 B，ERROR 终态变体——不带 messageId 键）。
     *
     * <p>runId/status 均来自服务端白名单值（数字 ID + 枚举状态），手工拼接安全。
     *
     * @param runId  Run 唯一标识（字符串）
     * @param status run 终态（ERROR）
     * @return {"runId":"...","status":"..."}（无 messageId 键——失败内容不作反馈目标）
     */
    private String buildEndPayload(String runId, String status) {
        return "{\"runId\":\"" + runId + "\",\"status\":\"" + status + "\"}";
    }

    /**
     * 构建 end 事件 payload（R2 补口 B，COMPLETED/CANCELLED 终态变体——追加 messageId 字段；
     * 2026-09-03 停止态改版：CANCELLED 亦携带，停止后反馈入口保留）。
     *
     * <p>messageId 恒字符串或显式 null（异常/未落库窗口无法解析时 null，前端可空容忍）。
     *
     * @param runId      Run 唯一标识（字符串）
     * @param status     run 终态（COMPLETED/CANCELLED）
     * @param messageId assistant 正文行消息 ID 字符串（可为 null）
     * @return {"runId":"...","status":"...","messageId":"..."|null}
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
     *
     * <p>BUG-15：补齐 C0 控制字符（U+0000-U+001F）转义——RFC 8259 禁止 JSON 字符串内
     * 出现裸控制字符，原实现仅覆盖反斜杠/引号与换行/回车/制表五种，正文/思考含其余
     * 控制字符（如换页、退格、垂直制表）时产出非法 JSON，前端 JSON.parse 抛错导致
     * 回放中断。现逐字符转义：常用短转义保留（与实时事件构造口径一致），其余控制
     * 字符统一十六进制转义（"u" + 四位小写十六进制），保证转义后恒为合法 JSON 且
     * 解析还原原文。
     */
    private String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                    // 非常用短转义的 C0 控制字符统一十六进制转义（换页→u000c、退格→u0008 等）
                default -> {
                    if (c < 0x20) {
                        sb.append('\\').append('u').append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
