package com.commerce.rag.bot.chat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 会话与消息的 JPA Repository
 */
@Repository
public class ChatRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${chat.max-history-rounds:10}")
    private int maxHistoryRounds;

    /**
     * 保存会话
     */
    @Transactional
    public ChatSession saveSession(ChatSession session) {
        entityManager.persist(session);
        return session;
    }

    /**
     * 查询会话
     */
    public ChatSession findSession(UUID sessionId) {
        return entityManager.find(ChatSession.class, sessionId);
    }

    /**
     * 查询用户所有活跃会话
     */
    @SuppressWarnings("unchecked")
    public List<ChatSession> findSessionsByUserId(String userId) {
        return entityManager.createQuery(
                        "SELECT s FROM ChatSession s WHERE s.userId = :userId AND s.status = 'ACTIVE' ORDER BY s.updatedAt DESC")
                .setParameter("userId", userId)
                .getResultList();
    }

    /**
     * 保存消息
     */
    @Transactional
    public ChatMessage saveMessage(ChatMessage message) {
        entityManager.persist(message);
        return message;
    }

    /**
     * 查询会话的最近消息（用于上下文）
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> findRecentMessages(UUID sessionId) {
        return entityManager.createQuery(
                        "SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC")
                .setParameter("sessionId", sessionId)
                .setMaxResults(maxHistoryRounds * 2)  // 每轮 = 用户消息 + AI 回复
                .getResultList();
    }

    /**
     * 查询会话的所有消息（正序）
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> findAllMessages(UUID sessionId) {
        return entityManager.createQuery(
                        "SELECT m FROM ChatMessage m WHERE m.sessionId = :sessionId ORDER BY m.createdAt ASC")
                .setParameter("sessionId", sessionId)
                .getResultList();
    }

    /**
     * 关闭会话
     */
    @Transactional
    public void closeSession(UUID sessionId) {
        ChatSession session = entityManager.find(ChatSession.class, sessionId);
        if (session != null) {
            session.setStatus("CLOSED");
            entityManager.merge(session);
        }
    }

    /**
     * 更新会话的上下文压缩摘要
     *
     * @param sessionId      会话 ID
     * @param contextSummary 压缩后的历史摘要文本
     */
    @Transactional
    public void updateContextSummary(UUID sessionId, String contextSummary) {
        ChatSession session = entityManager.find(ChatSession.class, sessionId);
        if (session != null) {
            session.setContextSummary(contextSummary);
            entityManager.merge(session);
        }
    }

    /**
     * 通过 runId 查询 ChatMessage（断线续传时查找已完成的流式消息）
     *
     * @param runId 流式回答关联 ID
     * @return 对应的 ChatMessage，不存在返回 null
     */
    public ChatMessage findByRunId(UUID runId) {
        @SuppressWarnings("unchecked")
        List<ChatMessage> results = entityManager.createQuery(
                        "SELECT m FROM ChatMessage m WHERE m.runId = :runId")
                .setParameter("runId", runId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 更新流式消息的内容和状态（流式过程中增量保存 / 流完成 / 取消 / 异常）
     *
     * @param messageId    消息 ID
     * @param content      当前拼接的文本内容
     * @param streamStatus 流式状态：streaming / completed / cancelled / error
     * @param tokenCount   token 用量（流完成时设置，其他时候可为 null）
     */
    @Transactional
    public void updateStreamMessage(UUID messageId, String content, String streamStatus, Integer tokenCount) {
        ChatMessage message = entityManager.find(ChatMessage.class, messageId);
        if (message != null) {
            message.setContent(content);
            message.setStreamStatus(streamStatus);
            if (tokenCount != null) {
                message.setTokenCount(tokenCount);
            }
            entityManager.merge(message);
        }
    }
}
