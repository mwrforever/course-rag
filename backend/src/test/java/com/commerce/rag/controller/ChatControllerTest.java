package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
import com.commerce.rag.worker.ChatRequestWorker;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ChatController 单元测试 —— Mock 所有 Service/Worker/Bridge，验证 SSE 端点逻辑
 *
 * <p>注意：ChatController 的 chat()/reconnect() 会创建真实 SseEmitter，
 * startHeartbeat 使用 @PostConstruct 创建的调度器。
 * 因此 @BeforeEach 需调用 init()，@AfterEach 需调用 destroy() 以清理线程。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController SSE 端点测试")
class ChatControllerTest {

    @Mock
    private ChatRequestWorker worker;

    @Mock
    private MemoryStreamBridge bridge;

    @Mock
    private ChatRunService chatRunService;

    @Mock
    private ChatSessionService chatSessionService;

    @Mock
    private ChatMessageService chatMessageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    private ChatController controller;
    private StreamProperties streamProperties;

    @SuppressWarnings("unchecked")
    private StreamOperations<String, Object, Object> streamOps;

    /** 创建模拟 HttpServletRequest，getAttribute("currentUserId") 返回 123L */
    private HttpServletRequest mockRequestWithUserId(Long userId) {
        HttpServletRequest mockRequest = mock(HttpServletRequest.class);
        when(mockRequest.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        return mockRequest;
    }

    @BeforeEach
    void setUp() {
        streamProperties = new StreamProperties("test-stream", "test-group", 10, 1000, 300, 15, 256);

        controller = new ChatController(
                worker,
                bridge,
                chatRunService,
                chatSessionService,
                chatMessageService,
                redisTemplate,
                streamProperties);

        // 调用 @PostConstruct 创建心跳调度器
        controller.init();

        // 公共 stub：redisTemplate.opsForStream() 链式调用（lenient 因为非所有测试都用到）
        streamOps = mock(StreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
    }

    @AfterEach
    void tearDown() {
        // 调用 @PreDestroy 关闭心跳调度器，防止线程泄漏
        controller.destroy();
    }

    // ==================== chat() 测试 ====================

    @Test
    @DisplayName("chat sessionId=null → 创建新会话 + 创建 run + 创建 ring + subscribe + XADD")
    void chat_nullSessionId_createsNewSessionAndRun() {
        // Given: mock 创建会话和 run
        ChatSession mockSession = mock(ChatSession.class);
        when(mockSession.getId()).thenReturn(456L);
        when(chatSessionService.createSession(eq(123L), anyString())).thenReturn(mockSession);

        ChatRun mockRun = mock(ChatRun.class);
        when(mockRun.getId()).thenReturn(123L);
        when(chatRunService.createRun(456L, 123L)).thenReturn(mockRun);

        // When
        SseEmitter emitter = controller.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好"));

        // Then: 验证完整流程
        verify(chatSessionService).createSession(eq(123L), anyString());
        verify(chatRunService).createRun(456L, 123L);
        verify(bridge).createRing("123");
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        verify(streamOps).add(eq("test-stream"), any(Map.class));
        verify(chatSessionService).updateLastMessageAt(456L);
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("chat sessionId=456 → 不创建新会话，直接创建 run")
    void chat_existingSessionId_doesNotCreateSession() {
        // Given: 会话 456 属于当前用户（P0-3 归属校验通过）
        ChatSession existingSession = mock(ChatSession.class);
        when(existingSession.getUserId()).thenReturn(123L);
        when(chatSessionService.findById(456L)).thenReturn(existingSession);

        ChatRun mockRun = mock(ChatRun.class);
        when(mockRun.getId()).thenReturn(789L);
        when(chatRunService.createRun(456L, 123L)).thenReturn(mockRun);

        // When
        SseEmitter emitter = controller.chat(mockRequestWithUserId(123L), new ChatRequest(456L, "你好"));

        // Then: 不调用 createSession
        verify(chatSessionService, never()).createSession(anyLong(), anyString());
        verify(chatRunService).createRun(456L, 123L);
        verify(bridge).createRing("789");
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("chat 空查询 → 抛出 400 ResponseStatusException")
    void chat_blankQuery_throws400() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.chat(mockRequestWithUserId(123L), new ChatRequest(null, "")));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("chat null 查询 → 抛出 400 ResponseStatusException")
    void chat_nullQuery_throws400() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.chat(mockRequestWithUserId(123L), new ChatRequest(null, null)));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("chat → 传入他人 sessionId 抛出 403")
    void chat_withOthersSession_throws403() {
        // 现有 mock：AuthInterceptor attribute userId=1（本测试用 123）
        ChatSession othersSession = new ChatSession();
        othersSession.setId(99L);
        othersSession.setUserId(2L); // 属于用户 2
        when(chatSessionService.findById(99L)).thenReturn(othersSession);

        ChatRequest request = new ChatRequest(99L, "你好");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.chat(mockRequestWithUserId(123L), request));
        assertEquals(403, ex.getStatusCode().value());
        verify(chatRunService, never()).createRun(any(), any());
    }

    @Test
    @DisplayName("XADD 失败 → run 状态回滚 ERROR + removeRing + 503（P0-4c）")
    void chat_xaddFailure_rollsBackRun() {
        // Given: 会话与 run 创建成功（复用既有 mock 构造），XADD 抛异常模拟 Redis 不可用
        ChatSession mockSession = mock(ChatSession.class);
        when(mockSession.getId()).thenReturn(456L);
        when(chatSessionService.createSession(eq(123L), anyString())).thenReturn(mockSession);

        ChatRun mockRun = mock(ChatRun.class);
        when(mockRun.getId()).thenReturn(123L);
        when(chatRunService.createRun(456L, 123L)).thenReturn(mockRun);

        doThrow(new RuntimeException("Redis 不可用")).when(streamOps).add(anyString(), anyMap());

        // When / Then: chat() 抛 503，run 状态回滚为 ERROR + 清理 ring
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好")));

        assertEquals(503, ex.getStatusCode().value());
        verify(chatRunService).updateStatus(123L, "ERROR");
        verify(bridge).removeRing("123");
    }

    @Test
    @DisplayName("XADD 与 DB 双挂 → updateStatus 失败仍 removeRing + 503（P0-4c 复合故障兜底）")
    void chat_xaddAndDbBothFail_stillRemovesRing() {
        // Given: 会话与 run 创建成功；XADD 与 run 状态回滚均抛异常（Redis+DB 双挂）
        ChatSession mockSession = mock(ChatSession.class);
        when(mockSession.getId()).thenReturn(456L);
        when(chatSessionService.createSession(eq(123L), anyString())).thenReturn(mockSession);

        ChatRun mockRun = mock(ChatRun.class);
        when(mockRun.getId()).thenReturn(123L);
        when(chatRunService.createRun(456L, 123L)).thenReturn(mockRun);

        doThrow(new RuntimeException("Redis 不可用")).when(streamOps).add(anyString(), anyMap());
        doThrow(new RuntimeException("数据库不可用")).when(chatRunService).updateStatus(anyLong(), anyString());

        // When / Then: 仍抛 503（不因回滚失败变 500），且 ring 必清理
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> controller.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好")));

        assertEquals(503, ex.getStatusCode().value());
        verify(chatRunService).updateStatus(123L, "ERROR");
        verify(bridge).removeRing("123");
    }

    // ==================== cancel() 测试 ====================

    @Test
    @DisplayName("cancel → 调用 worker.cancel，返回 200")
    void cancel_callsWorkerCancel_returns200() {
        // Given: run 123 属于当前用户（P0-3 归属校验通过）
        ChatRun ownRun = new ChatRun();
        ownRun.setId(123L);
        ownRun.setUserId(123L);
        when(chatRunService.findById(123L)).thenReturn(ownRun);

        // When
        ResponseEntity<Void> response = controller.cancel("123", mockRequestWithUserId(123L));

        // Then
        verify(worker).cancel("123");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("cancel → 他人 runId 返回 404（不泄露存在性）")
    void cancel_withOthersRun_returns404() {
        // Given: run 1 属于用户 2，当前用户为 123
        ChatRun othersRun = new ChatRun();
        othersRun.setId(1L);
        othersRun.setUserId(2L);
        when(chatRunService.findById(1L)).thenReturn(othersRun);

        // When / Then: checkRunOwnership 抛 ResponseStatusException(404)
        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.cancel("1", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getStatusCode().value());
        verify(worker, never()).cancel(anyString());
    }

    @Test
    @DisplayName("cancel → 非数字 runId 返回 404")
    void cancel_withNonNumericRunId_returns404() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.cancel("run-abc", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getStatusCode().value());
        verify(worker, never()).cancel(anyString());
        verify(chatRunService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("cancel → run 不存在返回 404")
    void cancel_withUnknownRun_returns404() {
        when(chatRunService.findById(456L)).thenReturn(null);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.cancel("456", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getStatusCode().value());
        verify(worker, never()).cancel(anyString());
    }

    // ==================== reconnect() 测试（P1-2 终态判定 + replayAndSubscribe） ====================

    @Test
    @DisplayName("reconnect ring 回放成功 → replayAndSubscribe 被调用，不再单独 subscribe")
    void reconnect_replaySuccess_replayAndSubscribe() {
        // Given: run 123 属于当前用户 + replayAndSubscribe 返回 true
        ChatRun ownRun = new ChatRun();
        ownRun.setId(123L);
        ownRun.setUserId(123L);
        when(chatRunService.findById(123L)).thenReturn(ownRun);
        when(bridge.replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class)))
                .thenReturn(true);

        // When
        SseEmitter emitter = controller.reconnect("123", 5L, mockRequestWithUserId(123L));

        // Then: 原子回放+订阅已注册，不单独 subscribe；启动心跳
        verify(bridge).replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class));
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放失败 → 返回 SseEmitter（含 error 事件），不 subscribe")
    void reconnect_pgReplayFailure_doesNotSubscribe() {
        // Given: run 123 属于当前用户 + replayAndSubscribe 返回 false + PG 无历史消息
        ChatRun ownRun = new ChatRun();
        ownRun.setId(123L);
        ownRun.setUserId(123L);
        when(chatRunService.findById(123L)).thenReturn(ownRun);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);

        // When
        SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 不调用 subscribe
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 已终态 → 补发 end 事件收尾，不 subscribe 不心跳")
    void reconnect_terminalRun_sendsEndAndCompletes() {
        // Given: run 123 属于当前用户且状态 COMPLETED；replayAndSubscribe 失败（ring 已移除）；PG 有历史消息
        ChatRun ownRun = new ChatRun();
        ownRun.setId(123L);
        ownRun.setUserId(123L);
        ownRun.setStatus("COMPLETED");
        when(chatRunService.findById(123L)).thenReturn(ownRun);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setMessageType("thinking");
        assistantMsg.setContent("历史思考内容");
        when(chatMessageService.findByRunId(123L)).thenReturn(List.of(assistantMsg));

        // When
        SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 终态分支——不 subscribe、不启动心跳（无额外 push）；PG 回放已执行
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        verify(chatMessageService).findByRunId(123L);
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 仍活跃 → 继续 subscribe + 心跳")
    void reconnect_activeRun_continuesSubscribe() {
        // Given: run 123 属于当前用户且状态 ACTIVE；replayAndSubscribe 失败（ring 覆盖）；PG 有历史消息
        ChatRun ownRun = new ChatRun();
        ownRun.setId(123L);
        ownRun.setUserId(123L);
        ownRun.setStatus("ACTIVE");
        when(chatRunService.findById(123L)).thenReturn(ownRun);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);

        ChatMessage assistantMsg = new ChatMessage();
        assistantMsg.setRole("ASSISTANT");
        assistantMsg.setMessageType("thinking");
        assistantMsg.setContent("历史思考内容");
        when(chatMessageService.findByRunId(123L)).thenReturn(List.of(assistantMsg));

        // When
        SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 非终态分支——继续订阅接收后续事件
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect → 他人 runId 返回 404")
    void reconnect_withOthersRun_returns404() {
        ChatRun othersRun = new ChatRun();
        othersRun.setId(1L);
        othersRun.setUserId(2L);
        when(chatRunService.findById(1L)).thenReturn(othersRun);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.reconnect("1", 0, mockRequestWithUserId(123L)));
        assertEquals(404, ex.getStatusCode().value());
        verify(bridge, never()).replayAndSubscribe(anyString(), anyLong(), any(SseEmitter.class));
    }

    // ==================== ExceptionHandler 测试 ====================

    @Test
    @DisplayName("handleConcurrentRun → 返回 409 Conflict + JSON 错误体")
    void handleConcurrentRun_returns409() {
        ResponseEntity<Map<String, String>> response =
                controller.handleConcurrentRun(new ConcurrentRunException("测试冲突"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("CONFLICT", response.getBody().get("error"));
        assertEquals("该会话已有正在进行的对话", response.getBody().get("message"));
    }
}
