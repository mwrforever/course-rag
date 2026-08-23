package com.commerce.rag.record;

/**
 * 分片链路回填对 —— prev/next 双向指针批量回填参数（M-1 + P1-4）
 *
 * <p>ETL 分片批量插入（ID 由 MP 参数处理器填充）后，单条批量 UPDATE 同时回填：
 * 前驱分片的 next_chunk_id 与后继分片的 prev_chunk_id
 * （原逐分片单条 UPDATE，N 分片 N 次 SQL → 1 次；批插前 ID 未知故双向均延后回填）。
 *
 * @param prevChunkId 前驱分片 ID（其 next_chunk_id 回填为 nextChunkId）
 * @param nextChunkId 后继分片 ID（其 prev_chunk_id 回填为 prevChunkId）
 */
public record ChunkLinkPair(Long prevChunkId, Long nextChunkId) {}
