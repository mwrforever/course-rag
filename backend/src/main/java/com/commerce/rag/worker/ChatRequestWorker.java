package com.commerce.rag.worker;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.bot.graph.RetrieveNode;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.exception.CancelledException;
import com.commerce.rag.properties.AgentProperties;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.properties.WorkerProperties;
import com.commerce.rag.record.AssistantEntitySplitter;
import com.commerce.rag.record.AssistantMessageCapture;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.record.PersistOutcome;
import com.commerce.rag.record.RetrievalSource;
import com.commerce.rag.service.AttachmentOrchestrator;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.MemoryExtractionPipeline;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.SseEventType;
import com.commerce.rag.stream.ThinkingPusher;
import com.commerce.rag.vo.ChatRunVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * Chat 请求 Worker —— 核心执行引擎。
 *
 * <p>职责：
 * <ol>
 *   <li>后台守护线程轮询 Redis Stream（XREADGROUP），将消息分发到 runPool 执行</li>
 *   <li>每个 run：pre-run 快照 → 状态 ACTIVE → SAA 图流式执行 → Transformer → Bridge</li>
 *   <li>取消（M2）：标志位置位 + 立刻 dispose 图流订阅（取消传播至上游连接）+ 主动唤醒
 *       终态等待，worker 线程统一收尾 CANCELLED 终态；doOnNext 检查点为兜底通道</li>
 *   <li>Pending 回收：定时扫描 XPENDING → XCLAIM 超时租约</li>
 *   <li>消息持久化：run 结束后批量 INSERT chat_message</li>
 * </ol>
 *
 * <p>线程模型：
 * <pre>
 * chat-consumer (daemon) → runPool (chat-worker-N) → SAA graph stream (reactor)
 * </pre>
 *
 * @author commerce-rag
 */
@Component
public class ChatRequestWorker {

    private static final Logger log = LoggerFactory.getLogger(ChatRequestWorker.class);

    private final StringRedisTemplate redisTemplate;
    private final CompiledGraph compiledGraph;
    private final BaseCheckpointSaver saver;
    private final SseEventTransformer transformer;
    private final MemoryStreamBridge bridge;
    private final IChatRunService chatRunService;
    private final IChatMessageService chatMessageService;
    private final StreamProperties streamProperties;
    private final WorkerProperties workerProperties;
    private final ThreadPoolExecutor runPool;
    /** 安全告警 Hook（BUG-11：run 结束 finally 清理 per-thread 检测状态，取消/异常路径不泄漏） */
    private final WarningHook warningHook;
    /** 附件处理编排（下载 → 按类型分发 → AttachmentContext，caption 拼 QU 查询，spec §5.1/§5.3） */
    private final AttachmentOrchestrator orchestrator;
    /** 偏好提取流水线（run 完成后异步触发，spec §7.6；不阻塞用户响应） */
    private final MemoryExtractionPipeline memoryExtractionPipeline;

    private final ObjectMapper objectMapper;
    /** 主对话模型名（METADATA 事件 model 字段，来自 rag.agent.model） */
    private final String agentModel;
    /** per-run 取消标记 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** run 级图流订阅句柄（M2 取消立即性：cancel 置位标志后立刻 dispose，取消传播至上游 DashScope 连接） */
    private final ConcurrentHashMap<String, Disposable> runDisposables = new ConcurrentHashMap<>();

    /**
     * run 级取消收尾 Runnable（M2/R1-a 主动唤醒）：R1 实证 dispose 先行时 onComplete/onError
     * 均静默丢弃（无任何回调），processRequest 的终态等待（latch）必须由 cancel 路径调用本
     * 收尾主动唤醒（与 doFinally(CANCEL) 构成双唤醒通道，先到先醒）；终态收尾本身统一由
     * worker 线程在等待返回后按 terminalPushed CAS 认领执行，防双终态/漏终态，也避免取消
     * 线程与 worker finally 清理（removeRing/注册表移除）竞争丢 END 事件。
     * 生命周期与 cancelFlags 同界（processRequest.finally 清理）。
     */
    private final ConcurrentHashMap<String, Runnable> cancelFinishers = new ConcurrentHashMap<>();

    /** Consumer 运行标志 */
    private volatile boolean running = false;

    /** M-8: ACTIVE run 巡检调度器 */
    private ScheduledExecutorService sweepScheduler;

    public ChatRequestWorker(
            StringRedisTemplate redisTemplate,
            CompiledGraph compiledGraph,
            BaseCheckpointSaver saver,
            SseEventTransformer transformer,
            MemoryStreamBridge bridge,
            IChatRunService chatRunService,
            IChatMessageService chatMessageService,
            StreamProperties streamProperties,
            WorkerProperties workerProperties,
            @Qualifier("runPool") ThreadPoolExecutor runPool,
            WarningHook warningHook,
            AttachmentOrchestrator orchestrator,
            MemoryExtractionPipeline memoryExtractionPipeline,
            ObjectMapper objectMapper,
            AgentProperties agentProperties) {
        this.redisTemplate = redisTemplate;
        this.compiledGraph = compiledGraph;
        this.saver = saver;
        this.transformer = transformer;
        this.bridge = bridge;
        this.chatRunService = chatRunService;
        this.chatMessageService = chatMessageService;
        this.streamProperties = streamProperties;
        this.workerProperties = workerProperties;
        this.runPool = runPool;
        this.warningHook = warningHook;
        this.orchestrator = orchestrator;
        this.memoryExtractionPipeline = memoryExtractionPipeline;
        this.objectMapper = objectMapper;
        // BUG-12 @Value 收敛：主对话模型名经 AgentProperties（rag.agent.model）强类型注入，
        // 取值与原 @Value 相同
        this.agentModel = agentProperties.model();
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    /**
     * 启动后台消费线程 + ACTIVE run 巡检调度。
     */
    @PostConstruct
    public void start() {
        running = true;

        Thread consumer = new Thread(this::consumeLoop, "chat-consumer");
        consumer.setDaemon(true);
        consumer.start();

        // M-8: ACTIVE run 巡检——进程崩溃/runPool 拒绝后 run 滞留 ACTIVE，
        // uniq_active_run_per_session 锁死会话（后续对话恒 409），每 60s 扫描置 ERROR 解锁
        // B2-3: 首扫 initial delay=0——启动即执行一次，兜底停机丢弃排队任务/崩溃滞留的
        // ACTIVE/QUEUED run（此前首扫在 60s 后，滚动重启窗口内会话最长 409 一分钟以上）
        sweepScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chat-run-sweep");
            t.setDaemon(true);
            return t;
        });
        sweepScheduler.scheduleAtFixedRate(this::sweepStaleRuns, 0, 60, TimeUnit.SECONDS);

        log.info(
                "ChatRequestWorker 启动: stream={}, group={}",
                streamProperties.requestStream(),
                streamProperties.consumerGroup());
    }

    /**
     * 优雅关闭：停止消费 → 关闭巡检调度器 → 等待 runPool 完成。
     */
    @PreDestroy
    public void stop() {
        log.info("ChatRequestWorker 关闭中...");
        running = false;
        if (sweepScheduler != null) {
            sweepScheduler.shutdownNow();
        }
        runPool.shutdown();
        try {
            if (!runPool.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("runPool 未在 30s 内终止，强制关闭");
                runPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runPool.shutdownNow();
        }
        log.info("ChatRequestWorker 已关闭");
    }

    /**
     * M-8 + B2-3: 巡检滞留的 ACTIVE/QUEUED run 并置 ERROR 解锁会话
     *
     * <p>ACTIVE 超时阈值 = worker.run-pool.stale-run-timeout-minutes（started_at 判定）；
     * QUEUED 滞留阈值 = worker.run-pool.stale-queued-timeout-minutes（created_at 判定，
     * B2-3——附件处理发生在转 ACTIVE 之前，该窗口内进程崩溃/停机丢任务的 run 全程
     * 停留 QUEUED，且同样占据 uniq_active_run_per_session 锁死会话）。
     *
     * <p>巡检将滞留 run 置 ERROR（endedAt 由 markErrorIfCurrent 自动设置；BUG-01 后以 SELECT
     * 观察状态为前提 CAS 原子判定，窗口内已迁移的 run 跳过不误杀），
     * 失败可见可手动重试。与 P1-5 的完成时刻短重试互补（覆盖执行期崩溃场景）。
     */
    private void sweepStaleRuns() {
        try {
            LocalDateTime startedBefore = LocalDateTime.now().minusMinutes(workerProperties.staleRunTimeoutMinutes());
            LocalDateTime queuedBefore = LocalDateTime.now().minusMinutes(workerProperties.staleQueuedTimeoutMinutes());
            List<ChatRunVO> stale = chatRunService.findStaleActive(startedBefore, queuedBefore);
            for (ChatRunVO run : stale) {
                try {
                    // BUG-01 巡检 TOCTOU：以 SELECT 时观察到的状态为前提做 CAS 条件 UPDATE 原子判定——
                    // SELECT→UPDATE 窗口内刚被 worker 取出转 ACTIVE 的 run 不被误杀（误杀会解锁会话，
                    // 新 run 与仍在执行的旧 run 同 thread_id 真并发）；主路径（滞留 run 置 ERROR 解锁
                    // 会话）行为不变，仍受 uniq_active_run_per_session 唯一索引保护
                    int updated = chatRunService.markErrorIfCurrent(run.id(), run.status());
                    if (updated > 0) {
                        log.warn(
                                "巡检发现滞留 run（ACTIVE/QUEUED 超时），置 ERROR 解锁会话: runId={}, sessionId={}, status={}",
                                run.id(),
                                run.sessionId(),
                                run.status());
                    } else {
                        log.info("巡检置 ERROR 未命中（run 状态已在扫描后迁移，跳过不误杀）: runId={}, 观察状态={}", run.id(), run.status());
                    }
                } catch (Exception e) {
                    log.error("巡检置 ERROR 失败: runId={}", run.id(), e);
                }
            }
        } catch (Exception e) {
            log.error("滞留 run 巡检失败（下轮重试）", e);
        }
    }

    // ========================================================================
    // Redis Stream 消费循环
    // ========================================================================

    /**
     * 后台守护线程主循环：XREADGROUP BLOCK → 分发到 runPool。
     */
    private void consumeLoop() {
        String streamKey = streamProperties.requestStream();
        String group = streamProperties.consumerGroup();
        String consumer = "worker-" + UUID.randomUUID().toString().substring(0, 8);
        long pollTimeout = streamProperties.pollTimeout();

        // 确保消费组存在（MKSTREAM 由 createGroup 内部处理）
        ensureConsumerGroup(streamKey, group);

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> messages = redisTemplate
                        .opsForStream()
                        .read(
                                Consumer.from(group, consumer),
                                StreamReadOptions.empty()
                                        .count(streamProperties.batchSize())
                                        .block(Duration.ofMillis(pollTimeout)),
                                StreamOffset.create(streamKey, ReadOffset.lastConsumed()));

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (MapRecord<String, Object, Object> msg : messages) {
                    // P3-2（用户 2026-08-15 裁决）：读到即 ACK，不等执行完成——
                    // 消费组不积累 pending，删除 reclaimPending/XCLAIM 重投机制（消除双跑窗口）；
                    // run 执行结果由 chat_run 状态兜底（失败可见，可手动重试）
                    ackMessage(msg.getId().getValue());
                    try {
                        runPool.submit(() -> processRequest(msg));
                    } catch (RejectedExecutionException e) {
                        // M-8: runPool 队列满（8 线程全忙 + 队列 100 满）不再 CallerRuns 内联执行——
                        // 内联会让消费者线程执行整个 run（最长 5 分钟），消费循环停摆、
                        // Redis Stream 所有新对话滞留；改为快速失败：消息已 ACK，
                        // run 状态回写 ERROR 解锁 uniq_active_run_per_session（会话可重试）
                        Object runIdObj = msg.getValue().get("runId");
                        String rejectedRunIdStr = runIdObj == null ? null : String.valueOf(runIdObj);
                        Long rejectedRunId = rejectedRunIdStr == null ? null : parseLongQuietly(rejectedRunIdStr);
                        // B2-1: 入口 chat() 在 XADD 前已 createRing + subscribe，被拒 run 不经过
                        // processRequest（其 finally 负责 removeRing）——必须在此补齐终态三件套，
                        // 否则客户端永久"生成中"（重连对未 close 的 ring 必返回 true，不进 PG 补终态降级）
                        // 且 ring + 阻塞在 outbox.take() 的投递线程永久泄漏
                        if (rejectedRunIdStr != null) {
                            // ① 推送 ERROR 终态事件（与 handleError 终态语义一致：payload 含 runId + ERROR；
                            //    run 从未执行、无任何事件入 ring，seq 从 1 起）
                            try {
                                Map<String, Object> payload = new LinkedHashMap<>();
                                payload.put("runId", rejectedRunIdStr);
                                payload.put("status", "ERROR");
                                payload.put("message", "当前排队请求过多，请稍后重试");
                                bridge.push(
                                        rejectedRunIdStr,
                                        new SseEvent(
                                                SseEventType.ERROR, 1, toJson(payload), System.currentTimeMillis()));
                            } catch (Exception pushEx) {
                                // 推送失败不得中断后续清理与状态回写
                                log.error("runPool 拒绝后推送 ERROR 终态事件失败: runId={}", rejectedRunIdStr, pushEx);
                            }
                            // ② 清理 ring（close 具备 B2-1 drain 语义：置 closed 后等投递线程把 outbox 中
                            //    已入队的 ERROR 事件投递给订阅者、排空后才 complete——先推后清不吞事件）
                            try {
                                bridge.removeRing(rejectedRunIdStr);
                            } catch (Exception removeEx) {
                                log.error("runPool 拒绝后清理 ring 失败: runId={}", rejectedRunIdStr, removeEx);
                            }
                            // ②.5 BUG-14：清理入队注册的取消条目——被拒 run 不经过 processRequest
                            // （其 finally 是条目唯一常规清理点），不清理则条目永久残留
                            cancelFlags.remove(rejectedRunIdStr);
                        }
                        // ③ run 状态回写 ERROR（解锁会话，失败可见可重试）
                        if (rejectedRunId != null) {
                            try {
                                chatRunService.updateStatus(rejectedRunId, "ERROR");
                            } catch (Exception dbEx) {
                                log.error("runPool 拒绝后回写 run=ERROR 失败: runId={}", rejectedRunId, dbEx);
                            }
                        }
                        log.error("runPool 队列已满，run 快速失败（消息已 ACK）: runId={}", runIdObj);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.error("consumeLoop 异常，1s 后重试", e);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        log.info("consumeLoop 退出: consumer={}", consumer);
    }

    /**
     * 确保消费组存在，已存在则忽略 BUSYGROUP 异常。
     */
    private void ensureConsumerGroup(String streamKey, String group) {
        try {
            redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), group);
            log.info("创建消费组: stream={}, group={}", streamKey, group);
        } catch (Exception e) {
            // BUSYGROUP: 消费组已存在，正常情况
            log.debug("消费组已存在或创建失败: stream={}, group={}, msg={}", streamKey, group, e.getMessage());
        }
    }

    // ========================================================================
    // 核心执行流程
    // ========================================================================

    /**
     * 处理单条 Redis Stream 消息（在 runPool 线程中执行）。
     *
     * <p>流程：
     * <ol>
     *   <li>解析请求 → runId, sessionId, userId, userQuery</li>
     *   <li>推送 METADATA（首个事件，附件处理前——2026-08-27 前移消除附件管线静默）</li>
     *   <li>附件处理（有附件先推 STAGE(attachments)，阶段可见）</li>
     *   <li>pre-run 快照: saver.get(config) → 容器级浅拷贝</li>
     *   <li>更新 run 状态: QUEUED → ACTIVE + 推送 STAGE(understanding)</li>
     *   <li>执行 SAA 图流: compiledGraph.stream(inputs, config)（M2 显式订阅 + latch 等待终态，
     *       订阅句柄注册 runDisposables 供 cancel 立刻 dispose；doOnNext 内按节点完成
     *       chunk 推 STAGE 跃迁 + SOURCES + 内容事件）</li>
     *   <li>onErrorResume: 取消/异常处理</li>
     *   <li>doOnComplete: END 事件 + 批量持久化</li>
     *   <li>finally: bridge.removeRing + cancelFlags/runDisposables/cancelFinishers 清理</li>
     * </ol>
     */
    private void processRequest(MapRecord<String, Object, Object> message) {
        String msgId = message.getId().getValue();
        Map<String, String> body = parseBody(message);

        String runIdStr = body.get("runId");
        String sessionIdStr = body.get("sessionId");
        String userIdStr = body.get("userId");
        String userQuery = body.get("query");

        Long runId;
        Long sessionId;
        Long userId;
        try {
            runId = Long.parseLong(runIdStr);
            sessionId = Long.parseLong(sessionIdStr);
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            log.error("请求参数解析失败: runId={}, sessionId={}, userId={}", runIdStr, sessionIdStr, userIdStr);
            ackMessage(msgId);
            return;
        }

        // 本次输入附件 JSON（缺省为空数组）：非法 JSON 按空处理，附件损坏不阻断对话。
        // 归一结果用 effectively final 变量承载（persistMessages 在 reactor 回调中引用）
        String rawAttachmentsJson = body.getOrDefault("attachments", "[]");
        String attachmentsJson;
        if (!isValidJsonArray(rawAttachmentsJson)) {
            log.warn("附件 JSON 非法，按空处理: runId={}", runIdStr);
            attachmentsJson = "[]";
        } else {
            attachmentsJson = rawAttachmentsJson;
        }

        // 构建 RunnableConfig（session_id 直接作 thread_id）
        // 设计文档 §2.1: threadId + userId 放 RunnableConfig，不放 State
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionIdStr)
                .addMetadata("userId", userIdStr)
                .build();

        // run 上下文（首事件前移：METADATA 需在附件处理前推送，见下方注释）
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create(runIdStr, sessionIdStr, agentModel);

        // ── per-run 思考事件推送通道注册（2026-08-28 对话流式时间线改版）──
        // QU / caption 等图内节点经 config.metadata[KEY_THINKING_CALLBACK] 取 ThinkingPusher
        // 实时推送 reasoning 片段（对模型不可见的瞬时引用通道，与 KEY_RETRIEVAL_SOURCES 同机制）；
        // seq 与主链路共享 runState 计数器，全局事件序号单调不乱号；ring 随 finally removeRing 关闭，
        // run 结束后回调残留引用推送会被 bridge 以「ring 不存在」拒绝，无需反注册
        ThinkingPusher thinkingPusher = new ThinkingPusher(runIdStr, bridge, runState, objectMapper);
        config.metadata().ifPresent(m -> m.put(RetrieveNode.KEY_THINKING_CALLBACK, thinkingPusher));

        // ── 正文/思考 delta 累加器（2026-08-28 时间线改版 Task 5）──
        // 在下方 doOnNext 推送点同步累积 transformer 产出的 DELTA/THINKING 事件：取消/错误路径
        // 图 state 往往没有终消息（in-flight 消息只在节点完成点进 state），落库若直接取 state
        // 会得到空正文或与前端已渲染不一致的内容；累加器记录的正是已推送事件序列，保证不变量
        // 「终态落库内容 ≡ 已推送事件序列」。正常完成路径不消费（state 汇总仍为权威）。
        DeltaAccumulator deltaAccumulator = new DeltaAccumulator(objectMapper);

        // ── 取消源注册（2026-08-28 时间线改版 Task 4）──
        // 供 RetrieveNode 三段并行 join 前即时检查取消（读到即抛 CancelledException 走既有取消分支），
        // 附件编排批循环同样复用本引用；读取语义与 checkCancelled 一致（标志存在且为 true 才算取消）。
        // 与 KEY_THINKING_CALLBACK 同通道——瞬时 Java 引用，不落 state/checkpoint、模型不可见。
        BooleanSupplier cancelSource = () -> isCancelled(runIdStr);
        config.metadata().ifPresent(m -> m.put(RetrieveNode.KEY_CANCEL_CHECK, cancelSource));

        // ── 检索来源回写容器注册（2026-08-28 T7 真机实证修复）──
        // SAA CompiledGraph.stream 交给图节点的 config 是 metadata 派生副本（HasMetadata$Builder
        // 以 new HashMap 拷贝），RetrieveNode 写入的 KEY_RETRIEVAL_SOURCES 只落副本、本 worker
        // 原实例恒读空——真机实证 SSE 无 SOURCES 事件、sources_json 恒 "[]"。注入 AtomicReference
        // 容器（对象引用经浅拷贝穿透派生副本），节点经 KEY_SOURCES_SINK 以 sink.set 跨副本写回，
        // 本 worker 读取统一走 readRetrievalSources（sink 优先、metadata 键回退）。与
        // KEY_THINKING_CALLBACK 同构——瞬时 Java 引用，不落 state/checkpoint、模型不可见。
        AtomicReference<List<RetrievalSource>> sourcesSink = new AtomicReference<>();
        config.metadata().ifPresent(m -> m.put(RetrieveNode.KEY_SOURCES_SINK, sourcesSink));

        // ── LLM 调用消息捕获容器注册（2026-08-29 消息实体化）──
        // 与 KEY_SOURCES_SINK 同构的「节点经注入回调」通道：QU/caption 捕获点经 metadata
        // 派生副本浅拷贝穿透读到容器并写入捕获（每次 LLM 调用一条完整消息），本 worker 原实例
        // 在 persistMessages 快照消费落 assistant 实体行（正常完成路径）；取消/错误路径不消费
        // （增量行模型不变）。瞬时 Java 引用，不落 state/checkpoint、模型不可见。
        AssistantMessageSink assistantSink = new AssistantMessageSink();
        config.metadata().ifPresent(m -> m.put(RetrieveNode.KEY_ASSISTANT_SINK, assistantSink));

        // ── 首事件前移（2026-08-27 C 端体验改版）──
        // ring 由入口 ChatStreamEntry 在 XADD 前已创建（createRing 为 computeIfAbsent 幂等），
        // 此处立即推 METADATA：原实现位于附件处理（process-timeout-ms 最长 60s）与两次 DB 写之后，
        // 附件管线全程对客户端静默是「等很久没输出」的直接来源之一。订阅先于 XADD 建立，
        // 前移不改变事件不丢语义（ring 回放覆盖断连窗口）。
        bridge.createRing(runIdStr);
        bridge.push(runIdStr, transformer.createMetadataEvent(runState));

        // ── 附件处理（spec §5.1：消息发送后 worker 内处理，caption/局部语料 Caffeine 缓存）──
        // 先处理当前消息的 attachments；第二轮起用户不再上传附件时，以 chat_run 为入口查该会话
        // 最近 3 个 run 的附件重建上下文（spec §5.1 最终三表决策：Caffeine 命中直接复用
        // caption/语料，未命中重新下载处理）。处理结果经 RunnableConfig.metadata 传 QU/RetrieveNode
        // （瞬时注入，不落 state/checkpoint）；处理前推 STAGE(attachments) 让长耗时解析对前端可见
        AttachmentContext attachmentContext = AttachmentContext.empty();
        List<AttachmentRecord> attachments = parseAttachments(attachmentsJson);
        if (attachments.isEmpty()) {
            // 后续轮次：查该会话最近 3 个 run 的附件重建（排除当前 run，url 去重）
            attachments = chatRunService.findRecentAttachments(sessionId, runId, 3);
        }
        if (!attachments.isEmpty()) {
            bridge.push(runIdStr, transformer.createStageEvent(runState, SseEventTransformer.STAGE_ATTACHMENTS));
            // Task 4 接线：thinkingPusher 使图片 VLM caption 走流式聚合，reasoning 实时推
            // attachments 阶段（THINKING 事件与本 STAGE 事件同阶段键）；cancelSource 使取消在
            // 附件批处理循环内即时生效（已取消跳过剩余文件，不再耗下载/VLM 配额）；
            // assistantSink 使 caption 调用完成点捕获 LLM 调用（消息实体化，spec §3.2）
            attachmentContext = orchestrator.process(attachments, thinkingPusher, cancelSource, assistantSink);
        }
        if (attachmentContext.hasAny()) {
            // 局部 final 转发（attachmentContext 上方被赋值非 effectively final，lambda 捕获需 final 变量）
            AttachmentContext ctxForMetadata = attachmentContext;
            config.metadata().ifPresent(m -> m.put(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT, ctxForMetadata));
        }

        // ── QU 输入组装：caption 前缀拼入 {query}（spec §5.3："图片1:[caption] 图片2:[caption] 用户问题"）──
        // QU 服务在 queryUnderstandingNode 内读取图输入的最后一条 UserMessage 文本，故把拼好的
        // quQuery 作为图输入的用户消息；持久化仍用原文 userQuery（chat_message 用户行不加 caption 前缀）
        String quQuery = userQuery;
        if (!attachmentContext.captions().isEmpty()) {
            String captionPrefix = attachmentContext.captions().stream()
                    .map(ImageCaptionResult::caption)
                    .collect(Collectors.joining(" "));
            quQuery = captionPrefix + " " + userQuery;
        }

        // 构建 SAA 图输入（messages 列表）
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", List.of(new UserMessage(quQuery)));

        // 流式过程中收集最后 NodeOutput（用于消息持久化）
        AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
        // B2-4: 终态事件已推送标记——doOnComplete 推 END 后终态回写（updateStatusWithRetry
        // 3 次耗尽上抛）进入 catch 时，不得再经 handleError 推第二个终态事件（客户端
        // 状态机收到双终态）；compareAndSet 保证 doOnComplete/onErrorResume/catch/取消收尾
        // （M2）四路径仅一个生效
        AtomicBoolean terminalPushed = new AtomicBoolean(false);
        // B2-4: 消息已持久化标记——doOnComplete 落库成功后终态回写失败进入 catch 时，
        // 跳过二次 persistMessages（重复落库防线，另有 V13 (run_id,seq) 唯一索引兜底）
        AtomicBoolean persisted = new AtomicBoolean(false);
        // B3-5: SOURCES 事件已推送标记——检索来源 metadata 就绪后首个 chunk 处推一次
        // （CAS 保证 doOnNext 多次触发仅推送一次；chat/unknown 意图无来源则不推）
        AtomicBoolean sourcesPushed = new AtomicBoolean(false);

        // 1. pre-run 快照（在 try 之前捕获：captureSnapshot 内部异常已兜底返回 null；
        //    单次赋值保持 effectively final，供 onErrorResume/doOnComplete lambda 捕获，
        //    且 catch 分支持久化时可直接读取游标）
        RunSnapshot snapshot = captureSnapshot(runIdStr, config);
        try {
            // 2. 状态 → ACTIVE（BUG-01 状态机守卫：仅 QUEUED 可迁移；0 行=run 已被巡检置 ERROR 等
            //    终态，迟到队列任务直接跳过图执行，避免复活终态 run 后同 thread_id 与用户新 run
            //    并发跑图——checkpoint 并发写、SSE 双流互踩）
            int activated = chatRunService.updateStatus(runId, "ACTIVE");
            if (activated == 0) {
                skipExecutionForGuardRejectedRun(runId, runIdStr, runState);
                return;
            }

            // 2.1 落库本次输入附件（业务入口表，spec §5.1 双存决策；紧随 ACTIVE 写入，保证入口数据不丢）
            chatRunService.updateAttachments(runId, attachmentsJson);

            // 3. 阶段事件：意图理解（覆盖 QU 阻塞 LLM 的静默窗口——QU 在图内执行，
            //    无 chunk 可观测，此处在图启动前推送；QU 完成后的阶段跃迁由 doOnNext
            //    的 transformStages 按节点完成 chunk 驱动）
            bridge.push(runIdStr, transformer.createStageEvent(runState, SseEventTransformer.STAGE_UNDERSTANDING));

            // 图执行可观测性（dev 定位）：输入问题/附件摘要（截断），完成汇总在 doOnComplete
            log.info(
                    "图执行开始: runId={}, 问题预览={}, 附件预览={}",
                    runIdStr,
                    truncateText(userQuery, 50),
                    truncateText(attachmentsJson, 80));

            // 4. 执行 SAA 图流（M2：blockLast 改显式订阅 + latch 等待终态——订阅句柄注册到
            //    runDisposables 供 cancel() 立刻 dispose 中断上游连接；总时长仍以 5 分钟
            //    latch 超时兜底，语义与原 blockLast 超时一致）
            CountDownLatch streamDone = new CountDownLatch(1);
            // 经订阅错误通道回传的回调异常（doOnComplete 尾段等未经 onErrorResume 消化的
            // 异常）：收集后在等待点重抛落入外层 catch——等价原 blockLast 的异常传播语义
            AtomicReference<Throwable> reactorFailure = new AtomicReference<>();
            // M2/R1-a 取消主动收尾（唤醒通道）：R1 实证 dispose 先行时 onComplete/onError 均
            // 静默丢弃（无任何回调），worker 线程的 latch 等待必须由 cancel 路径主动唤醒；
            // 终态收尾统一由 worker 线程在等待返回后按 terminalPushed CAS 认领执行（与本
            // Runnable/doOnComplete/onErrorResume 互斥，防双终态/漏终态，也避免取消线程与
            // worker finally 清理（removeRing/注册表）竞争丢 END 事件）
            Runnable cancelFinisher = streamDone::countDown;

            Disposable subscription = compiledGraph.stream(inputs, config)
                    .doOnNext(chunk -> {
                        // 取消检测
                        checkCancelled(runIdStr);
                        // B3-5：检索来源就绪后补推 SOURCES 事件（首个回答 token 前，一次性）
                        maybePushSources(runIdStr, runState, config, sourcesPushed);
                        // STAGE 阶段跃迁：QU 完成→检索/生成、retrieveNode 完成→生成、
                        // reactAgent 首个模型 chunk→生成兜底（RunState CAS 保证每阶段仅一次）
                        transformer.transformStages(chunk, runState).forEach(e -> bridge.push(runIdStr, e));
                        // 记录最后输出（用于持久化）
                        lastOutput.set(chunk);
                        // transform → 推送点同步累积（Task 5：先累积后推送，不改 transformer 纯函数性；
                        // assistantSink：FINISHED 模型输出结束点捕获主 agent 调用，消息实体化 spec §3.2）
                        List<SseEvent> events = transformer.transform(chunk, runState, assistantSink);
                        events.forEach(e -> {
                            deltaAccumulator.accumulate(e);
                            bridge.push(runIdStr, e);
                        });
                    })
                    .onErrorResume(e -> {
                        // P0-7: 与 catch 分支对齐的兜底——handleCancelled/handleError 内部
                        // （bridge.push / updateStatus）再抛异常不得从 Reactor 链传播，
                        // 否则等待点重抛 → 落入 catch 分支二次 handleError（双终态）+ 重复持久化
                        // B2-4: compareAndSet 占位终态——后续 catch 分支不再推第二个终态
                        if (terminalPushed.compareAndSet(false, true)) {
                            try {
                                if (isCancelledError(e)) {
                                    handleCancelled(runIdStr, runId, runState, config, deltaAccumulator);
                                } else {
                                    handleError(runIdStr, runId, runState, e);
                                }
                            } catch (Exception errorEx) {
                                log.error("onErrorResume 分支终态处理失败 runId={}", runId, errorEx);
                            }
                        }
                        // 持久化已收集的消息（游标 = pre-run checkpoint 消息数，只落本轮新增）；
                        // R2：错误终态不带 messageId，返回的 assistantMessageId 在此分支不消费；
                        // 来源=取消/错误路径（Task 5）：正文/思考以 delta 累加器优先（与前端已渲染一致）
                        persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                attachmentsJson,
                                readSourcesJson(config),
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get(),
                                thinkingPusher,
                                deltaAccumulator,
                                assistantSink,
                                true);
                        return Mono.empty();
                    })
                    .doOnComplete(() -> {
                        // M2/R1-b：dispose 与完成的竞态——流恰已 onComplete 时终态回调正常到达，
                        // 已取消则跳过正常完成逻辑、不认领终态（doFinally 唤醒后由 worker 线程
                        // 在等待返回处统一走取消终态），防把取消误报为 COMPLETED、防漏终态
                        if (isCancelled(runIdStr)) {
                            return;
                        }
                        // B2-4/M2: 前置 CAS 认领终态——认领失败 = 终态已由 onErrorResume 处理，
                        // 本回调直接返回（原「先落库后置位」在取消竞态下会与取消收尾双终态，改为先认领）
                        if (!terminalPushed.compareAndSet(false, true)) {
                            return;
                        }
                        // 先持久化消息、再推 END + 写 COMPLETED 终态——
                        // 「run 终态 = 数据已完整落库」语义（chat_message 与状态非原子，
                        // 先状态后落库会让外部观察者在终态可见时查到空消息表）
                        // R2 补口 B：落库返回 assistant 正文行回填 ID，END 事件据此携带 messageId；
                        // 来源=正常完成路径（Task 5）：state 汇总为权威，delta 累加器不参与
                        PersistOutcome outcome = persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                attachmentsJson,
                                readSourcesJson(config),
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get(),
                                thinkingPusher,
                                deltaAccumulator,
                                assistantSink,
                                false);
                        persisted.set(outcome.persisted());
                        // 图执行完成可观测性（dev 定位）：最终回答正文摘要（截断，禁打完整响应体）
                        String finalAnswer = deltaAccumulator.text();
                        log.info(
                                "图执行完成: runId={}, 回答={}字, 预览={}",
                                runIdStr,
                                finalAnswer.length(),
                                truncateText(finalAnswer, 200));
                        // 终态标记已在回调入口 CAS 认领（M2）——handleCompleted 推 END 后
                        // updateStatusWithRetry 若耗尽上抛（经错误通道重抛入 catch），catch 分支
                        // 据已认领标记跳过第二个终态事件（客户端只认首个终态）
                        handleCompleted(runIdStr, runId, runState, outcome.assistantMessageId());
                        // 偏好提取异步触发（spec §7.6：run 完成、SSE 已发送完后异步，不阻塞用户响应；
                        // 仅 COMPLETED 触发——error/cancel 路径不提取）
                        triggerPreferenceExtraction(userId, lastOutput.get());
                        // 经历记忆提取异步触发（spec §8.4：与偏好同一触发点仅 run COMPLETED 后、
                        // 独立任务独立 prompt、共用防抖队列；error/cancel 路径不触发）
                        triggerEpisodicExtraction(userId, sessionId, lastOutput.get());
                    })
                    .doFinally(signal -> streamDone.countDown())
                    .subscribe(null, reactorFailure::set);

            runDisposables.put(runIdStr, subscription);
            cancelFinishers.put(runIdStr, cancelFinisher);
            // 注册后补检：取消可能落在订阅建立前（排队/附件/QU 阶段——cancel() 时无句柄可
            // dispose），此刻主动 dispose + 收尾，兑现「取消立即生效不等首个 chunk 检查点」
            if (isCancelled(runIdStr)) {
                Disposable registered = runDisposables.get(runIdStr);
                if (registered != null && !registered.isDisposed()) {
                    registered.dispose();
                    log.info("注册后发现 run 已取消，立即 dispose 图流订阅: runId={}", runIdStr);
                }
                Runnable finisher = cancelFinishers.get(runIdStr);
                if (finisher != null) {
                    finisher.run();
                }
            }

            // 等待终态（subscribe 惰性启动由本行等待承接；5 分钟总兜底超时与原 blockLast 一致）
            boolean finished;
            try {
                finished = streamDone.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscription.dispose();
                throw new IllegalStateException("图执行等待被中断: runId=" + runId, e);
            }
            if (!finished) {
                subscription.dispose();
                throw new IllegalStateException("图执行总时长超时（timeout on graph stream, 5 分钟兜底）: runId=" + runId);
            }
            // M2/R1-a 取消终态统一收尾点：dispose 先行（doFinally(CANCEL)/取消收尾双通道唤醒）
            // 或 R1-b 完成竞态（doOnComplete 见取消标记主动让位）时，终态尚未被认领——
            // 由本线程 CAS 认领后补齐取消终态 + 增量落库；认领失败 = 终态已由
            // onErrorResume/doOnComplete 处理（互斥不双终态）
            if (isCancelled(runIdStr) && terminalPushed.compareAndSet(false, true)) {
                try {
                    handleCancelled(runIdStr, runId, runState, config, deltaAccumulator);
                } catch (Exception cancelEx) {
                    log.error("取消收尾终态处理失败 runId={}", runId, cancelEx);
                }
                // 取消终态同样需要增量行落库（终态落库 ≡ 已推送事件序列不变量）；
                // persisted 标记与正常完成路径同口径（防 catch 分支二次落库）
                try {
                    PersistOutcome cancelOutcome = persistMessages(
                            runId,
                            sessionId,
                            userQuery,
                            attachmentsJson,
                            readSourcesJson(config),
                            snapshot != null ? snapshot.historyMessageCount() : 0,
                            lastOutput.get(),
                            thinkingPusher,
                            deltaAccumulator,
                            assistantSink,
                            true);
                    persisted.set(cancelOutcome.persisted());
                } catch (Exception persistEx) {
                    log.error("取消收尾增量落库失败 runId={}", runId, persistEx);
                }
            }
            // 回调通道异常重抛（等价原 blockLast 把 doOnComplete 尾段异常抛给外层 catch 的语义）
            Throwable failure = reactorFailure.get();
            if (failure != null) {
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new IllegalStateException("图执行回调异常: runId=" + runId, failure);
            }

        } catch (Exception e) {
            log.error("processRequest 致命错误 runId={}", runId, e);
            // P0-4b 修复：补齐终态——推送 ERROR 事件 + 持久化已收集消息（与 onErrorResume 分支对齐）。
            // handleError 单独兜底：其内部状态更新失败不得阻断消息持久化（修复审查 finding）
            // B2-4: 终态事件已推送（doOnComplete 的 END / onErrorResume 的终态）时不再推第二个
            // 终态事件——客户端状态机只认首个终态；run 滞留 ACTIVE 由 M-8 巡检兜底收敛为 ERROR
            if (terminalPushed.compareAndSet(false, true)) {
                try {
                    handleError(runIdStr, runId, runState, e);
                } catch (Exception errorEx) {
                    log.error("handleError 执行失败 runId={}", runId, errorEx);
                }
            } else {
                log.warn("终态事件已推送，跳过 catch 分支二次终态处理（run 状态由巡检兜底收敛）: runId={}", runId);
            }
            // B2-4: 消息已持久化（doOnComplete 落库成功）时跳过二次 persistMessages——
            // 完成时刻 DB 故障恢复后重复整批插入会造成 chat_message 成倍重复
            if (persisted.get()) {
                log.info("消息已持久化，跳过 catch 分支重复持久化: runId={}", runId);
            } else {
                // R2：错误终态不带 messageId，此分支仅消费 persisted 标记；
                // 来源=取消/错误路径（Task 5）：正文/思考以 delta 累加器优先（与前端已渲染一致）
                PersistOutcome retryOutcome = persistMessages(
                        runId,
                        sessionId,
                        userQuery,
                        attachmentsJson,
                        readSourcesJson(config),
                        snapshot != null ? snapshot.historyMessageCount() : 0,
                        lastOutput.get(),
                        thinkingPusher,
                        deltaAccumulator,
                        assistantSink,
                        true);
                persisted.set(retryOutcome.persisted());
            }
        } finally {
            bridge.removeRing(runIdStr);
            cancelFlags.remove(runIdStr);
            // M2：清理 run 级订阅句柄与取消收尾注册（生命周期与 cancelFlags 同界，run 终止即失效）
            runDisposables.remove(runIdStr);
            cancelFinishers.remove(runIdStr);
            // BUG-11：任何路径（含取消/异常）都清理 WarningHook per-thread 检测状态——
            // 原实现仅自然结束/软停清理，取消与异常终止的 run 使 DetectionState 常驻至会话结束
            warningHook.cleanupSession(sessionIdStr);
        }
    }

    // ========================================================================
    // 取消
    // ========================================================================

    /**
     * BUG-01 守卫拒绝后的收尾：跳过图执行并为仍在等待的客户端补推终态事件。
     *
     * <p>触发场景：高峰积压下巡检已把滞留 QUEUED 的 run 置 ERROR 解锁会话（用户可能已重发新 run），
     * 迟到队列任务此时才被取出执行——updateStatus(ACTIVE) 被「仅 QUEUED 可迁移」守卫拒绝（0 行）。
     * 本方法补查 run 实际终态并推送终态事件（客户端状态机收到终态而非悬挂到 emitter 超时），
     * 事件经 ring 入队后由 finally 的 removeRing 按 drain 语义送达；run 非终态（重复投递被并发
     * 任务接管的极端窗口）时不推事件，避免污染执行中流。
     *
     * @param runId    Run ID（Long）
     * @param runIdStr Run 唯一标识（字符串，ring 键）
     * @param runState SSE 事件序列状态（seqId 递增）
     */
    private void skipExecutionForGuardRejectedRun(Long runId, String runIdStr, SseEventTransformer.RunState runState) {
        ChatRunVO current = chatRunService.findById(runId);
        String status = current == null ? null : current.status();
        log.warn("run 迁移 ACTIVE 被状态机守卫拒绝，跳过图执行: runId={}, 当前状态={}", runId, status);
        // 非终态（重复投递/状态异常窗口）：不推终态事件，仅记日志跳过
        if (!"COMPLETED".equals(status) && !"CANCELLED".equals(status) && !"ERROR".equals(status)) {
            return;
        }
        // 补推终态事件（携带实际终态；ERROR 类型事件对客户端状态机即终态信号）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runIdStr);
        payload.put("status", status);
        payload.put("message", "请求排队超时已被结束，请重新发送");
        bridge.push(
                runIdStr,
                new SseEvent(SseEventType.ERROR, runState.nextSeq(), toJson(payload), System.currentTimeMillis()));
    }

    /**
     * 注册待执行 run 的取消条目（由入口 ChatStreamEntry 在 XADD 成功后调用）。
     *
     * <p>BUG-14 生命周期修正：cancelFlags 条目原由 cancel() 自身 computeIfAbsent 创建，
     * 唯一清理路径是 processRequest.finally——对已完成 run 取消会在 TOCTOU 窗口内
     * 重建永不清除的残留条目。现改为「入队注册 → finally 清理」的完整生命周期：
     * 条目在 run 入队时即存在（排队期取消同样生效），run 终止时由 finally 清理。
     *
     * <p>putIfAbsent 幂等且不覆盖已有值：已置位的取消标记（入队后即被取消）不会被重置。
     *
     * @param runId Run 唯一标识（字符串形态，与 cancelFlags 键一致）
     */
    public void registerPendingRun(String runId) {
        cancelFlags.putIfAbsent(runId, new AtomicBoolean(false));
    }

    /**
     * 取消指定 run（由 Controller 调用）。
     *
     * <p>BUG-14：改用 computeIfPresent 仅对已注册（排队/执行中）的 run 置位——
     * 条目不存在说明 run 未在执行或已完成（其 finally 已清理），置位是 no-op，
     * 消除原 computeIfAbsent 对已完成 run 重建残留条目的 TOCTOU 泄漏。
     *
     * <p>M2 取消立即性（R1 实证）：置位后<b>立刻 dispose 图流订阅</b>——reactor cancel
     * 信号沿算子链传播并关闭上游 DashScope 流式 SSE 连接（不等 chunk 边界检查点，主 agent
     * 单次 LLM 调用 8~18s 内即时生效）；随后调用 per-run 取消收尾 Runnable 主动唤醒
     * processRequest 的终态等待——R1 实证 dispose 先行时 onComplete/onError 均静默丢弃
     * （无任何回调），不唤醒将挂满 5 分钟兜底超时误走 ERROR；终态收尾由 worker 线程在等待
     * 返回处按 terminalPushed CAS 认领统一执行。订阅未注册（run 排队/附件/图启动前阶段）
     * 时无句柄可 dispose，靠后续检查点或注册后补检生效。边界：QU/检索 join 等同步段为
     * 软取消（跑完但结果丢弃，不可中断），属预期边界。
     */
    public void cancel(String runId) {
        // 仅当条目已存在（run 排队/执行中）时原子置位；run 不在执行中则记录后忽略
        AtomicBoolean flag = cancelFlags.computeIfPresent(runId, (k, f) -> {
            f.set(true);
            return f;
        });
        // M2 取消立即性：立刻 dispose 图流订阅（取消传播至上游连接，不等 chunk 边界检查点）
        Disposable subscription = runDisposables.get(runId);
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("已 dispose 图流订阅（取消传播至上游）: runId={}", runId);
        }
        // R1-a 主动唤醒：唤醒 worker 终态等待（终态收尾由 worker 线程在等待返回处按
        // terminalPushed CAS 认领统一执行——与 doOnComplete/onErrorResume 互斥不双终态，
        // 且 END 推送先于 worker finally 的 removeRing，事件不丢）
        Runnable finisher = cancelFinishers.get(runId);
        if (finisher != null) {
            finisher.run();
        }
        if (flag != null) {
            log.info("请求取消 run: runId={}", runId);
        } else {
            log.info("取消请求忽略（run 不在执行中）: runId={}", runId);
        }
    }

    // ========================================================================
    // pre-run 快照
    // ========================================================================

    /**
     * 捕获 pre-run 快照（saver.get → 容器级浅拷贝）。
     *
     * <p>设计文档 §3.4：使用 saver.get(config) 获取 checkpoint，对 state 做容器级浅拷贝
     * （顶层 Map 独立、值对象引用共享），防止图执行过程中修改原 checkpoint 数据导致回滚失效。
     *
     * <p>F2-11 说明：设计文档原文使用 {@code saver.getTuple(config)} 获取 checkpoint tuple，
     * 但 SAA 1.1.2.0 API 中 {@link BaseCheckpointSaver} 无 {@code getTuple} 方法
     * （该 API 属于 LangGraph4j 原生接口，SAA 未暴露）。此处使用 {@code saver.get(config)}
     * 替代，返回 {@code Optional<Checkpoint>}，功能等价。
     * 浅拷贝通过 {@code new HashMap<>(state)} 实现——SAA 1.1.2.0 实证图执行期不原地修改
     * checkpoint state，浅拷贝即可满足回滚隔离，且保留 Message 类型（P1-3；原 JSON 深拷贝
     * 经无多态注册的 ObjectMapper 会把 Message 反序列化为 LinkedHashMap，类型破坏）。
     * 快照失败降级（记 warn 不阻塞 run）。
     *
     * @param runId  Run 唯一标识
     * @param config RunnableConfig（含 threadId + userId）
     * @return 快照（state 为容器级浅拷贝），失败时返回 null
     */
    private RunSnapshot captureSnapshot(String runId, RunnableConfig config) {
        try {
            Optional<Checkpoint> opt = saver.get(config);
            if (opt.isEmpty()) {
                log.debug("pre-run 快照: 无历史 checkpoint runId={}", runId);
                return null;
            }
            Checkpoint cp = opt.get();
            // P1-3: 容器级浅拷贝替代 JSON 深拷贝——SAA 1.1.2.0 实证（OverAllState.updateState
            // 用 Stream.collect 产新 Map、AppendStrategy 用 new ArrayList 产新 List）图执行期
            // 不原地修改 checkpoint state，顶层 Map 独立即可保证快照安全，且 Message 类型 100% 保留
            // （JSON 往返会经无多态注册的 ObjectMapper 把 Message 反序列化为 LinkedHashMap，类型破坏）
            Map<String, Object> stateCopy = new HashMap<>(cp.getState());
            // 计算持久化游标：pre-run checkpoint 中 messages 列表长度（P0-4a 去重）
            int historyCount = 0;
            Object messagesObj = stateCopy.get("messages");
            if (messagesObj instanceof List<?> messageList) {
                historyCount = messageList.size();
            }
            return new RunSnapshot(
                    runId,
                    cp.getId(),
                    cp.getNodeId(),
                    cp.getNextNodeId(),
                    stateCopy,
                    historyCount,
                    System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("pre-run 快照失败 runId={} (不阻塞)", runId, e);
            return null;
        }
    }

    // ========================================================================
    // 消息持久化
    // ========================================================================

    /**
     * 将 run 过程中的消息持久化到 chat_message 表（双路径分流，spec §3.5）。
     *
     * <p><b>正常完成路径（abnormalPath=false，消息实体化 2026-08-29）</b>：
     * 每次 LLM 调用落<b>一条</b> {@code message_type='assistant'} 实体行（content 为 spec §3.1
     * JSON：{schema, stage, reasoning[], toolCalls[], text}，thinking_stage 列不写）——与厂商
     * assistant 消息 1:1，B 端一行看全貌；sources 仅主 agent 实体行落真实来源（独立非模型
     * 事件，spec §3.3）；TOOL_RESULT 行保持独立事件行（非模型消息）。消费面（C 端历史 /
     * 降级回放）由 {@link AssistantEntitySplitter} 拆实体还原事件序行，前端零改动。
     *
     * <p><b>取消/错误路径（abnormalPath=true，维持现状增量行）</b>：query_plan 行 + 阶段
     * thinking 行（写 thinking_stage 列）+ 正文行（DeltaAccumulator 优先，state 汇总回退），
     * 一条不改——不变量「终态落库 ≡ 已推送序列」双路径各自成立（正常=实体行完整可拆回；
     * 取消=增量行即已推送事件镜像）。
     *
     * <p>消息来源（正常路径）：
     * <ol>
     *   <li>用户查询 → ChatMessage(role=USER)</li>
     *   <li>assistant 实体行 ← AssistantMessageSink 捕获（QU 流式聚合完成点 / caption 调用
     *       完成点 / transformer FINISHED 模型输出结束点，spec §3.2）</li>
     *   <li>TOOL_RESULT 行 ← 最终图状态 messages 中的 ToolResponseMessage</li>
     * </ol>
     *
     * <p>N-F2-5 说明：{@code lastOutput.state()} 是 SAA 流式输出的最后一个 NodeOutput
     * 携带的状态。若 lastOutput 为 null（流式异常中断无 chunk 输出），则仅持久化用户消息。
     *
     * @param runId            Run 唯一标识
     * @param sessionId        会话 ID
     * @param userQuery        本轮用户问题原文
     * @param attachmentsJson  本轮输入附件 JSON 数组字符串（已校验合法，落用户消息行 attachments_json，spec §5.1）
     * @param sourcesJson      检索引用来源 JSON 数组字符串（B3-5：assistant 实体行 sources_json；
     *                         null/空按 "[]" 处理——chat/unknown 意图与空检索场景）
     * @param historyCursor    持久化游标（pre-run checkpoint 中 messages 数，P0-4a）：
     *                         仅持久化 rawList 中 index >= historyCursor 的新增消息，避免历史消息每轮重插
     * @param lastOutput       流式输出的最后一个 NodeOutput（可为 null，此时仅持久化用户消息）
     * @param thinkingPusher   per-run 思考推送通道（2026-08-28 时间线改版：understanding/attachments
     *                         阶段思考不在 state.messages，取消/错误路径从该通道累加缓冲补落
     *                         thinking 行；可为 null）
     * @param deltaAccumulator per-run 正文/思考 delta 累加器（2026-08-28 时间线改版 Task 5：推送点累积的
     *                         已推送事件序列，取消/错误路径落库事实源；可为 null——视为空，回退 state）
     * @param assistantSink    per-run LLM 调用捕获容器（2026-08-29 消息实体化：正常完成路径
     *                         实体行事实源；可为 null——视为空，正常路径不落实体行）
     * @param abnormalPath     落库来源标志（Task 5）：true=取消/错误路径（增量行模型不变）；
     *                         false=正常完成路径（实体行模型）
     * @return 落库结果（R2 补口 B）：persisted=true=已落库（含 (run_id,seq) 唯一索引冲突的幂等跳过，
     *         调用方不得重试）；false=落库失败且未确认写入（调用方可重试）；
     *         assistantMessageId=assistant 正文行/实体行（role=ASSISTANT 且 messageType 为 null
     *         或 "assistant"）落库回填的雪花 ID——幂等跳过/失败/无正文行时为 null（END 事件
     *         messageId 显式 null 降级）
     */
    @SuppressWarnings("unchecked")
    private PersistOutcome persistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            String sourcesJson,
            int historyCursor,
            NodeOutput lastOutput,
            ThinkingPusher thinkingPusher,
            DeltaAccumulator deltaAccumulator,
            AssistantMessageSink assistantSink,
            boolean abnormalPath) {
        List<ChatMessage> messages = new ArrayList<>();
        int seq = 0;

        // R2 意图落库修复：从最终 state 的查询计划取意图规范名小写（修复 intent_type 恒 NULL；
        // 无计划（异常中断/QU 未写入）返回 null，实体行 intent_type 保持 null——存量行不受影响）
        QueryPlan queryPlan = resolveQueryPlan(lastOutput);
        String intentType = queryPlan == null ? null : queryPlan.intent().code();

        // 1. 用户消息（携带本轮附件列表，供前端渲染/审计回放 —— spec §5.1 双存决策）
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRunId(runId);
        userMsg.setRole("USER");
        userMsg.setContent(userQuery);
        userMsg.setSeq(seq++);
        userMsg.setSourcesJson("[]");
        userMsg.setAttachmentsJson(attachmentsJson);
        messages.add(userMsg);

        if (abnormalPath) {
            // ====================================================================
            // 取消/错误路径（spec §3.5）：维持现状增量行模型，一条不改
            // ====================================================================

            // 2. query_plan 行（2026-08-28 时间线改版）：QU 签出的需求解析结果落库，content 与 SSE
            //    query_plan 事件同款 JSON（buildQueryPlanPayload 单一构造点），回放可重建该事件；
            //    seq 恒排在本轮所有 thinking 行之前；无计划（异常中断）不落行
            ChatMessage queryPlanRow = buildQueryPlanRow(runId, sessionId, queryPlan);
            if (queryPlanRow != null) {
                queryPlanRow.setSeq(seq++);
                messages.add(queryPlanRow);
            }

            // 3. 图内节点阶段思考行（understanding / attachments，2026-08-28 时间线改版）：
            //    QU/caption 的思考经 ThinkingPusher 瞬时通道推送、不进 state.messages——
            //    从 per-run 累加缓冲按阶段生成 thinking 行（thinking_stage 落列区分来源），
            //    seq 排在主 agent generating thinking 行之前（主 thinking 来自下方 state 消息转换）
            if (thinkingPusher != null) {
                for (Map.Entry<String, String> entry :
                        thinkingPusher.accumulated().entrySet()) {
                    String stageThinking = entry.getValue() == null ? "" : entry.getValue();
                    if (stageThinking.isBlank()) {
                        continue;
                    }
                    ChatMessage cm = new ChatMessage();
                    cm.setSessionId(sessionId);
                    cm.setRunId(runId);
                    cm.setRole("ASSISTANT");
                    cm.setMessageType("thinking");
                    cm.setThinkingStage(entry.getKey());
                    cm.setContent(stageThinking);
                    cm.setSourcesJson("[]");
                    cm.setSeq(seq++);
                    messages.add(cm);
                }
            }

            // 4. 取消/错误路径：delta 累加器优先落库（Task 5，不变量「终态落库内容 ≡ 已推送事件序列」）——
            //    流中断时 state 往往没有终消息（in-flight 消息只在节点完成点进 state），正文行用
            //    textAcc、generating 思考行用 thinkingAcc[generating]；两者皆空回退下方 state 汇总。
            boolean accBodyUsed = false;
            boolean accGeneratingThinkingUsed = false;
            if (deltaAccumulator != null) {
                // 4.1 generating 思考行：transformer 产出的 THINKING 事件（固定 stage=generating）累积全文
                String accThinking = deltaAccumulator.thinking(SseEventTransformer.STAGE_GENERATING);
                if (accThinking != null && !accThinking.isBlank()) {
                    accGeneratingThinkingUsed = true;
                    ChatMessage cm = new ChatMessage();
                    cm.setSessionId(sessionId);
                    cm.setRunId(runId);
                    cm.setRole("ASSISTANT");
                    cm.setMessageType("thinking");
                    cm.setThinkingStage(SseEventTransformer.STAGE_GENERATING);
                    cm.setContent(accThinking);
                    cm.setSourcesJson("[]");
                    cm.setSeq(seq++);
                    messages.add(cm);
                }
                // 4.2 正文行：已推送 DELTA 事件片段按序拼接（与前端已渲染严格一致）
                String accBody = deltaAccumulator.text();
                if (!accBody.isBlank()) {
                    accBodyUsed = true;
                    ChatMessage cm = new ChatMessage();
                    cm.setSessionId(sessionId);
                    cm.setRunId(runId);
                    cm.setRole("ASSISTANT");
                    cm.setContent(accBody);
                    // 检索来源与 state 汇总路径同源（metadata 通道，chat/unknown 意图无来源保持 "[]"）
                    cm.setSourcesJson(sourcesJson == null || sourcesJson.isBlank() ? "[]" : sourcesJson);
                    // R2：正文行意图标注口径与 state 路径一致（无计划保持 null）
                    if (intentType != null) {
                        cm.setIntentType(intentType);
                    }
                    cm.setSeq(seq++);
                    messages.add(cm);
                }
            }

            // 5. 从最终状态提取 messages 列表，仅转换本轮新增（index >= 游标）——P0-4a 修复：
            //    state 跨 run 累积（AppendStrategy），游标 = pre-run checkpoint 消息数，
            //    否则历史 assistant/thinking/tool 消息每轮全量重插。
            //    Task 5：累加器已落库的行在此抑制同义 state 行（正文/generating 思考），防止双行重复；
            //    TOOL_CALL/TOOL_RESULT 行不抑制（前端同样已见对应事件，照常落库）
            if (lastOutput != null && lastOutput.state() != null) {
                Optional<Object> messagesOpt = lastOutput.state().value("messages");
                if (messagesOpt.isPresent() && messagesOpt.get() instanceof List<?> rawList) {
                    int start = Math.max(0, Math.min(historyCursor, rawList.size()));
                    for (int i = start; i < rawList.size(); i++) {
                        Object item = rawList.get(i);
                        if (item instanceof Message msg) {
                            // F2-12: 跳过 UserMessage —— 用户消息已在步骤1单独插入，不重复
                            if (msg instanceof UserMessage) {
                                continue;
                            }
                            List<ChatMessage> converted = toChatMessages(msg, runId, sessionId, sourcesJson);
                            for (ChatMessage cm : converted) {
                                // 累加器已落正文行 → 抑制 state 侧 assistant 正文行（防双行）
                                if (accBodyUsed && "ASSISTANT".equals(cm.getRole()) && cm.getMessageType() == null) {
                                    continue;
                                }
                                // 累加器已落 generating 思考行 → 抑制 state 侧同阶段思考行（防双行）
                                if (accGeneratingThinkingUsed
                                        && "thinking".equals(cm.getMessageType())
                                        && SseEventTransformer.STAGE_GENERATING.equals(cm.getThinkingStage())) {
                                    continue;
                                }
                                cm.setSeq(seq++);
                                // R2：仅 assistant 正文行（messageType==null）标注意图——
                                // thinking/TOOL_* 行与用户行不落（意图描述的是本轮 AI 回答性质）
                                if (intentType != null
                                        && "ASSISTANT".equals(cm.getRole())
                                        && cm.getMessageType() == null) {
                                    cm.setIntentType(intentType);
                                }
                                messages.add(cm);
                            }
                        }
                    }
                }
            }
        } else {
            // ====================================================================
            // 正常完成路径（消息实体化，spec §3.3）：每次 LLM 调用一条 assistant 实体行
            // ====================================================================

            List<AssistantMessageCapture> captures = assistantSink == null ? List.of() : assistantSink.snapshot();

            // 3. TOOL_RESULT 行预收集（BUG-11）：按 toolCallId 把工具结果配对到「发起该调用的
            //    捕获」——实时事件序为 TOOL_CALL（实体行内）→ TOOL_RESULT → 下一实体行，
            //    落库 seq 必须穿插在对应实体行之后，回放（前端按行 seq 重建时间轴）才与实时
            //    一致；修复前集中排在全部实体行之后，多轮工具调用回放时序错乱。
            //    游标语义与取消路径一致：仅取本轮新增（index >= historyCursor）；
            //    未配对结果（理论兜底：实体序列化失败跳行）按到达序收敛到全部实体行之后。
            Map<String, Integer> toolCallCaptureIndex = new HashMap<>();
            for (int i = 0; i < captures.size(); i++) {
                for (AssistantMessageCapture.AssistantToolCall toolCall :
                        captures.get(i).toolCalls()) {
                    if (toolCall.id() != null && !toolCall.id().isEmpty()) {
                        // putIfAbsent：同一 id 重复出现时锚定首次捕获（toolCallId 模型侧唯一）
                        toolCallCaptureIndex.putIfAbsent(toolCall.id(), i);
                    }
                }
            }
            List<List<ToolResponseMessage.ToolResponse>> resultsByCapture = new ArrayList<>(captures.size());
            for (int i = 0; i < captures.size(); i++) {
                resultsByCapture.add(new ArrayList<>());
            }
            List<ToolResponseMessage.ToolResponse> tailResults = new ArrayList<>();
            if (lastOutput != null && lastOutput.state() != null) {
                Optional<Object> messagesOpt = lastOutput.state().value("messages");
                if (messagesOpt.isPresent() && messagesOpt.get() instanceof List<?> rawList) {
                    int start = Math.max(0, Math.min(historyCursor, rawList.size()));
                    for (int i = start; i < rawList.size(); i++) {
                        Object item = rawList.get(i);
                        if (item instanceof ToolResponseMessage trm) {
                            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                                // ToolResponse.id() 契约非空（Spring AI 框架保证），直接查表配对；
                                // 未命中（含 get 返回 null）走 tailResults 兜底，与原判空分支行为一致
                                Integer captureIdx = toolCallCaptureIndex.get(tr.id());
                                if (captureIdx == null) {
                                    tailResults.add(tr);
                                } else {
                                    resultsByCapture.get(captureIdx).add(tr);
                                }
                            }
                        }
                    }
                }
            }

            // 2. assistant 实体行：QU / caption / 主 agent 各一次调用一条（捕获顺序 = 调用结束序）。
            //    seq = 拆行末位 VO 序号——n 个拆行 VO 占 [seq, seq+n-1]（虚拟位，无实际行），
            //    实体行取末位；消费面拆行函数按「实体seq-(n-1)…实体seq」倒推，读写两侧同源一致
            //    （(run_id,seq) 唯一索引无冲突、跨实体/TOOL_RESULT 混合排序正确）。
            //    每条实体行落位后紧随其挂载的 TOOL_RESULT 行（BUG-11 事件序穿插）
            for (int ci = 0; ci < captures.size(); ci++) {
                AssistantMessageCapture capture = captures.get(ci);
                String entityJson = AssistantEntitySplitter.toEntityJson(capture);
                if (entityJson == null) {
                    // 序列化失败（理论不可达：纯 LinkedHashMap 结构），跳过该调用不落行——
                    // 其挂载的工具结果行仍须落库（挂在当前位置，不丢行）
                    seq = appendToolResultRows(messages, resultsByCapture.get(ci), runId, sessionId, seq);
                    continue;
                }
                int voCount = AssistantEntitySplitter.voCount(entityJson);
                if (voCount == 0) {
                    // 空调用（无思考/正文/工具调用）：与实体化前「空消息不落行」语义一致
                    seq = appendToolResultRows(messages, resultsByCapture.get(ci), runId, sessionId, seq);
                    continue;
                }
                ChatMessage row = new ChatMessage();
                row.setSessionId(sessionId);
                row.setRunId(runId);
                row.setRole("ASSISTANT");
                row.setMessageType("assistant");
                row.setContent(entityJson);
                // 检索来源：仅主 agent 实体行落真实来源（sources 为独立非模型事件，spec §3.3
                // 注释「sources 行保持独立」——拆行正文 VO 透传，C 端历史来源卡不回归）；
                // QU/caption 实体恒 "[]"
                row.setSourcesJson(
                        SseEventTransformer.STAGE_GENERATING.equals(capture.stage())
                                ? (sourcesJson == null || sourcesJson.isBlank() ? "[]" : sourcesJson)
                                : "[]");
                // R2：仅主 agent 实体行标注意图（与实体化前正文行同口径；QU/caption 实体不标）
                if (SseEventTransformer.STAGE_GENERATING.equals(capture.stage()) && intentType != null) {
                    row.setIntentType(intentType);
                }
                row.setSeq(seq + voCount - 1);
                seq += voCount;
                messages.add(row);

                // BUG-11：该调用发起的工具结果行紧随其实体行分配 seq（实时事件序），保持独立
                // 事件行（非模型消息，spec §3.3），与实时 TOOL_RESULT 事件 schema 一致
                seq = appendToolResultRows(messages, resultsByCapture.get(ci), runId, sessionId, seq);
            }

            // 未配对工具结果兜底：按到达序排全部实体行之后（与修复前集中末尾行为一致）
            seq = appendToolResultRows(messages, tailResults, runId, sessionId, seq);
        }

        // 6. 批量插入
        if (!messages.isEmpty()) {
            try {
                chatMessageService.batchInsert(messages);
                log.info("持久化消息: runId={}, count={}", runId, messages.size());
                // R2：saveBatch 返回后实体 ID 已回填，反向扫描取最后一条 assistant 正文/实体行 ID
                return new PersistOutcome(true, resolveAssistantBodyId(messages));
            } catch (DataIntegrityViolationException e) {
                // B2-4 数据层兜底：(run_id,seq) 唯一索引（V13 uniq_chat_message_run_seq）冲突
                // = 本批消息已落库（完成路径重试/双路径重复调用），幂等跳过——按已落库处理，
                // 调用方不得因该冲突再次重试
                log.warn("消息已落库（(run_id,seq) 唯一索引冲突），幂等跳过重复落库: runId={}, count={}", runId, messages.size());
                return new PersistOutcome(true, null);
            } catch (Exception e) {
                log.error("消息持久化失败 runId={}", runId, e);
                return new PersistOutcome(false, null);
            }
        }
        // 无消息需落库（防御分支：用户消息恒存在，正常不达此处）——视为已处理
        return new PersistOutcome(true, null);
    }

    /**
     * 反向扫描定位最后一条 assistant 正文行/实体行的落库回填 ID（R2 补口 B）。
     *
     * <p>正文行判定：role=ASSISTANT 且 messageType 为 null（增量行正文）或 "assistant"
     * （2026-08-29 消息实体化实体行——正常完成路径正文语义由实体行承载）；thinking/TOOL_*
     * 行、用户行均跳过。反向扫描保证多轮 assistant 输出场景取到「最终回答」；
     * batchInsert（saveBatch）返回后实体雪花 ID 已回填，直接读取即可。
     *
     * @param messages 本批已落库的消息实体列表（ID 已回填）
     * @return 最后一条 assistant 正文/实体行 ID；无正文行时返回 null
     */
    private Long resolveAssistantBodyId(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage cm = messages.get(i);
            if ("ASSISTANT".equals(cm.getRole())
                    && (cm.getMessageType() == null || "assistant".equals(cm.getMessageType()))) {
                return cm.getId();
            }
        }
        return null;
    }

    /**
     * 从最终 state 提取查询计划（R2 意图落库 + 2026-08-28 query_plan 行共用单一提取点）。
     *
     * <p>KEY_QUERY_PLAN 由 queryUnderstandingNode 写入（ReplaceStrategy，每次 run 覆盖）。
     * 异常中断（lastOutput=null）或计划缺失时返回 null——正文行 intent_type 保持 null、
     * 不落 query_plan 行，前端按可空处理。
     *
     * @param lastOutput 流式输出的最后一个 NodeOutput（可为 null）
     * @return 查询计划；无 state/无计划/类型不符时返回 null
     */
    private QueryPlan resolveQueryPlan(NodeOutput lastOutput) {
        if (lastOutput == null || lastOutput.state() == null) {
            return null;
        }
        return lastOutput
                .state()
                .value(KEY_QUERY_PLAN)
                .filter(QueryPlan.class::isInstance)
                .map(QueryPlan.class::cast)
                .orElse(null);
    }

    /**
     * 构建 query_plan 落库行（2026-08-28 对话流式时间线改版）。
     *
     * <p>messageType="query_plan"、content 与 SSE query_plan 事件 payload 同款 JSON
     * （{@link SseEventTransformer#buildQueryPlanPayload} 单一构造点，PG 降级回放可原样重建事件）。
     * 序列化失败或无计划返回 null（跳过该行，不阻断主流程落库）。
     *
     * @param runId     Run ID
     * @param sessionId 会话 ID
     * @param plan      查询计划（可为 null——null 时不落行）
     * @return 待赋 seq 的 query_plan 行；无计划/序列化失败返回 null
     */
    private ChatMessage buildQueryPlanRow(Long runId, Long sessionId, QueryPlan plan) {
        if (plan == null) {
            return null;
        }
        try {
            // 与 SSE 事件 payload 同款 JSON 契约（intent/rewritten/filters.courseNames）
            ChatMessage row = new ChatMessage();
            row.setSessionId(sessionId);
            row.setRunId(runId);
            row.setRole("ASSISTANT");
            row.setMessageType("query_plan");
            row.setContent(objectMapper.writeValueAsString(SseEventTransformer.buildQueryPlanPayload(plan)));
            row.setSourcesJson("[]");
            return row;
        } catch (JsonProcessingException e) {
            log.warn("query_plan 行序列化失败，跳过该行落库: runId={}, err={}", runId, e.getMessage());
            return null;
        }
    }

    /**
     * 将单个 Spring AI Message 转换为 0~N 个 ChatMessage 实体。
     *
     * <p>一个 AssistantMessage 可能产出多条记录：
     * <ul>
     *   <li>主消息（文本内容）</li>
     *   <li>每个 ToolCall 一条 TOOL_CALL 记录</li>
     * </ul>
     * 一个 ToolResponseMessage 产出多个 TOOL_RESULT 记录。
     * UserMessage 产出一条 USER 记录。
     * 其他类型（SystemMessage 等）跳过。
     *
     * @param msg         Spring AI 消息对象
     * @param runId       Run ID
     * @param sessionId   会话 ID
     * @param sourcesJson 检索引用来源 JSON（B3-5：仅 assistant 正文行落 sources_json，空/blank 按 "[]"）
     * @return 转换后的 ChatMessage 实体列表（0~N 条）
     */
    private List<ChatMessage> toChatMessages(Message msg, Long runId, Long sessionId, String sourcesJson) {
        List<ChatMessage> result = new ArrayList<>();

        if (msg instanceof UserMessage) {
            ChatMessage cm = new ChatMessage();
            cm.setSessionId(sessionId);
            cm.setRunId(runId);
            cm.setRole("USER");
            cm.setContent(msg.getText());
            cm.setSourcesJson("[]");
            result.add(cm);
        } else if (msg instanceof AssistantMessage am) {
            // thinking 持久化 —— 设计文档 §3.5: msg_type='thinking', 存 reasoning 内容
            // （2026-08-28 时间线改版：state 消息来源的思考恒属主 agent 生成阶段，
            //   thinking_stage 落 generating；understanding/attachments 行由 persistMessages
            //   从 ThinkingPusher 累加缓冲另行生成，不经此路径）
            String reasoning = extractReasoningContent(am);
            if (reasoning != null && !reasoning.isEmpty()) {
                ChatMessage thinkingMsg = new ChatMessage();
                thinkingMsg.setSessionId(sessionId);
                thinkingMsg.setRunId(runId);
                thinkingMsg.setRole("ASSISTANT");
                thinkingMsg.setMessageType("thinking");
                thinkingMsg.setThinkingStage(SseEventTransformer.STAGE_GENERATING);
                thinkingMsg.setContent(reasoning);
                thinkingMsg.setSourcesJson("[]");
                result.add(thinkingMsg);
            }

            // 文本内容（可能为空，仅含 toolCalls）
            String text = am.getText();
            if (text != null && !text.isEmpty()) {
                ChatMessage cm = new ChatMessage();
                cm.setSessionId(sessionId);
                cm.setRunId(runId);
                cm.setRole("ASSISTANT");
                cm.setContent(text);
                // B3-5：assistant 正文行落真实检索来源（引用依据）；其余行（thinking/TOOL_*）保持 "[]"
                cm.setSourcesJson(sourcesJson == null || sourcesJson.isBlank() ? "[]" : sourcesJson);
                result.add(cm);
            }
            // 工具调用 —— 实时 TOOL_CALL 事件 schema 一致化（P1-2）：toolCallId/toolName/input
            if (am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    ChatMessage cm = new ChatMessage();
                    cm.setSessionId(sessionId);
                    cm.setRunId(runId);
                    cm.setRole("ASSISTANT");
                    cm.setMessageType("TOOL_CALL");
                    cm.setContent(buildToolCallContent(tc.id(), tc.name(), tc.arguments()));
                    cm.setSourcesJson("[]");
                    result.add(cm);
                }
            }
        } else if (msg instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                ChatMessage cm = new ChatMessage();
                cm.setSessionId(sessionId);
                cm.setRunId(runId);
                cm.setRole("ASSISTANT");
                cm.setMessageType("TOOL_RESULT");
                // 实时 TOOL_RESULT 事件 schema 一致化（P1-2）：toolCallId/status/output
                cm.setContent(buildToolResultContent(tr.id(), tr.responseData()));
                cm.setSourcesJson("[]");
                result.add(cm);
            }
        }
        // SystemMessage 等其他类型跳过

        return result;
    }

    // ========================================================================
    // 事件处理
    // ========================================================================

    /**
     * 处理正常完成：END 事件（含 messageId）+ 状态 COMPLETED。
     *
     * <p>R2 补口 B：END payload 扩为 {@code {runId, status:"COMPLETED", messageId}}——
     * messageId 为 assistant 正文行落库回填雪花 ID 的字符串形式（与 runId 字符串风格一致），
     * 幂等跳过/无正文行/异常降级时显式 null（前端 {@code messageId?: string | null} 可空容忍）。
     * 调用先于本方法的 persistMessages 已完成落库，时序天然保证 ID 可用。
     *
     * <p>P1-5：updateStatus 走短重试（3 次递增退避）——完成时刻 DB 瞬时故障若直接上抛，
     * run 滞留 ACTIVE，uniq_active_run_per_session 使该会话后续 chat() 永久 409 锁死；
     * 瞬时故障恢复后重试可收敛到 COMPLETED。
     *
     * @param runIdStr          Run 唯一标识（字符串）
     * @param runId             Run ID（Long）
     * @param runState          SSE 事件序列状态
     * @param assistantMessageId assistant 正文行落库回填 ID（可为 null——幂等跳过/无正文行）
     */
    private void handleCompleted(
            String runIdStr, Long runId, SseEventTransformer.RunState runState, Long assistantMessageId) {
        // LinkedHashMap：messageId 需显式输出 null（Map.of 不支持 null 值）且保证字段序稳定
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runIdStr);
        payload.put("status", "COMPLETED");
        payload.put("messageId", assistantMessageId == null ? null : assistantMessageId.toString());
        bridge.push(
                runIdStr,
                new SseEvent(SseEventType.END, runState.nextSeq(), toJson(payload), System.currentTimeMillis()));
        updateStatusWithRetry(runId, "COMPLETED");
    }

    /**
     * 触发偏好提取（spec §7.6：run 完成后异步；消息取最终 state 的 messages 列表，
     * 含本轮用户消息（附件 caption 已拼入图输入 UserMessage）与助手最终回答）
     *
     * @param userId     当前用户 ID（硬隔离过滤键）
     * @param lastOutput 流式最后一个 NodeOutput（可为 null——异常路径不触发）
     */
    private void triggerPreferenceExtraction(Long userId, NodeOutput lastOutput) {
        if (userId == null || lastOutput == null || lastOutput.state() == null) {
            return;
        }
        lastOutput
                .state()
                .value("messages")
                .filter(m -> m instanceof List<?>)
                .map(m -> (List<Message>) m)
                .ifPresent(msgs -> memoryExtractionPipeline.submit(userId, msgs));
    }

    /**
     * 触发经历记忆提取（spec §8.4：与偏好提取同一触发点 run COMPLETED 后、独立任务独立 prompt、
     * 共用防抖队列机制；error/cancel 路径不触发）
     *
     * @param userId     当前用户 ID（硬隔离过滤键）
     * @param sessionId  当前会话 ID（记忆 source_session_id 落库）
     * @param lastOutput 流式最后一个 NodeOutput（可为 null——异常路径不触发）
     */
    private void triggerEpisodicExtraction(Long userId, Long sessionId, NodeOutput lastOutput) {
        if (userId == null || lastOutput == null || lastOutput.state() == null) {
            return;
        }
        lastOutput
                .state()
                .value("messages")
                .filter(m -> m instanceof List<?>)
                .map(m -> (List<Message>) m)
                .ifPresent(msgs -> memoryExtractionPipeline.submitEpisodic(userId, sessionId, msgs));
    }

    /**
     * 处理取消：CANCELLED END 事件 + 状态 CANCELLED + 前滚补写 checkpoint。
     *
     * <p>M2/D6（R1-4 实证修正）：取消不再回滚 pre-run 快照——回滚会把本轮用户消息与半截
     * 回复一并抹掉，与「下一轮 run 上下文包含已停止生成的半截内容」不变量冲突（业界一致，
     * ChatGPT 同行为）。改为<b>前滚补写</b>：R1 实证流式中途取消时当前流式节点 checkpoint
     * 完全不写（写在 concatWith 尾部、仅正常 onComplete 才执行），saver.get 恢复至最后已
     * 完成节点边界、messages 不含半截 AI 回复——故显式取当前 state、追加半截
     * AssistantMessage（已推 delta 拼接）后写入新 checkpoint；无任何已推送 delta 时无内容
     * 可前滚，跳过写入仅原样收口。pre-run 快照机制本身保留（M5 编辑/重新生成复用）。
     * 半截内容另经 chat_message 增量行落库供前端渲染（M4 口径），两路径独立。
     *
     * @param runIdStr         Run 唯一标识（字符串）
     * @param runId            Run ID（Long）
     * @param runState         SSE 事件序列状态
     * @param config           RunnableConfig（含 threadId，前滚 checkpoint 读写定位）
     * @param deltaAccumulator per-run 正文/思考 delta 累加器（半截正文事实源；可为 null）
     */
    private void handleCancelled(
            String runIdStr,
            Long runId,
            SseEventTransformer.RunState runState,
            RunnableConfig config,
            DeltaAccumulator deltaAccumulator) {
        String payload = toJson(Map.of("runId", runIdStr, "status", "CANCELLED"));
        bridge.push(runIdStr, new SseEvent(SseEventType.END, runState.nextSeq(), payload, System.currentTimeMillis()));
        updateStatusWithRetry(runId, "CANCELLED");
        forwardRollCheckpoint(runIdStr, config, deltaAccumulator);
        log.info("Run 已取消（checkpoint 前滚保留半截内容，不回滚）: runId={}", runId);
    }

    /**
     * 前滚补写 checkpoint（M2/R1-4）：当前 checkpoint state 的 messages 追加半截
     * AssistantMessage 后写入新 checkpoint（新 id/ts，不删旧 checkpoint），节点游标沿用
     * 当前值——下一轮 run 从同一边界续跑，仅上下文多出半截内容。
     *
     * <p>写入模式复用原取消回滚的构造方式（M5 将以 replay 编排公共方法形态重建）。失败仅记
     * warn 不阻断取消终态（半截内容仍有 chat_message 增量行兜底，下一轮上下文缺失可容忍）。
     *
     * @param runIdStr         Run 唯一标识
     * @param config           RunnableConfig（threadId 定位 checkpoint）
     * @param deltaAccumulator per-run delta 累加器（半截正文 = 已推送 DELTA 拼接；可为 null）
     */
    private void forwardRollCheckpoint(String runIdStr, RunnableConfig config, DeltaAccumulator deltaAccumulator) {
        try {
            String halfText = deltaAccumulator == null ? "" : deltaAccumulator.text();
            if (halfText == null || halfText.isBlank()) {
                // 无任何已推送 delta：无半截内容可前滚，原样收口不写 checkpoint（D6 不回滚）
                log.info("取消时无已推送 delta，跳过 checkpoint 前滚: runId={}", runIdStr);
                return;
            }
            Optional<Checkpoint> currentOpt = saver.get(config);
            if (currentOpt.isEmpty()) {
                // 理论不可达（有 delta 必经 reactAgent，此前 QU 等节点已完成并写 checkpoint）；
                // 防御性原样收口，不构造缺失上下文的新 checkpoint
                log.warn("取消时无历史 checkpoint 可前滚，跳过: runId={}", runIdStr);
                return;
            }
            Checkpoint current = currentOpt.get();
            // 容器级浅拷贝 + ArrayList 拷贝追加（P1-3：Message 类型不经 JSON 往返，100% 保留）
            Map<String, Object> state = new HashMap<>(current.getState());
            List<Object> messages = new ArrayList<>();
            Object messagesObj = state.get("messages");
            if (messagesObj instanceof List<?> messageList) {
                messages.addAll(messageList);
            }
            // 追加半截 AssistantMessage（正文 = 已推送 DELTA 拼接，与前端已渲染一致）
            messages.add(new AssistantMessage(halfText));
            state.put("messages", messages);
            Checkpoint newCp = Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .state(state)
                    .nodeId(current.getNodeId())
                    .nextNodeId(current.getNextNodeId())
                    .build();
            saver.put(config, newCp);
            log.info(
                    "取消后前滚 checkpoint（messages 追加半截 AssistantMessage）: runId={}, 追加字数={}, newCheckpointId={}",
                    runIdStr,
                    halfText.length(),
                    newCp.getId());
        } catch (Exception e) {
            log.warn("取消后前滚 checkpoint 失败（不阻断取消终态）: runId={}, err={}", runIdStr, e.getMessage());
        }
    }

    /**
     * 更新 Run 状态（P1-5：短重试 3 次 + 递增退避，消除完成时刻 DB 瞬时故障导致
     * run 滞留 ACTIVE 的会话锁死；3 次仍失败上抛由调用方兜底）
     *
     * @param runId  Run ID
     * @param status 新状态（ACTIVE/COMPLETED/CANCELLED/ERROR）
     */
    private void updateStatusWithRetry(Long runId, String status) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                chatRunService.updateStatus(runId, status);
                return;
            } catch (Exception e) {
                log.warn("更新 Run 状态失败（第 {} 次）: runId={}, status={}", attempt, runId, status, e);
                if (attempt < 3) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException interruptedEx) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        throw new IllegalStateException("更新 Run 状态失败: runId=" + runId + ", status=" + status);
    }

    /**
     * 处理异常：ERROR 事件（中文文案）+ 状态 ERROR + 错误信息。
     *
     * <p>N3-1：SSE error 事件的 message 按异常类型映射中文用户文案
     * （{@link #userFacingErrorMessage}），原始英文异常消息仅入日志（下方 error 日志含
     * 异常类名与网关响应体摘要），不透传前端。
     */
    private void handleError(String runIdStr, Long runId, SseEventTransformer.RunState runState, Throwable e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runIdStr);
        payload.put("status", "ERROR");
        payload.put("message", userFacingErrorMessage(e));
        bridge.push(
                runIdStr,
                new SseEvent(SseEventType.ERROR, runState.nextSeq(), toJson(payload), System.currentTimeMillis()));
        updateStatusWithRetry(runId, "ERROR");
        // 响应体摘要（WebClientResponseException 携带 LLM 网关 400/429 详情——如 DashScope 欠费
        // Arrearage，2026-08-30 实证仅有状态码无法定位根因，补打响应体截断）；
        // N3-1：异常类名与原始消息显式入日志（前端只见中文文案，技术细节留服务端排查）
        log.error(
                "Run 执行异常: runId={}, 异常类={}, 原始消息={}, 网关响应体={}",
                runId,
                e.getClass().getName(),
                e.getMessage(),
                responseBodyOf(e),
                e);
    }

    /**
     * 按异常类型映射面向用户的中文错误文案（N3-1：原始英文异常消息不透传前端）
     *
     * <p>映射保持简单（3 类，instanceof + 消息关键词判定）：
     * <ol>
     *   <li>超时类（TimeoutException 及消息含 timeout/timed out）→「模型服务响应超时，请稍后重试」；
     *       判定先于网络断连——SocketTimeoutException 属 IOException 子类，先按超时归类避免误报</li>
     *   <li>网络断连类（IOException 及消息含 connection reset/refused、broken pipe）→「网络连接中断，请重试」</li>
     *   <li>其它 →「服务暂时不可用，请稍后重试」</li>
     * </ol>
     *
     * @param e 图执行抛出的异常（消息可为 null）
     * @return 中文用户文案（恒非空）
     */
    static String userFacingErrorMessage(Throwable e) {
        String message = e.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        // 超时类：类型或消息命中（reactor blockLast 超时为 IllegalStateException("Timeout on ...")，靠消息命中）
        if (e instanceof TimeoutException || lower.contains("timeout") || lower.contains("timed out")) {
            return "模型服务响应超时，请稍后重试";
        }
        // 网络断连类：IO 异常类型或典型断连关键词
        if (e instanceof IOException
                || lower.contains("connection reset")
                || lower.contains("connection refused")
                || lower.contains("broken pipe")) {
            return "网络连接中断，请重试";
        }
        // 兜底：不暴露内部技术细节
        return "服务暂时不可用，请稍后重试";
    }

    /** 提取 LLM 网关异常响应体摘要（WebClientResponseException 携带业务错误详情；非网关异常返回空串） */
    private static String responseBodyOf(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return "";
            }
            return body.length() <= 300 ? body : body.substring(0, 300) + "...";
        }
        return "";
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 补推 SOURCES 事件（B3-5，契约补齐——docs/plans/2026-07-16-frontend-design.md §1.6.4
     * SSE 10 事件之一，此前全链路零发送）
     *
     * <p>时机：RetrieveNode 检索命中非空后把来源列表经 KEY_SOURCES_SINK 容器跨派生副本写回
     * （生产链路 SAA 派生副本下 KEY_RETRIEVAL_SOURCES metadata 写对 worker 原实例不可见，
     * 读取统一走 {@link #readRetrievalSources} 双通道，T7 修复），本方法在来源就绪后的首个
     * chunk 处推送一次（早于该 chunk 的 THINKING/DELTA 事件——首个回答 token 前）；
     * chat/unknown 意图与空检索无来源则不推（sourcesJson 同理保持 "[]"）。
     *
     * <p>payload 结构：契约文档未细化，按最小可用 {@code {"sources":[{chunkId,docTitle,headingPath,score}]}}
     * （前端「引用来源」卡片列表渲染所需字段）。
     *
     * @param runIdStr      Run 唯一标识（ring 键）
     * @param runState      SSE 事件序列状态（seqId 递增）
     * @param config        RunnableConfig（worker 原实例，来源经 {@link #readRetrievalSources} 双通道读取）
     * @param sourcesPushed 本 run 的 SOURCES 已推送标记（CAS 保证仅推一次）
     */
    private void maybePushSources(
            String runIdStr,
            SseEventTransformer.RunState runState,
            RunnableConfig config,
            AtomicBoolean sourcesPushed) {
        if (sourcesPushed.get()) {
            return;
        }
        Object sources = readRetrievalSources(config);
        if (!(sources instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        if (sourcesPushed.compareAndSet(false, true)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sources", list);
            bridge.push(
                    runIdStr,
                    new SseEvent(
                            SseEventType.SOURCES, runState.nextSeq(), toJson(payload), System.currentTimeMillis()));
            log.info("已推送 SOURCES 事件: runId={}, 来源数={}", runIdStr, list.size());
        }
    }

    /**
     * 读取检索来源 JSON（B3-5：chat_message.sources_json 持久化用）
     *
     * <p>经 {@link #readRetrievalSources}（sink 容器优先、metadata 键回退，T7 修复）读
     * RetrieveNode 写回的来源列表并序列化为 JSON 数组；无来源（chat/unknown 意图/空检索）
     * 或序列化失败返回 {@code "[]"}（契约第 2 节：集合字段恒输出 [] 而非 null）。
     *
     * @param config RunnableConfig（worker 原实例，来源经 {@link #readRetrievalSources} 双通道读取）
     * @return 来源 JSON 数组字符串，无来源时为 "[]"
     */
    private String readSourcesJson(RunnableConfig config) {
        Object sources = readRetrievalSources(config);
        if (!(sources instanceof List<?> list) || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            // 序列化失败降级为空数组（来源缺失不影响消息落库主流程）
            log.warn("检索来源 JSON 序列化失败，sourcesJson 降级为空数组: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 统一读取检索来源列表（T7 修复：双通道读取——sink 容器优先、metadata 键回退）。
     *
     * <p>双通道原因：SAA CompiledGraph.stream 交给图节点的 config 是 metadata 派生副本
     * （HasMetadata$Builder 以 new HashMap 浅拷贝），RetrieveNode 写入的 KEY_RETRIEVAL_SOURCES
     * 只落副本、本 worker 原实例经该键恒读空（真机实证 SOURCES 事件与 sources_json 恒空）；
     * 本 worker 在 run 开始注入 KEY_SOURCES_SINK 容器（AtomicReference 引用经浅拷贝穿透派生
     * 副本），节点 sink.set 跨副本写回——生产链路唯一可靠通道。metadata 键回退兜底直传同一
     * config 实例的场景（单测模拟、图内其他潜在消费方）。
     *
     * @param config RunnableConfig（worker 原实例，metadata 含 run 开始自注册的 sink 容器）
     * @return 检索来源列表（元素 RetrievalSource）；无来源/类型不符返回 null——
     *         调用方沿用 {@code instanceof List<?>} 判空语义（与既有单通道读取一致）
     */
    private Object readRetrievalSources(RunnableConfig config) {
        // ① sink 容器优先：RetrieveNode 经 KEY_SOURCES_SINK 跨派生副本写回的生产通道
        // （容器存在且节点已 set 才有值；未写回时 get 为 null，落到下方回退）
        Object sink =
                config.metadata().map(m -> m.get(RetrieveNode.KEY_SOURCES_SINK)).orElse(null);
        if (sink instanceof AtomicReference<?> ref && ref.get() != null) {
            return ref.get();
        }
        // ② metadata 键回退：直传同一 config 实例场景（生产派生副本链路上此键恒空，回退不生效属预期）
        return config.metadata()
                .map(m -> m.get(RetrieveNode.KEY_RETRIEVAL_SOURCES))
                .orElse(null);
    }

    /**
     * 取消检测：如果 cancelFlags 中 runId 对应的标记为 true，抛出 CancelledException。
     */
    private void checkCancelled(String runId) {
        if (isCancelled(runId)) {
            throw new CancelledException(runId);
        }
    }

    /**
     * 取消标志读取（worker 取消语义唯一入口）：cancelFlags 中 runId 标志存在且为 true 即已取消。
     *
     * <p>doOnNext 检查点抛异常前的判定、以及经 {@code KEY_CANCEL_CHECK} 注入 config.metadata
     * 供 RetrieveNode join 前检查与附件编排批循环检查，共用本方法保证三处取消口径一致。
     *
     * @param runId Run 唯一标识（字符串形态，cancelFlags 键）
     * @return true 表示本 run 已被请求取消
     */
    private boolean isCancelled(String runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        return flag != null && flag.get();
    }

    /**
     * 终态分类的取消判定：异常自身或 cause 链上任一元素为 CancelledException 即按取消处理。
     *
     * <p>doOnNext 检查点抛出的 CancelledException 原样直达 onErrorResume；但图节点内（如
     * RetrieveNode join 前检查点）抛出的取消异常可能经图引擎异步链包装（CompletionException 等），
     * 仅 {@code instanceof} 直判会漏分类成 ERROR 终态，故沿 cause 链溯源（限深防自引用死循环）。
     *
     * @param e onErrorResume 收到的异常
     * @return true 表示应按取消分支处理
     */
    private static boolean isCancelledError(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof CancelledException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析 Redis Stream 消息体为 Map。
     */
    private Map<String, String> parseBody(MapRecord<String, Object, Object> message) {
        Map<String, String> body = new HashMap<>();
        Map<Object, Object> raw = message.getValue();
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            body.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return body;
    }

    /**
     * 校验字符串是否为合法 JSON 数组。
     *
     * @param json 待校验字符串（null/空白/非 JSON/JSON 非数组均视为非法）
     * @return true=合法 JSON 数组；false=非法（调用方按空数组处理，附件损坏不阻断对话）
     */
    private boolean isValidJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.isArray();
        } catch (Exception e) {
            // 解析失败视为非法 JSON，交由调用方按空数组兜底
            return false;
        }
    }

    /**
     * 解析附件 JSON 数组字符串为附件记录列表（worker 附件处理段消费，spec §5.1）
     *
     * <p>入参已由归一逻辑保证为合法 JSON 数组（缺省 "[]"），此处仅做反序列化；
     * 解析失败兜底返回空列表（record 反序列化异常不阻断对话）。
     *
     * @param attachmentsJson 附件 JSON 数组字符串（归一后非空）
     * @return 附件记录列表（解析失败返回空列表）
     */
    private List<AttachmentRecord> parseAttachments(String attachmentsJson) {
        try {
            return objectMapper.readValue(attachmentsJson, new TypeReference<List<AttachmentRecord>>() {});
        } catch (Exception e) {
            log.warn("附件 JSON 解析失败，按空处理: error={}", e.getMessage());
            return List.of();
        }
    }

    /** 宽松解析 Long（队列拒绝分支读取 runId 用），解析失败返回 null */
    private Long parseLongQuietly(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ACK 消息（无论成功/失败都 ACK，确保不反复重跑）。
     */
    private void ackMessage(String msgId) {
        try {
            redisTemplate
                    .opsForStream()
                    .acknowledge(streamProperties.requestStream(), streamProperties.consumerGroup(), msgId);
        } catch (Exception e) {
            log.warn("XACK 失败: msgId={}, err={}", msgId, e.getMessage());
        }
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * 从 AssistantMessage 的 metadata 中提取 reasoningContent（DashScope 思考内容）。
     *
     * <p>DashScope ChatModel 将 reasoning 存储在 AssistantMessage.metadata["reasoningContent"]，
     * 值为 String 类型（可能为空字符串）。
     *
     * @param message Spring AI 消息对象
     * @return reasoningContent 字符串，无值时返回 null
     */
    private String extractReasoningContent(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("reasoningContent");
        if (value instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * 构建 TOOL_CALL 消息内容 JSON —— 与实时 TOOL_CALL 事件（SseEventTransformer）schema 一致：
     * {@code {"toolCallId":"...","toolName":"...","input":{...}}}
     *
     * <p>P1-2：原格式 {@code {"tool","args"}} 与实时事件字段名不一致——PG 降级回放时
     * 前端 ToolCallCard 按 toolCallId 配对 tool_call↔tool_result 无法工作；统一后
     * 回放路径可原样透传 content，且历史旧格式由回放侧兼容重建。
     *
     * @param toolCallId 工具调用 ID（模型生成）
     * @param toolName   工具名称
     * @param arguments  工具参数 JSON 字符串（可能为 null/空）
     * @return 符合实时事件 schema 的 JSON 字符串
     */
    private String buildToolCallContent(String toolCallId, String toolName, String arguments) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("toolCallId", toolCallId != null ? toolCallId : "");
        content.put("toolName", toolName != null ? toolName : "");
        // arguments 本身是 JSON 字符串，直接嵌入；若解析失败则作为纯文本
        if (arguments != null && !arguments.isBlank()) {
            try {
                content.put("input", objectMapper.readTree(arguments));
            } catch (JsonProcessingException e) {
                // arguments 非合法 JSON，作为字符串保留
                content.put("input", arguments);
            }
        } else {
            content.put("input", Map.of());
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{\"toolCallId\":\"" + toolCallId + "\",\"toolName\":\"" + toolName + "\",\"input\":{}}";
        }
    }

    /**
     * 追加一批 TOOL_RESULT 落库行（BUG-11：正常路径事件序穿插的唯一落行点）。
     *
     * <p>每条工具结果落一条独立事件行（非模型消息，spec §3.3），content 与实时 TOOL_RESULT
     * 事件 schema 一致（toolCallId/status/output）；seq 在调用方当前位置顺序分配——
     * 调用方保证传入位置与实时事件序一致（实体行之后 / 全部实体行之后兜底）。
     *
     * @param messages 落库行累计列表（原地追加）
     * @param results  本批工具结果（到达序，可为空列表）
     * @param runId    Run 唯一标识
     * @param sessionId 会话 ID
     * @param seq      当前 seq 游标
     * @return 消费后的新 seq 游标（消费 n 条结果则 +n）
     */
    private int appendToolResultRows(
            List<ChatMessage> messages,
            List<ToolResponseMessage.ToolResponse> results,
            Long runId,
            Long sessionId,
            int seq) {
        for (ToolResponseMessage.ToolResponse tr : results) {
            ChatMessage cm = new ChatMessage();
            cm.setSessionId(sessionId);
            cm.setRunId(runId);
            cm.setRole("ASSISTANT");
            cm.setMessageType("TOOL_RESULT");
            cm.setContent(buildToolResultContent(tr.id(), tr.responseData()));
            cm.setSourcesJson("[]");
            cm.setSeq(seq++);
            messages.add(cm);
        }
        return seq;
    }

    /**
     * 构建 TOOL_RESULT 消息内容 JSON —— 与实时 TOOL_RESULT 事件（SseEventTransformer）schema 一致：
     * {@code {"toolCallId":"...","status":"success","output":"..."}}
     *
     * <p>P1-2：与 {@link #buildToolCallContent} 同理，统一落库格式与实时事件。
     * BUG-16（2026-08-31）：output 经 {@link SseEventTransformer#truncateToolOutput} 与实时事件
     * 统一截断到 4000 字符——修复前落库存全量、实时截 4000，前端实时与回放口径分叉；正常/取消
     * 两路径的 TOOL_RESULT 行均经本方法落库，单点收敛截断口径。
     *
     * @param toolCallId   工具调用 ID（模型生成）
     * @param responseData 工具返回数据字符串
     * @return 符合实时事件 schema 的 JSON 字符串（output 已按统一口径截断）
     */
    private String buildToolResultContent(String toolCallId, String responseData) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("toolCallId", toolCallId != null ? toolCallId : "");
        content.put("status", "success");
        content.put("output", SseEventTransformer.truncateToolOutput(responseData));
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{\"toolCallId\":\"" + toolCallId + "\",\"status\":\"success\",\"output\":\"\"}";
        }
    }

    /** 日志文本摘要（超长截断加省略号，dev 定位用，禁止完整响应体入日志） */
    private static String truncateText(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
