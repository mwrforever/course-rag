package com.commerce.rag.stream;

/**
 * SSE 事件数据载体。
 * 每个 SseEvent 对应一条 SSE 推送，携带类型、序号、payload 和时间戳。
 */
public record SseEvent(SseEventType type, long seqId, String payload, long timestamp) {

    public SseEvent {
        if (type == null) {
            throw new IllegalArgumentException("SseEvent type must not be null");
        }
        if (seqId < 0) {
            throw new IllegalArgumentException("SseEvent seqId must be >= 0: " + seqId);
        }
        if (payload == null) {
            throw new IllegalArgumentException("SseEvent payload must not be null");
        }
        if (timestamp <= 0) {
            throw new IllegalArgumentException("SseEvent timestamp must be > 0: " + timestamp);
        }
    }
}
