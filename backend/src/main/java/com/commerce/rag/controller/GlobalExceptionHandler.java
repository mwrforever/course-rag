package com.commerce.rag.controller;

import com.commerce.rag.controller.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理器 —— 统一捕获异常并包装成 ApiResponse
 *
 * <p>处理策略（§七 异常处理）：
 * <ul>
 *   <li>ResponseStatusException → 对应 HttpStatus 码</li>
 *   <li>IllegalArgumentException → 400</li>
 *   <li>SecurityException → 403</li>
 *   <li>Exception → 500</li>
 * </ul>
 * 所有响应统一包成 {@link ApiResponse#fail(int, String)}。
 *
 * @author commerce-rag
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理 ResponseStatusException —— 使用异常中指定的 HTTP 状态码
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ApiResponse<Void> handleResponseStatusException(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        log.warn("业务异常: status={}, reason={}", code, e.getReason());
        return ApiResponse.fail(code, e.getReason());
    }

    /**
     * 处理 IllegalArgumentException —— 参数校验失败，返回 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    /**
     * 处理 SecurityException —— 权限不足，返回 403
     */
    @ExceptionHandler(SecurityException.class)
    public ApiResponse<Void> handleSecurityException(SecurityException e) {
        log.warn("权限异常: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.FORBIDDEN.value(), "无权操作");
    }

    /**
     * 处理 AccessDeniedException —— @PreAuthorize 鉴权拒绝，返回 403
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ExceptionHandler(AccessDeniedException.class)
    public ApiResponse<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("鉴权拒绝: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.FORBIDDEN.value(), "无权操作");
    }

    /**
     * 处理其他未捕获异常 —— 返回 500
     */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误");
    }
}
