package com.commerce.rag.bot.tool.dto;

import com.commerce.rag.bot.IntentType;
import java.util.List;

/**
 * Knowledge base search result returned by {@code SearchKnowledgeTool}.
 *
 * <p>Record DTO — wraps the list of retrieved knowledge chunks after RRF fusion
 * and rerank. {@code chunkId} is retained on each chunk for B-side source tracing.
 *
 * @param chunks ranked knowledge chunks (best match first)
 */
public record KnowledgeSearchResult(List<KnowledgeChunk> chunks) {

    /**
     * A single retrieved knowledge chunk.
     *
     * <p>7 字段（对照设计 §2.4，含 docTitle）。
     *
     * @param chunkId        unique chunk identifier (for B-side source tracing)
     * @param content        chunk text content
     * @param source         source document name (deprecated — new schema has no source field;
     *                       currently set to empty string, will be populated from PG document.title)
     * @param docTitle       来源文档标题（从 PG document.title 关联查询，替代已废弃的 source 字段）
     * @param headingPath    heading path within the source document (e.g. "Ch3 > 3.2")
     * @param score          relevance score after rerank (0.0 ~ 1.0)
     * @param collectionType which intent partition this chunk belongs to
     */
    public record KnowledgeChunk(
            String chunkId,
            String content,
            @Deprecated String source,
            String docTitle,
            String headingPath,
            double score,
            IntentType collectionType) {}
}
