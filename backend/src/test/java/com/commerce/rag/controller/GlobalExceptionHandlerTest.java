package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * GlobalExceptionHandler 单元测试 —— 异常处理器逐一验证返回的业务码、消息与真实 HTTP 状态
 *
 * <p>P2-3 契约统一：所有 handler 的 HTTP 状态码与 body code 一致（原实现 HTTP 恒 200 的双轨问题）。
 *
 * @author commerce-rag
 */
@DisplayName("GlobalExceptionHandler 异常处理测试")
class GlobalExceptionHandlerTest {

    /** 被测试的异常处理器（无状态，直接实例化） */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 断言方法上的 @ResponseStatus 注解契约（HTTP 状态 = 指定值） */
    private void assertResponseStatus(Class<?>[] paramTypes, HttpStatus expected) throws Exception {
        var method = GlobalExceptionHandler.class.getMethod("handle" + paramTypes[0].getSimpleName(), paramTypes);
        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
        assertNotNull(responseStatus, "handler 必须标注 @ResponseStatus");
        assertEquals(expected, responseStatus.value(), "@ResponseStatus 应为 " + expected);
    }

    /** 按方法名直接断言 @ResponseStatus（P0-6 三个 handler 方法名不含异常类全名） */
    private void assertResponseStatusByMethod(String methodName, Class<?> paramType, HttpStatus expected)
            throws Exception {
        var method = GlobalExceptionHandler.class.getMethod(methodName, paramType);
        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
        assertNotNull(responseStatus, "handler 必须标注 @ResponseStatus");
        assertEquals(expected, responseStatus.value(), "@ResponseStatus 应为 " + expected);
    }

    @Test
    @DisplayName("handleBizException → ResponseEntity 404 + body code 404（业务错误统一通道）")
    void handleBizException_returns404WithMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBizException(new BizException(ErrorCode.NOT_FOUND, "课程不存在"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "HTTP 状态应为 404");
        assertNotNull(response.getBody());
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getBody().code(), "业务码应为 404");
        assertTrue(response.getBody().message().contains("课程不存在"), "message 应包含异常消息");
    }

    @Test
    @DisplayName("handleBizException → 409 CONFLICT 错误码映射（并发冲突）")
    void handleBizException_mapsConflict() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleBizException(new BizException(ErrorCode.CONFLICT, "资源冲突"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode(), "HTTP 状态应为 409");
        assertEquals(HttpStatus.CONFLICT.value(), response.getBody().code(), "业务码应为 409");
    }

    @Test
    @DisplayName("handleDataAccess → @ResponseStatus(503) + body code 503（数据库暂不可用）")
    void handleDataAccess_returns503() {
        ApiResponse<Void> result = handler.handleDataAccess(new DataAccessException("connection refused") {});
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), result.code(), "业务码应为 503");
        assertEquals("数据库暂时不可用，请稍后重试", result.message());
    }

    @Test
    @DisplayName("handleIllegalArgumentException → @ResponseStatus(400) + body code 400")
    void handleIllegalArgumentException_returns400WithMessage() throws Exception {
        assertResponseStatus(new Class<?>[] {IllegalArgumentException.class}, HttpStatus.BAD_REQUEST);
        ApiResponse<Void> result = handler.handleIllegalArgumentException(new IllegalArgumentException("参数错误"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertEquals("参数错误", result.message(), "message 应透传异常消息");
    }

    @Test
    @DisplayName("handleSecurityException → @ResponseStatus(403) + body code 403")
    void handleSecurityException_returns403() throws Exception {
        assertResponseStatus(new Class<?>[] {SecurityException.class}, HttpStatus.FORBIDDEN);
        ApiResponse<Void> result = handler.handleSecurityException(new SecurityException("越权访问"));
        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());
    }

    @Test
    @DisplayName("handleAccessDeniedException → @ResponseStatus(403) + body code 403")
    void handleAccessDeniedException_returns403() throws Exception {
        assertResponseStatus(new Class<?>[] {AccessDeniedException.class}, HttpStatus.FORBIDDEN);
        ApiResponse<Void> result = handler.handleAccessDeniedException(new AccessDeniedException("Access Denied"));
        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());
    }

    @Test
    @DisplayName("handleTypeMismatch → @ResponseStatus(400) + body code 400（P0-6 参数类型错误）")
    void handleTypeMismatch_returns400() throws Exception {
        assertResponseStatusByMethod(
                "handleTypeMismatch", MethodArgumentTypeMismatchException.class, HttpStatus.BAD_REQUEST);
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "page", null, null);
        ApiResponse<Void> result = handler.handleTypeMismatch(ex);
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertTrue(result.message().contains("page"), "message 应包含出错的参数名");
    }

    @Test
    @DisplayName("handleMessageNotReadable → @ResponseStatus(400) + body code 400（P0-6 请求体解析失败）")
    void handleMessageNotReadable_returns400() throws Exception {
        assertResponseStatusByMethod(
                "handleMessageNotReadable", HttpMessageNotReadableException.class, HttpStatus.BAD_REQUEST);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error");
        ApiResponse<Void> result = handler.handleMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertEquals("请求体格式错误", result.message());
    }

    @Test
    @DisplayName("handleMethodArgumentNotValid → @ResponseStatus(400) + body code 400（P0-6 @Valid 校验失败）")
    void handleMethodArgumentNotValid_returns400() throws Exception {
        assertResponseStatusByMethod(
                "handleMethodArgumentNotValid", MethodArgumentNotValidException.class, HttpStatus.BAD_REQUEST);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "name", "名称不能为空"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(null, binding);
        ApiResponse<Void> result = handler.handleMethodArgumentNotValid(ex);
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertTrue(result.message().contains("name"), "message 应包含校验失败的字段");
    }

    @Test
    @DisplayName("handleException → @ResponseStatus(500) + body code 500")
    void handleException_returns500() throws Exception {
        assertResponseStatus(new Class<?>[] {Exception.class}, HttpStatus.INTERNAL_SERVER_ERROR);
        ApiResponse<Void> result = handler.handleException(new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), result.code(), "业务码应为 500");
        assertEquals("服务器内部错误", result.message());
    }
}
