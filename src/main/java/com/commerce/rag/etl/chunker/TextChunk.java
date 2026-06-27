package com.commerce.rag.etl.chunker;

/**
 * 文本分块
 *
 * @param content  分块的文本内容
 * @param metadata 分块元数据
 */
public record TextChunk(
        String content,
        ChunkMetadata metadata
) {}
