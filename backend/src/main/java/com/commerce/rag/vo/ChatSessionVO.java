package com.commerce.rag.vo;

import java.time.LocalDateTime;

/**
 * 会话摘要视图对象 —— controller 出参（B 端管理接口 H1/H2）
 *
 * <p>与 ChatSession 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。
 *
 * @param id            会话 ID
 * @param userId        所属用户 ID
 * @param title         会话标题
 * @param status        状态（ACTIVE / CLOSED）
 * @param lastMessageAt 最后消息时间
 * @param model         使用的模型
 * @param createdAt     创建时间
 */
public record ChatSessionVO(
        Long id,
        Long userId,
        String title,
        String status,
        LocalDateTime lastMessageAt,
        String model,
        LocalDateTime createdAt) {}
