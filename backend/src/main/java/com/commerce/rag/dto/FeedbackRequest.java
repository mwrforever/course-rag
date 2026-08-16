package com.commerce.rag.dto;

/**
 * 反馈创建请求
 *
 * @param sessionId  会话 ID
 * @param messageId  消息 ID
 * @param isLiked    是否点赞（NULL/TRUE/FALSE）
 * @param intentType 意图类型（可选）
 */
public record FeedbackRequest(Long sessionId, Long messageId, Boolean isLiked, String intentType) {}
