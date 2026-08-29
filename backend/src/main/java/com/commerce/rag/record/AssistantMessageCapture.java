package com.commerce.rag.record;

import java.util.List;

/**
 * 单次 LLM 调用的完整消息捕获（消息实体化，2026-08-29）。
 *
 * <p>与厂商 assistant 消息 1:1：一次 chat completion（QU / caption / 主 agent）在
 * 模型输出完成点捕获 {@code reasoning + text + toolCalls} 三字段同体，run 终结时由
 * ChatRequestWorker 落一条 {@code message_type='assistant'} 实体行（content 为 spec §3.1
 * JSON）。捕获方（QueryUnderstandingService / ImageCaptionService / SseEventTransformer）
 * 经 {@link AssistantMessageSink} 容器汇总，落库方（worker persistMessages）快照消费。
 *
 * @param stage    调用所属阶段键（understanding / attachments / generating，与 SSE THINKING
 *                 stage 键同口径）
 * @param reasoning 该次调用的思考全文（可为 null/空——无思考的调用；按换行拆行入实体 JSON）
 * @param text     调用 content 原文（主 agent=正文；QU=query_plan payload JSON 字符串；
 *                 caption=描述文本；可为 null——纯工具调用/思考失败场景）
 * @param toolCalls 主 agent 工具调用列表（QU/caption 恒空；可为 null）
 */
public record AssistantMessageCapture(String stage, String reasoning, String text, List<AssistantToolCall> toolCalls) {

    /**
     * 工具调用数据（与实时 TOOL_CALL 事件 schema 同构：toolCallId/toolName/input）。
     *
     * @param id        工具调用 ID（模型生成）
     * @param name      工具名称
     * @param arguments 工具参数 JSON 字符串（可为 null/空）
     */
    public record AssistantToolCall(String id, String name, String arguments) {}
}
