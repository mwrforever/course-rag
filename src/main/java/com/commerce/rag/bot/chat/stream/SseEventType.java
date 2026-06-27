package com.commerce.rag.bot.chat.stream;

/**
 * SSE 事件类型枚举
 *
 * 定义前端与后端之间的 SSE 事件协议：
 * - START: 流开始，携带 runId 和 messageId
 * - DELTA: 文本增量（打字效果）
 * - CITATION: RAG 来源引用
 * - DONE: 流正常结束，携带 token 用量
 * - CANCELLED: 流被取消
 * - ERROR: 流异常中断
 * - HEARTBEAT: 保活心跳
 */
public enum SseEventType {

    START("start"),
    DELTA("delta"),
    CITATION("citation"),
    DONE("done"),
    CANCELLED("cancelled"),
    ERROR("error"),
    HEARTBEAT("heartbeat");

    /** SSE event 字段值（前端 EventSource 监听的事件名） */
    public final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }
}
