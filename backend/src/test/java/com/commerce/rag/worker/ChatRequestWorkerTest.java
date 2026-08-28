package com.commerce.rag.worker;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.RetrieveNode;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.rewrite.QueryPlanFilters;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.exception.CancelledException;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.properties.WorkerProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;

/**
 * ChatRequestWorker 单元测试 —— 验证取消机制和错误处理逻辑
 *
 * <p>由于 processRequest 是 private 方法，使用反射调用。
 * 重点测试 cancel() 公共方法和 processRequest 的正常/取消/异常流程。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRequestWorker 核心引擎测试")
class ChatRequestWorkerTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private CompiledGraph compiledGraph;

    @Mock
    private BaseCheckpointSaver saver;

    @Mock
    private SseEventTransformer transformer;

    @Mock
    private MemoryStreamBridge bridge;

    @Mock
    private IChatRunService chatRunService;

    @Mock
    private IChatMessageService chatMessageService;

    @Mock
    private ThreadPoolExecutor runPool;

    @Mock
    private WarningHook warningHook;

    @Mock
    private AttachmentOrchestrator orchestrator;

    @Mock
    private MemoryExtractionPipeline memoryExtractionPipeline;

    @Mock
    private WorkerProperties workerProperties;

    private StreamOperations<String, Object, Object> streamOps;
    private ChatRequestWorker worker;
    private StreamProperties streamProperties;

    @BeforeEach
    void setUp() throws Exception {
        streamProperties = new StreamProperties("chat:request", "chat-workers", 10, 2000, 300, 15, 256);

        worker = new ChatRequestWorker(
                redisTemplate,
                compiledGraph,
                saver,
                transformer,
                bridge,
                chatRunService,
                chatMessageService,
                streamProperties,
                workerProperties,
                runPool,
                warningHook,
                orchestrator,
                memoryExtractionPipeline,
                new ObjectMapper(),
                "qwen3.8-max");

        // 公共 stub：saver.get 返回空 Optional（无历史 checkpoint）
        lenient().when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());

        // 公共 stub：transformer.createMetadataEvent 返回一个真实 SseEvent
        SseEvent metadataEvent = new SseEvent(SseEventType.METADATA, 1, "{}", System.currentTimeMillis());
        lenient().when(transformer.createMetadataEvent(any())).thenReturn(metadataEvent);

        // 公共 stub：transformer.createStageEvent 返回一个真实 STAGE 事件（2026-08-27 阶段事件，
        // 默认 null 会让 push 后的事件过滤断言 NPE）
        SseEvent stageEvent =
                new SseEvent(SseEventType.STAGE, 2, "{\"stage\":\"understanding\"}", System.currentTimeMillis());
        lenient().when(transformer.createStageEvent(any(), anyString())).thenReturn(stageEvent);

        // 公共 stub：transformer.transformStages 返回空列表（阶段跃迁逻辑由 SseEventTransformerTest 覆盖）
        lenient()
                .when(transformer.transformStages(any(NodeOutput.class), any()))
                .thenReturn(java.util.List.of());

        // 公共 stub：transformer.transform 返回空列表（默认不产生事件）
        lenient().when(transformer.transform(any(NodeOutput.class), any())).thenReturn(java.util.List.of());

        // 公共 stub：redisTemplate.opsForStream() 链式调用
        streamOps = mock(StreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient()
                .when(streamOps.acknowledge(anyString(), anyString(), anyString()))
                .thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        // 清理后台消费线程（stop 置 running=false）与中断标志，避免用例间相互污染
        worker.stop();
        Thread.interrupted();
    }

    // ==================== 辅助方法 ====================

    /** 创建 mock MapRecord，模拟 Redis Stream 消息 */
    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> createMockRecord(
            String runId, String sessionId, String userId, String query) {
        MapRecord<String, Object, Object> mockRecord = mock(MapRecord.class);
        RecordId mockRecordId = mock(RecordId.class);
        lenient().when(mockRecordId.getValue()).thenReturn("123-0");
        lenient().when(mockRecord.getId()).thenReturn(mockRecordId);

        Map<Object, Object> body = new HashMap<>();
        body.put("runId", runId);
        body.put("sessionId", sessionId);
        body.put("userId", userId);
        body.put("query", valueOrDefault(query, "测试问题"));
        lenient().when(mockRecord.getValue()).thenReturn(body);

        return mockRecord;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value != null ? value : defaultValue;
    }

    /** 创建指定 body 的 mock MapRecord（Redis Stream 消息，用于附件等扩展字段构造） */
    @SuppressWarnings("unchecked")
    private MapRecord<String, Object, Object> createMockRecordWithBody(Map<String, Object> body) {
        MapRecord<String, Object, Object> mockRecord = mock(MapRecord.class);
        RecordId mockRecordId = mock(RecordId.class);
        lenient().when(mockRecordId.getValue()).thenReturn("123-0");
        lenient().when(mockRecord.getId()).thenReturn(mockRecordId);
        // getValue() 返回 Map<Object,Object>，入参（键 String）拷贝调整为 Object 键类型以匹配返回值
        lenient().when(mockRecord.getValue()).thenReturn(new HashMap<Object, Object>(body));
        return mockRecord;
    }

    /** 通过反射调用 private processRequest 方法 */
    private void invokeProcessRequest(MapRecord<String, Object, Object> record) throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod("processRequest", MapRecord.class);
        method.setAccessible(true);
        method.invoke(worker, record);
    }

    /** 通过反射获取 cancelFlags */
    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, AtomicBoolean> getCancelFlags() throws Exception {
        Field f = ChatRequestWorker.class.getDeclaredField("cancelFlags");
        f.setAccessible(true);
        return (ConcurrentHashMap<String, AtomicBoolean>) f.get(worker);
    }

    /** 通过反射调用 private persistMessages（无 thinkingPusher 场景：不落 understanding 阶段思考行）
     * 返回落库结果（R2 补口 B：persisted=已落库/幂等跳过；assistantMessageId=assistant 正文行落库回填 ID）
     * Task 5：默认正常完成路径来源（state 汇总权威、无累加器） */
    private PersistOutcome invokePersistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            String sourcesJson,
            int historyCursor,
            NodeOutput lastOutput)
            throws Exception {
        return invokePersistMessages(
                runId,
                sessionId,
                userQuery,
                attachmentsJson,
                sourcesJson,
                historyCursor,
                lastOutput,
                null,
                null,
                false);
    }

    /** 通过反射调用 private persistMessages（P0-4a 游标去重 + 2026-08-28 时间线 query_plan/thinking_stage 行；
     * Task 5：默认正常完成路径来源） */
    private PersistOutcome invokePersistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            String sourcesJson,
            int historyCursor,
            NodeOutput lastOutput,
            ThinkingPusher thinkingPusher)
            throws Exception {
        return invokePersistMessages(
                runId,
                sessionId,
                userQuery,
                attachmentsJson,
                sourcesJson,
                historyCursor,
                lastOutput,
                thinkingPusher,
                null,
                false);
    }

    /** 通过反射调用 private persistMessages（Task 5 全参：delta 累加器 + 落库来源标志） */
    private PersistOutcome invokePersistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            String sourcesJson,
            int historyCursor,
            NodeOutput lastOutput,
            ThinkingPusher thinkingPusher,
            DeltaAccumulator deltaAccumulator,
            boolean abnormalPath)
            throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod(
                "persistMessages",
                Long.class,
                Long.class,
                String.class,
                String.class,
                String.class,
                int.class,
                NodeOutput.class,
                ThinkingPusher.class,
                DeltaAccumulator.class,
                boolean.class);
        method.setAccessible(true);
        return (PersistOutcome) method.invoke(
                worker,
                runId,
                sessionId,
                userQuery,
                attachmentsJson,
                sourcesJson,
                historyCursor,
                lastOutput,
                thinkingPusher,
                deltaAccumulator,
                abnormalPath);
    }

    // ==================== cancel() 测试 ====================

    @Test
    @DisplayName("cancel 设置取消标记 — cancelFlags 包含 runId=true")
    void cancel_setsFlag() throws Exception {
        worker.cancel("run123");

        ConcurrentHashMap<String, AtomicBoolean> flags = getCancelFlags();
        assertTrue(flags.containsKey("run123"));
        assertTrue(flags.get("run123").get());
    }

    @Test
    @DisplayName("cancel 多次调用同一 runId — 标记仍为 true")
    void cancel_multipleCalls_flagRemainsTrue() throws Exception {
        worker.cancel("run123");
        worker.cancel("run123");

        ConcurrentHashMap<String, AtomicBoolean> flags = getCancelFlags();
        assertTrue(flags.get("run123").get());
    }

    @Test
    @DisplayName("cancel 不同 runId — 各自独立设置")
    void cancel_differentRunIds_independentFlags() throws Exception {
        worker.cancel("run1");
        worker.cancel("run2");

        ConcurrentHashMap<String, AtomicBoolean> flags = getCancelFlags();
        assertTrue(flags.get("run1").get());
        assertTrue(flags.get("run2").get());
    }

    // ==================== processRequest 正常完成流程 ====================

    @Test
    @DisplayName("正常完成流程 — stream 返回 1 个 chunk → updateStatus(COMPLETED)")
    void processRequest_normalCompletion_updatesStatusCompleted() throws Exception {
        // Given
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null); // 无 state，persistMessages 只存用户消息
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 状态先 ACTIVE 后 COMPLETED
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "COMPLETED");
        // bridge.push 至少调用 2 次（metadata + END）
        verify(bridge, atLeast(2)).push(eq("100"), any(SseEvent.class));
        // bridge.createRing 被调用
        verify(bridge).createRing("100");
        // bridge.removeRing 在 finally 中调用
        verify(bridge).removeRing("100");
    }

    @Test
    @DisplayName("正常完成 — run COMPLETED 后触发偏好提取（spec §7.6：消息取最终 state messages）")
    @SuppressWarnings("unchecked")
    void processRequest_completed_triggersPreferenceExtraction() throws Exception {
        // Given: 最终 state 含本轮用户消息 + 助手最终回答
        UserMessage userMsg = new UserMessage("你好");
        AssistantMessage assistantMsg = new AssistantMessage("你好，有什么可以帮你？");
        OverAllState state = new OverAllState(Map.of("messages", List.of(userMsg, assistantMsg)));
        NodeOutput mockChunk = mock(NodeOutput.class);
        when(mockChunk.state()).thenReturn(state);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: COMPLETED 后异步投递偏好提取（userId 硬隔离过滤键，消息 = 最终 state messages）
        verify(chatRunService).updateStatus(100L, "COMPLETED");
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryExtractionPipeline).submit(eq(300L), captor.capture());
        List<Message> submitted = captor.getValue();
        assertEquals(2, submitted.size());
        assertTrue(submitted.get(0) instanceof UserMessage, "消息[0] 应为本轮用户消息");
        assertTrue(submitted.get(1) instanceof AssistantMessage, "消息[1] 应为助手最终回答");
    }

    @Test
    @DisplayName("正常完成 — run COMPLETED 后触发经历记忆提取（spec §8.4：与偏好同触发点、消息取最终 state、sessionId 落记忆来源）")
    @SuppressWarnings("unchecked")
    void processRequest_completed_triggersEpisodicExtraction() throws Exception {
        // Given: 最终 state 含本轮用户消息 + 助手最终回答
        UserMessage userMsg = new UserMessage("你好");
        AssistantMessage assistantMsg = new AssistantMessage("你好，有什么可以帮你？");
        OverAllState state = new OverAllState(Map.of("messages", List.of(userMsg, assistantMsg)));
        NodeOutput mockChunk = mock(NodeOutput.class);
        when(mockChunk.state()).thenReturn(state);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: COMPLETED 后异步投递经历记忆提取
        // （userId=300 硬隔离、sessionId=200 来源会话落库、消息=最终 state messages，spec §8.4）
        verify(chatRunService).updateStatus(100L, "COMPLETED");
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(memoryExtractionPipeline).submitEpisodic(eq(300L), eq(200L), captor.capture());
        List<Message> submitted = captor.getValue();
        assertEquals(2, submitted.size());
        assertTrue(submitted.get(0) instanceof UserMessage, "消息[0] 应为本轮用户消息");
        assertTrue(submitted.get(1) instanceof AssistantMessage, "消息[1] 应为助手最终回答");
    }

    // ==================== processRequest 取消流程 ====================

    @Test
    @DisplayName("取消流程 — cancel 后 stream 触发 CancelledException → updateStatus(CANCELLED)")
    void processRequest_cancelled_updatesStatusCancelled() throws Exception {
        // Given: 先设置取消标记
        worker.cancel("100");

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 状态更新为 CANCELLED
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "CANCELLED");
        // bridge.push 包含 CANCELLED END 事件
        verify(bridge, atLeast(1)).push(eq("100"), any(SseEvent.class));
        // spec §7.6/§8.4：非 COMPLETED 终态（cancel）不触发偏好/经历提取
        verify(memoryExtractionPipeline, never()).submit(any(), any());
        verify(memoryExtractionPipeline, never()).submitEpisodic(any(), any(), any());
    }

    // ==================== processRequest 异常流程 ====================

    @Test
    @DisplayName("异常流程 — stream 返回 error → updateStatus(ERROR)")
    void processRequest_error_updatesStatusError() throws Exception {
        // Given
        when(compiledGraph.stream(any(), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new RuntimeException("模型超时")));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 状态更新为 ERROR
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "ERROR");
        // bridge.push 包含 ERROR 事件
        verify(bridge, atLeast(1)).push(eq("100"), any(SseEvent.class));
        // spec §7.6/§8.4：非 COMPLETED 终态（error）不触发偏好/经历提取
        verify(memoryExtractionPipeline, never()).submit(any(), any());
        verify(memoryExtractionPipeline, never()).submitEpisodic(any(), any(), any());
    }

    // ==================== onErrorResume 取消分类 cause 链溯源（评审 I-1 补测） ====================

    @Test
    @DisplayName("取消分类 — CancelledException 被 CompletionException 包装仍收敛 CANCELLED 终态（isCancelledError cause 链）")
    void processRequest_cancelledWrappedInCause_updatesStatusCancelled() throws Exception {
        // Given: 图节点内检查点抛出的取消异常经图引擎异步链包装（CompletionException 壳，
        // 直判 instanceof 会漏分类成 ERROR——本用例锁死 cause 链溯源行为）
        when(compiledGraph.stream(any(), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new CompletionException(new CancelledException("100"))));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: cause 链命中取消 → updateStatus 走取消路径（不得误落 ERROR），END 事件携带 status:CANCELLED
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "CANCELLED");
        verify(chatRunService, never()).updateStatus(100L, "ERROR");
        ArgumentCaptor<SseEvent> pushed = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeastOnce()).push(eq("100"), pushed.capture());
        SseEvent endEvent = pushed.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.END)
                .findFirst()
                .orElseThrow(() -> new AssertionError("取消终态必须推送 END 事件"));
        assertTrue(endEvent.payload().contains("CANCELLED"), "END payload 应携带 status:CANCELLED");
        // spec §7.6/§8.4：取消终态不触发偏好/经历提取
        verify(memoryExtractionPipeline, never()).submit(any(), any());
        verify(memoryExtractionPipeline, never()).submitEpisodic(any(), any(), any());
    }

    @Test
    @DisplayName("取消分类反向 — cause 链不含 CancelledException 的包装异常仍收敛 ERROR 终态（不误判取消）")
    void processRequest_errorWithNonCancelCauseChain_updatesStatusError() throws Exception {
        // Given: 两层包装的非取消异常（cause 链上无任何 CancelledException）
        when(compiledGraph.stream(any(), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new RuntimeException("图引擎执行失败", new IllegalStateException("检索 IO 异常"))));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: cause 链溯源不命中 → 仍走 ERROR 分支（推送 ERROR 事件，不得收敛 CANCELLED）
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "ERROR");
        verify(chatRunService, never()).updateStatus(100L, "CANCELLED");
        ArgumentCaptor<SseEvent> pushed = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeastOnce()).push(eq("100"), pushed.capture());
        assertTrue(
                pushed.getAllValues().stream()
                        .anyMatch(e ->
                                e.type() == SseEventType.ERROR && e.payload().contains("ERROR")),
                "非取消异常应推送 ERROR 终态事件");
    }

    // ==================== processRequest 参数解析失败 ====================

    @Test
    @DisplayName("参数解析失败 — runId 非数字 → 直接 ACK，不执行 run")
    void processRequest_invalidRunId_acksWithoutExecution() throws Exception {
        // Given: runId 为非数字
        MapRecord<String, Object, Object> record = createMockRecord("abc", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 不调用 createRun/updateStatus，不调用 bridge.createRing
        verify(chatRunService, never()).updateStatus(anyLong(), anyString());
        verify(bridge, never()).createRing(anyString());
    }

    // ==================== processRequest finally 清理 ====================

    @Test
    @DisplayName("finally 清理 — 正常完成后 removeRing 被调用，ACK 不在 processRequest（P3-2 读即 ACK 在 consumeLoop）")
    void processRequest_finallyCleanup() throws Exception {
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        invokeProcessRequest(record);

        // finally 块：removeRing 被调用
        verify(bridge).removeRing("100");
        // P3-2（用户 2026-08-15 裁决）：ACK 已移至 consumeLoop 读即 ACK，processRequest 不再触发 ACK
        verify(redisTemplate.opsForStream(), never()).acknowledge(anyString(), anyString(), anyString());
    }

    // ==================== persistMessages 游标去重（P0-4a） ====================

    @Test
    @DisplayName("persistMessages → 游标跳过历史消息，仅持久化本轮新增（P0-4a）")
    void persistMessages_withHistoryCursor_skipsHistory() throws Exception {
        // Given: rawList 含 2 条历史（index 0/1）+ 1 条本轮新增（index 2）
        UserMessage historyUser = new UserMessage("历史问题");
        AssistantMessage historyAssistant = new AssistantMessage("历史回答");
        AssistantMessage newAssistant = new AssistantMessage("本轮回答");

        NodeOutput lastOutput = mock(NodeOutput.class);
        // SAA OverAllState.data() 返回不可变视图，改用 Map 构造器构建 state（构造器内部拷贝为可变 map）
        OverAllState state = new OverAllState(Map.of("messages", List.of(historyUser, historyAssistant, newAssistant)));
        when(lastOutput.state()).thenReturn(state);

        // When: 游标=2（历史 2 条）
        invokePersistMessages(1L, 1L, "本轮问题", "[]", "[]", 2, lastOutput);

        // Then: 仅持久化本轮（USER 用户消息 + 本轮新增 assistant）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> inserted = captor.getValue();
        assertEquals(2, inserted.size()); // USER + 本轮 assistant
        assertEquals("USER", inserted.get(0).getRole());
        assertEquals("本轮回答", inserted.get(1).getContent());
    }

    // ==================== persistMessages 时间线行落库（2026-08-28 query_plan + thinking_stage） ====================

    @Test
    @DisplayName("persistMessages → query_plan 行 + understanding/generating thinking 行按 seq 排序且落 thinking_stage")
    @SuppressWarnings("unchecked")
    void persistMessages_timelineRows_queryPlanBeforeThinkingAndStagePersisted() throws Exception {
        // Given: 最终 state 含 QueryPlan（QU 签出）+ 主 agent 终消息（reasoningContent 思考 + 正文）
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("高等数学 大纲"), new QueryPlanFilters(List.of("高等数学")), false);
        AssistantMessage finalAssistant = AssistantMessage.builder()
                .content("这是回答")
                .properties(Map.of("reasoningContent", "主agent思考"))
                .build();
        OverAllState state = new OverAllState(
                Map.of("messages", List.of(new UserMessage("高等数学怎么学"), finalAssistant), KEY_QUERY_PLAN, plan));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        // understanding 阶段思考不在 state.messages，来自 ThinkingPusher 累加缓冲（真实实例 + mock bridge）
        ThinkingPusher pusher =
                new ThinkingPusher("1", bridge, SseEventTransformer.RunState.create("1", "1", "m"), new ObjectMapper());
        pusher.push(SseEventTransformer.STAGE_UNDERSTANDING, "先分析意图，");
        pusher.push(SseEventTransformer.STAGE_UNDERSTANDING, "再收窄到课程查询");

        // When
        invokePersistMessages(1L, 2L, "高等数学怎么学", "[]", "[]", 0, lastOutput, pusher);

        // Then: 行序 user(0) → query_plan(1) → understanding thinking(2) → generating thinking(3) → 正文(4)
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> rows = captor.getValue();
        assertEquals(5, rows.size());
        assertEquals("USER", rows.get(0).getRole());
        assertEquals(0, rows.get(0).getSeq().intValue());

        ChatMessage planRow = rows.get(1);
        assertEquals("query_plan", planRow.getMessageType());
        assertEquals(1, planRow.getSeq().intValue());
        // content 与 SSE query_plan 事件 payload 同款 JSON（单一构造点契约一致）
        assertEquals(
                "{\"intent\":\"knowledge_question\",\"rewritten\":[\"高等数学 大纲\"],"
                        + "\"filters\":{\"courseNames\":[\"高等数学\"]}}",
                planRow.getContent());

        ChatMessage understandingRow = rows.get(2);
        assertEquals("thinking", understandingRow.getMessageType());
        assertEquals("understanding", understandingRow.getThinkingStage());
        assertEquals("先分析意图，再收窄到课程查询", understandingRow.getContent());

        ChatMessage generatingRow = rows.get(3);
        assertEquals("thinking", generatingRow.getMessageType());
        assertEquals("generating", generatingRow.getThinkingStage());
        assertEquals("主agent思考", generatingRow.getContent());

        ChatMessage bodyRow = rows.get(4);
        assertEquals("这是回答", bodyRow.getContent());
        assertNull(bodyRow.getMessageType());
        // R2 意图标注仅正文行（query_plan/thinking 行不标）
        assertEquals("knowledge_question", bodyRow.getIntentType());
        assertNull(planRow.getIntentType());
        assertNull(understandingRow.getIntentType());
    }

    @Test
    @DisplayName("persistMessages → 无 QueryPlan（异常中断）不落 query_plan 行；pusher 无思考不落成对 thinking 行")
    @SuppressWarnings("unchecked")
    void persistMessages_noPlanNoThinking_fallbackToLegacyRows() throws Exception {
        AssistantMessage body = new AssistantMessage("回答");
        OverAllState state = new OverAllState(Map.of("messages", List.of(body)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        invokePersistMessages(
                1L,
                2L,
                "问题",
                "[]",
                "[]",
                0,
                lastOutput,
                new ThinkingPusher(
                        "1", bridge, SseEventTransformer.RunState.create("1", "1", "m"), new ObjectMapper()));

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> rows = captor.getValue();
        assertEquals(2, rows.size(), "仅 user + 正文两行，无 query_plan / 阶段 thinking 行");
        assertTrue(rows.stream().noneMatch(r -> "query_plan".equals(r.getMessageType())));
    }

    // ==================== Task 5：取消/错误路径 delta 累加器落库（不变量） ====================

    /** 构造 DELTA 事件（payload {text}；seq/timestamp 与累加器消费无关，占位合法值即可） */
    private SseEvent deltaEvent(String text) throws Exception {
        return new SseEvent(
                SseEventType.DELTA,
                1,
                new ObjectMapper().writeValueAsString(Map.of("text", text)),
                System.currentTimeMillis());
    }

    /** 构造 THINKING 事件（payload {delta, stage}） */
    private SseEvent thinkingEvent(String delta, String stage) throws Exception {
        return new SseEvent(
                SseEventType.THINKING,
                1,
                new ObjectMapper().writeValueAsString(Map.of("delta", delta, "stage", stage)),
                System.currentTimeMillis());
    }

    /** 从 SSE payload JSON 提取指定字符串字段（不变量断言用：与已推送事件逐字对齐） */
    private String payloadField(String payload, String field) throws Exception {
        return new ObjectMapper().readTree(payload).path(field).asText(null);
    }

    @Test
    @DisplayName("不变量（Task 5）— 错误中断 state 无终消息：终态落库内容 ≡ 已推送事件序列")
    @SuppressWarnings("unchecked")
    void processRequest_errorPath_persistedContentEqualsPushedEvents_invariant() throws Exception {
        // Given: 三个 chunk 依次产出 generating THINKING + 两条 DELTA，随后流中断
        // （chunk.state()=null → lastOutput 无终消息，state 汇总路径无正文可落）
        NodeOutput c1 = mock(NodeOutput.class);
        NodeOutput c2 = mock(NodeOutput.class);
        NodeOutput c3 = mock(NodeOutput.class);
        lenient().when(c1.state()).thenReturn(null);
        lenient().when(c2.state()).thenReturn(null);
        lenient().when(c3.state()).thenReturn(null);
        when(transformer.transform(any(NodeOutput.class), any()))
                .thenReturn(List.of(thinkingEvent("先想一步，", "generating")))
                .thenReturn(List.of(deltaEvent("你")))
                .thenReturn(List.of(deltaEvent("好，世界")));
        when(compiledGraph.stream(any(), any(RunnableConfig.class)))
                .thenReturn(Flux.just(c1, c2, c3).concatWith(Flux.error(new RuntimeException("生成中断"))));

        // When
        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // Then: 错误终态正常收敛 + 落库恰好一次（persisted 防双写不回归）
        verify(chatRunService).updateStatus(100L, "ERROR");
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService, times(1)).batchInsert(msgCaptor.capture());
        List<ChatMessage> rows = msgCaptor.getValue();
        ChatMessage bodyRow = rows.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("错误路径应存在累加器正文行"));
        ChatMessage thinkingRow = rows.stream()
                .filter(m -> "thinking".equals(m.getMessageType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("错误路径应存在 generating 思考行"));

        // 不变量断言：落库内容与 bridge.push 实际推送的事件序列逐字对齐（而非仅对齐 stub 数据）
        ArgumentCaptor<SseEvent> evtCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeastOnce()).push(eq("100"), evtCaptor.capture());
        StringBuilder pushedText = new StringBuilder();
        StringBuilder pushedThinking = new StringBuilder();
        evtCaptor.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.DELTA || e.type() == SseEventType.THINKING)
                .forEach(e -> {
                    try {
                        if (e.type() == SseEventType.DELTA) {
                            pushedText.append(payloadField(e.payload(), "text"));
                        } else {
                            pushedThinking.append(payloadField(e.payload(), "delta"));
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
        assertEquals(pushedText.toString(), bodyRow.getContent(), "落库正文必须等于已推送 DELTA 拼接（不变量）");
        assertEquals(pushedThinking.toString(), thinkingRow.getContent(), "落库思考必须等于已推送 THINKING 拼接（不变量）");
        assertEquals("generating", thinkingRow.getThinkingStage(), "transformer 产思考恒为 generating 阶段");
    }

    @Test
    @DisplayName("不变量（Task 5）— 取消中断（流中途置取消标记）：落库正文 = 已推送 delta 前缀")
    @SuppressWarnings("unchecked")
    void processRequest_cancelPath_persistedBodyEqualsPushedDeltaPrefix() throws Exception {
        // Given: chunk1 产出一条 DELTA 后标记取消，chunk2 的 doOnNext 检查点抛 CancelledException
        NodeOutput c1 = mock(NodeOutput.class);
        NodeOutput c2 = mock(NodeOutput.class);
        lenient().when(c1.state()).thenReturn(null);
        lenient().when(c2.state()).thenReturn(null);
        when(transformer.transform(any(NodeOutput.class), any()))
                .thenReturn(List.of(deltaEvent("生成到一半的")))
                .thenReturn(List.of(deltaEvent("被取消的部分")));
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.create(sink -> {
            sink.next(c1);
            // chunk1 已推送后置取消标记：chunk2 检查点即抛取消异常（中断点前事件已到前端）
            worker.cancel("100");
            sink.next(c2);
            sink.complete();
        }));

        // When
        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // Then: 取消终态 + 落库一次，正文 = 已推送部分（state 无终消息，累加器为唯一事实源）
        verify(chatRunService).updateStatus(100L, "CANCELLED");
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService, times(1)).batchInsert(msgCaptor.capture());
        ChatMessage bodyRow = msgCaptor.getValue().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("取消路径应存在累加器正文行"));
        assertEquals("生成到一半的", bodyRow.getContent(), "取消路径落库正文必须等于已推送 delta 前缀");
    }

    @Test
    @DisplayName("Task 5 取消/错误路径 — 累加器优先且抑制 state 同义行（正文/generating 思考不双行）")
    @SuppressWarnings("unchecked")
    void persistMessages_abnormalWithAccumulator_suppressesStateDuplicates() throws Exception {
        // Given: state 同时带有终消息（generating 思考 + 正文）且累加器非空——不抑制会出现双行
        AssistantMessage finalAssistant = AssistantMessage.builder()
                .content("state 汇总正文")
                .properties(Map.of("reasoningContent", "state 汇总思考"))
                .build();
        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("问题"), finalAssistant)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        DeltaAccumulator acc = new DeltaAccumulator(new ObjectMapper());
        acc.accumulate(deltaEvent("已推送正文"));
        acc.accumulate(thinkingEvent("已推送思考", "generating"));

        // When: 取消/错误路径（abnormalPath=true）
        invokePersistMessages(1L, 2L, "问题", "[]", "[]", 0, lastOutput, null, acc, true);

        // Then: 正文/思考各仅一行且取累加器内容（与前端已渲染一致）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> rows = captor.getValue();
        List<ChatMessage> bodyRows = rows.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .toList();
        assertEquals(1, bodyRows.size(), "正文行不得双行（state 同义行被抑制）");
        assertEquals("已推送正文", bodyRows.get(0).getContent());
        List<ChatMessage> thinkingRows =
                rows.stream().filter(m -> "thinking".equals(m.getMessageType())).toList();
        assertEquals(1, thinkingRows.size(), "generating 思考行不得双行（state 同义行被抑制）");
        assertEquals("已推送思考", thinkingRows.get(0).getContent());
        assertEquals("generating", thinkingRows.get(0).getThinkingStage());
    }

    @Test
    @DisplayName("Task 5 取消/错误路径 — 累加器为空回退 state 汇总（中断前无 delta 的窗口）")
    @SuppressWarnings("unchecked")
    void persistMessages_abnormalEmptyAccumulator_fallsBackToState() throws Exception {
        // Given: 累加器为空（中断发生在首个 DELTA 之前），state 带终消息
        AssistantMessage finalAssistant = new AssistantMessage("state 汇总正文");
        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("问题"), finalAssistant)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        invokePersistMessages(
                1L, 2L, "问题", "[]", "[]", 0, lastOutput, null, new DeltaAccumulator(new ObjectMapper()), true);

        // Then: 回退 state 汇总正文（累加器空不得导致正文丢失）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        ChatMessage bodyRow = captor.getValue().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("回退 state 时应存在正文行"));
        assertEquals("state 汇总正文", bodyRow.getContent());
    }

    @Test
    @DisplayName("Task 5 正常完成路径 — state 汇总为权威，累加器不参与（abnormalPath=false）")
    @SuppressWarnings("unchecked")
    void persistMessages_normalPath_stateAuthoritativeAccumulatorIgnored() throws Exception {
        // Given: state 带终消息，累加器也非空（正常完成时两者等价，state 为权威）
        AssistantMessage finalAssistant = new AssistantMessage("state 汇总正文");
        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("问题"), finalAssistant)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);
        DeltaAccumulator acc = new DeltaAccumulator(new ObjectMapper());
        acc.accumulate(deltaEvent("已推送正文"));

        invokePersistMessages(1L, 2L, "问题", "[]", "[]", 0, lastOutput, null, acc, false);

        // Then: 正文取 state 汇总且单行（累加器不产生第二行）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> bodyRows = captor.getValue().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .toList();
        assertEquals(1, bodyRows.size());
        assertEquals("state 汇总正文", bodyRows.get(0).getContent());
    }

    @Test
    @DisplayName("Task 5 取消/错误路径 — (run_id,seq) 唯一索引冲突幂等路径不回归")
    void persistMessages_abnormalPath_uniqueIndexConflict_treatedAsPersisted() throws Exception {
        // Given: 累加器非空的取消路径重试落库，本批消息撞唯一索引（重复落库幂等兜底不回归）
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(null);
        doThrow(new DataIntegrityViolationException("重复键违反唯一约束 uniq_chat_message_run_seq"))
                .when(chatMessageService)
                .batchInsert(anyList());
        DeltaAccumulator acc = new DeltaAccumulator(new ObjectMapper());
        acc.accumulate(deltaEvent("已推送正文"));

        // When
        PersistOutcome outcome = invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput, null, acc, true);

        // Then: 幂等跳过按已落库处理（调用方不得重试）
        assertTrue(outcome.persisted(), "取消路径唯一索引冲突同样按已落库幂等处理");
        assertNull(outcome.assistantMessageId());
    }

    // ==================== catch 分支 handleError 失败兜底（P0-4b 复合故障） ====================

    @Test
    @DisplayName("catch 分支 handleError 失败 → 仍持久化用户消息（P0-4b 复合故障兜底）")
    void processRequest_handleErrorFails_stillPersists() throws Exception {
        // Given: 图流启动即抛异常（进入 catch 分支），且 updateStatus(ERROR) 抛异常模拟 handleError 内部失败
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenThrow(new RuntimeException("图流启动失败"));
        doThrow(new RuntimeException("数据库不可用")).when(chatRunService).updateStatus(anyLong(), eq("ERROR"));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: handleError 失败不阻断持久化——用户消息仍批量落库
        verify(chatMessageService).batchInsert(anyList());
    }

    // ==================== P1-3 取消回滚 checkpoint 类型保留 ====================

    @Test
    @DisplayName("取消回滚 — 快照 messages 元素保留 Message 类型（P1-3 容器级浅拷贝）")
    void rollbackCheckpoint_preservesMessageTypes() throws Exception {
        // Given: saver.get 返回含真实 Spring AI Message 的 checkpoint（第二轮对话场景）
        Checkpoint cp = Checkpoint.builder()
                .id("cp-1")
                .state(Map.of("messages", List.of(new UserMessage("历史问题"), new AssistantMessage("历史回答"))))
                .nodeId("node-1")
                .nextNodeId("node-2")
                .build();
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(cp));

        // 取消路径：执行前设置取消标记，首个 chunk 触发 CancelledException
        worker.cancel("100");
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 回滚写入的 checkpoint state 中 messages 元素仍是 Message 子类（非 LinkedHashMap）
        ArgumentCaptor<Checkpoint> cpCaptor = ArgumentCaptor.forClass(Checkpoint.class);
        verify(saver).put(any(RunnableConfig.class), cpCaptor.capture());
        Checkpoint newCp = cpCaptor.getValue();
        List<?> messages = (List<?>) newCp.getState().get("messages");
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage, "回滚后 messages[0] 应为 UserMessage");
        assertTrue(messages.get(1) instanceof AssistantMessage, "回滚后 messages[1] 应为 AssistantMessage");
    }

    // ==================== B3-5：SOURCES 事件 + sourcesJson 真实来源 ====================

    @Test
    @DisplayName("SOURCES（B3-5）— 检索来源就绪后推一次 SOURCES 事件，assistant 正文 sourcesJson 落真实来源")
    @SuppressWarnings("unchecked")
    void processRequest_retrievalSources_pushesSourcesEventAndPersistsSourcesJson() throws Exception {
        // 图流时序模拟：chunk1（检索完成前，无来源）→ 模拟 RetrieveNode 写入来源 metadata → chunk2（最终 state）
        NodeOutput before = mock(NodeOutput.class);
        lenient().when(before.state()).thenReturn(null);
        AssistantMessage assistantMsg = new AssistantMessage("引用资料的回答");
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(assistantMsg)));
        NodeOutput after = mock(NodeOutput.class);
        lenient().when(after.state()).thenReturn(finalState);

        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenAnswer(inv -> {
            RunnableConfig cfg = inv.getArgument(1);
            return Flux.create(sink -> {
                sink.next(before);
                // 模拟 RetrieveNode 检索完成：来源列表写入 metadata（B3-5 通道）
                cfg.metadata()
                        .ifPresent(m -> m.put(
                                RetrieveNode.KEY_RETRIEVAL_SOURCES,
                                List.of(new RetrievalSource("c1", "高等数学讲义", "第一章", 0.9, "片段正文预览"))));
                sink.next(after);
                sink.complete();
            });
        });

        invokeProcessRequest(createMockRecord("100", "200", "300", "高等数学怎么学"));

        // SOURCES 事件恰好推送一次（检索完成处、首个回答 token 前），payload 含来源字段
        ArgumentCaptor<SseEvent> evtCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeast(1)).push(eq("100"), evtCaptor.capture());
        List<SseEvent> sourcesEvents = evtCaptor.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.SOURCES)
                .toList();
        assertEquals(1, sourcesEvents.size(), "SOURCES 事件应恰好推送一次");
        assertTrue(sourcesEvents.get(0).payload().contains("高等数学讲义"), "payload 应含来源文档标题");
        assertTrue(sourcesEvents.get(0).payload().contains("c1"), "payload 应含 chunkId");

        // assistant 正文行 sourcesJson 持久化真实来源（非 "[]"）
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(msgCaptor.capture());
        List<ChatMessage> inserted = msgCaptor.getValue();
        ChatMessage assistantRow = inserted.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 assistant 正文行"));
        assertTrue(assistantRow.getSourcesJson().contains("高等数学讲义"), "assistant 正文 sourcesJson 应为真实来源");
    }

    @Test
    @DisplayName("SOURCES（B3-5）— 无检索来源（chat/unknown 意图）：不推 SOURCES、sourcesJson 保持 \"[]\"")
    @SuppressWarnings("unchecked")
    void processRequest_noRetrievalSources_neverPushesSourcesEvent() throws Exception {
        // chat 意图：图流不写来源 metadata（RetrieveNode 不检索）
        NodeOutput chunk = mock(NodeOutput.class);
        AssistantMessage assistantMsg = new AssistantMessage("直接对话回答");
        OverAllState finalState = new OverAllState(Map.of("messages", List.of(assistantMsg)));
        when(chunk.state()).thenReturn(finalState);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(chunk));

        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // 无来源 → 不推 SOURCES 事件
        ArgumentCaptor<SseEvent> evtCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeast(1)).push(eq("100"), evtCaptor.capture());
        assertTrue(
                evtCaptor.getAllValues().stream().noneMatch(e -> e.type() == SseEventType.SOURCES),
                "无检索来源不得推送 SOURCES 事件");

        // sourcesJson 保持 "[]"（契约第 2 节：集合字段恒输出 []）
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(msgCaptor.capture());
        ChatMessage assistantRow = msgCaptor.getValue().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 assistant 正文行"));
        assertEquals("[]", assistantRow.getSourcesJson());
    }

    // ==================== 思考事件推送通道注册（2026-08-28 对话流式时间线改版） ====================

    @Test
    @DisplayName("run 开始 → config.metadata 注册 ThinkingPusher 回调（KEY_THINKING_CALLBACK，图节点消费通道）")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void processRequest_registersThinkingPusher() throws Exception {
        // Given: 正常完成的图流，捕获传入 compiledGraph.stream 的 RunnableConfig
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        when(compiledGraph.stream(any(Map.class), configCaptor.capture())).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // Then: metadata 携带本 run 的 ThinkingPusher 实例（QU/caption 节点据此实时推思考片段；
        // 瞬时引用通道与 KEY_RETRIEVAL_SOURCES 同机制，不进 State/checkpoint、对模型不可见）
        Object callback = configCaptor
                .getValue()
                .metadata(RetrieveNode.KEY_THINKING_CALLBACK)
                .orElse(null);
        assertInstanceOf(ThinkingPusher.class, callback, "config.metadata 应注册 ThinkingPusher 回调实例");
    }

    // ==================== P1-2 工具消息落库格式（与实时事件 schema 一致） ====================

    @Test
    @DisplayName("P1-2 TOOL_CALL/TOOL_RESULT 落库格式 = 实时事件 schema（toolCallId/toolName/input + toolCallId/status/output）")
    void persistMessages_toolMessages_useLiveEventSchema() throws Exception {
        // Given: 图状态含一条带 toolCall 的 AssistantMessage + 一条 ToolResponseMessage
        // （构造器为 protected，用 mock 构造；ToolCall/ToolResponse record 可直 new）
        AssistantMessage toolAssistant = mock(AssistantMessage.class);
        when(toolAssistant.getText()).thenReturn("");
        when(toolAssistant.hasToolCalls()).thenReturn(true);
        when(toolAssistant.getToolCalls())
                .thenReturn(List.of(new AssistantMessage.ToolCall(
                        "call-123", "function", "searchKnowledge", "{\"query\":\"Java 课程\"}")));
        ToolResponseMessage toolResponse = mock(ToolResponseMessage.class);
        when(toolResponse.getResponses())
                .thenReturn(List.of(
                        new ToolResponseMessage.ToolResponse("call-123", "searchKnowledge", "{\"chunks\":[]}")));
        OverAllState state = new OverAllState(Map.of("messages", List.of(toolAssistant, toolResponse)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        // When: 游标=0（全部新增）
        invokePersistMessages(1L, 1L, "课程问题", "[]", "[]", 0, lastOutput);

        // Then: TOOL_CALL 落库 content 为实时 schema（toolCallId/toolName/input）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> inserted = captor.getValue();

        ChatMessage toolCallMsg = inserted.stream()
                .filter(m -> "TOOL_CALL".equals(m.getMessageType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应落库 TOOL_CALL 消息"));
        assertTrue(toolCallMsg.getContent().contains("\"toolCallId\":\"call-123\""), "应含 toolCallId");
        assertTrue(toolCallMsg.getContent().contains("\"toolName\":\"searchKnowledge\""), "应含 toolName");
        assertFalse(toolCallMsg.getContent().contains("\"tool\":\"searchKnowledge\""), "不得再使用旧 tool 字段");

        ChatMessage toolResultMsg = inserted.stream()
                .filter(m -> "TOOL_RESULT".equals(m.getMessageType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应落库 TOOL_RESULT 消息"));
        assertTrue(toolResultMsg.getContent().contains("\"toolCallId\":\"call-123\""), "应含 toolCallId");
        assertTrue(toolResultMsg.getContent().contains("\"status\":\"success\""), "应含 status=success");
    }

    // ==================== 生命周期：start/stop/消费循环 ====================

    @Test
    @DisplayName("start → 启动消费线程并确保消费组存在")
    void start_createsConsumerAndEnsuresGroup() throws Exception {
        worker.start();

        // 消费线程启动后应创建消费组并进入 XREADGROUP 轮询
        verify(streamOps, timeout(2000).atLeast(1))
                .createGroup(eq("chat:request"), any(ReadOffset.class), eq("chat-workers"));
        verify(streamOps, timeout(2000).atLeast(1))
                .read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));

        worker.stop();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("stop → runPool 未在期限内终止时强制关闭")
    void stop_forceShutdownWhenPoolNotTerminated() throws Exception {
        // awaitTermination 模拟返回 false（未在 30s 内终止）→ 走强制关闭分支
        when(runPool.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(false);

        worker.stop();

        // 优雅关闭路径：shutdown → awaitTermination 超时 → shutdownNow
        verify(runPool).shutdown();
        verify(runPool).shutdownNow();
    }

    @Test
    @DisplayName("stop → awaitTermination 被中断时强制关闭并恢复中断标志")
    void stop_interruptedDuringAwait_forceShutdown() throws Exception {
        doThrow(new InterruptedException("模拟等待中断")).when(runPool).awaitTermination(anyLong(), any(TimeUnit.class));

        worker.stop();

        verify(runPool).shutdownNow();
        // stop 内部 Thread.currentThread().interrupt() 设置了中断标志，此处读取并清理
        assertTrue(Thread.interrupted(), "中断路径应设置当前线程中断标志");
    }

    @Test
    @DisplayName("consumeLoop → 读到消息立即 ACK 并提交 runPool 执行")
    void consumeLoop_readsMessage_acksAndDispatches() throws Exception {
        // XREADGROUP 返回 1 条消息 → 读即 ACK + 分发到 runPool
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));

        worker.start();

        // 消息被提交到 runPool 执行
        verify(runPool, timeout(3000).atLeast(1)).submit(any(Runnable.class));
        // P3-2 读即 ACK：消息确认先于执行分发
        verify(streamOps, atLeastOnce()).acknowledge(eq("chat:request"), eq("chat-workers"), eq("123-0"));

        worker.stop();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("consumeLoop → read 异常时 1s 后重试，停止后退出循环")
    void consumeLoop_readError_retriesThenExits() throws Exception {
        // read 持续抛异常 → catch 分支记录错误并休眠重试
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RuntimeException("Redis 连接断开"));

        worker.start();

        // 等待发生第 2 次 read（首轮异常后 1s 重试），证明 catch 重试分支生效
        verify(streamOps, timeout(4000).atLeast(2))
                .read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));
        worker.stop();
        // 等待 catch 内重试休眠结束：醒来后 running=false 退出循环
        Thread.sleep(1100);
    }

    @Test
    @DisplayName("consumeLoop → 重试休眠期间被中断 → 立即退出循环")
    void consumeLoop_interruptedDuringRetrySleep_breaksLoop() throws Exception {
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenThrow(new RuntimeException("Redis 连接断开"));

        worker.start();
        // 等待首轮 read 抛异常进入 catch 重试休眠
        verify(streamOps, timeout(3000).atLeast(1))
                .read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));

        // 找到消费线程并中断其重试休眠（catch 内 InterruptedException → break 退出）
        Thread consumerThread = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "chat-consumer".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 chat-consumer 消费线程"));
        consumerThread.interrupt();

        // 中断后线程应快速退出
        long deadline = System.currentTimeMillis() + 3000;
        while (consumerThread.isAlive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(consumerThread.isAlive(), "消费线程应在中断后退出");

        worker.stop();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("ensureConsumerGroup → 消费组已存在（BUSYGROUP）时忽略并继续")
    void ensureConsumerGroup_groupExists_ignored() throws Exception {
        doThrow(new RuntimeException("BUSYGROUP Consumer group already exists"))
                .when(streamOps)
                .createGroup(anyString(), any(ReadOffset.class), anyString());

        worker.start();

        // createGroup 异常被吞掉，消费循环继续进入 XREADGROUP
        verify(streamOps, timeout(3000).atLeast(1))
                .read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class));

        worker.stop();
        Thread.sleep(100);
    }

    // ==================== runPool 队列满拒绝分支（B2-1） ====================

    @Test
    @DisplayName("runPool 队列满拒绝 → 推送 ERROR 终态事件 + 清理 ring + 状态回写 ERROR（B2-1）")
    void consumeLoop_poolRejected_pushesTerminalEventCleansRingAndMarksError() throws Exception {
        // Given: runPool 提交即拒绝（8 线程 + 100 队列全满），消息含合法 runId
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));
        when(runPool.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("队列已满"));

        worker.start();

        // Then: ERROR 终态事件推送到入口已建的 ring（payload 含 runId 与 ERROR 终态标记，
        // 客户端据此结束"生成中"状态；无该事件则重连也永久无终态）
        // 说明：mock read 无阻塞语义会连续重读同一消息，拒绝分支被反复触发，故用 atLeast(1)
        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, timeout(3000).atLeast(1)).push(eq("100"), eventCaptor.capture());
        SseEvent rejectedEvent = eventCaptor.getAllValues().get(0);
        assertEquals(SseEventType.ERROR, rejectedEvent.type());
        assertTrue(rejectedEvent.payload().contains("\"runId\":\"100\""), "事件 payload 应含 runId");
        assertTrue(rejectedEvent.payload().contains("\"status\":\"ERROR\""), "事件 payload 应含 ERROR 终态标记");
        // Then: ring 被清理（否则 ring + 阻塞在 outbox.take() 的投递线程永久泄漏）
        verify(bridge, timeout(3000).atLeast(1)).removeRing("100");
        // Then: run 状态回写 ERROR（解锁 uniq_active_run_per_session）
        verify(chatRunService, timeout(3000).atLeast(1)).updateStatus(100L, "ERROR");

        worker.stop();
        Thread.sleep(100);
    }

    @Test
    @DisplayName("runPool 拒绝 → 终态事件推送失败不中断 ring 清理与状态回写（B2-1）")
    void consumeLoop_poolRejected_pushFailureStillCleansRingAndMarksError() throws Exception {
        // Given: runPool 拒绝且 bridge.push 抛异常（复合故障——推送失败不得中断清理链）
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");
        when(streamOps.read(any(Consumer.class), any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(List.of(record));
        when(runPool.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("队列已满"));
        doThrow(new RuntimeException("ring 推送失败")).when(bridge).push(eq("100"), any(SseEvent.class));

        worker.start();

        // Then: 清理与状态回写仍执行（推送失败仅记日志）
        verify(bridge, timeout(3000).atLeast(1)).removeRing("100");
        verify(chatRunService, timeout(3000).atLeast(1)).updateStatus(100L, "ERROR");

        worker.stop();
        Thread.sleep(100);
    }

    // ==================== 完成时刻 DB 故障双终态防护（B2-4） ====================

    @Test
    @DisplayName("完成回写耗尽 → END 已推送后 catch 不再推第二终态、消息不重复落库（B2-4）")
    void processRequest_statusUpdateExhaustsAfterEnd_noSecondTerminalOrDoublePersist() throws Exception {
        // Given: 图流正常完成，但 COMPLETED 终态回写 3 次重试全部失败（完成时刻 DB 持续故障）
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));
        // ACTIVE 转换显式放行（严格桩模式下未匹配的调用会抛 PotentialStubbingProblem）
        doNothing().when(chatRunService).updateStatus(anyLong(), eq("ACTIVE"));
        doThrow(new RuntimeException("数据库不可用")).when(chatRunService).updateStatus(anyLong(), eq("COMPLETED"));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 终态事件仅推送一次（END(COMPLETED) 在异常前已推送），不再追加 ERROR 双终态
        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeast(1)).push(eq("100"), eventCaptor.capture());
        List<SseEvent> terminalEvents = eventCaptor.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.END || e.type() == SseEventType.ERROR)
                .toList();
        assertEquals(1, terminalEvents.size(), "终态事件应仅推送一次，实际: " + terminalEvents);
        assertEquals(SseEventType.END, terminalEvents.get(0).type(), "首个（唯一）终态应为 END(COMPLETED)");
        assertTrue(terminalEvents.get(0).payload().contains("COMPLETED"));
        // Then: doOnComplete 已成功落库（1 次），catch 分支不再二次 persistMessages 重复落库
        verify(chatMessageService, times(1)).batchInsert(anyList());
        // Then: 不再经 handleError 回写 ERROR（客户端已见 COMPLETED，DB 滞留 ACTIVE 由 M-8 巡检兜底收敛）
        verify(chatRunService, never()).updateStatus(100L, "ERROR");
    }

    @Test
    @DisplayName("persistMessages → 批量插入撞 (run_id,seq) 唯一索引按已落库幂等处理（B2-4 数据层兜底）")
    void persistMessages_uniqueIndexConflict_treatedAsPersisted() throws Exception {
        // Given: 本批消息已落库（重复调用场景），唯一索引拒绝重复插入
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(null);
        doThrow(new DataIntegrityViolationException("重复键违反唯一约束 uniq_chat_message_run_seq"))
                .when(chatMessageService)
                .batchInsert(anyList());

        // When: 幂等跳过、不外抛异常，persisted=true（已落库，调用方不再重试）
        PersistOutcome outcome = invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput);

        assertTrue(outcome.persisted(), "唯一索引冲突应按已落库处理（幂等跳过）");
        assertNull(outcome.assistantMessageId(), "幂等跳过分支本批未新落库，无回填 ID（END 事件 messageId 降级 null）");
        verify(chatMessageService).batchInsert(anyList());
    }

    @Test
    @DisplayName("persistMessages → 非冲突 DB 异常返回 false（可由 catch 分支重试补落库）")
    void persistMessages_genericDbFailure_returnFalseForRetry() throws Exception {
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(null);
        doThrow(new RuntimeException("连接池耗尽")).when(chatMessageService).batchInsert(anyList());

        PersistOutcome outcome = invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput);

        // 落库失败且非幂等冲突 → persisted=false，允许 catch 分支重试（消息未落库，重试无害）
        assertFalse(outcome.persisted());
        assertNull(outcome.assistantMessageId(), "落库失败无消息 ID");
        verify(chatMessageService).batchInsert(anyList());
    }

    // ==================== R2 补口 B：END 事件 messageId + intentType 落库修复 ====================

    @Test
    @DisplayName("R2 persistMessages → 反向扫描定位最后一条 assistant 正文行（跳过 thinking/TOOL_*），返回其落库回填雪花 ID")
    void persistMessages_返回assistant正文消息ID() throws Exception {
        // Given: 一条 assistant 消息展开为 thinking + 正文 + TOOL_CALL 三行；
        // batchInsert 模拟 MP saveBatch 行为——插入后回填雪花 ID
        AssistantMessage assistantMsg = mock(AssistantMessage.class);
        when(assistantMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考过程"));
        when(assistantMsg.getText()).thenReturn("最终回答");
        when(assistantMsg.hasToolCalls()).thenReturn(true);
        when(assistantMsg.getToolCalls())
                .thenReturn(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "searchKnowledge", "{\"query\":\"课程\"}")));
        OverAllState state = new OverAllState(Map.of("messages", List.of(assistantMsg)));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);
        doAnswer(inv -> {
                    List<ChatMessage> inserted = inv.getArgument(0);
                    long id = 9000L;
                    for (ChatMessage m : inserted) {
                        m.setId(id++);
                    }
                    return null;
                })
                .when(chatMessageService)
                .batchInsert(anyList());

        // When
        PersistOutcome outcome = invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput);

        // Then: 落库行序 [USER, thinking, 正文, TOOL_CALL]，反向扫描应命中正文行（messageType==null）
        assertTrue(outcome.persisted(), "正常落库应返回 persisted=true");
        assertEquals(9002L, outcome.assistantMessageId(), "应返回最后一条 assistant 正文行的回填 ID");
    }

    @Test
    @DisplayName("R2 persistMessages → 异常中断仅用户消息场景：assistantMessageId 为 null、persisted 仍 true")
    void persistMessages_无assistant正文时返回null() throws Exception {
        // Given: 流式异常中断无 chunk 输出（lastOutput=null）→ 仅落用户消息
        // When
        PersistOutcome outcome = invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, null);

        // Then: 用户消息落库成功仍视为 persisted；无 assistant 正文行 → ID 为 null（END 事件 messageId 显式 null）
        assertTrue(outcome.persisted(), "用户消息落库成功仍视为 persisted");
        assertNull(outcome.assistantMessageId(), "无 assistant 正文行时 ID 为 null");
        verify(chatMessageService).batchInsert(anyList());
    }

    @Test
    @DisplayName("R2 doOnComplete → END(COMPLETED) 事件 payload 携带落库回填的 assistant messageId（字符串）")
    void doOnComplete的END事件携带messageId() throws Exception {
        // Given: 最终 state 含本轮 assistant 回答；batchInsert 模拟 saveBatch 回填雪花 ID
        AssistantMessage assistantMsg = new AssistantMessage("最终回答内容");
        OverAllState state = new OverAllState(Map.of("messages", List.of(assistantMsg)));
        NodeOutput mockChunk = mock(NodeOutput.class);
        when(mockChunk.state()).thenReturn(state);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));
        doAnswer(inv -> {
                    List<ChatMessage> inserted = inv.getArgument(0);
                    long id = 7000L;
                    for (ChatMessage m : inserted) {
                        m.setId(id++);
                    }
                    return null;
                })
                .when(chatMessageService)
                .batchInsert(anyList());

        // When
        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // Then: END payload 含 messageId 字符串（先落库回填 ID、后推 END 的时序保证反馈目标可用）
        ArgumentCaptor<SseEvent> evtCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeast(2)).push(eq("100"), evtCaptor.capture());
        SseEvent endEvent = evtCaptor.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.END)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("正常完成应推送 END 事件"));
        assertTrue(endEvent.payload().contains("\"status\":\"COMPLETED\""), "终态应为 COMPLETED");
        assertTrue(
                endEvent.payload().contains("\"messageId\":\"7001\""),
                "END payload 应含 assistant 正文行落库 ID（字符串）: " + endEvent.payload());
    }

    @Test
    @DisplayName("R2 取消路径 → END(CANCELLED) 事件不含 messageId 键（半截内容不作反馈目标）")
    void 取消路径END事件不含messageId() throws Exception {
        // Given: run 起步前设置取消标记，首个 chunk 触发 CancelledException
        worker.cancel("100");
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(createMockRecord("100", "200", "300", "你好"));

        // Then: CANCELLED 终态 payload 仅 runId/status，无 messageId 键
        // （时序上先于落库 + 语义上半截回答不得作为反馈目标）
        ArgumentCaptor<SseEvent> evtCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(bridge, atLeast(1)).push(eq("100"), evtCaptor.capture());
        SseEvent endEvent = evtCaptor.getAllValues().stream()
                .filter(e -> e.type() == SseEventType.END)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("取消路径应推送 END 事件"));
        assertTrue(endEvent.payload().contains("\"status\":\"CANCELLED\""), "终态应为 CANCELLED");
        assertFalse(endEvent.payload().contains("messageId"), "CANCELLED 终态不得携带 messageId 键");
    }

    @Test
    @DisplayName("R2 assistant 正文行 → intentType 从 KEY_QUERY_PLAN 取规范名小写落库（修复 intent_type 恒 NULL）")
    void assistant正文行写入intentType() throws Exception {
        // Given: 最终 state 含 messages + 查询计划（intent=knowledge_question）
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("重写查询"), new QueryPlanFilters(List.of()), false);
        AssistantMessage assistantMsg = new AssistantMessage("引用资料的回答");
        OverAllState state = new OverAllState(Map.of("messages", List.of(assistantMsg), KEY_QUERY_PLAN, plan));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        // When
        invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput);

        // Then: assistant 正文行 intent_type 写入规范名小写；用户行不写（意图仅标注 AI 回答）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        ChatMessage assistantRow = captor.getValue().stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && m.getMessageType() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在 assistant 正文行"));
        assertEquals("knowledge_question", assistantRow.getIntentType(), "intent_type 应写入规范名小写");
        ChatMessage userRow = captor.getValue().stream()
                .filter(m -> "USER".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应存在用户消息行"));
        assertNull(userRow.getIntentType(), "用户行不写意图");
    }

    // ==================== 巡检覆盖滞留 QUEUED（B2-3） ====================

    /** 反射调用 private sweepStaleRuns（巡检入口） */
    private void invokeSweepStaleRuns() throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod("sweepStaleRuns");
        method.setAccessible(true);
        method.invoke(worker);
    }

    @Test
    @DisplayName("巡检 → 滞留 QUEUED run 置 ERROR（B2-3：按 created_at 超阈值判定，解锁会话 409）")
    void sweepStaleRuns_staleQueued_resetsToError() throws Exception {
        // Given: ACTIVE 超时阈值 10min、QUEUED 滞留阈值 5min，巡检查回一条滞留 QUEUED run
        when(workerProperties.staleRunTimeoutMinutes()).thenReturn(10);
        when(workerProperties.staleQueuedTimeoutMinutes()).thenReturn(5);
        when(chatRunService.findStaleActive(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(new ChatRunVO(
                        101L, 1L, 1L, "QUEUED", LocalDateTime.now().minusMinutes(30))));

        invokeSweepStaleRuns();

        // Then: 滞留 QUEUED run 被置 ERROR（解除 uniq_active_run_per_session 会话锁死）
        verify(chatRunService).updateStatus(101L, "ERROR");
        // Then: QUEUED 阈值按 stale-queued-timeout-minutes 计算（created_at < now-5min）
        ArgumentCaptor<LocalDateTime> queuedBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chatRunService).findStaleActive(any(LocalDateTime.class), queuedBefore.capture());
        long minutes =
                Duration.between(queuedBefore.getValue(), LocalDateTime.now()).toMinutes();
        assertTrue(minutes >= 4 && minutes <= 6, "QUEUED 阈值应为 now-5min（实际 " + minutes + "min）");
    }

    @Test
    @DisplayName("巡检 → 未超时的 QUEUED/ACTIVE（SQL 过滤后无返回）不动作")
    void sweepStaleRuns_noStaleRuns_noStatusUpdate() throws Exception {
        when(workerProperties.staleRunTimeoutMinutes()).thenReturn(10);
        when(workerProperties.staleQueuedTimeoutMinutes()).thenReturn(5);
        when(chatRunService.findStaleActive(any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of());

        invokeSweepStaleRuns();

        // Then: 无滞留 run 时不得误置任何状态
        verify(chatRunService, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    @DisplayName("启动时立即执行一次巡检（B2-3：兜底停机丢弃排队任务场景）")
    void start_runsStartupSweep() throws Exception {
        worker.start();

        // initial delay=0：启动即扫描一次滞留 ACTIVE/QUEUED run（此前首扫在 60s 后，
        // 滚动重启丢任务场景下会话最长 409 一分钟以上）
        verify(chatRunService, timeout(2000).atLeast(1))
                .findStaleActive(any(LocalDateTime.class), any(LocalDateTime.class));

        worker.stop();
        Thread.sleep(100);
    }

    // ==================== processRequest 边界分支 ====================

    @Test
    @DisplayName("onErrorResume → handleError 内部失败被吞并，消息仍持久化")
    void processRequest_onErrorResumeHandleErrorFails_stillPersists() throws Exception {
        // Flux.error 进入 onErrorResume 分支；ACTIVE 更新正常、ERROR 终态更新三次失败后抛 IllegalStateException
        doNothing().when(chatRunService).updateStatus(anyLong(), eq("ACTIVE"));
        when(compiledGraph.stream(any(), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new RuntimeException("模型超时")));
        doThrow(new RuntimeException("数据库不可用")).when(chatRunService).updateStatus(anyLong(), eq("ERROR"));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 终态处理失败被吞并，消息持久化兜底仍执行
        verify(chatMessageService).batchInsert(anyList());
    }

    @Test
    @DisplayName("captureSnapshot → saver.get 异常时降级为 null，不阻塞 run")
    void processRequest_snapshotFailure_degradesGracefully() throws Exception {
        // 快照存储异常 → captureSnapshot 内部兜底返回 null
        when(saver.get(any(RunnableConfig.class))).thenThrow(new RuntimeException("快照存储不可用"));

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 快照失败仅记 warn，run 正常流转 ACTIVE → COMPLETED
        verify(chatRunService).updateStatus(100L, "ACTIVE");
        verify(chatRunService).updateStatus(100L, "COMPLETED");
    }

    // ==================== 附件入队/落库（spec §5.1 双存） ====================

    @Test
    @DisplayName("带附件消息 — run 与 message 均落 attachments_json（合法 JSON 数组透传）")
    void processRequest_withAttachments_runAndMessagePersist() throws Exception {
        // Given: Redis 消息 body 含附件 JSON 数组（上传接口返回的 type/url/name/size）
        String attachmentsJson = "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1}]";
        Map<String, Object> body = new HashMap<>();
        body.put("runId", "100");
        body.put("sessionId", "200");
        body.put("userId", "300");
        body.put("query", "这张图里是什么");
        body.put("attachments", attachmentsJson);
        MapRecord<String, Object, Object> record = createMockRecordWithBody(body);

        // 附件编排 mock：返回空上下文（本用例聚焦 run/message 双存，不关心 caption 组装）
        when(orchestrator.process(anyList(), any(), any())).thenReturn(AttachmentContext.empty());

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 业务入口表 chat_run 落 attachments_json（合法 JSON 原样透传）
        verify(chatRunService).updateAttachments(100L, attachmentsJson);
        // 渲染/审计表 chat_message 用户消息行落 attachments_json（spec §5.1 双存决策）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        ChatMessage userMsg = captor.getValue().stream()
                .filter(m -> "USER".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应含用户消息行"));
        assertEquals(attachmentsJson, userMsg.getAttachmentsJson());
    }

    @Test
    @DisplayName("附件 JSON 非法 — 按空数组处理，run 与 message 均落 []（附件损坏不阻断对话）")
    void processRequest_invalidAttachmentsJson_degradesToEmptyArray() throws Exception {
        // Given: Redis 消息 body 含非法附件 JSON
        Map<String, Object> body = new HashMap<>();
        body.put("runId", "100");
        body.put("sessionId", "200");
        body.put("userId", "300");
        body.put("query", "这是什么附件");
        body.put("attachments", "not-json{");
        MapRecord<String, Object, Object> record = createMockRecordWithBody(body);

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 非法 JSON 归一为空数组，双表均落 []
        verify(chatRunService).updateAttachments(100L, "[]");
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        ChatMessage userMsg = captor.getValue().stream()
                .filter(m -> "USER".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应含用户消息行"));
        assertEquals("[]", userMsg.getAttachmentsJson());
    }

    @Test
    @DisplayName("无 attachments 键 — 默认空数组，run 与 message 均落 []（历史消息格式兼容）")
    void processRequest_withoutAttachments_defaultsToEmptyArray() throws Exception {
        // Given: 消息不含 attachments 键（既有消息格式，向后兼容）
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 无附件时双表均落 []
        verify(chatRunService).updateAttachments(100L, "[]");
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        ChatMessage userMsg = captor.getValue().stream()
                .filter(m -> "USER".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应含用户消息行"));
        assertEquals("[]", userMsg.getAttachmentsJson());
    }

    // ==================== 附件处理与 QU caption 拼装（Task 9，spec §5.1/§5.3） ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("带图片附件 — caption 拼入 QU 查询 + metadata 携带附件上下文，持久化用户消息仍为原文")
    void processRequest_withImageAttachment_captionPrefixedAndMetadata() throws Exception {
        // Given: 消息带 1 张图片附件，orchestrator 返回含 caption 的附件上下文
        String attachmentsJson = "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1}]";
        Map<String, Object> body = new HashMap<>();
        body.put("runId", "100");
        body.put("sessionId", "200");
        body.put("userId", "300");
        body.put("query", "这张图里是什么");
        body.put("attachments", attachmentsJson);
        MapRecord<String, Object, Object> record = createMockRecordWithBody(body);

        AttachmentContext context =
                new AttachmentContext(List.of(new ImageCaptionResult("图片1:红色图表", "a.png")), Map.of());
        when(orchestrator.process(anyList(), any(), any())).thenReturn(context);

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        ArgumentCaptor<Map> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        when(compiledGraph.stream(inputsCaptor.capture(), configCaptor.capture()))
                .thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: QU 图输入的用户消息带 caption 前缀（spec §5.3："图片N:[caption] 用户问题"）
        List<?> msgs = (List<?>) inputsCaptor.getValue().get("messages");
        assertEquals("图片1:红色图表 这张图里是什么", ((UserMessage) msgs.get(0)).getText());

        // Then: orchestrator 收到解析后的附件记录（url/type 原样透传），且 Task 4 接线传入
        // thinkingPusher（caption 思考流式）与取消源（附件批循环即时取消）
        ArgumentCaptor<List<AttachmentRecord>> attCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<ThinkingPusher> pusherCaptor = ArgumentCaptor.forClass(ThinkingPusher.class);
        ArgumentCaptor<BooleanSupplier> cancelCaptor = ArgumentCaptor.forClass(BooleanSupplier.class);
        verify(orchestrator).process(attCaptor.capture(), pusherCaptor.capture(), cancelCaptor.capture());
        assertEquals(1, attCaptor.getValue().size());
        assertEquals("image", attCaptor.getValue().get(0).type());
        assertEquals("0/a.png", attCaptor.getValue().get(0).url());
        assertNotNull(pusherCaptor.getValue(), "SSE 链路必须传入 per-run thinkingPusher");
        assertNotNull(cancelCaptor.getValue(), "必须传入 run 级取消源");
        assertFalse(cancelCaptor.getValue().getAsBoolean(), "未被取消的 run 取消源应返回 false");

        // Then: metadata 携带附件上下文（QU/RetrieveNode 消费通道）
        assertEquals(
                context,
                configCaptor
                        .getValue()
                        .metadata(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT)
                        .orElse(null));
        // Then: metadata 注册取消源（RetrieveNode 三段 join 前检查点消费通道，Task 4）
        assertTrue(
                configCaptor.getValue().metadata(RetrieveNode.KEY_CANCEL_CHECK).orElse(null) instanceof BooleanSupplier,
                "worker 必须在 config.metadata 注册 KEY_CANCEL_CHECK 取消源");

        // Then: 当前消息已带附件 → 不触发 chat_run 历史重建（Task 11 重建仅覆盖后续无附件轮次）
        verify(chatRunService, never()).findRecentAttachments(anyLong(), anyLong(), anyInt());

        // Then: 持久化用户消息仍为原文（不带 caption 前缀，chat_message 渲染/审计不回显图片标注）
        ArgumentCaptor<List<ChatMessage>> msgCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(msgCaptor.capture());
        ChatMessage userMsg = msgCaptor.getValue().stream()
                .filter(m -> "USER".equals(m.getRole()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应含用户消息行"));
        assertEquals("这张图里是什么", userMsg.getContent());
        assertEquals(attachmentsJson, userMsg.getAttachmentsJson());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("带文档附件 — metadata 携带文档局部语料，query 无 caption 前缀保持原样")
    void processRequest_withDocumentAttachment_metadataCarriesDocuments() throws Exception {
        // Given: 消息带 1 个文档附件，orchestrator 返回仅含 documents 的上下文
        String attachmentsJson = "[{\"type\":\"document\",\"url\":\"0/doc.pdf\",\"name\":\"doc.pdf\",\"size\":2}]";
        Map<String, Object> body = new HashMap<>();
        body.put("runId", "100");
        body.put("sessionId", "200");
        body.put("userId", "300");
        body.put("query", "这份文档讲了什么");
        body.put("attachments", attachmentsJson);
        MapRecord<String, Object, Object> record = createMockRecordWithBody(body);

        AttachmentContext context = new AttachmentContext(
                List.of(), Map.of("0/doc.pdf", List.of(new DocumentLocalChunk("附件正文", new float[] {1f}, 0))));
        when(orchestrator.process(anyList(), any(), any())).thenReturn(context);

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        ArgumentCaptor<Map> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        when(compiledGraph.stream(inputsCaptor.capture(), configCaptor.capture()))
                .thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 无 caption 时 QU 查询原样（文档附件不拼前缀）
        List<?> msgs = (List<?>) inputsCaptor.getValue().get("messages");
        assertEquals("这份文档讲了什么", ((UserMessage) msgs.get(0)).getText());
        // Then: metadata 携带文档局部语料
        assertEquals(
                context,
                configCaptor
                        .getValue()
                        .metadata(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT)
                        .orElse(null));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("首事件前移 — METADATA 与 STAGE(attachments) 先于 orchestrator.process 推送（附件管线静默消除）")
    void processRequest_metadataPushedBeforeAttachmentProcessing() throws Exception {
        // Given: 消息带 1 个图片附件（触发 orchestrator.process 慢路径）
        String attachmentsJson = "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1}]";
        Map<String, Object> body = new HashMap<>();
        body.put("runId", "100");
        body.put("sessionId", "200");
        body.put("userId", "300");
        body.put("query", "这张图里是什么");
        body.put("attachments", attachmentsJson);
        MapRecord<String, Object, Object> record = createMockRecordWithBody(body);
        when(orchestrator.process(anyList(), any(), any())).thenReturn(AttachmentContext.empty());

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(Map.class), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: METADATA → STAGE(attachments) → orchestrator.process 严格时序
        // （2026-08-27 前移：附件处理最长 60s，此前该窗口对客户端零事件）
        InOrder inOrder = inOrder(bridge, orchestrator);
        inOrder.verify(bridge).push(eq("100"), argThat((SseEvent e) -> e != null && e.type() == SseEventType.METADATA));
        inOrder.verify(bridge).push(eq("100"), argThat((SseEvent e) -> e != null && e.type() == SseEventType.STAGE));
        inOrder.verify(orchestrator).process(anyList(), any(), any());
        // 且图执行前推送 STAGE(understanding)（覆盖 QU 阻塞 LLM 静默窗口）
        verify(transformer, atLeastOnce()).createStageEvent(any(), eq(SseEventTransformer.STAGE_UNDERSTANDING));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("无附件消息 — 不调用 orchestrator，query 原样，metadata 无附件上下文（重建分支查无历史同样无处理）")
    void processRequest_withoutAttachments_noOrchestratorCallAndNoMetadata() throws Exception {
        // Given: 消息不含 attachments 键（既有格式，向后兼容）
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        ArgumentCaptor<Map> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        when(compiledGraph.stream(inputsCaptor.capture(), configCaptor.capture()))
                .thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 触发重建查询但查无历史（mock 默认空列表）→ orchestrator 不被调用，query 原样，无附件上下文 metadata
        verify(chatRunService).findRecentAttachments(200L, 100L, 3);
        verify(orchestrator, never()).process(anyList(), any(), any());
        List<?> msgs = (List<?>) inputsCaptor.getValue().get("messages");
        assertEquals("你好", ((UserMessage) msgs.get(0)).getText());
        assertTrue(configCaptor
                .getValue()
                .metadata(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT)
                .isEmpty());
    }

    // ==================== 后续轮次附件重建（Task 11，spec §5.1 最终三表决策） ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("无附件消息 — 按 session 查最近 run 附件重建上下文（findRecentAttachments → orchestrator → metadata）")
    void processRequest_rebuildFromRecentRun() throws Exception {
        // Given: body 不含 attachments 键（第二轮后续轮次，用户不再上传附件）
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "课程后续问题");

        // chatRunService 返回最近 run 中重建出的附件记录（排除当前 run，url 去重后）
        List<AttachmentRecord> recent = List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1L));
        when(chatRunService.findRecentAttachments(200L, 100L, 3)).thenReturn(recent);

        // orchestrator 返回含旧图 caption 的附件上下文（Caffeine 命中直接复用或重新 caption）
        AttachmentContext context = new AttachmentContext(List.of(new ImageCaptionResult("图片1:旧图", "a.png")), Map.of());
        when(orchestrator.process(eq(recent), any(), any())).thenReturn(context);

        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        ArgumentCaptor<Map> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<RunnableConfig> configCaptor = ArgumentCaptor.forClass(RunnableConfig.class);
        when(compiledGraph.stream(inputsCaptor.capture(), configCaptor.capture()))
                .thenReturn(Flux.just(mockChunk));

        // When
        invokeProcessRequest(record);

        // Then: 以 chat_run 为入口查该会话最近 3 个 run 的附件（排除当前 run）
        verify(chatRunService).findRecentAttachments(200L, 100L, 3);
        // orchestrator.process 收到重建出的附件记录（pusher/取消源由 worker 接线传入）
        verify(orchestrator).process(eq(recent), any(), any());
        // metadata 携带重建的附件上下文（QU/RetrieveNode 消费通道）
        assertEquals(
                context,
                configCaptor
                        .getValue()
                        .metadata(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT)
                        .orElse(null));
        // QU 查询携带重建的旧图 caption 前缀（spec §5.3："图片N:[caption] 用户问题"）
        List<?> msgs = (List<?>) inputsCaptor.getValue().get("messages");
        assertEquals("图片1:旧图 课程后续问题", ((UserMessage) msgs.get(0)).getText());
    }

    // ==================== persistMessages 边界分支 ====================

    @Test
    @DisplayName("persistMessages → state 中的 UserMessage 跳过，避免与查询消息重复落库（F2-12）")
    void persistMessages_skipsUserMessageInState() throws Exception {
        // state 含 UserMessage + AssistantMessage：UserMessage 已在步骤1单独插入，跳过
        OverAllState state =
                new OverAllState(Map.of("messages", List.of(new UserMessage("历史追问"), new AssistantMessage("补充回答"))));
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(state);

        // When
        invokePersistMessages(1L, 1L, "本轮问题", "[]", "[]", 0, lastOutput);

        // Then: 仅落库本轮 USER 查询 + ASSISTANT 补充回答，不重复 USER
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> inserted = captor.getValue();
        assertEquals(2, inserted.size());
        assertEquals("USER", inserted.get(0).getRole());
        assertEquals("补充回答", inserted.get(1).getContent());
    }

    @Test
    @DisplayName("persistMessages → 批量插入失败被吞并，不阻断 run")
    void persistMessages_batchInsertFailure_swallowed() throws Exception {
        doThrow(new RuntimeException("数据库不可用")).when(chatMessageService).batchInsert(anyList());
        NodeOutput lastOutput = mock(NodeOutput.class);
        when(lastOutput.state()).thenReturn(null);

        // 不抛异常即验证通过（异常在 persistMessages 内部被吞并）
        invokePersistMessages(1L, 1L, "问题", "[]", "[]", 0, lastOutput);
        verify(chatMessageService).batchInsert(anyList());
    }

    // ==================== toChatMessages 分支 ====================

    /** 反射调用 private toChatMessages（B3-5 后含 sourcesJson 参数） */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> invokeToChatMessages(Message msg, Long runId, Long sessionId) throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod(
                "toChatMessages", Message.class, Long.class, Long.class, String.class);
        method.setAccessible(true);
        return (List<ChatMessage>) method.invoke(worker, msg, runId, sessionId, "[]");
    }

    @Test
    @DisplayName("toChatMessages → UserMessage 产出 USER 记录")
    void toChatMessages_userMessage_createsUserRecord() throws Exception {
        List<ChatMessage> result = invokeToChatMessages(new UserMessage("直接问题"), 1L, 2L);

        assertEquals(1, result.size());
        assertEquals("USER", result.get(0).getRole());
        assertEquals("直接问题", result.get(0).getContent());
    }

    @Test
    @DisplayName("toChatMessages → AssistantMessage 带 reasoningContent 产出 thinking 记录")
    void toChatMessages_assistantThinking_createsThinkingRecord() throws Exception {
        // DashScope 思考内容存放于 metadata.reasoningContent
        AssistantMessage thinkingAssistant = mock(AssistantMessage.class);
        when(thinkingAssistant.getMetadata()).thenReturn(Map.of("reasoningContent", "深度思考过程"));
        when(thinkingAssistant.getText()).thenReturn("");
        when(thinkingAssistant.hasToolCalls()).thenReturn(false);

        List<ChatMessage> result = invokeToChatMessages(thinkingAssistant, 1L, 2L);

        assertEquals(1, result.size());
        assertEquals("thinking", result.get(0).getMessageType());
        assertEquals("深度思考过程", result.get(0).getContent());
        // 2026-08-28 时间线改版：state 消息来源的思考恒属主 agent 生成阶段
        assertEquals("generating", result.get(0).getThinkingStage());
    }

    // ==================== 其它私有方法边界 ====================

    /** 构造使用指定 ObjectMapper 的 worker（用于序列化失败分支） */
    private ChatRequestWorker newWorker(ObjectMapper mapper) {
        return new ChatRequestWorker(
                redisTemplate,
                compiledGraph,
                saver,
                transformer,
                bridge,
                chatRunService,
                chatMessageService,
                streamProperties,
                workerProperties,
                runPool,
                warningHook,
                orchestrator,
                memoryExtractionPipeline,
                mapper,
                "qwen3.8-max");
    }

    @Test
    @DisplayName("handleCancelled → checkpoint 回滚失败仅记 warn，取消终态不受影响")
    void handleCancelled_rollbackFails_degrades() throws Exception {
        // saver.get 返回快照 → 回滚写入 checkpoint 时抛异常
        Checkpoint cp = Checkpoint.builder()
                .id("cp-1")
                .state(Map.of("messages", List.of(new UserMessage("历史问题"))))
                .nodeId("node-1")
                .nextNodeId("node-2")
                .build();
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(cp));
        doThrow(new RuntimeException("checkpoint 写入失败"))
                .when(saver)
                .put(any(RunnableConfig.class), any(Checkpoint.class));

        worker.cancel("100");
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 回滚失败被吞并，取消流程仍完成终态
        verify(chatRunService).updateStatus(100L, "CANCELLED");
    }

    @Test
    @DisplayName("ackMessage → XACK 失败仅记 warn，不阻断流程")
    void ackMessage_failure_swallowed() throws Exception {
        doThrow(new RuntimeException("ACK 失败")).when(streamOps).acknowledge(anyString(), anyString(), anyString());

        // runId 非数字 → 解析失败后执行 ackMessage，ack 失败被吞并
        MapRecord<String, Object, Object> record = createMockRecord("abc", "200", "300", "你好");

        // When
        invokeProcessRequest(record);

        // Then: 不执行任何状态更新，异常未外抛
        verify(chatRunService, never()).updateStatus(anyLong(), anyString());
    }

    /** 反射调用 private toJson */
    private String invokeToJson(ChatRequestWorker target, Map<String, Object> map) throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod("toJson", Map.class);
        method.setAccessible(true);
        return (String) method.invoke(target, map);
    }

    @Test
    @DisplayName("toJson → 空/Null Map 返回空 JSON 对象")
    void toJson_emptyOrNullMap_returnsBraces() throws Exception {
        assertEquals("{}", invokeToJson(worker, null));
        assertEquals("{}", invokeToJson(worker, Map.of()));
    }

    @Test
    @DisplayName("toJson → 序列化失败时降级返回空 JSON 对象")
    void toJson_serializationFailure_returnsBraces() throws Exception {
        // 使用序列化必失败的 ObjectMapper 触发降级分支
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("序列化失败") {});
        ChatRequestWorker failingWorker = newWorker(failingMapper);

        assertEquals("{}", invokeToJson(failingWorker, Map.of("status", "ERROR")));
    }

    /** 反射调用 private buildToolCallContent */
    private String invokeBuildToolCallContent(ChatRequestWorker target, String toolCallId, String toolName, String args)
            throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod(
                "buildToolCallContent", String.class, String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, toolCallId, toolName, args);
    }

    @Test
    @DisplayName("buildToolCallContent → 非 JSON 参数保留为纯文本")
    void buildToolCallContent_invalidJson_keepsAsText() throws Exception {
        String content = invokeBuildToolCallContent(worker, "call-1", "searchKnowledge", "非JSON{");

        // 参数解析失败降级为纯文本，schema 字段不变
        assertTrue(content.contains("\"toolCallId\":\"call-1\""));
        assertTrue(content.contains("\"input\":\"非JSON{\""));
    }

    @Test
    @DisplayName("buildToolCallContent → 空参数降级为空对象")
    void buildToolCallContent_blankArguments_emptyInput() throws Exception {
        String content = invokeBuildToolCallContent(worker, "call-1", "searchKnowledge", null);

        assertTrue(content.contains("\"input\":{}"));
    }

    @Test
    @DisplayName("buildToolCallContent → 序列化失败降级返回固定格式")
    void buildToolCallContent_serializationFailure_fallback() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("序列化失败") {});
        ChatRequestWorker failingWorker = newWorker(failingMapper);

        String content = invokeBuildToolCallContent(failingWorker, "call-1", "searchKnowledge", null);

        assertTrue(content.contains("\"toolCallId\":\"call-1\""));
        assertTrue(content.contains("\"toolName\":\"searchKnowledge\""));
    }

    /** 反射调用 private buildToolResultContent */
    private String invokeBuildToolResultContent(ChatRequestWorker target, String toolCallId, String responseData)
            throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod("buildToolResultContent", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(target, toolCallId, responseData);
    }

    @Test
    @DisplayName("buildToolResultContent → 序列化失败降级返回固定格式")
    void buildToolResultContent_serializationFailure_fallback() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("序列化失败") {});
        ChatRequestWorker failingWorker = newWorker(failingMapper);

        String content = invokeBuildToolResultContent(failingWorker, "call-1", "查询结果");

        assertTrue(content.contains("\"toolCallId\":\"call-1\""));
        assertTrue(content.contains("\"status\":\"success\""));
    }

    @Test
    @DisplayName("updateStatusWithRetry → 重试休眠被中断时立即返回（不重抛）")
    void updateStatusWithRetry_interruptedDuringRetry_returnsQuietly() throws Exception {
        // ACTIVE 更新正常（避免 strict stubbing 误判）、COMPLETED 更新持续失败 → 第 1 次重试休眠期间被中断
        doNothing().when(chatRunService).updateStatus(anyLong(), eq("ACTIVE"));
        doThrow(new RuntimeException("DB 瞬时故障")).when(chatRunService).updateStatus(anyLong(), eq("COMPLETED"));
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));
        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        // 在独立线程中执行 processRequest，主线程在重试休眠期（500ms）中断它
        Thread[] runner = new Thread[1];
        runner[0] = new Thread(() -> {
            try {
                invokeProcessRequest(record);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        runner[0].start();
        Thread.sleep(300);
        runner[0].interrupt();
        runner[0].join(5000);

        assertFalse(runner[0].isAlive(), "processRequest 应在中断处理后正常返回");
        // 中断分支不重抛异常，消息持久化兜底仍执行
        verify(chatMessageService, atLeastOnce()).batchInsert(anyList());
    }
}
