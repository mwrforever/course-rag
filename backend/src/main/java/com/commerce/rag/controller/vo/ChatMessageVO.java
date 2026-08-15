package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * 会话消息视图对象 —— controller 出参（B 端管理接口 H2 消息列表）
 *
 * <p>与 ChatMessage 实体同名业务字段一一对应，剔除内部字段：
 * sessionId（由会话上下文携带）、sourcesJson/tokenCount/confidence/traceId
 * （渲染审计与溯源内部字段）、deleted（逻辑删除标记）。
 *
 * @param id          消息 ID
 * @param role        角色（user / assistant / system）
 * @param content     消息内容
 * @param messageType 消息类型（TEXT 等）
 * @param intentType  意图类型（knowledge_question / chat / unknown）
 * @param runId       所属运行 ID
 * @param seq         消息序号
 * @param createdAt   创建时间
 */
public record ChatMessageVO(
        Long id,
        String role,
        String content,
        String messageType,
        String intentType,
        Long runId,
        Integer seq,
        LocalDateTime createdAt) {}
