package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.service.ChatMessageService;
import com.commerce.rag.service.ChatSessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminSessionController 单元测试 —— B 端会话管理端点 H1-H4
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSessionController 会话管理端点测试")
class AdminSessionControllerTest {

    @Mock
    private ChatSessionService sessionService;

    @Mock
    private ChatMessageService messageService;

    private AdminSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminSessionController(sessionService, messageService);
    }

    private ChatSession session(Long id) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setUserId(5L);
        s.setTitle("会话" + id);
        s.setStatus("ACTIVE");
        s.setLastMessageAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        s.setModel("qwen3.8-max");
        s.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        return s;
    }

    private ChatMessage message(Long id) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(1L);
        m.setRole("user");
        m.setContent("问题" + id);
        m.setMessageType("TEXT");
        m.setIntentType("knowledge_question");
        m.setRunId(10L);
        m.setSeq(1);
        m.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 1));
        return m;
    }

    @Test
    @DisplayName("H1 list → 分页返回会话摘要列表")
    void list_returnsPagedSessions() {
        Page<ChatSession> paged = new Page<>(1, 20);
        paged.setRecords(List.of(session(1L)));
        paged.setTotal(1);
        when(sessionService.findAllSessions(1, 20)).thenReturn(paged);

        ApiResponse<PageResponse<Map<String, Object>>> result = controller.list(1, 20);

        Map<String, Object> record = result.data().records().get(0);
        assertEquals(1L, record.get("id"));
        assertEquals(5L, record.get("userId"));
        assertEquals("会话1", record.get("title"));
        assertEquals("qwen3.8-max", record.get("model"));
        assertNotNull(record.get("lastMessageAt"));
    }

    @Test
    @DisplayName("H2 detail → 会话不存在抛 404")
    void detail_sessionNotFound_throws404() {
        when(sessionService.findById(99L)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> controller.detail(99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("会话不存在", ex.getReason());
        verify(messageService, never()).findBySessionId(anyLong());
    }

    @Test
    @DisplayName("H2 detail → 返回会话摘要与消息列表")
    void detail_returnsSessionWithMessages() {
        when(sessionService.findById(1L)).thenReturn(session(1L));
        when(messageService.findBySessionId(1L)).thenReturn(List.of(message(1L)));

        ApiResponse<Map<String, Object>> result = controller.detail(1L);

        Map<String, Object> data = result.data();
        assertEquals("会话1", data.get("title"));
        List<?> messages = (List<?>) data.get("messages");
        assertEquals(1, messages.size());
        Map<?, ?> msg = (Map<?, ?>) messages.get(0);
        assertEquals("user", msg.get("role"));
        assertEquals("问题1", msg.get("content"));
        assertEquals("knowledge_question", msg.get("intentType"));
        assertEquals(10L, msg.get("runId"));
    }

    @Test
    @DisplayName("H3 close → 调用 closeSession")
    void close_callsCloseSession() {
        controller.close(1L);

        verify(sessionService).closeSession(1L);
    }

    @Test
    @DisplayName("H4 delete → 携带操作者 ID 调用 deleteSession")
    void delete_callsDeleteSessionWithOperator() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(9L);

        controller.delete(req, 1L);

        verify(sessionService).deleteSession(1L, 9L);
    }
}
