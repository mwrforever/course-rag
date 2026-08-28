package com.commerce.rag.stream;

/**
 * SSE 流式事件类型枚举。
 * 与前端设计文档 §1.6.4 的 10 事件 schema 对应（另含本类新增的 STAGE、QUERY_PLAN，见成员注释）。
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
    /**
     * 阶段进度事件（2026-08-27 C 端体验改版新增）。
     *
     * <p>动机：QU（阻塞 LLM）→ 检索 → rerank → 主模型首 token 的串行链路全程
     * 对前端静默（THINKING/DELTA 之前无任何可见事件，心跳是 JS 不可见的注释行），
     * 用户感知"等很久一点内容都没有"。STAGE 在链路各阶段边界推送，让"上下文准备"
     * 过程可见（先准备上下文，再流式返回最终结果）。
     *
     * <p>payload：{@code {"stage":"understanding|retrieving|generating|attachments",
     * "label":"中文文案"}}；stage 供前端阶段机消费，label 直接展示。
     */
    STAGE("stage"),
    /**
     * QU 需求解析完成事件（2026-08-28 对话流式时间线改版新增）。
     *
     * <p>动机：QueryUnderstanding（阻塞 LLM）完成后其解析结果此前仅进图 State，
     * 前端无感知；用户在"理解问题"阶段结束后到检索开始之间仍看不到任何实质内容。
     * QUERY_PLAN 在 QU 节点完成边界推送，让需求解析结果对前端即时可见。
     *
     * <p>payload：{@code {"intent":"意图","rewritten":"改写后问题","filters":{...}}}；
     * intent/rewritten/filters 为 QU 输出三要素，前端可用于展示"理解为…"提示行。
     */
    QUERY_PLAN("query_plan"),
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
