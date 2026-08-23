package com.commerce.rag.worker;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.exception.CancelledException;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.properties.WorkerProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.service.AttachmentOrchestrator;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.MemoryExtractionPipeline;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.SseEventType;
import com.commerce.rag.vo.ChatRunVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Chat 请求 Worker —— 核心执行引擎。
 *
 * <p>职责：
 * <ol>
 *   <li>后台守护线程轮询 Redis Stream（XREADGROUP），将消息分发到 runPool 执行</li>
 *   <li>每个 run：pre-run 快照 → 状态 ACTIVE → SAA 图流式执行 → Transformer → Bridge</li>
 *   <li>取消检测：{@code ConcurrentHashMap<runId, AtomicBoolean>} + doOnNext 检查点</li>
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
            @Value("${rag.agent.model:qwen3.8-max}") String agentModel) {
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
        this.agentModel = agentModel;
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
     * <p>巡检将滞留 run 置 ERROR（endedAt 由 updateStatus 自动设置），
     * 失败可见可手动重试。与 P1-5 的完成时刻短重试互补（覆盖执行期崩溃场景）。
     */
    private void sweepStaleRuns() {
        try {
            LocalDateTime startedBefore = LocalDateTime.now().minusMinutes(workerProperties.staleRunTimeoutMinutes());
            LocalDateTime queuedBefore = LocalDateTime.now().minusMinutes(workerProperties.staleQueuedTimeoutMinutes());
            List<ChatRunVO> stale = chatRunService.findStaleActive(startedBefore, queuedBefore);
            for (ChatRunVO run : stale) {
                try {
                    chatRunService.updateStatus(run.id(), "ERROR");
                    log.warn(
                            "巡检发现滞留 run（ACTIVE/QUEUED 超时），置 ERROR 解锁会话: runId={}, sessionId={}, status={}",
                            run.id(),
                            run.sessionId(),
                            run.status());
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
                            // ② 清理 ring（先推事件再清理，投递线程先消费完 ERROR 事件再随 close 退出）
                            try {
                                bridge.removeRing(rejectedRunIdStr);
                            } catch (Exception removeEx) {
                                log.error("runPool 拒绝后清理 ring 失败: runId={}", rejectedRunIdStr, removeEx);
                            }
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
     *   <li>pre-run 快照: saver.get(config) → 容器级浅拷贝</li>
     *   <li>更新 run 状态: QUEUED → ACTIVE</li>
     *   <li>创建 bridge ring + 推送 metadata 事件</li>
     *   <li>执行 SAA 图流: compiledGraph.stream(inputs, config)</li>
     *   <li>onErrorResume: 取消/异常处理</li>
     *   <li>doOnComplete: END 事件 + 批量持久化</li>
     *   <li>finally: bridge.removeRing + cancelFlags.remove + XACK</li>
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

        // ── 附件处理（spec §5.1：消息发送后 worker 内处理，caption/局部语料 Caffeine 缓存）──
        // 先处理当前消息的 attachments；第二轮起用户不再上传附件时，以 chat_run 为入口查该会话
        // 最近 3 个 run 的附件重建上下文（spec §5.1 最终三表决策：Caffeine 命中直接复用
        // caption/语料，未命中重新下载处理）。处理结果经 RunnableConfig.metadata 传 QU/RetrieveNode
        // （瞬时注入，不落 state/checkpoint）
        AttachmentContext attachmentContext = AttachmentContext.empty();
        List<AttachmentRecord> attachments = parseAttachments(attachmentsJson);
        if (attachments.isEmpty()) {
            // 后续轮次：查该会话最近 3 个 run 的附件重建（排除当前 run，url 去重）
            attachments = chatRunService.findRecentAttachments(sessionId, runId, 3);
        }
        if (!attachments.isEmpty()) {
            attachmentContext = orchestrator.process(attachments);
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

        // run 上下文
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create(runIdStr, sessionIdStr, agentModel);

        // 流式过程中收集最后 NodeOutput（用于消息持久化）
        AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
        // 标记是否已发生错误/取消（防止 doOnComplete 覆盖状态）
        AtomicBoolean errored = new AtomicBoolean(false);

        // 1. pre-run 快照（在 try 之前捕获：captureSnapshot 内部异常已兜底返回 null；
        //    单次赋值保持 effectively final，供 onErrorResume/doOnComplete lambda 捕获，
        //    且 catch 分支持久化时可直接读取游标）
        RunSnapshot snapshot = captureSnapshot(runIdStr, config);
        try {
            // 2. 状态 → ACTIVE
            chatRunService.updateStatus(runId, "ACTIVE");

            // 2.1 落库本次输入附件（业务入口表，spec §5.1 双存决策；紧随 ACTIVE 写入，保证入口数据不丢）
            chatRunService.updateAttachments(runId, attachmentsJson);

            // 3. bridge 创建 ring
            bridge.createRing(runIdStr);

            // 4. metadata 事件（首个事件）
            bridge.push(runIdStr, transformer.createMetadataEvent(runState));

            // 5. 执行 SAA 图流
            compiledGraph.stream(inputs, config)
                    .doOnNext(chunk -> {
                        // 取消检测
                        checkCancelled(runIdStr);
                        // 记录最后输出（用于持久化）
                        lastOutput.set(chunk);
                        // transform → bridge.push
                        List<SseEvent> events = transformer.transform(chunk, runState);
                        events.forEach(e -> bridge.push(runIdStr, e));
                    })
                    .onErrorResume(e -> {
                        errored.set(true);
                        // P0-7: 与 catch 分支对齐的兜底——handleCancelled/handleError 内部
                        // （bridge.push / updateStatus）再抛异常不得从 Reactor 链传播，
                        // 否则 blockLast 抛出 → 落入 catch 分支二次 handleError（双终态）+ 重复持久化
                        try {
                            if (e instanceof CancelledException) {
                                handleCancelled(runIdStr, runId, runState, config, snapshot);
                            } else {
                                handleError(runIdStr, runId, runState, e);
                            }
                        } catch (Exception errorEx) {
                            log.error("onErrorResume 分支终态处理失败 runId={}", runId, errorEx);
                        }
                        // 持久化已收集的消息（游标 = pre-run checkpoint 消息数，只落本轮新增）
                        persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                attachmentsJson,
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get());
                        return Mono.empty();
                    })
                    .doOnComplete(() -> {
                        if (errored.get()) {
                            // 错误/取消已在 onErrorResume 处理，跳过正常完成逻辑
                            return;
                        }
                        // 先持久化消息、再推 END + 写 COMPLETED 终态——
                        // 「run 终态 = 数据已完整落库」语义（chat_message 与状态非原子，
                        // 先状态后落库会让外部观察者在终态可见时查到空消息表）
                        persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                attachmentsJson,
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get());
                        handleCompleted(runIdStr, runId, runState);
                        // 偏好提取异步触发（spec §7.6：run 完成、SSE 已发送完后异步，不阻塞用户响应；
                        // 仅 COMPLETED 触发——error/cancel 路径不提取）
                        triggerPreferenceExtraction(userId, lastOutput.get());
                        // 经历记忆提取异步触发（spec §8.4：与偏好同一触发点仅 run COMPLETED 后、
                        // 独立任务独立 prompt、共用防抖队列；error/cancel 路径不触发）
                        triggerEpisodicExtraction(userId, sessionId, lastOutput.get());
                    })
                    .blockLast(Duration.ofMinutes(5));

        } catch (Exception e) {
            log.error("processRequest 致命错误 runId={}", runId, e);
            errored.set(true);
            // P0-4b 修复：补齐终态——推送 ERROR 事件 + 持久化已收集消息（与 onErrorResume 分支对齐）。
            // handleError 单独兜底：其内部状态更新失败不得阻断消息持久化（修复审查 finding）
            try {
                handleError(runIdStr, runId, runState, e);
            } catch (Exception errorEx) {
                log.error("handleError 执行失败 runId={}", runId, errorEx);
            }
            persistMessages(
                    runId,
                    sessionId,
                    userQuery,
                    attachmentsJson,
                    snapshot != null ? snapshot.historyMessageCount() : 0,
                    lastOutput.get());
        } finally {
            bridge.removeRing(runIdStr);
            cancelFlags.remove(runIdStr);
            // BUG-11：任何路径（含取消/异常）都清理 WarningHook per-thread 检测状态——
            // 原实现仅自然结束/软停清理，取消与异常终止的 run 使 DetectionState 常驻至会话结束
            warningHook.cleanupSession(sessionIdStr);
        }
    }

    // ========================================================================
    // 取消
    // ========================================================================

    /**
     * 取消指定 run（由 Controller 调用）。
     * 设置取消标记后，下次 doOnNext 检查时抛出 CancelledException。
     */
    public void cancel(String runId) {
        cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean()).set(true);
        log.info("请求取消 run: runId={}", runId);
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
     * 将 run 过程中的消息持久化到 chat_message 表。
     *
     * <p>消息来源：
     * <ol>
     *   <li>用户查询 → ChatMessage(role=USER)</li>
     *   <li>最终图状态的 messages 列表 → 转换为 ChatMessage 实体</li>
     * </ol>
     *
     * <p>N-F2-5 说明：{@code lastOutput.state()} 是 SAA 流式输出的最后一个 NodeOutput
     * 携带的状态。对于 ReactAgent，最后一个 chunk 通常是 AGENT_MODEL_FINISHED，
     * 其 state 已包含执行完成后的完整 messages 列表（含本轮 AssistantMessage）。
     * 若 lastOutput 为 null（流式异常中断无 chunk 输出），则仅持久化用户消息。
     * 如遇 lastOutput.state() 遗漏 messages 的边界场景，可通过 saver.get(config)
     * 从完整 checkpoint state 提取作为 fallback。
     *
     * @param runId            Run 唯一标识
     * @param sessionId        会话 ID
     * @param userQuery        本轮用户问题原文
     * @param attachmentsJson  本轮输入附件 JSON 数组字符串（已校验合法，落用户消息行 attachments_json，spec §5.1）
     * @param historyCursor    持久化游标（pre-run checkpoint 中 messages 数，P0-4a）：
     *                         仅持久化 rawList 中 index >= historyCursor 的新增消息，避免历史消息每轮重插
     * @param lastOutput       流式输出的最后一个 NodeOutput（可为 null，此时仅持久化用户消息）
     */
    @SuppressWarnings("unchecked")
    private void persistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            int historyCursor,
            NodeOutput lastOutput) {
        List<ChatMessage> messages = new ArrayList<>();
        int seq = 0;

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

        // 2. 从最终状态提取 messages 列表，仅转换本轮新增（index >= 游标）——P0-4a 修复：
        //    state 跨 run 累积（AppendStrategy），游标 = pre-run checkpoint 消息数，
        //    否则历史 assistant/thinking/tool 消息每轮全量重插
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
                        List<ChatMessage> converted = toChatMessages(msg, runId, sessionId);
                        for (ChatMessage cm : converted) {
                            cm.setSeq(seq++);
                            messages.add(cm);
                        }
                    }
                }
            }
        }

        // 3. 批量插入
        if (!messages.isEmpty()) {
            try {
                chatMessageService.batchInsert(messages);
                log.info("持久化消息: runId={}, count={}", runId, messages.size());
            } catch (Exception e) {
                log.error("消息持久化失败 runId={}", runId, e);
            }
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
     */
    private List<ChatMessage> toChatMessages(Message msg, Long runId, Long sessionId) {
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
            String reasoning = extractReasoningContent(am);
            if (reasoning != null && !reasoning.isEmpty()) {
                ChatMessage thinkingMsg = new ChatMessage();
                thinkingMsg.setSessionId(sessionId);
                thinkingMsg.setRunId(runId);
                thinkingMsg.setRole("ASSISTANT");
                thinkingMsg.setMessageType("thinking");
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
                cm.setSourcesJson("[]");
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
     * 处理正常完成：END 事件 + 状态 COMPLETED。
     *
     * <p>P1-5：updateStatus 走短重试（3 次递增退避）——完成时刻 DB 瞬时故障若直接上抛，
     * run 滞留 ACTIVE，uniq_active_run_per_session 使该会话后续 chat() 永久 409 锁死；
     * 瞬时故障恢复后重试可收敛到 COMPLETED。
     */
    private void handleCompleted(String runIdStr, Long runId, SseEventTransformer.RunState runState) {
        String payload = toJson(Map.of("runId", runIdStr, "status", "COMPLETED"));
        bridge.push(runIdStr, new SseEvent(SseEventType.END, runState.nextSeq(), payload, System.currentTimeMillis()));
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
     * 处理取消：CANCELLED END 事件 + 状态 CANCELLED + checkpoint 回滚。
     *
     * <p>设计文档 §3.4 要求：取消后"写新 checkpoint，恢复 pendingWrites，不删旧 checkpoint"。
     * 使用 pre-run 快照（RunSnapshot）恢复到执行前的状态。
     *
     * @param runIdStr Run 唯一标识（字符串）
     * @param runId    Run ID（Long）
     * @param runState SSE 事件序列状态
     * @param config   RunnableConfig（含 threadId，用于 checkpoint 写入）
     * @param snapshot pre-run 快照（可能为 null，快照失败时降级）
     */
    private void handleCancelled(
            String runIdStr,
            Long runId,
            SseEventTransformer.RunState runState,
            RunnableConfig config,
            RunSnapshot snapshot) {
        String payload = toJson(Map.of("runId", runIdStr, "status", "CANCELLED"));
        bridge.push(runIdStr, new SseEvent(SseEventType.END, runState.nextSeq(), payload, System.currentTimeMillis()));
        updateStatusWithRetry(runId, "CANCELLED");

        // 回滚 checkpoint：写新 checkpoint（新 id/ts），恢复快照状态，不删旧 checkpoint
        if (snapshot != null) {
            rollbackCheckpoint(config, snapshot);
        } else {
            log.warn("取消时无 pre-run 快照可用，跳过回滚: runId={}", runIdStr);
        }

        log.info("Run 已取消: runId={}", runId);
    }

    /**
     * 回滚 checkpoint —— 使用 pre-run 快照恢复状态
     *
     * <p>设计文档 §3.4：写新 checkpoint（新 id/ts），恢复 pendingWrites，不删旧 checkpoint。
     *
     * @param config   RunnableConfig（含 threadId）
     * @param snapshot pre-run 快照
     */
    private void rollbackCheckpoint(RunnableConfig config, RunSnapshot snapshot) {
        try {
            Checkpoint newCp = Checkpoint.builder()
                    .id(UUID.randomUUID().toString())
                    .state(snapshot.state())
                    .nodeId(snapshot.nodeId())
                    .nextNodeId(snapshot.nextNodeId())
                    .build();
            saver.put(config, newCp);
            log.info(
                    "取消后回滚 checkpoint: runId={}, oldCheckpointId={}, newCheckpointId={}",
                    snapshot.runId(),
                    snapshot.checkpointId(),
                    newCp.getId());
        } catch (Exception e) {
            log.warn("取消后回滚 checkpoint 失败: runId={}, err={}", snapshot.runId(), e.getMessage());
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
     * 处理异常：ERROR 事件 + 状态 ERROR + 错误信息。
     */
    private void handleError(String runIdStr, Long runId, SseEventTransformer.RunState runState, Throwable e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runIdStr);
        payload.put("status", "ERROR");
        payload.put(
                "message",
                e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        bridge.push(
                runIdStr,
                new SseEvent(SseEventType.ERROR, runState.nextSeq(), toJson(payload), System.currentTimeMillis()));
        updateStatusWithRetry(runId, "ERROR");
        log.error("Run 执行异常: runId={}", runId, e);
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 取消检测：如果 cancelFlags 中 runId 对应的标记为 true，抛出 CancelledException。
     */
    private void checkCancelled(String runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag != null && flag.get()) {
            throw new CancelledException(runId);
        }
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
     * 构建 TOOL_RESULT 消息内容 JSON —— 与实时 TOOL_RESULT 事件（SseEventTransformer）schema 一致：
     * {@code {"toolCallId":"...","status":"success","output":"..."}}
     *
     * <p>P1-2：与 {@link #buildToolCallContent} 同理，统一落库格式与实时事件。
     *
     * @param toolCallId   工具调用 ID（模型生成）
     * @param responseData 工具返回数据字符串
     * @return 符合实时事件 schema 的 JSON 字符串
     */
    private String buildToolResultContent(String toolCallId, String responseData) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("toolCallId", toolCallId != null ? toolCallId : "");
        content.put("status", "success");
        content.put("output", responseData != null ? responseData : "");
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{\"toolCallId\":\"" + toolCallId + "\",\"status\":\"success\",\"output\":\"\"}";
        }
    }
}
