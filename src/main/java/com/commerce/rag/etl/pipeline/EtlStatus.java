package com.commerce.rag.etl.pipeline;

/**
 * ETL 处理状态枚举
 *
 * 状态机流转：UPLOADED → PARSING → CHUNKING → EMBEDDING → INDEXING → COMPLETED / FAILED
 */
public enum EtlStatus {
    UPLOADED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    INDEXING,
    COMPLETED,
    FAILED
}
