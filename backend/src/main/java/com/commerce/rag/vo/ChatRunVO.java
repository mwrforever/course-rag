package com.commerce.rag.vo;

import java.time.LocalDateTime;

/**
 * Run 视图对象 —— stream 组件与接口层使用的 Run 流转对象
 *
 * <p>与 ChatRun 实体同名业务字段一一对应，剔除内部字段：
 * modelCalls/traceId/errorMessage/metaJson（内部执行计数与元数据）、
 * deleted（逻辑删除标记）、startedAt/endedAt（内部执行时间点）。
 * chat_run 表无 updated_at 列，故不设该字段。
 *
 * @param id        主键 ID（雪花 ID）
 * @param sessionId 所属会话 ID
 * @param userId    发起用户 ID
 * @param status    Run 状态：QUEUED / ACTIVE / COMPLETED / CANCELLED / ERROR
 * @param createdAt 创建时间
 */
public record ChatRunVO(Long id, Long sessionId, Long userId, String status, LocalDateTime createdAt) {}
