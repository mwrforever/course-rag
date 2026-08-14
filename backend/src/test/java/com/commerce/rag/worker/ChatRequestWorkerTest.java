package com.commerce.rag.worker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.commerce.rag.config.StreamProperties;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.service.ChatMessageService;
import com.commerce.rag.service.ChatRunService;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.SseEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
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
    private ChatRunService chatRunService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private ThreadPoolExecutor runPool;

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
                runPool,
                new ObjectMapper(),
                30);

        // 公共 stub：saver.get 返回空 Optional（无历史 checkpoint）
        lenient().when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());

        // 公共 stub：transformer.createMetadataEvent 返回一个真实 SseEvent
        SseEvent metadataEvent = new SseEvent(SseEventType.METADATA, 1, "{}", System.currentTimeMillis());
        lenient().when(transformer.createMetadataEvent(any())).thenReturn(metadataEvent);

        // 公共 stub：transformer.transform 返回空列表（默认不产生事件）
        lenient().when(transformer.transform(any(NodeOutput.class), any())).thenReturn(java.util.List.of());

        // 公共 stub：redisTemplate.opsForStream() 链式调用
        StreamOperations<String, Object, Object> streamOps = mock(StreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
        lenient()
                .when(streamOps.acknowledge(anyString(), anyString(), anyString()))
                .thenReturn(0L);
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
            Long runId, Long sessionId, String userQuery, int historyCursor, NodeOutput lastOutput) throws Exception {
        Method method = ChatRequestWorker.class.getDeclaredMethod(
                "persistMessages", Long.class, Long.class, String.class, int.class, NodeOutput.class);
        method.setAccessible(true);
        method.invoke(worker, runId, sessionId, userQuery, historyCursor, lastOutput);
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
    @DisplayName("finally 清理 — 正常完成后 removeRing + ACK 被调用")
    void processRequest_finallyCleanup() throws Exception {
        NodeOutput mockChunk = mock(NodeOutput.class);
        lenient().when(mockChunk.state()).thenReturn(null);
        when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));

        MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

        invokeProcessRequest(record);

        // finally 块：removeRing 被调用
        verify(bridge).removeRing("100");
        // ACK 被调用
        verify(redisTemplate.opsForStream()).acknowledge(eq("chat:request"), eq("chat-workers"), eq("123-0"));
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
        invokePersistMessages(1L, 1L, "本轮问题", 2, lastOutput);

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
}
