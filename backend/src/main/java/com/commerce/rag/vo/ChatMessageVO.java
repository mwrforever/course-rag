package com.commerce.rag.vo;

import java.time.LocalDateTime;

/**
 * 会话消息视图对象 —— controller 出参（B 端管理接口 H2 消息列表）+ PG 降级回放数据通道
 *
 * <p>与 ChatMessage 实体同名业务字段一一对应，剔除内部字段：
 * sessionId（由会话上下文携带）、sourcesJson/tokenCount/confidence/traceId
 * （渲染审计与溯源内部字段）、deleted（逻辑删除标记）。
 *
 * <p>2026-08-28 时间线改版：新增 thinkingStage（thinking 行的阶段键
 * understanding/attachments/generating）——ChatStreamEntry.replayFromPg 降级回放
 * 据此重建带 stage 的 THINKING 事件；thinking 行无该列值（历史存量行）时为 null，
 * 回放侧输出 JSON null（前端降级 generating），不报错。
 *
 * @param id           消息 ID
 * @param role         角色（user / assistant / system）
 * @param content      消息内容
 * @param messageType  消息类型（TEXT 等）
 * @param thinkingStage thinking 行的思考阶段键（非 thinking 行为 null；历史存量行亦可为 null）
 * @param intentType   意图类型（knowledge_question / chat / unknown）
 * @param runId        所属运行 ID
 * @param seq          消息序号
 * @param createdAt    创建时间
 */
public record ChatMessageVO(
        Long id,
        String role,
        String content,
        String messageType,
        String thinkingStage,
        String intentType,
        Long runId,
        Integer seq,
        LocalDateTime createdAt) {}
