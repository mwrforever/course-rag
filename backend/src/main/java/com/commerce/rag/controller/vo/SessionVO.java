package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * 会话视图对象 —— controller 出参（C 端接口 J6 我的会话 / J7 创建会话）
 *
 * <p>与 ChatSession 实体同名业务字段一一对应，剔除内部管理字段
 * （userId/model/deleted/updatedAt）。
 *
 * @param id            会话 ID
 * @param title         会话标题
 * @param status        状态（ACTIVE / CLOSED）
 * @param lastMessageAt 最后消息时间（新建会话时为 null）
 * @param createdAt     创建时间
 */
public record SessionVO(Long id, String title, String status, LocalDateTime lastMessageAt, LocalDateTime createdAt) {}
