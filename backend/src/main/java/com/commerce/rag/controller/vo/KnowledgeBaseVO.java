package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * 知识库视图对象 —— controller 出参（B 端管理接口）
 *
 * <p>与 KnowledgeBase 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。
 *
 * @param id          知识库 ID
 * @param name        知识库名称
 * @param description 知识库描述
 * @param status      状态（ACTIVE / ARCHIVED）
 * @param createdBy   创建者 ID（教师 user_id）
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 */
public record KnowledgeBaseVO(
        Long id,
        String name,
        String description,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
