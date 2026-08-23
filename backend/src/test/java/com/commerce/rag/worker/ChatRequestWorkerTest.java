package com.commerce.rag.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.properties.WorkerProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.service.AttachmentOrchestrator;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.MemoryExtractionPipeline;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.SseEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
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

    /** 通过反射调用 private persistMessages（P0-4a 游标去重用例：验证仅持久化游标后的新增消息） */
    private void invokePersistMessages(
            Long runId,
            Long sessionId,
            String userQuery,
            String attachmentsJson,
            int historyCursor,
            NodeOutput lastOutput)
            throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod(
                "persistMessages", Long.class, Long.class, String.class, String.class, int.class, NodeOutput.class);
        method.setAccessible(true);
        method.invoke(worker, runId, sessionId, userQuery, attachmentsJson, historyCursor, lastOutput);
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
        invokePersistMessages(1L, 1L, "本轮问题", "[]", 2, lastOutput);

        // Then: 仅持久化本轮（USER 用户消息 + 本轮新增 assistant）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> inserted = captor.getValue();
        assertEquals(2, inserted.size()); // USER + 本轮 assistant
        assertEquals("USER", inserted.get(0).getRole());
        assertEquals("本轮回答", inserted.get(1).getContent());
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
        invokePersistMessages(1L, 1L, "课程问题", "[]", 0, lastOutput);

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
        when(orchestrator.process(anyList())).thenReturn(AttachmentContext.empty());

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
        when(orchestrator.process(anyList())).thenReturn(context);

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

        // Then: orchestrator 收到解析后的附件记录（url/type 原样透传）
        ArgumentCaptor<List<AttachmentRecord>> attCaptor = ArgumentCaptor.forClass(List.class);
        verify(orchestrator).process(attCaptor.capture());
        assertEquals(1, attCaptor.getValue().size());
        assertEquals("image", attCaptor.getValue().get(0).type());
        assertEquals("0/a.png", attCaptor.getValue().get(0).url());

        // Then: metadata 携带附件上下文（QU/RetrieveNode 消费通道）
        assertEquals(
                context,
                configCaptor
                        .getValue()
                        .metadata(AttachmentOrchestrator.KEY_ATTACHMENT_CONTEXT)
                        .orElse(null));

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
        when(orchestrator.process(anyList())).thenReturn(context);

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
        verify(orchestrator, never()).process(anyList());
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
        when(orchestrator.process(recent)).thenReturn(context);

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
        // orchestrator.process 收到重建出的附件记录
        verify(orchestrator).process(recent);
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
        invokePersistMessages(1L, 1L, "本轮问题", "[]", 0, lastOutput);

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
        invokePersistMessages(1L, 1L, "问题", "[]", 0, lastOutput);
        verify(chatMessageService).batchInsert(anyList());
    }

    // ==================== toChatMessages 分支 ====================

    /** 反射调用 private toChatMessages */
    @SuppressWarnings("unchecked")
    private List<ChatMessage> invokeToChatMessages(Message msg, Long runId, Long sessionId) throws Exception {
        Method method =
                ChatRequestWorker.class.getDeclaredMethod("toChatMessages", Message.class, Long.class, Long.class);
        method.setAccessible(true);
        return (List<ChatMessage>) method.invoke(worker, msg, runId, sessionId);
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
