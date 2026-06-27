package com.commerce.rag.retrieval.search;

/**
 * 检索结果
 *
 * 统一的检索结果结构，Dense / Keyword / Hybrid 三路共用。
 *
 * @param chunkId     分块 ID（PG 中的 UUID）
 * @param docId       所属文档 ID
 * @param content     分块文本内容
 * @param headingPath 标题层级路径
 * @param score       检索分数（不同阶段含义不同：向量距离/RRF分/最终综合分）
 * @param source      来源标识：DENSE / KEYWORD / HYBRID
 */
public record SearchResult(
        String chunkId,
        String docId,
        String content,
        String headingPath,
        double score,
        String source
) {
    /**
     * 创建一个带新分数的副本（用于 rerank 后更新分数）
     */
    public SearchResult withScore(double newScore) {
        return new SearchResult(chunkId, docId, content, headingPath, newScore, source);
    }

    /**
     * 创建一个带新来源标识的副本
     */
    public SearchResult withSource(String newSource) {
        return new SearchResult(chunkId, docId, content, headingPath, score, newSource);
    }
}
