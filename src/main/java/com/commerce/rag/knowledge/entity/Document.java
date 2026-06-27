package com.commerce.rag.knowledge.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * 文档实体
 *
 * 记录每个上传文档的元信息和 ETL 处理状态。
 * 状态机流转：UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXING → COMPLETED / FAILED
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** 所属知识库 ID */
    @Column(name = "kb_id", nullable = false)
    private UUID kbId;

    /** 文档标题 */
    @Column(nullable = false, length = 500)
    private String title;

    /** 原始文件路径 */
    @Column(length = 1000)
    private String sourcePath;

    /** 文件类型：PDF / DOCX / PPTX / MD / TXT */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 文件大小（字节） */
    @Column(name = "file_size")
    private Long fileSize;

    /** ETL 处理状态 */
    @Column(name = "parse_status", nullable = false, length = 20)
    private String parseStatus;

    /** 分块数量（处理完成后填充） */
    @Column(name = "chunk_count")
    private Integer chunkCount;

    /** 处理失败时的错误信息 */
    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (parseStatus == null) parseStatus = "UPLOADED";
        if (chunkCount == null) chunkCount = 0;
        if (fileSize == null) fileSize = 0L;
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
