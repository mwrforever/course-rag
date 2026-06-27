package com.commerce.rag.feedback.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 用户反馈实体
 *
 * 记录用户对 AI 回答的评分和文字反馈，
 * 用于持续优化 RAG 知识库质量和对话体验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_feedback")
public class UserFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属会话 ID */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 关联的 AI 消息 ID */
    @Column(name = "message_id", nullable = false)
    private UUID messageId;

    /** 评分：1=非常不满意，5=非常满意 */
    @Column(nullable = false)
    private Short rating;

    /** 用户文字反馈（可选） */
    private String comment;

    /** 自动关联的意图类型：COURSE_INFO / HEURISTIC / QA */
    @Column(name = "intent_type", length = 20)
    private String intentType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
