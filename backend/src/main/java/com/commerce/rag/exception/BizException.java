package com.commerce.rag.exception;

/**
 * 业务异常 —— 统一携带错误码的业务错误载体
 *
 * <p>工程宪法「异常规范」：业务错误一律抛本异常（{@code throw new BizException(ErrorCode.XXX, 消息)}），
 * 禁止散落 {@code ResponseStatusException}。
 * 由 {@link com.commerce.rag.controller.GlobalExceptionHandler} 统一转换为
 * ApiResponse（code 与 HTTP 状态同值，保持前端契约）。
 *
 * <p>线程安全：不可变对象，仅承载错误信息，可安全跨线程传递。
 *
 * @author commerce-rag
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /** 错误码（与 HTTP 状态同值） */
    public int getCode() {
        return errorCode.getCode();
    }

    /** 错误码枚举 */
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
