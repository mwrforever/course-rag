package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.PageResponse;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatSessionDetailVO;
import com.commerce.rag.vo.ChatSessionVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * AdminSessionController 单元测试 —— B 端会话管理端点 H1-H4
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminSessionController 会话管理端点测试")
class AdminSessionControllerTest {

    @Mock
    private IChatSessionService sessionService;

    @Mock
    private IChatMessageService messageService;

    private AdminSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminSessionController(sessionService, messageService);
    }

    private ChatSessionVO sessionVO(Long id) {
        return new ChatSessionVO(
                id,
                5L,
                "会话" + id,
                "ACTIVE",
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "qwen3.8-max",
                LocalDateTime.of(2026, 8, 15, 9, 0));
    }

    private ChatMessageVO messageVO(Long id) {
        return new ChatMessageVO(
                id, "user", "问题" + id, "TEXT", "knowledge_question", 10L, 1, LocalDateTime.of(2026, 8, 15, 9, 1));
    }

    @Test
    @DisplayName("H1 list → 分页返回会话摘要列表（VO）")
    void list_returnsPagedSessions() {
        Page<ChatSessionVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(sessionVO(1L)));
        paged.setTotal(1);
        when(sessionService.findAllSessions(1, 20)).thenReturn(paged);

        ApiResponse<PageResponse<ChatSessionVO>> result = controller.list(1, 20);

        ChatSessionVO record = result.data().records().get(0);
        assertEquals(1L, record.id());
        assertEquals(5L, record.userId());
        assertEquals("会话1", record.title());
        assertEquals("qwen3.8-max", record.model());
        assertNotNull(record.lastMessageAt());
    }

    @Test
    @DisplayName("H2 detail → 会话不存在抛 404")
    void detail_sessionNotFound_throws404() {
        when(sessionService.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.detail(99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertEquals("会话不存在", ex.getMessage());
        verify(messageService, never()).findBySessionId(anyLong());
    }

    @Test
    @DisplayName("H2 detail → 由摘要 VO + 消息 VO 列表组装详情（controller 零 entity）")
    void detail_returnsSessionWithMessages() {
        when(sessionService.findById(1L)).thenReturn(sessionVO(1L));
        when(messageService.findBySessionId(1L)).thenReturn(List.of(messageVO(1L)));

        ApiResponse<ChatSessionDetailVO> result = controller.detail(1L);

        ChatSessionDetailVO data = result.data();
        assertEquals("会话1", data.title());
        assertEquals(5L, data.userId());
        assertEquals(1, data.messages().size());
        ChatMessageVO msg = data.messages().get(0);
        assertEquals("user", msg.role());
        assertEquals("问题1", msg.content());
        assertEquals("knowledge_question", msg.intentType());
        assertEquals(10L, msg.runId());
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
