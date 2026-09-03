package com.commerce.rag.vo;

/**
 * 会话内终态 run 状态视图（M4 历史回显徽标数据源；接口独立演化不复用 ChatRunVO）
 *
 * <p>由 {@code IChatRunService.findVisibleRunStatuses} 按会话查出的终态 run 三列投影，
 * 供 {@code ChatMessageServiceImpl.findStudentMessagesBySession} 历史回显两步查询第一步
 * 消费：runId 列表用于消息表 run_id IN 过滤，status/errorMessage 随行下发供前端渲染
 * 「已停止生成 / 生成失败」未完成徽标。
 *
 * @param runId        run ID
 * @param status       终态：COMPLETED / CANCELLED / ERROR
 * @param errorMessage 错误信息（仅 ERROR 行非 null，取自 chat_run.error_message）
 * @author commerce-rag
 */
public record ChatRunStatusVO(Long runId, String status, String errorMessage) {}
