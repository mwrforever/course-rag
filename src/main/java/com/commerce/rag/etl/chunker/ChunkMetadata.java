package com.commerce.rag.etl.chunker;

/**
 * 分块元数据
 *
 * 每个 chunk 携带的上下文信息，用于：
 * 1. 写入 Milvus 时作为 payload 字段
 * 2. 检索时过滤和排序
 * 3. 回答生成时标注来源引用
 *
 * @param docId       所属文档 ID
 * @param chunkIndex  在文档中的顺序（从 0 开始）
 * @param parentTitle 所属章节标题
 * @param headingPath 标题层级路径，如 "第1章 > 1.2 > 1.2.1"
 * @param startPage   起始页码（0 表示无页码）
 * @param endPage     结束页码
 * @param tokenCount  估算 token 数量
 */
public record ChunkMetadata(
        String docId,
        int chunkIndex,
        String parentTitle,
        String headingPath,
        int startPage,
        int endPage,
        int tokenCount
) {}
