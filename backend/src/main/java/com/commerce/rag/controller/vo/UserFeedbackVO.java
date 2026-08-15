package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * 用户反馈视图对象 —— controller 出参（B 端管理接口）
 *
 * <p>与 UserFeedback 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。
 *
 * @param id         反馈 ID
 * @param sessionId  所属会话 ID
 * @param messageId  被反馈的消息 ID
 * @param userId     反馈用户 ID
 * @param isLiked    是否点赞（NULL=未评，TRUE=赞，FALSE=踩）
 * @param intentType 意图类型（TECHNICAL_QA / COURSE_INFO）
 * @param createdAt  创建时间
 */
public record UserFeedbackVO(
        Long id,
        Long sessionId,
        Long messageId,
        Long userId,
        Boolean isLiked,
        String intentType,
        LocalDateTime createdAt) {}
