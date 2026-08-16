package com.commerce.rag.record;

/**
 * 分片链路回填对 —— next_chunk_id 批量回填参数（M-1）
 *
 * <p>ETL 分片落库后，对「有前驱的分片」执行单条批量 UPDATE 回填 next_chunk_id
 * （原逐分片单条 UPDATE，N 分片 N 次 SQL → 1 次）。
 *
 * @param prevChunkId 前驱分片 ID（即待回填 next_chunk_id 的分片）
 * @param nextChunkId 后继分片 ID（写入 prevChunkId 的 next_chunk_id）
 */
public record ChunkLinkPair(Long prevChunkId, Long nextChunkId) {}
