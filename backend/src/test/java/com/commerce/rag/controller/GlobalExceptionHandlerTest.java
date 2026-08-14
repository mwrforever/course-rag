package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.controller.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * GlobalExceptionHandler 单元测试 —— 5 个异常处理器逐一验证返回的业务码与消息
 *
 * <p>直接 new GlobalExceptionHandler() 调用各 handler 方法（纯逻辑测试，无需 Spring 上下文）。
 * 断言真实业务行为：body 业务码与 message 与各异常类型一一对应。
 * handleAccessDeniedException 额外锁定 @ResponseStatus 注解契约（HTTP 403），
 * 该 HTTP 状态行为由 AdminUserControllerSecurityTest 端到端覆盖。
 *
 * @author commerce-rag
 */
@DisplayName("GlobalExceptionHandler 异常处理测试")
class GlobalExceptionHandlerTest {

    /** 被测试的异常处理器（无状态，直接实例化） */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("handleResponseStatusException → 404 且 message 含异常原因")
    void handleResponseStatusException_returns404WithReason() {
        ApiResponse<Void> result =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在"));

        assertEquals(HttpStatus.NOT_FOUND.value(), result.code(), "业务码应为 404");
        assertTrue(result.message().contains("不存在"), "message 应包含异常原因");
    }

    @Test
    @DisplayName("handleIllegalArgumentException → 400 且 message 透传")
    void handleIllegalArgumentException_returns400WithMessage() {
        ApiResponse<Void> result = handler.handleIllegalArgumentException(new IllegalArgumentException("参数错误"));

        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertEquals("参数错误", result.message(), "message 应透传异常消息");
    }

    @Test
    @DisplayName("handleSecurityException → 403 且 message=无权操作")
    void handleSecurityException_returns403() {
        ApiResponse<Void> result = handler.handleSecurityException(new SecurityException("越权访问"));

        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());
    }

    @Test
    @DisplayName("handleAccessDeniedException → 403 且 message=无权操作")
    void handleAccessDeniedException_returns403() throws Exception {
        ApiResponse<Void> result = handler.handleAccessDeniedException(new AccessDeniedException("Access Denied"));

        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());

        // 锁定 @ResponseStatus(FORBIDDEN) 注解契约：HTTP 状态必须为 403（本任务对 brief 的必要补充）
        var method = GlobalExceptionHandler.class.getMethod("handleAccessDeniedException", AccessDeniedException.class);
        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
        assertNotNull(responseStatus, "handleAccessDeniedException 必须标注 @ResponseStatus");
        assertEquals(HttpStatus.FORBIDDEN, responseStatus.value(), "@ResponseStatus 应为 403");
    }

    @Test
    @DisplayName("handleException → 500 且 message=服务器内部错误")
    void handleException_returns500() {
        ApiResponse<Void> result = handler.handleException(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), result.code(), "业务码应为 500");
        assertEquals("服务器内部错误", result.message());
    }
}
