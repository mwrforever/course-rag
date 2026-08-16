package com.commerce.rag.exception;

import org.springframework.http.HttpStatus;

/**
 * 错误码枚举 —— 业务错误码统一登记
 *
 * <p>code 值与 HTTP 状态码同值（保持 ApiResponse.code = HTTP 状态码的前端契约），
 * 不引入独立业务码段；message 为默认提示，抛出时可覆盖。
 *
 * @author commerce-rag
 */
public enum ErrorCode {

    /** 请求参数错误（400） */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "请求参数错误"),

    /** 未认证或登录已过期（401） */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "未认证或登录已过期"),

    /** 无权操作（403） */
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权操作"),

    /** 资源不存在（404） */
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),

    /** 资源状态冲突（409） */
    CONFLICT(HttpStatus.CONFLICT, "资源状态冲突"),

    /** 服务暂不可用（503） */
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "服务暂不可用"),

    /** 服务器内部错误（500） */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /** HTTP 状态码（与 code 同值） */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /** 业务码（与 HTTP 状态同值） */
    public int getCode() {
        return httpStatus.value();
    }

    /** 默认提示消息 */
    public String getMessage() {
        return message;
    }
}
