package com.commerce.rag.etl.store;

import com.commerce.rag.etl.chunker.TextChunk;
import com.commerce.rag.knowledge.entity.DocumentChunk;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * PostgreSQL 关键字索引存储
 *
 * 负责：
 * 1. 将 chunk 内容写入 document_chunk 表（触发器自动更新 tsvector 列）
 * 2. 按 doc_id 删除旧分块（增量更新）
 * 3. 全文检索查询（供 retrieval 模块的 KeywordRetriever 调用）
 *
 * tsvector GIN 索引由 V3 迁移脚本创建，INSERT/UPDATE 触发器自动维护。
 */
@Slf4j
@Component
public class KeywordIndexStore {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 批量保存 chunk 到 PostgreSQL（tsvector 由触发器自动更新）
     *
     * @param chunks 分块列表
     * @param docId  所属文档 ID
     * @param kbId   所属知识库 ID
     */
    @Transactional
    public void batchSave(List<TextChunk> chunks, UUID docId, UUID kbId) {
        log.info("批量保存 chunk 到 PostgreSQL: docId={}, 数量={}", docId, chunks.size());

        for (TextChunk chunk : chunks) {
            DocumentChunk entity = DocumentChunk.builder()
                    .docId(docId)
                    .kbId(kbId)
                    .chunkIndex(chunk.metadata().chunkIndex())
                    .content(chunk.content())
                    .headingPath(chunk.metadata().headingPath())
                    .parentTitle(chunk.metadata().parentTitle())
                    .startPage(chunk.metadata().startPage())
                    .endPage(chunk.metadata().endPage())
                    .tokenCount(chunk.metadata().tokenCount())
                    .activeCount(0)
                    .build();
            entityManager.persist(entity);
        }

        log.info("PostgreSQL 保存完成: docId={}", docId);
    }

    /**
     * 按 doc_id 删除旧分块
     *
     * @param docId 文档 ID
     * @return 删除的记录数
     */
    @Transactional
    public int deleteByDocId(UUID docId) {
        int deleted = entityManager.createQuery(
                        "DELETE FROM DocumentChunk WHERE docId = :docId")
                .setParameter("docId", docId)
                .executeUpdate();
        log.info("删除旧分块: docId={}, 删除数={}", docId, deleted);
        return deleted;
    }

    /**
     * 全文检索：基于 tsvector 的关键字检索
     *
     * 使用 PostgreSQL ts_rank_cd 函数计算相关性分数。
     * 返回结果按分数降序排列。
     *
     * @param query 用户查询（会做简单分词处理）
     * @param kbId  知识库 ID（可选，为 null 时不限制）
     * @param limit 返回数量上限
     * @return 检索结果列表（chunk_id, content, heading_path, score）
     */
    @SuppressWarnings("unchecked")
    public List<Object[]> fullTextSearch(String query, UUID kbId, int limit) {
        // 将用户 query 转换为 tsquery 格式（空格分隔的词用 & 连接）
        String tsQuery = buildTsQuery(query);

        String sql;
        if (kbId != null) {
            sql = String.format("""
                SELECT id::text, content, heading_path, doc_id::text,
                       ts_rank_cd(content_tsv, to_tsquery('simple', '%s')) as score
                FROM document_chunk
                WHERE content_tsv @@ to_tsquery('simple', '%s')
                  AND kb_id = '%s'
                ORDER BY score DESC
                LIMIT %d
                """, tsQuery, tsQuery, kbId, limit);
        } else {
            sql = String.format("""
                SELECT id::text, content, heading_path, doc_id::text,
                       ts_rank_cd(content_tsv, to_tsquery('simple', '%s')) as score
                FROM document_chunk
                WHERE content_tsv @@ to_tsquery('simple', '%s')
                ORDER BY score DESC
                LIMIT %d
                """, tsQuery, tsQuery, limit);
        }

        return entityManager.createNativeQuery(sql).getResultList();
    }

    /**
     * 将自然语言 query 转换为 PostgreSQL tsquery 格式
     *
     * 简单策略：按空格拆分词，用 & 连接。
     * 生产环境建议使用 zhparser 或 pg_jieba 做中文分词。
     */
    private String buildTsQuery(String query) {
        if (query == null || query.isBlank()) return "";
        // 转义单引号，按空格拆分词，用 OR 连接（宽召回）
        String[] terms = query.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < terms.length; i++) {
            String term = terms[i].replaceAll("'", "''");
            if (i > 0) sb.append(" | ");
            sb.append(term);
        }
        return sb.toString();
    }
}
