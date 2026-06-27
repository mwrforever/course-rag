package com.commerce.rag.bot.chat;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话消息实体
 *
 * 记录每条用户/AI 消息，AI 消息携带 RAG 检索来源信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chat_message")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属会话 ID */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    /** 消息角色：USER / ASSISTANT / SYSTEM */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 意图类型（仅 ASSISTANT 消息）：COURSE_INFO / HEURISTIC / QA */
    @Column(name = "intent_type", length = 20)
    private String intentType;

    /**
     * RAG 检索来源信息
     * 格式: [{"chunkId":"...","headingPath":"...","score":0.85}]
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sources_json", columnDefinition = "jsonb")
    private List<Map<String, Object>> sourcesJson;

    /** token 用量 */
    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (tokenCount == null) tokenCount = 0;
        createdAt = Instant.now();
    }
}
