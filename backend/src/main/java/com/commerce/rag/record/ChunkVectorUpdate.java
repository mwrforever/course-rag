package com.commerce.rag.record;

/**
 * 分片向量回写记录 —— dense_vector / milvus_pk 批量回写参数（H-3）
 *
 * <p>ETL 向量化阶段批量 embedding 后，对一批分片执行单条批量 UPDATE
 * 回写 dense_vector（BYTEA）+ milvus_pk（原逐分片单条 UPDATE）。
 *
 * @param chunkId    分片 ID
 * @param denseVector embedding 输出向量（float[] → BYTEA）
 * @param milvusPk   Milvus 主键（恒等于分片 ID 字符串）
 */
public record ChunkVectorUpdate(Long chunkId, byte[] denseVector, String milvusPk) {}
