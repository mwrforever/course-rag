package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.stream.ChatStreamEntry;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * ChatController 单元测试 —— 薄端点转发测试
 *
 * <p>ChatController 已瘦身为纯转发层：SSE 编排逻辑（会话/run 创建、Redis 入队、心跳、
 * 归属校验、断线回放）统一收编在 {@link ChatStreamEntry}，由 ChatStreamEntryTest 覆盖。
 * 本测试仅断言 3 个端点参数绑定与逐行转发一致。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController 薄端点转发测试")
class ChatControllerTest {

    @Mock
    private ChatStreamEntry chatStreamEntry;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(chatStreamEntry);
    }

    // ==================== chat() 转发测试 ====================

    @Test
    @DisplayName("chat → 原样转发 request 与 ChatRequest 给 ChatStreamEntry，返回同一 emitter")
    void chat_forwardsToChatStreamEntry() {
        // Given
        HttpServletRequest req = mock(HttpServletRequest.class);
        ChatRequest chatRequest = new ChatRequest(1L, "什么是 RAG？");
        SseEmitter emitter = mock(SseEmitter.class);
        when(chatStreamEntry.chat(req, chatRequest)).thenReturn(emitter);

        // When
        SseEmitter result = controller.chat(req, chatRequest);

        // Then: 转发参数一致 + 返回同一个 emitter 实例
        verify(chatStreamEntry).chat(req, chatRequest);
        assertSame(emitter, result);
    }

    // ==================== cancel() 转发测试 ====================

    @Test
    @DisplayName("cancel → 原样转发 runId 给 ChatStreamEntry，返回同一 ResponseEntity")
    void cancel_forwardsToChatStreamEntry() {
        // Given
        HttpServletRequest req = mock(HttpServletRequest.class);
        ResponseEntity<Void> response = ResponseEntity.ok().build();
        when(chatStreamEntry.cancel("123", req)).thenReturn(response);

        // When
        ResponseEntity<Void> result = controller.cancel("123", req);

        // Then: 转发 runId 一致 + 返回同一个响应
        verify(chatStreamEntry).cancel("123", req);
        assertSame(response, result);
        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    // ==================== reconnect() 转发测试 ====================

    @Test
    @DisplayName("reconnect → 原样转发 runId/lastEventId 给 ChatStreamEntry，返回同一 emitter")
    void reconnect_forwardsToChatStreamEntry() {
        // Given
        HttpServletRequest req = mock(HttpServletRequest.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(chatStreamEntry.reconnect("123", 5L, req)).thenReturn(emitter);

        // When
        SseEmitter result = controller.reconnect("123", 5L, req);

        // Then: 转发 runId/lastEventId 一致 + 返回同一个 emitter 实例
        verify(chatStreamEntry).reconnect("123", 5L, req);
        assertSame(emitter, result);
    }

    // ==================== 类级角色门禁 ====================

    @Test
    @DisplayName("P2-4 类级 @PreAuthorize 允许学生与 B 端角色使用对话")
    void classLevel_hasRoleGateForStudentTeacherSuperAdmin() throws Exception {
        var annotation =
                ChatController.class.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertNotNull(annotation, "ChatController 必须声明 @PreAuthorize");
        assertEquals("hasAnyRole('STUDENT', 'TEACHER', 'SUPER_ADMIN')", annotation.value(), "角色门禁应显式允许 C 端学生与 B 端角色");
    }
}
