package com.commerce.rag.feedback.entity;

/**
 * 反馈统计结果
 *
 * @param intentType    意图类型
 * @param totalCount    总反馈数
 * @param avgRating     平均评分
 * @param positiveCount 好评数（rating >= 4）
 * @param negativeCount 差评数（rating <= 2）
 */
public record FeedbackStats(
        String intentType,
        long totalCount,
        double avgRating,
        long positiveCount,
        long negativeCount
) {}
