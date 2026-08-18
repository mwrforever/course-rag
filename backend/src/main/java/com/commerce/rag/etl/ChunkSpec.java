package com.commerce.rag.etl;

/**
 * 待落库分片规格 —— 分片阶段的临时数据结构（三种内容类型统一载体）
 *
 * @param content         分片内容（text 正文 / image caption / table Markdown）
 * @param headingPath     标题导航路径（如「第一章 > 1.1 小节」）
 * @param contentType     内容类型：text / image / table
 * @param imageUrl        图片分片的 MinIO objectKey（其余类型为 null）
 * @param metadataJson    附加元数据 JSON（如图片 resourceName）
 * @param charOffsetStart 原文字符偏移起点（尽力而为，图片为 null）
 * @param charOffsetEnd   原文字符偏移终点（尽力而为，图片为 null）
 *
 * @author commerce-rag
 */
public record ChunkSpec(
        String content,
        String headingPath,
        String contentType,
        String imageUrl,
        String metadataJson,
        Integer charOffsetStart,
        Integer charOffsetEnd) {}
