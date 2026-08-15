package com.commerce.rag.service;

/**
 * 并发 Run 冲突异常
 *
 * <p>当同一 session 已有活跃 run（QUEUED 或 ACTIVE）时，再次创建 run 会触发
 * DB partial unique index（uniq_active_run_per_session）冲突，
 * 抛出此异常通知调用方进行重试或提示用户。
 *
 * @author commerce-rag
 */
public class ConcurrentRunException extends RuntimeException {

    public ConcurrentRunException(String message) {
        super(message);
    }

    public ConcurrentRunException(String message, Throwable cause) {
        super(message, cause);
    }
}
