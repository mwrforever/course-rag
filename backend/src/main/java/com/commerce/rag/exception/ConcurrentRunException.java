package com.commerce.rag.exception;

/**
 * 并发 Run 冲突异常 —— 业务冲突错误（409）
 *
 * <p>当同一 session 已有活跃 run（QUEUED 或 ACTIVE）时，再次创建 run 会触发
 * DB partial unique index（uniq_active_run_per_session）冲突，
 * 抛出此异常通知调用方进行重试或提示用户。
 *
 * <p>继承 {@link BizException}（ErrorCode.CONFLICT），由全局异常处理器统一转为 409。
 *
 * @author commerce-rag
 */
public class ConcurrentRunException extends BizException {

    public ConcurrentRunException(String message) {
        super(ErrorCode.CONFLICT, message);
    }

    public ConcurrentRunException(String message, Throwable cause) {
        super(ErrorCode.CONFLICT, message, cause);
    }
}
