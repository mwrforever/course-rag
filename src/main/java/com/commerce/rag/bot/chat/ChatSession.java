package com.commerce.rag.bot.chat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 对话会话实体
 *
 * 管理用户的多轮对话状态，每条会话包含若干 ChatMessage。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_session")
public class ChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 用户标识 */
    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    /** 会话标题（可由第一条消息自动生成） */
    @Column(length = 300)
    private String title;

    /** 会话状态：ACTIVE / CLOSED */
    @Column(nullable = false, length = 20)
    private String status;

    /** 上下文压缩摘要（长对话触发 70% 阈值时生成，避免每次重新压缩） */
    @Column(name = "context_summary", columnDefinition = "TEXT")
    private String contextSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (status == null) status = "ACTIVE";
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
