package com.commerce.rag.common.exception;

/**
 * RAG 系统业务异常
 *
 * 所有业务逻辑中可预期的错误均抛出此异常，
 * 由全局异常处理器统一捕获并返回结构化错误响应。
 */
public class RagBusinessException extends RuntimeException {

    /** 业务错误码 */
    private final int code;

    public RagBusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public RagBusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RagBusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
