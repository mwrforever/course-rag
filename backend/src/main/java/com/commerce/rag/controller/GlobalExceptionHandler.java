package com.commerce.rag.controller;

import com.commerce.rag.controller.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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
     * （P2-3：真实 HTTP 状态码，原实现 HTTP 恒 200 的双轨问题）
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
        int code = e.getStatusCode().value();
        log.warn("业务异常: status={}, reason={}", code, e.getReason());
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.fail(code, e.getReason()));
    }

    /**
     * 处理 IllegalArgumentException —— 参数校验失败，返回 400（P2-3 真实 HTTP 状态码）
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("参数异常: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage());
    }

    /**
     * 处理 SecurityException —— 权限不足，返回 403（P2-3 真实 HTTP 状态码）
     */
    @ResponseStatus(HttpStatus.FORBIDDEN)
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
     * 处理 MethodArgumentTypeMismatchException —— 路径/查询参数类型错误（如 ?page=abc），返回 400
     *
     * <p>P0-6：P2-3 之前该类异常落入 Exception 兜底 → 客户端参数错误被标为 500。
     * 客户端参数错误一律真实 HTTP 400，与「IllegalArgumentException → 400」契约一致。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ApiResponse<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: name={}, value={}", e.getName(), e.getValue());
        return ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "参数类型错误: " + e.getName());
    }

    /**
     * 处理 HttpMessageNotReadableException —— 请求体 JSON 解析失败（如空 body、非法 JSON），返回 400
     *
     * <p>P0-6：同参数类型错误，客户端请求体错误不得映射为 500。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "请求体格式错误");
    }

    /**
     * 处理 MethodArgumentNotValidException —— @Valid 校验失败（如 @NotBlank 空值），返回 400
     *
     * <p>P0-6：登录/刷新/用户管理等 @Valid 请求体校验失败不得映射为 500，
     * 返回首个校验失败的字段与原因，便于前端定位。
     */
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String detail =
                fieldError != null ? fieldError.getField() + " " + fieldError.getDefaultMessage() : e.getMessage();
        log.warn("参数校验失败: {}", detail);
        return ApiResponse.fail(HttpStatus.BAD_REQUEST.value(), "参数校验失败: " + detail);
    }

    /**
     * 处理其他未捕获异常 —— 返回 500（P2-3 真实 HTTP 状态码）
     */
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("未预期异常", e);
        return ApiResponse.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "服务器内部错误");
    }
}
