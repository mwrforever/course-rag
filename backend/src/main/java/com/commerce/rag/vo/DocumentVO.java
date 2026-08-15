package com.commerce.rag.vo;

import java.time.LocalDateTime;

/**
 * 文档视图对象 —— controller 出参（B 端管理接口）
 *
 * <p>与 Document 实体同名业务字段一一对应，剔除敏感列：
 * <ul>
 *   <li>sourcePath（MinIO objectKey）—— 内部存储路径，不对外暴露</li>
 *   <li>deleted（逻辑删除标记）—— 内部数据管理字段</li>
 * </ul>
 *
 * @param id            文档 ID
 * @param kbId          所属知识库 ID
 * @param title         文档标题
 * @param fileType      文件类型（pdf/docx/pptx/md 等）
 * @param fileSize      文件大小（字节）
 * @param parseStatus   解析状态（PENDING/PARSING/PARSED/CHUNKING/CHUNKED/EMBEDDING/INDEXED/FAILED）
 * @param chunkCount    分片数量
 * @param errorMessage  错误信息（parse_status=FAILED 时填充）
 * @param metadataJson  元数据 JSON（Tika 解析的作者、页数等）
 * @param courseId      课程 ID（DEFAULT=通用资料库）
 * @param createdBy     创建者 ID
 * @param createdAt     创建时间
 * @param updatedAt     更新时间
 */
public record DocumentVO(
        Long id,
        Long kbId,
        String title,
        String fileType,
        Long fileSize,
        String parseStatus,
        Integer chunkCount,
        String errorMessage,
        String metadataJson,
        String courseId,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
