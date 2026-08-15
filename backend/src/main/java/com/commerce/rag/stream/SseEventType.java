package com.commerce.rag.stream;

/**
 * SSE 流式事件类型枚举。
 * 与前端设计文档 §1.6.4 的 10 事件 schema 对应。
 *
 * <p>关于 HEARTBEAT：SSE 协议规范中 heartbeat 是以 {@code :} 开头的注释行
 * （如 {@code :heartbeat}），用于保活探测。但 Spring {@code SseEmitter} 的
 * {@code event().name()} 设置的是 SSE {@code event:} 字段，无法产生纯注释行。
 * 因此实际发送时使用 {@code SseEmitter.event().comment("heartbeat")} 产生注释行，
 * 枚举值仅为 "heartbeat"（不含冒号，冒号由 SSE 协议语法添加）。
 */
public enum SseEventType {
    METADATA("metadata"),
    THINKING("thinking"),
    THINKING_END("thinking_end"),
    DELTA("delta"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    SOURCES("sources"),
    ERROR("error"),
    END("end"),
    /** 心跳保活事件：实际通过 SSE 注释行 {@code :heartbeat} 发送，非命名事件 */
    HEARTBEAT("heartbeat");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }
}
