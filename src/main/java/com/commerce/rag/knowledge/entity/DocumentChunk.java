package com.commerce.rag.knowledge.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文档分块实体
 *
 * 每个分块与 Milvus 中的一条向量记录对应（通过 chunk_id 关联）。
 * 同时存储在 PostgreSQL 中以支持关键字检索（tsvector GIN 索引）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "document_chunk")
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属文档 ID */
    @Column(name = "doc_id", nullable = false)
    private UUID docId;

    /** 所属知识库 ID */
    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    /** 在文档中的顺序索引（从 0 开始） */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 分块的原始文本内容 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** 标题层级路径，如 "第1章 > 1.2 节" */
    @Column(name = "heading_path", length = 500)
    private String headingPath;

    /** 所属章节标题 */
    @Column(name = "parent_title", length = 300)
    private String parentTitle;

    /** 起始页码 */
    @Column(name = "start_page")
    private Integer startPage;

    /** 结束页码 */
    @Column(name = "end_page")
    private Integer endPage;

    /** token 数量（用于分块策略控制） */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 被检索命中的次数，用于热度加权排序 */
    @Column(name = "active_count")
    private Integer activeCount;

    /** 扩展元数据（JSON 格式） */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", columnDefinition = "jsonb")
    private Map<String, Object> metadataJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (activeCount == null) activeCount = 0;
        if (tokenCount == null) tokenCount = 0;
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
