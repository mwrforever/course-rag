package com.commerce.rag.feedback;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.feedback.entity.FeedbackStats;
import com.commerce.rag.feedback.entity.UserFeedback;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 用户反馈服务
 *
 * 负责：
 * 1. 采集用户对 AI 回答的评分和评论
 * 2. 按意图类型统计反馈数据
 * 3. 查询低分回答供人工复核
 */
@Slf4j
@Service
public class FeedbackService {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 提交反馈
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID（AI 回复的消息）
     * @param rating    评分（1-5）
     * @param comment   用户评论（可选）
     * @return 保存的反馈记录
     */
    @Transactional
    public UserFeedback submitFeedback(UUID sessionId, UUID messageId,
                                        short rating, String comment) {
        // 查询关联消息的意图类型
        ChatMessage message = entityManager.find(ChatMessage.class, messageId);
        String intentType = message != null ? message.getIntentType() : null;

        UserFeedback feedback = UserFeedback.builder()
                .sessionId(sessionId)
                .messageId(messageId)
                .rating(rating)
                .comment(comment)
                .intentType(intentType)
                .build();

        entityManager.persist(feedback);
        log.info("反馈已提交: sessionId={}, messageId={}, rating={}, intentType={}",
                sessionId, messageId, rating, intentType);
        return feedback;
    }

    /**
     * 按意图类型统计反馈数据
     *
     * @return 各意图的统计结果列表
     */
    @SuppressWarnings("unchecked")
    public List<FeedbackStats> getStats() {
        String sql = """
            SELECT intent_type,
                   COUNT(*) as total_count,
                   AVG(rating::numeric) as avg_rating,
                   COUNT(*) FILTER (WHERE rating >= 4) as positive_count,
                   COUNT(*) FILTER (WHERE rating <= 2) as negative_count
            FROM user_feedback
            GROUP BY intent_type
            ORDER BY total_count DESC
            """;

        List<Object[]> rows = entityManager.createNativeQuery(sql).getResultList();
        return rows.stream()
                .map(row -> new FeedbackStats(
                        String.valueOf(row[0]),
                        ((Number) row[1]).longValue(),
                        ((Number) row[2]).doubleValue(),
                        ((Number) row[3]).longValue(),
                        ((Number) row[4]).longValue()
                ))
                .toList();
    }

    /**
     * 查询低分回答列表（供人工复核）
     *
     * @param limit 返回数量
     * @return 低分反馈列表（rating <= 2）
     */
    @SuppressWarnings("unchecked")
    public List<UserFeedback> getLowRatedFeedbacks(int limit) {
        return entityManager.createQuery(
                        "SELECT f FROM UserFeedback f WHERE f.rating <= 2 ORDER BY f.createdAt DESC")
                .setMaxResults(limit)
                .getResultList();
    }
}
