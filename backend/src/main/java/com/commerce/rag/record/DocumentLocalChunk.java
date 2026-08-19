package com.commerce.rag.record;

/**
 * 文档附件局部检索分片（内存态，不进 PG/Milvus）
 *
 * @param text   分片文本
 * @param vector 分片向量（embedding 模型输出）
 * @param index  文档内序号（0 起，检索结果定位用）
 */
public record DocumentLocalChunk(String text, float[] vector, int index) {}
