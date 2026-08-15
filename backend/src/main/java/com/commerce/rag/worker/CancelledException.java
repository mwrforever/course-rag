package com.commerce.rag.worker;

/**
 * Run 被取消时抛出的异常。
 * 由 {@link ChatRequestWorker#cancel} 设置取消标记后，
 * 在 {@code doOnNext} 检查点抛出，触发 {@code onErrorResume} 走取消分支。
 */
public class CancelledException extends RuntimeException {

    public CancelledException(String runId) {
        super("Run cancelled: " + runId);
    }
}
