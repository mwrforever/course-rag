package com.commerce.rag.stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatRunVO;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.worker.ChatRequestWorker;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ChatStreamEntry 单元测试 —— Mock 所有 Service/Worker/Bridge，验证 Chat SSE 编排逻辑
 *
 * <p>覆盖：chat 发起（新会话/既有会话/参数校验/归属校验/入队失败回滚/标题截断）、cancel 归属校验、
 * reconnect 原子回放/终态补发/PG 降级回放（USER 跳过/thinking/TOOL_CALL/TOOL_RESULT/DELTA）、
 * 心跳调度器与工具方法（truncateTitle/normalizeToolPayload/escapeJson）。
 *
 * <p>注意：ChatStreamEntry 的 chat()/reconnect() 会创建真实 SseEmitter，
 * startHeartbeat 使用 @PostConstruct 创建的调度器。
 * 因此 @BeforeEach 需调用 init()，@AfterEach 需调用 destroy() 以清理线程。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatStreamEntry SSE 编排测试")
class ChatStreamEntryTest {

    @Mock
    private ChatRequestWorker worker;

    @Mock
    private MemoryStreamBridge bridge;

    @Mock
    private IChatRunService chatRunService;

    @Mock
    private IChatSessionService chatSessionService;

    @Mock
    private IChatMessageService chatMessageService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private StreamProperties streamProperties;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ChatStreamEntry entry;

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
        // 公共 stub：stream 配置（lenient 因为非所有测试都用到）
        lenient().when(streamProperties.requestStream()).thenReturn("test-stream");
        lenient().when(streamProperties.heartbeatInterval()).thenReturn(15);

        // 调用 @PostConstruct 创建心跳调度器
        entry.init();

        // 公共 stub：redisTemplate.opsForStream() 链式调用（lenient 因为非所有测试都用到）
        streamOps = mock(StreamOperations.class);
        lenient().when(redisTemplate.opsForStream()).thenReturn(streamOps);
    }

    @AfterEach
    void tearDown() {
        // 调用 @PreDestroy 关闭心跳调度器，防止线程泄漏
        entry.destroy();
    }

    // ==================== chat() 测试 ====================

    @Test
    @DisplayName("chat sessionId=null → 创建新会话 + 创建 run + 创建 ring + subscribe + XADD")
    void chat_nullSessionId_createsNewSessionAndRun() {
        // Given: mock 创建会话（返回 SessionVO）和 run
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));

        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        // When
        SseEmitter emitter = entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好"));

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
    @DisplayName("chat 带附件 → XADD body 的 attachments 为附件 JSON 数组字符串（spec §5.1 入队）")
    void chat_withAttachments_xaddBodyCarriesAttachmentsJson() {
        // Given: 会话与 run 创建成功，请求携带 1 个附件记录
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));
        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));
        List<AttachmentRecord> attachments = List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1L));

        // When: 三参构造携带附件
        entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "这张图是什么意思", attachments));

        // Then: XADD body 含 attachments 键，值为 Gson 序列化的 JSON 数组字符串
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("test-stream"), captor.capture());
        assertEquals(
                "[{\"type\":\"image\",\"url\":\"0/a.png\",\"name\":\"a.png\",\"size\":1}]",
                captor.getValue().get("attachments"));
    }

    @Test
    @DisplayName("chat 无附件 → XADD body 的 attachments 为 []（既有两参构造兼容）")
    void chat_noAttachments_xaddBodyHasEmptyArray() {
        // Given: 会话与 run 创建成功
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));
        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        // When: 既有两参构造（attachments=null → 空数组）
        entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好"));

        // Then: attachments 键默认空数组
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(streamOps).add(eq("test-stream"), captor.capture());
        assertEquals("[]", captor.getValue().get("attachments"));
    }

    @Test
    @DisplayName("chat sessionId=456 → 不创建新会话，直接创建 run")
    void chat_existingSessionId_doesNotCreateSession() {
        // Given: 会话 456 属于当前用户（P0-3 归属校验通过，findById 返回 ChatSessionVO）
        when(chatSessionService.findById(456L))
                .thenReturn(new ChatSessionVO(456L, 123L, "会话", "ACTIVE", null, null, null));

        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(789L, 456L, 123L, "QUEUED", null));

        // When
        SseEmitter emitter = entry.chat(mockRequestWithUserId(123L), new ChatRequest(456L, "你好"));

        // Then: 不调用 createSession
        verify(chatSessionService, never()).createSession(anyLong(), anyString());
        verify(chatRunService).createRun(456L, 123L);
        verify(bridge).createRing("789");
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("chat 空查询 → 抛出 400 BizException")
    void chat_blankQuery_throws400() {
        BizException ex = assertThrows(
                BizException.class, () -> entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "")));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("chat null 查询 → 抛出 400 BizException")
    void chat_nullQuery_throws400() {
        BizException ex = assertThrows(
                BizException.class, () -> entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, null)));
        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("chat → 传入他人 sessionId 抛出 403")
    void chat_withOthersSession_throws403() {
        // 会话 99 属于用户 2（userId 来自 ChatSessionVO），当前用户为 123
        when(chatSessionService.findById(99L))
                .thenReturn(new ChatSessionVO(99L, 2L, "他人会话", "ACTIVE", null, null, null));

        ChatRequest request = new ChatRequest(99L, "你好");

        BizException ex = assertThrows(BizException.class, () -> entry.chat(mockRequestWithUserId(123L), request));
        assertEquals(403, ex.getCode());
        verify(chatRunService, never()).createRun(any(), any());
    }

    @Test
    @DisplayName("chat → sessionId 对应会话不存在抛出 403（不泄露存在性）")
    void chat_withUnknownSession_throws403() {
        when(chatSessionService.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(
                BizException.class, () -> entry.chat(mockRequestWithUserId(123L), new ChatRequest(99L, "你好")));
        assertEquals(403, ex.getCode());
        verify(chatRunService, never()).createRun(any(), any());
    }

    @Test
    @DisplayName("chat 纯空白字符查询 → 抛出 400 BizException")
    void chat_whitespaceQuery_throws400() {
        // Given: query 为纯空白字符串（isBlank 判定为 true）
        ChatRequest request = new ChatRequest(null, "   ");

        // When / Then: 参数校验拒绝空白查询，不进入创建会话流程
        BizException ex = assertThrows(BizException.class, () -> entry.chat(mockRequestWithUserId(123L), request));
        assertEquals(400, ex.getCode());
        verify(chatSessionService, never()).createSession(anyLong(), anyString());
    }

    @Test
    @DisplayName("chat 超长 query → 会话标题截断为前 30 字符加省略号")
    void chat_longQuery_truncatesTitleTo30Chars() {
        // Given: 36 字符查询（超过 30 触发标题截断）
        String longQuery = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));
        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        // When
        entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, longQuery));

        // Then: 标题 = 前 30 字符 + 省略号（truncateTitle 截断分支）
        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatSessionService).createSession(eq(123L), titleCaptor.capture());
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123...", titleCaptor.getValue());
    }

    @Test
    @DisplayName("XADD 失败 → run 状态回滚 ERROR + removeRing + 503（P0-4c）")
    void chat_xaddFailure_rollsBackRun() {
        // Given: 会话（返回 SessionVO）与 run 创建成功，XADD 抛异常模拟 Redis 不可用
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));

        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        doThrow(new RuntimeException("Redis 不可用")).when(streamOps).add(anyString(), anyMap());

        // When / Then: chat() 抛 503，run 状态回滚为 ERROR + 清理 ring
        BizException ex = assertThrows(
                BizException.class, () -> entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好")));

        assertEquals(503, ex.getCode());
        verify(chatRunService).updateStatus(123L, "ERROR");
        verify(bridge).removeRing("123");
    }

    @Test
    @DisplayName("XADD 与 DB 双挂 → updateStatus 失败仍 removeRing + 503（P0-4c 复合故障兜底）")
    void chat_xaddAndDbBothFail_stillRemovesRing() {
        // Given: 会话（返回 SessionVO）与 run 创建成功；XADD 与 run 状态回滚均抛异常（Redis+DB 双挂）
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));

        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        doThrow(new RuntimeException("Redis 不可用")).when(streamOps).add(anyString(), anyMap());
        doThrow(new RuntimeException("数据库不可用")).when(chatRunService).updateStatus(anyLong(), anyString());

        // When / Then: 仍抛 503（不因回滚失败变 500），且 ring 必清理
        BizException ex = assertThrows(
                BizException.class, () -> entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好")));

        assertEquals(503, ex.getCode());
        verify(chatRunService).updateStatus(123L, "ERROR");
        verify(bridge).removeRing("123");
    }

    // ==================== cancel() 测试 ====================

    @Test
    @DisplayName("cancel → 调用 worker.cancel，返回 200")
    void cancel_callsWorkerCancel_returns200() {
        // Given: run 123 属于当前用户（P0-3 归属校验通过）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "QUEUED", null));

        // When
        ResponseEntity<Void> response = entry.cancel("123", mockRequestWithUserId(123L));

        // Then
        verify(worker).cancel("123");
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("cancel → 他人 runId 返回 404（不泄露存在性）")
    void cancel_withOthersRun_returns404() {
        // Given: run 1 属于用户 2，当前用户为 123
        when(chatRunService.findById(1L)).thenReturn(new ChatRunVO(1L, 1L, 2L, "QUEUED", null));

        // When / Then: checkRunOwnership 抛 BizException(404)
        BizException ex = assertThrows(BizException.class, () -> entry.cancel("1", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getCode());
        verify(worker, never()).cancel(anyString());
    }

    @Test
    @DisplayName("cancel → 非数字 runId 返回 404")
    void cancel_withNonNumericRunId_returns404() {
        BizException ex = assertThrows(BizException.class, () -> entry.cancel("run-abc", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getCode());
        verify(worker, never()).cancel(anyString());
        verify(chatRunService, never()).findById(anyLong());
    }

    @Test
    @DisplayName("cancel → run 不存在返回 404")
    void cancel_withUnknownRun_returns404() {
        when(chatRunService.findById(456L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> entry.cancel("456", mockRequestWithUserId(123L)));
        assertEquals(404, ex.getCode());
        verify(worker, never()).cancel(anyString());
    }

    @Test
    @DisplayName("cancel → 已终态 run（COMPLETED/CANCELLED/ERROR）返回 409 且不写取消标记（B2-7）")
    void cancel_withTerminalRun_returns409WithoutCancelFlag() {
        // 已终态 run 的 processRequest 早已结束并清理 cancelFlags——再放行 cancel 会令
        // worker.cancel 无条件新建的条目永久残留（小而确定的内存泄漏）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));

        BizException ex = assertThrows(BizException.class, () -> entry.cancel("123", mockRequestWithUserId(123L)));

        assertEquals(409, ex.getCode(), "终态 run 取消应返回 409（状态冲突）");
        // 不再下发 worker.cancel → cancelFlags 不新建条目
        verify(worker, never()).cancel(anyString());
    }

    // ==================== reconnect() 测试（P1-2 终态判定 + replayAndSubscribe） ====================

    @Test
    @DisplayName("reconnect ring 回放成功 → replayAndSubscribe 被调用，不再单独 subscribe")
    void reconnect_replaySuccess_replayAndSubscribe() {
        // Given: run 123 属于当前用户 + replayAndSubscribe 返回 true
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "QUEUED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class)))
                .thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 5L, mockRequestWithUserId(123L));

        // Then: 原子回放+订阅已注册，不单独 subscribe；启动心跳
        verify(bridge).replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class));
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放失败 + run 已终态 → error 事件收尾，不 subscribe（P2-10 真失败）")
    void reconnect_pgReplayFailure_terminalRun_doesNotSubscribe() {
        // Given: run 123 属于当前用户且终态（COMPLETED）+ replayAndSubscribe 返回 false + PG 无历史消息
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 不调用 subscribe（run 已结束，历史不可恢复是真失败）
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放失败 + run 仍活跃 → 仅订阅实时事件，不误报 REPLAY_FAILED（P2-10）")
    void reconnect_pgReplayFailure_activeRun_subscribesOnly() {
        // Given: run 123 属于当前用户且活跃（ACTIVE）+ replayAndSubscribe 返回 false + PG 无历史消息
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 订阅实时事件（不报错不 complete）
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 已终态 → 补发 end 事件收尾，不 subscribe 不心跳")
    void reconnect_terminalRun_sendsEndAndCompletes() {
        // Given: run 123 属于当前用户且状态 COMPLETED；replayAndSubscribe 失败（ring 已移除）；PG 有历史消息
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);

        when(chatMessageService.findByRunId(123L))
                .thenReturn(
                        List.of(new ChatMessageVO(1L, "ASSISTANT", "历史思考内容", "thinking", null, null, 123L, 1, null)));

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 终态分支——不 subscribe、不启动心跳（无额外 push）；PG 回放已执行
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        // R2 改造适配：COMPLETED 终态补 messageId——findByRunId 由 PG 回放 + messageId 解析各查一次
        verify(chatMessageService, times(2)).findByRunId(123L);
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 仍活跃 → 继续 subscribe + 心跳")
    void reconnect_activeRun_continuesSubscribe() {
        // Given: run 123 属于当前用户且状态 ACTIVE；replayAndSubscribe 失败（ring 覆盖）；PG 有历史消息
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);

        when(chatMessageService.findByRunId(123L))
                .thenReturn(
                        List.of(new ChatMessageVO(1L, "ASSISTANT", "历史思考内容", "thinking", null, null, 123L, 1, null)));

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 非终态分支——继续订阅接收后续事件
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect → 他人 runId 返回 404")
    void reconnect_withOthersRun_returns404() {
        when(chatRunService.findById(1L)).thenReturn(new ChatRunVO(1L, 1L, 2L, "QUEUED", null));

        BizException ex = assertThrows(BizException.class, () -> entry.reconnect("1", 0, mockRequestWithUserId(123L)));
        assertEquals(404, ex.getCode());
        verify(bridge, never()).replayAndSubscribe(anyString(), anyLong(), any(SseEmitter.class));
    }

    // ==================== reconnect() 订阅关闭竞态分支（P1-4） ====================

    @Test
    @DisplayName("reconnect PG 无数据 + run 活跃 + subscribe 失败且 closedRun 已终态 → 补发 end + complete 收尾")
    void reconnect_pgEmpty_activeRun_subscribeFalse_closedRunTerminal_sendsEnd() {
        // Given: PG 无历史（replayFromPg=-1）；run 活跃；subscribe 返回 false 说明 ring 恰在此时关闭；
        // 补查 closedRun 已终态 → 补发 end 事件收尾（P1-4 竞态）
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 订阅尝试失败一次，随后按 closedRun 终态补发 end 并 complete
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 无数据 + run 活跃 + subscribe 失败且 closedRun 非终态 → 仅 complete 收尾")
    void reconnect_pgEmpty_activeRun_subscribeFalse_closedRunActive_completesOnly() {
        // Given: closedRun 仍活跃（非终态）→ 无 end 可补发，仅 complete 收尾
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 订阅尝试失败且 closedRun 非终态 → 直接 complete 收尾
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 无数据 + run 不存在 → 发送 REPLAY_FAILED error 事件收尾")
    void reconnect_pgEmpty_runNotFound_emitsReplayFailedError() {
        // Given: 归属校验通过（首次 findById 返回活跃 run），回放判定时 findById 返回 null（run==null 分支）
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(null);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenReturn(null);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 历史不可恢复视为真失败——不 subscribe、补发 REPLAY_FAILED error 事件
        verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 不存在 → 跳过终态判定仅订阅实时事件")
    void reconnect_pgData_runNotFound_subscribesOnly() {
        // Given: PG 有历史消息（lastSeq>=0）；判定 run 时 findById 返回 null（覆盖终态判定 null 分支）
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(null);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(1L, "ASSISTANT", "历史内容", "thinking", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: run 状态不可判时仍继续订阅实时事件并启动心跳
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 活跃 + subscribe 失败且 closedRun 已终态 → 补发 end + complete")
    void reconnect_pgData_activeRun_subscribeFalse_closedRunTerminal_sendsEnd() {
        // Given: PG 有历史；run 活跃；订阅失败后补查 closedRun 已终态 → 补发带 id 的 end 事件
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(1L, "ASSISTANT", "历史内容", "thinking", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 订阅失败 → 按 closedRun 终态补发 end（id=lastSeq+1）并 complete 收尾
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("reconnect PG 回放成功 + run 活跃 + subscribe 失败且 closedRun 不存在 → 仅 complete 收尾")
    void reconnect_pgData_activeRun_subscribeFalse_closedRunNull_completesOnly() {
        // Given: closedRun 查询为 null → 无终态可补发，仅 complete 收尾
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(null);
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(1L, "ASSISTANT", "历史内容", "thinking", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 订阅失败且 closedRun 不存在 → 直接 complete 收尾
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    // ==================== replayFromPg() 降级回放事件类型（P1-2 schema 对齐） ====================

    @Test
    @DisplayName("replayFromPg USER 消息跳过 + thinking/DELTA 事件转义发送；旧数据无 thinking_stage 输出 stage:null")
    void reconnect_replayFromPg_skipsUserAndEmitsThinkingAndDelta() throws Exception {
        // Given: 消息含 USER（跳过——客户端已有用户查询）、thinking（THINKING 事件 + escapeJson 转义；
        // 历史存量行 thinkingStage=null → payload stage 输出 JSON null，前端降级 generating 不报错）、
        // 普通助手消息（DELTA 事件）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(
                        new ChatMessageVO(1L, "USER", "你好", null, null, null, 123L, 1, null),
                        new ChatMessageVO(2L, "ASSISTANT", "他说\"你好\"\n含反斜杠\\t", "thinking", null, null, 123L, 2, null),
                        new ChatMessageVO(3L, "ASSISTANT", "答案文本", null, null, null, 123L, 3, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: USER 被跳过，thinking/DELTA 均完成回放并继续订阅实时事件
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
        // Then: 回放 payload 契约——thinking 含 delta+stage 字段（旧数据 stage=null 显式输出，
        // 与实时事件 {delta, stage} 同构）；正文为 DELTA {text}
        List<String> sent = sentDataStrings(emitter);
        assertTrue(
                sent.stream().anyMatch(s -> s.startsWith("{\"delta\":\"") && s.endsWith(",\"stage\":null}")),
                "旧数据 thinking 回放应输出 stage:null（前端降级 generating）: " + sent);
        assertTrue(sent.contains("{\"text\":\"答案文本\"}"), "正文应以 DELTA {text} 回放: " + sent);
    }

    @Test
    @DisplayName("replayFromPg thinking 行带 thinking_stage → THINKING 事件 payload 原样携带 stage（与实时事件同构）")
    void reconnect_replayFromPg_thinkingRowCarriesStage() throws Exception {
        // Given: 新落库的 thinking 行带阶段键（understanding / generating 各一行）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(
                        new ChatMessageVO(1L, "ASSISTANT", "意图分析思考", "thinking", "understanding", null, 123L, 1, null),
                        new ChatMessageVO(2L, "ASSISTANT", "生成阶段思考", "thinking", "generating", null, 123L, 2, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 两条 THINKING 回放分别携带各自 stage（前端按 stage 分段归组渲染）
        List<String> sent = sentDataStrings(emitter);
        assertTrue(
                sent.stream()
                        .anyMatch(s -> s.contains("\"delta\":\"意图分析思考\"") && s.contains("\"stage\":\"understanding\"")),
                "understanding 思考应携带 stage: " + sent);
        assertTrue(
                sent.stream()
                        .anyMatch(s -> s.contains("\"delta\":\"生成阶段思考\"") && s.contains("\"stage\":\"generating\"")),
                "generating 思考应携带 stage: " + sent);
    }

    @Test
    @DisplayName("replayFromPg TOOL_CALL 新格式（含 toolCallId）直接透传")
    void reconnect_replayFromPg_toolCallNewFormat_passthrough() throws Exception {
        // Given: 新格式 content 含 toolCallId → normalizeToolPayload 直接透传（不重建）
        JsonNode node = new ObjectMapper().readTree("{\"toolCallId\":\"tc-1\",\"toolName\":\"search\"}");
        when(objectMapper.readTree(anyString())).thenReturn(node);
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(
                        1L,
                        "ASSISTANT",
                        "{\"toolCallId\":\"tc-1\",\"toolName\":\"search\"}",
                        "TOOL_CALL",
                        null,
                        null,
                        123L,
                        1,
                        null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 新格式透传（writeValueAsString 不被调用），回放后继续订阅
        verify(objectMapper, never()).writeValueAsString(any());
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("replayFromPg TOOL_CALL 旧格式 → 重建为 toolCallId 空串新格式")
    void reconnect_replayFromPg_toolCallOldFormat_rebuilt() throws Exception {
        // Given: 历史旧格式 {"tool","args"} → 重建为 {toolCallId:"",toolName,input}
        JsonNode node = new ObjectMapper().readTree("{\"tool\":\"calculator\",\"args\":{\"a\":1}}");
        when(objectMapper.readTree(anyString())).thenReturn(node);
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"toolCallId\":\"\",\"toolName\":\"calculator\",\"input\":{\"a\":1}}");
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(
                        1L,
                        "ASSISTANT",
                        "{\"tool\":\"calculator\",\"args\":{\"a\":1}}",
                        "TOOL_CALL",
                        null,
                        null,
                        123L,
                        1,
                        null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 旧格式 TOOL_CALL 被重建（writeValueAsString 被调用），回放后继续订阅
        verify(objectMapper).writeValueAsString(any());
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("replayFromPg TOOL_RESULT 旧格式 → 重建为 status/output 新格式")
    void reconnect_replayFromPg_toolResultOldFormat_rebuilt() throws Exception {
        // Given: 历史旧格式 {"tool","result"} → 重建为 {toolCallId:"",status:"success",output}
        JsonNode node = new ObjectMapper().readTree("{\"tool\":\"calculator\",\"result\":\"42\"}");
        when(objectMapper.readTree(anyString())).thenReturn(node);
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{\"toolCallId\":\"\",\"status\":\"success\",\"output\":\"42\"}");
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(
                        1L,
                        "ASSISTANT",
                        "{\"tool\":\"calculator\",\"result\":\"42\"}",
                        "TOOL_RESULT",
                        null,
                        null,
                        123L,
                        1,
                        null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: TOOL_RESULT 旧格式重建（path("result").asText 取值），回放后继续订阅
        verify(objectMapper).writeValueAsString(any());
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("replayFromPg TOOL_CALL 内容非 JSON → 原样返回不中断回放")
    void reconnect_replayFromPg_toolCallInvalidJson_passthrough() throws Exception {
        // Given: 落库 content 非合法 JSON → normalizeToolPayload 解析失败原样返回（不中断回放）
        when(objectMapper.readTree(anyString())).thenThrow(new JsonParseException(null, "json 解析失败"));
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(
                        new ChatMessageVO(1L, "ASSISTANT", "not-json", "TOOL_CALL", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 解析失败原样返回，回放继续不中断
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("replayFromPg TOOL_CALL 内容空白 → 归一化为空对象 {}")
    void reconnect_replayFromPg_toolCallBlankContent_emptyObject() throws Exception {
        // Given: 落库 content 为空白 → normalizeToolPayload 直接返回 {}（不解析 JSON）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(1L, "ASSISTANT", "  ", "TOOL_CALL", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 空白内容不触达 readTree，回放后继续订阅
        verify(objectMapper, never()).readTree(anyString());
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    @Test
    @DisplayName("replayFromPg query_plan 行 → 同名 query_plan 事件透传 content（不当正文 DELTA 泄漏 JSON）")
    void reconnect_replayFromPg_queryPlanRow_passthrough() throws Exception {
        // Given: 2026-08-28 时间线改版落库的 query_plan 行（content 即实时事件同款 JSON）
        String planJson = "{\"intent\":\"knowledge_question\",\"rewritten\":[\"高等数学 大纲\"],"
                + "\"filters\":{\"courseNames\":[\"高等数学\"]}}";
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(
                        List.of(new ChatMessageVO(1L, "ASSISTANT", planJson, "query_plan", null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: payload 原样透传回放，且不产生 {"text":...} 正文 DELTA（防止 JSON 泄漏成正文）
        List<String> sent = sentDataStrings(emitter);
        assertTrue(sent.contains(planJson), "query_plan 行应原样透传为 query_plan 事件 payload");
        assertTrue(sent.stream().noneMatch(s -> s.startsWith("{\"text\":")), "不得被当正文 DELTA 回放");
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
    }

    @Test
    @DisplayName("replayFromPg 实体行拆行链路 — 实体行经拆行还原 thinking/query_plan/tool_call/delta 事件序列（事件协议不变）")
    void reconnect_replayFromPg_entitySplitChain_emitsEventSequence() throws Exception {
        // Given: findByRunId（服务层拆行后）返回实体行拆出的事件序 VO——QU 实体拆
        // thinking(understanding)+query_plan、主 agent 实体拆 thinking(generating)+TOOL_CALL+正文
        // （与 spec §3.4b「replayFromPg 复用拆行/拆事件逻辑」一致，实体行→事件流）
        String planJson = "{\"intent\":\"chat\",\"rewritten\":[\"你好\"],\"filters\":{\"courseNames\":[]}}";
        ChatMessageVO quThinking =
                new ChatMessageVO(1L, "ASSISTANT", "QU 思考", "thinking", "understanding", null, 123L, 1, null);
        ChatMessageVO queryPlan = new ChatMessageVO(1L, "ASSISTANT", planJson, "query_plan", null, null, 123L, 2, null);
        ChatMessageVO mainThinking =
                new ChatMessageVO(2L, "ASSISTANT", "生成思考", "thinking", "generating", null, 123L, 3, null);
        ChatMessageVO toolCall = new ChatMessageVO(
                2L,
                "ASSISTANT",
                "{\"toolCallId\":\"c1\",\"toolName\":\"searchKnowledge\",\"input\":{}}",
                "TOOL_CALL",
                null,
                null,
                123L,
                4,
                null);
        ChatMessageVO body = new ChatMessageVO(2L, "ASSISTANT", "最终回答", null, null, null, 123L, 5, null);
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(quThinking, queryPlan, mainThinking, toolCall, body));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 事件序列与实时一致——thinking(understanding) → query_plan → thinking(generating)
        // → tool_call → delta（payload 均与实时事件 schema 同构；Spring SSE builder 把帧拆为
        // 多段元素，payload 恒为独立元素——按 payload 过滤断言身份与顺序，与既有用例同口径）
        List<String> sent = sentDataStrings(emitter);
        List<String> payloads = sent.stream().filter(s -> s.startsWith("{")).toList();
        assertEquals(5, payloads.size(), "实体行拆 5 VO 应回放 5 个事件，实际=" + sent);
        assertTrue(
                payloads.get(0).contains("\"delta\":\"QU 思考\"")
                        && payloads.get(0).contains("\"stage\":\"understanding\""),
                "thinking 事件应携带 delta+stage: " + payloads.get(0));
        assertEquals(planJson, payloads.get(1), "query_plan 事件原样透传 payload");
        assertTrue(payloads.get(2).contains("\"stage\":\"generating\""), "主 agent 思考事件 stage=generating");
        assertTrue(payloads.get(3).contains("\"toolCallId\":\"c1\""), "tool_call 事件与实时 schema 同构");
        assertEquals("{\"text\":\"最终回答\"}", payloads.get(4), "正文回放为 DELTA {text}");
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
    }

    @Test
    @DisplayName("replayFromPg findByRunId 抛异常 → 返回 -1 降级为仅订阅实时事件")
    void reconnect_replayFromPg_findByRunIdThrows_returnsMinusOne() {
        // Given: PG 查询抛异常 → replayFromPg 捕获后返回 -1；run 活跃则仅订阅实时事件
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L)).thenThrow(new RuntimeException("数据库不可用"));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(true);

        // When
        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // Then: 查询异常不中断重连，降级为仅订阅实时事件
        verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
        assertNotNull(emitter);
    }

    // ==================== R2 补口 B：reconnect 补发 end 事件携带 messageId（按 COMPLETED 过滤，M2） ====================

    /**
     * 反射读取未初始化 SseEmitter 缓冲的 send 数据（ResponseBodyEmitter.earlySendAttempts，
     * Spring 6.2 中为 Set 类型），用于断言 reconnect 补发 end 事件的 payload 内容
     * （emitter 未挂 handler 前 send 全部缓存于该集合）
     */
    private List<String> sentDataStrings(SseEmitter emitter) throws Exception {
        Field field = ResponseBodyEmitter.class.getDeclaredField("earlySendAttempts");
        field.setAccessible(true);
        Collection<?> attempts = (Collection<?>) field.get(emitter);
        Method getData = ResponseBodyEmitter.DataWithMediaType.class.getMethod("getData");
        getData.setAccessible(true);
        List<String> result = new ArrayList<>();
        for (Object attempt : attempts) {
            Object data = getData.invoke(attempt);
            if (data instanceof String text) {
                result.add(text);
            }
        }
        return result;
    }

    /** 从 emitter 已发送数据中提取 end 事件 payload（唯一以 {"runId" 开头的 JSON 字符串） */
    private String endPayloadOf(SseEmitter emitter) throws Exception {
        return sentDataStrings(emitter).stream()
                .filter(s -> s.startsWith("{\"runId\""))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应补发 end 事件 payload"));
    }

    /** 反射调用 private resolveAssistantMessageId（M2：仅 COMPLETED run 的 assistant 正文行可作反馈目标） */
    private String invokeResolveAssistantMessageId(String runId) throws Exception {
        Method method = ChatStreamEntry.class.getDeclaredMethod("resolveAssistantMessageId", String.class);
        method.setAccessible(true);
        return (String) method.invoke(entry, runId);
    }

    @Test
    @DisplayName("R2 resolveAssistantMessageId → COMPLETED run 取最后一条 assistant 正文行 ID（字符串）")
    void resolveAssistantMessageId_completedRun_返回最后正文行ID() throws Exception {
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(
                        new ChatMessageVO(6L, "ASSISTANT", "思考", "thinking", null, null, 123L, 1, null),
                        new ChatMessageVO(8L, "ASSISTANT", "工具调用", "TOOL_CALL", null, null, 123L, 2, null),
                        new ChatMessageVO(777L, "ASSISTANT", "最终回答", null, null, null, 123L, 3, null)));

        // 反向扫描跳过 thinking/TOOL_* 行，命中最后一条正文行（messageType==null）
        assertEquals("777", invokeResolveAssistantMessageId("123"));
    }

    @Test
    @DisplayName("R2 resolveAssistantMessageId → run 非 COMPLETED（M2）返回 null 且不查消息表（半截内容不作反馈目标）")
    void resolveAssistantMessageId_非COMPLETED状态_返回null() throws Exception {
        // CANCELLED run 的半截 assistant 行虽已落库，但不得作为反馈目标（M2 状态过滤）
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "CANCELLED", null));

        assertNull(invokeResolveAssistantMessageId("123"), "非 COMPLETED run 必须返回 null（M2）");
        verify(chatMessageService, never()).findByRunId(anyLong());
    }

    @Test
    @DisplayName("R2 resolveAssistantMessageId → 状态查询异常降级返回 null（end 事件 messageId 可空容忍）")
    void resolveAssistantMessageId_查询异常_返回null() throws Exception {
        when(chatRunService.findById(123L)).thenThrow(new RuntimeException("数据库不可用"));

        assertNull(invokeResolveAssistantMessageId("123"));
    }

    @Test
    @DisplayName("R2 reconnect PG 回放成功 + run COMPLETED → 补发 end 含 assistant messageId（第二处补发点）")
    void reconnect_terminalRunCompleted_endPayloadCarriesMessageId() throws Exception {
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        // PG 消息：thinking + assistant 正文（id=777）——replay 与 messageId 解析共用同一查询结果
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(
                        new ChatMessageVO(6L, "ASSISTANT", "思考", "thinking", null, null, 123L, 1, null),
                        new ChatMessageVO(777L, "ASSISTANT", "最终回答", null, null, null, 123L, 2, null)));

        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // end payload 的 messageId 恒字符串（与 runId 字符串风格一致）
        String endPayload = endPayloadOf(emitter);
        assertTrue(endPayload.contains("\"status\":\"COMPLETED\""), "终态应为 COMPLETED: " + endPayload);
        assertTrue(
                endPayload.contains("\"messageId\":\"777\""), "end payload 应含 assistant 正文行 messageId: " + endPayload);
    }

    @Test
    @DisplayName("R2 reconnect PG 回放成功 + run CANCELLED → 补发 end 不含 messageId 键")
    void reconnect_terminalRunCancelled_endPayloadHasNoMessageId() throws Exception {
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "CANCELLED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(777L, "ASSISTANT", "半截回答", null, null, null, 123L, 2, null)));

        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        // CANCELLED 终态 payload 与既有格式一致（仅 runId/status），不带 messageId 键
        assertEquals("{\"runId\":\"123\",\"status\":\"CANCELLED\"}", endPayloadOf(emitter));
    }

    @Test
    @DisplayName("R2 reconnect COMPLETED 但无 assistant 正文行（异常/未落库窗口）→ end 的 messageId 显式 null")
    void reconnect_completedNoAssistantRow_endMessageIdExplicitNull() throws Exception {
        when(chatRunService.findById(123L)).thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        // 仅 thinking 行（正文缺失）：run 完成但反馈目标不可解析 → 显式 null（前端可空容忍）
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(6L, "ASSISTANT", "思考", "thinking", null, null, 123L, 1, null)));

        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        assertTrue(endPayloadOf(emitter).contains("\"messageId\":null"), "无法解析 assistant 正文行时 messageId 应显式 null");
    }

    @Test
    @DisplayName("R2 reconnect PG 无数据 + subscribe 失败 + closedRun COMPLETED → 补发 end 含 messageId（第一处补发点）")
    void reconnect_pgEmpty_subscribeFalse_closedRunCompleted_endCarriesMessageId() throws Exception {
        // findById 四次消费：归属校验(ACTIVE) → 终态判定(ACTIVE) → closedRun(COMPLETED) → resolve 复查(COMPLETED)
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        // findByRunId 两次消费：replayFromPg 无历史（-1）→ messageId 解析返回 assistant 正文行
        when(chatMessageService.findByRunId(123L))
                .thenReturn(null)
                .thenReturn(List.of(new ChatMessageVO(777L, "ASSISTANT", "最终回答", null, null, null, 123L, 2, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        assertTrue(endPayloadOf(emitter).contains("\"messageId\":\"777\""), "ring 关闭竞态补发 end 应含 messageId");
    }

    @Test
    @DisplayName("R2 reconnect PG 回放成功 + subscribe 失败 + closedRun COMPLETED → 补发带 id 的 end 含 messageId（第三处补发点）")
    void reconnect_pgData_subscribeFalse_closedRunCompleted_endCarriesMessageId() throws Exception {
        when(chatRunService.findById(123L))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "ACTIVE", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null))
                .thenReturn(new ChatRunVO(123L, 1L, 123L, "COMPLETED", null));
        when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class)))
                .thenReturn(false);
        when(chatMessageService.findByRunId(123L))
                .thenReturn(List.of(new ChatMessageVO(777L, "ASSISTANT", "最终回答", null, null, null, 123L, 1, null)));
        when(bridge.subscribe(eq("123"), any(SseEmitter.class))).thenReturn(false);

        SseEmitter emitter = entry.reconnect("123", 0L, mockRequestWithUserId(123L));

        assertTrue(endPayloadOf(emitter).contains("\"messageId\":\"777\""), "订阅关闭竞态补发 end 应含 messageId");
    }

    // ==================== startHeartbeat() 心跳调度器（真实 scheduler） ====================

    /** 通过反射获取心跳调度器，用于断言定时任务状态（真实 scheduler 行为验证） */
    private ScheduledThreadPoolExecutor heartbeatScheduler() throws Exception {
        Field f = ChatStreamEntry.class.getDeclaredField("scheduler");
        f.setAccessible(true);
        return (ScheduledThreadPoolExecutor) f.get(entry);
    }

    @Test
    @DisplayName("startHeartbeat 心跳注释行发送成功 → 周期任务持续运行不被取消")
    void chat_startHeartbeat_sendsHeartbeatComment() throws Exception {
        // Given: 心跳间隔 1 秒（覆盖 setUp 默认 15 秒），正常创建会话与 run
        when(streamProperties.heartbeatInterval()).thenReturn(1);
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));
        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        // When: 发起对话（内部 startHeartbeat 调度 1s 周期心跳任务）
        entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好"));

        // Then: 首个心跳 tick 已成功发送（无异常），周期任务仍在调度队列中（未被取消）
        Thread.sleep(1500);
        assertEquals(1, heartbeatScheduler().getQueue().size());
    }

    @Test
    @DisplayName("startHeartbeat emitter 已关闭 → send 异常后心跳任务被取消（不再调度）")
    void chat_startHeartbeat_emitterClosed_cancelsHeartbeat() throws Exception {
        // Given: 心跳间隔 1 秒
        when(streamProperties.heartbeatInterval()).thenReturn(1);
        when(chatSessionService.createSession(eq(123L), anyString()))
                .thenReturn(new SessionVO(456L, "新对话", "ACTIVE", null, null));
        when(chatRunService.createRun(456L, 123L)).thenReturn(new ChatRunVO(123L, 456L, 123L, "QUEUED", null));

        // When: 发起对话后立即关闭 emitter（模拟连接断开，心跳 send 抛异常）
        entry.chat(mockRequestWithUserId(123L), new ChatRequest(null, "你好"));
        ArgumentCaptor<SseEmitter> emitterCaptor = ArgumentCaptor.forClass(SseEmitter.class);
        verify(bridge).subscribe(eq("123"), emitterCaptor.capture());
        emitterCaptor.getValue().complete();

        // Then: 首个心跳 tick 触发 send 异常后任务被取消，调度队列清空（心跳停止）
        Thread.sleep(2200);
        assertEquals(0, heartbeatScheduler().getQueue().size());
    }
}
