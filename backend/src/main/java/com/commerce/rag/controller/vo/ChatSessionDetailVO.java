package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话详情视图对象 —— controller 出参（B 端管理接口 H2）
 *
 * <p>会话摘要字段 + 消息列表的扁平组合，保持与原接口 JSON 结构一致。
 *
 * @param id            会话 ID
 * @param userId        所属用户 ID
 * @param title         会话标题
 * @param status        状态（ACTIVE / CLOSED）
 * @param lastMessageAt 最后消息时间
 * @param model         使用的模型
 * @param createdAt     创建时间
 * @param messages      会话消息列表
 */
public record ChatSessionDetailVO(
        Long id,
        Long userId,
        String title,
        String status,
        LocalDateTime lastMessageAt,
        String model,
        LocalDateTime createdAt,
        List<ChatMessageVO> messages) {}
