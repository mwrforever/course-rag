package com.commerce.rag.controller.dto;

/**
 * 统一 API 响应包装类。
 *
 * @param code    业务码（0=成功，非 0=错误）
 * @param message 提示信息
 * @param data    业务数据
 * @param <T>     数据类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }

    /**
     * 失败响应（自定义错误码 + 消息），语义等价于 {@link #error(int, String)}
     */
    public static <T> ApiResponse<T> fail(int code, String message) {
        return error(code, message);
    }

    /**
     * 失败响应（默认 500 错误码 + 自定义消息）
     */
    public static <T> ApiResponse<T> fail(String message) {
        return error(500, message);
    }
}
