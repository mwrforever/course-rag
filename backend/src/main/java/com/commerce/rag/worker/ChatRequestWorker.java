package com.commerce.rag.worker;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.config.StreamProperties;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.service.ChatMessageService;
import com.commerce.rag.service.ChatRunService;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.SseEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ChatRunService chatRunService;
    private final ChatMessageService chatMessageService;
    private final StreamProperties streamProperties;
    private final ThreadPoolExecutor runPool;
    private final ObjectMapper objectMapper;
    private final int staleLeaseMinutes;

    /** per-run 取消标记 */
    private final ConcurrentHashMap<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    /** pre-run 快照 ThreadLocal（同线程内可访问，如 Hook 需要读取） */
    private final ThreadLocal<RunSnapshot> runSnapshot = new ThreadLocal<>();

    /** Consumer 运行标志 */
    private volatile boolean running = false;

    /** Pending 回收调度器 */
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "chat-pending-reclaim");
        t.setDaemon(true);
        return t;
    });

    public ChatRequestWorker(
            StringRedisTemplate redisTemplate,
            CompiledGraph compiledGraph,
            BaseCheckpointSaver saver,
            SseEventTransformer transformer,
            MemoryStreamBridge bridge,
            ChatRunService chatRunService,
            ChatMessageService chatMessageService,
            StreamProperties streamProperties,
            @Qualifier("runPool") ThreadPoolExecutor runPool,
            ObjectMapper objectMapper,
            @Value("${rag.agent.stale-lease-minutes:30}") int staleLeaseMinutes) {
        this.redisTemplate = redisTemplate;
        this.compiledGraph = compiledGraph;
        this.saver = saver;
        this.transformer = transformer;
        this.bridge = bridge;
        this.chatRunService = chatRunService;
        this.chatMessageService = chatMessageService;
        this.streamProperties = streamProperties;
        this.runPool = runPool;
        this.objectMapper = objectMapper;
        this.staleLeaseMinutes = staleLeaseMinutes;
    }

    // ========================================================================
    // 生命周期
    // ========================================================================

    /**
     * 启动后台消费线程 + pending 回收调度。
     */
    @PostConstruct
    public void start() {
        running = true;

        Thread consumer = new Thread(this::consumeLoop, "chat-consumer");
        consumer.setDaemon(true);
        consumer.start();

        scheduler.scheduleWithFixedDelay(this::reclaimPending, 1, 1, TimeUnit.MINUTES);
        log.info(
                "ChatRequestWorker 启动: stream={}, group={}",
                streamProperties.requestStream(),
                streamProperties.consumerGroup());
    }

    /**
     * 优雅关闭：停止消费 → 等待 runPool 完成 → 关闭调度器。
     */
    @PreDestroy
    public void stop() {
        log.info("ChatRequestWorker 关闭中...");
        running = false;
        scheduler.shutdownNow();
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
                    runPool.submit(() -> processRequest(msg));
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
    // Pending 回收
    // ========================================================================

    /**
     * 定时扫描 pending 列表，XCLAIM 超过 staleLeaseMinutes 的消息。
     * 简化实现：使用 XPENDING + XCLAIM 基本操作。
     */
    private void reclaimPending() {
        String streamKey = streamProperties.requestStream();
        String group = streamProperties.consumerGroup();

        try {
            // 使用 raw 命令执行 XPENDING 概要
            // 返回格式: [totalPending, minId, maxId, consumers...]
            org.springframework.data.redis.connection.stream.PendingMessagesSummary summary =
                    redisTemplate.opsForStream().pending(streamKey, group);
            if (summary == null || summary.getTotalPendingMessages() == 0) {
                return;
            }

            log.info("Pending 消息数: {}, 开始回收超时租约", summary.getTotalPendingMessages());

            // 获取详细 pending 列表
            org.springframework.data.redis.connection.stream.PendingMessages pending = redisTemplate
                    .opsForStream()
                    .pending(streamKey, group, org.springframework.data.domain.Range.unbounded(), 100);

            String consumer = "reclaim-" + UUID.randomUUID().toString().substring(0, 8);
            long staleThresholdMs = staleLeaseMinutes * 60_000L;

            for (org.springframework.data.redis.connection.stream.PendingMessage pm : pending) {
                // getElapsedTimeSinceLastDelivery() 已返回空闲时长，直接使用
                long idleTime = pm.getElapsedTimeSinceLastDelivery().toMillis();
                if (idleTime > staleThresholdMs) {
                    try {
                        // XCLAIM 超时消息（minIdleTime = staleLeaseMinutes）
                        List<MapRecord<String, Object, Object>> claimedRecords = redisTemplate
                                .opsForStream()
                                .claim(streamKey, group, consumer, Duration.ofMinutes(staleLeaseMinutes), pm.getId());
                        log.warn("回收 stale pending 消息: id={}, idleMs={}", pm.getId(), idleTime);
                        // 将回收的消息重新提交到 runPool 处理，避免永久丢失
                        if (claimedRecords != null) {
                            for (MapRecord<String, Object, Object> record : claimedRecords) {
                                runPool.submit(() -> processRequest(record));
                            }
                        }
                    } catch (Exception e) {
                        log.warn("XCLAIM 失败: id={}, err={}", pm.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("reclaimPending 异常", e);
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
     *   <li>pre-run 快照: saver.get(config) → deepCopy</li>
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
        try {
            runId = Long.parseLong(runIdStr);
            sessionId = Long.parseLong(sessionIdStr);
            Long.parseLong(userIdStr); // 验证 userId 格式
        } catch (NumberFormatException e) {
            log.error("请求参数解析失败: runId={}, sessionId={}, userId={}", runIdStr, sessionIdStr, userIdStr);
            ackMessage(msgId);
            return;
        }

        // 构建 RunnableConfig（session_id 直接作 thread_id）
        // 设计文档 §2.1: threadId + userId 放 RunnableConfig，不放 State
        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionIdStr)
                .addMetadata("userId", userIdStr)
                .build();

        // 构建 SAA 图输入（messages 列表）
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("messages", List.of(new UserMessage(userQuery)));

        // run 上下文
        SseEventTransformer.RunState runState =
                SseEventTransformer.RunState.create(runIdStr, sessionIdStr, "qwen3.7-max");

        // 流式过程中收集最后 NodeOutput（用于消息持久化）
        AtomicReference<NodeOutput> lastOutput = new AtomicReference<>();
        // 标记是否已发生错误/取消（防止 doOnComplete 覆盖状态）
        AtomicBoolean errored = new AtomicBoolean(false);

        // 1. pre-run 快照（在 try 之前捕获：captureSnapshot 内部异常已兜底返回 null；
        //    单次赋值保持 effectively final，供 onErrorResume/doOnComplete lambda 捕获，
        //    且 catch 分支持久化时可直接读取游标）
        RunSnapshot snapshot = captureSnapshot(runIdStr, config);
        try {
            runSnapshot.set(snapshot);

            // 2. 状态 → ACTIVE
            chatRunService.updateStatus(runId, "ACTIVE");

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
                        if (e instanceof CancelledException) {
                            handleCancelled(runIdStr, runId, runState, config, snapshot);
                        } else {
                            handleError(runIdStr, runId, runState, e);
                        }
                        // 持久化已收集的消息（游标 = pre-run checkpoint 消息数，只落本轮新增）
                        persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get());
                        // F2-8: 缓存最终结果到 Redis（TTL=responseTtl），供断线重连快速恢复
                        cacheFinalResult(runId, lastOutput.get());
                        return Mono.empty();
                    })
                    .doOnComplete(() -> {
                        if (errored.get()) {
                            // 错误/取消已在 onErrorResume 处理，跳过正常完成逻辑
                            return;
                        }
                        handleCompleted(runIdStr, runId, runState);
                        persistMessages(
                                runId,
                                sessionId,
                                userQuery,
                                snapshot != null ? snapshot.historyMessageCount() : 0,
                                lastOutput.get());
                        // F2-8: 缓存最终结果到 Redis（TTL=responseTtl），供断线重连快速恢复
                        cacheFinalResult(runId, lastOutput.get());
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
                    snapshot != null ? snapshot.historyMessageCount() : 0,
                    lastOutput.get());
            cacheFinalResult(runId, lastOutput.get());
        } finally {
            bridge.removeRing(runIdStr);
            cancelFlags.remove(runIdStr);
            runSnapshot.remove();
            ackMessage(msgId);
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
     * 捕获 pre-run 快照（saver.get → deepCopy）。
     *
     * <p>设计文档 §3.4：使用 saver.get(config) 获取 checkpoint，对 state 做深拷贝，
     * 防止图执行过程中修改原 checkpoint 数据导致回滚失效。
     *
     * <p>F2-11 说明：设计文档原文使用 {@code saver.getTuple(config)} 获取 checkpoint tuple，
     * 但 SAA 1.1.2.0 API 中 {@link BaseCheckpointSaver} 无 {@code getTuple} 方法
     * （该 API 属于 LangGraph4j 原生接口，SAA 未暴露）。此处使用 {@code saver.get(config)}
     * 替代，返回 {@code Optional<Checkpoint>}，功能等价。
     * 深拷贝通过 Jackson 序列化/反序列化实现（见 {@link #deepCopyState}），
     * 确保 channelValues 等嵌套对象完全独立，满足回滚隔离要求。
     * 快照失败降级（记 warn 不阻塞 run）。
     *
     * @param runId  Run 唯一标识
     * @param config RunnableConfig（含 threadId + userId）
     * @return 深拷贝快照，失败时返回 null
     */
    private RunSnapshot captureSnapshot(String runId, RunnableConfig config) {
        try {
            Optional<Checkpoint> opt = saver.get(config);
            if (opt.isEmpty()) {
                log.debug("pre-run 快照: 无历史 checkpoint runId={}", runId);
                return null;
            }
            Checkpoint cp = opt.get();
            // 深拷贝 state：通过 Jackson 序列化/反序列化，确保 channelValues 等嵌套对象完全独立
            Map<String, Object> stateCopy = deepCopyState(cp.getState());
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

    /**
     * 对 checkpoint state 做深拷贝（序列化 → 反序列化）。
     *
     * <p>设计文档 §3.4 要求深拷贝 channelValues + channelVersions + pendingWrites，
     * 但 SAA Checkpoint.getState() 返回合并后的 Map，通过 Jackson 序列化确保所有嵌套
     * 对象（如 List<Message>）均产生独立副本，避免图执行修改影响快照。
     *
     * @param state 原始 state Map
     * @return 深拷贝后的 state Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyState(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return new HashMap<>();
        }
        try {
            String json = objectMapper.writeValueAsString(state);
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            // 序列化失败降级为浅拷贝（至少顶层独立）
            log.warn("state 深拷贝序列化失败，降级浅拷贝", e);
            return new HashMap<>(state);
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
     * @param runId         Run 唯一标识
     * @param sessionId     会话 ID
     * @param userQuery     本轮用户问题原文
     * @param historyCursor 持久化游标（pre-run checkpoint 中 messages 数，P0-4a）：
     *                      仅持久化 rawList 中 index >= historyCursor 的新增消息，避免历史消息每轮重插
     * @param lastOutput    流式输出的最后一个 NodeOutput（可为 null，此时仅持久化用户消息）
     */
    @SuppressWarnings("unchecked")
    private void persistMessages(
            Long runId, Long sessionId, String userQuery, int historyCursor, NodeOutput lastOutput) {
        List<ChatMessage> messages = new ArrayList<>();
        int seq = 0;

        // 1. 用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRunId(runId);
        userMsg.setRole("USER");
        userMsg.setContent(userQuery);
        userMsg.setSeq(seq++);
        userMsg.setSourcesJson("[]");
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
     * F2-8: 缓存最终结果到 Redis，供断线重连快速恢复。
     *
     * <p>设计文档 §3.6：run 结束后，将 assistant 最终文本缓存到 Redis
     * （key={@code chat:result:{runId}}，TTL={@code responseTtl} 秒）。
     * 缓存失败不终止 run（记 warn），fallback 查 PG chat_message 表。
     *
     * @param runId      Run ID
     * @param lastOutput 流式输出的最后一个 NodeOutput
     */
    private void cacheFinalResult(Long runId, NodeOutput lastOutput) {
        try {
            if (lastOutput == null || lastOutput.state() == null) {
                return;
            }
            Optional<Object> messagesOpt = lastOutput.state().value("messages");
            if (messagesOpt.isEmpty() || !(messagesOpt.get() instanceof List<?> rawList)) {
                return;
            }

            // 从后往前找最后一条 AssistantMessage 的纯文本内容
            for (int i = rawList.size() - 1; i >= 0; i--) {
                Object item = rawList.get(i);
                if (item instanceof AssistantMessage am) {
                    String text = am.getText();
                    if (text != null && !text.isEmpty()) {
                        redisTemplate
                                .opsForValue()
                                .set("chat:result:" + runId, text, Duration.ofSeconds(streamProperties.responseTtl()));
                        log.debug("Redis 结果缓存成功: runId={}, ttl={}s", runId, streamProperties.responseTtl());
                        return;
                    }
                }
            }
        } catch (Exception e) {
            // 缓存失败不终止，fallback 查 PG chat_message 表（§3.6）
            log.warn("Redis 结果缓存失败 runId={} (不终止，降级查 PG)", runId, e);
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
            // 工具调用 —— 设计文档 §3.5: {"tool":"searchKnowledge","args":{...}}
            if (am.hasToolCalls()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    ChatMessage cm = new ChatMessage();
                    cm.setSessionId(sessionId);
                    cm.setRunId(runId);
                    cm.setRole("ASSISTANT");
                    cm.setMessageType("TOOL_CALL");
                    // 构建设计要求的 JSON 格式: {"tool":"<name>","args":<arguments>}
                    cm.setContent(buildToolCallContent(tc.name(), tc.arguments()));
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
                // 构建设计要求的 JSON 格式: {"tool":"<name>","result":"<responseData>"}
                cm.setContent(buildToolResultContent(tr.name(), tr.responseData()));
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
     */
    private void handleCompleted(String runIdStr, Long runId, SseEventTransformer.RunState runState) {
        String payload = toJson(Map.of("runId", runIdStr, "status", "COMPLETED"));
        bridge.push(runIdStr, new SseEvent(SseEventType.END, runState.nextSeq(), payload, System.currentTimeMillis()));
        chatRunService.updateStatus(runId, "COMPLETED");
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
        chatRunService.updateStatus(runId, "CANCELLED");

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
        chatRunService.updateStatus(runId, "ERROR");
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
     * 构建 TOOL_CALL 消息内容 JSON —— 设计文档 §3.5 要求格式：
     * {@code {"tool":"searchKnowledge","args":{...}}}
     *
     * @param toolName  工具名称
     * @param arguments 工具参数 JSON 字符串（可能为 null/空）
     * @return 符合设计格式的 JSON 字符串
     */
    private String buildToolCallContent(String toolName, String arguments) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tool", toolName != null ? toolName : "");
        // arguments 本身是 JSON 字符串，直接嵌入；若解析失败则作为纯文本
        if (arguments != null && !arguments.isBlank()) {
            try {
                content.put("args", objectMapper.readTree(arguments));
            } catch (JsonProcessingException e) {
                // arguments 非合法 JSON，作为字符串保留
                content.put("args", arguments);
            }
        } else {
            content.put("args", Map.of());
        }
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{\"tool\":\"" + toolName + "\",\"args\":{}}";
        }
    }

    /**
     * 构建 TOOL_RESULT 消息内容 JSON —— 设计文档 §3.5 要求格式：
     * {@code {"tool":"searchKnowledge","result":"..."}}
     *
     * @param toolName     工具名称
     * @param responseData 工具返回数据字符串
     * @return 符合设计格式的 JSON 字符串
     */
    private String buildToolResultContent(String toolName, String responseData) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("tool", toolName != null ? toolName : "");
        content.put("result", responseData != null ? responseData : "");
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            return "{\"tool\":\"" + toolName + "\",\"result\":\"\"}";
        }
    }
}
