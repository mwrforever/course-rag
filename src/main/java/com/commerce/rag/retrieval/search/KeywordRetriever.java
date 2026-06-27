package com.commerce.rag.retrieval.search;

import com.commerce.rag.etl.store.KeywordIndexStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 关键字检索器（基于 PostgreSQL 全文检索）
 *
 * 使用 PostgreSQL tsvector + GIN 索引实现关键字匹配，
 * 作为 hybrid search 的 sparse 通道。
 *
 * 与 Dense 向量检索互补：
 * - Dense 擅长语义相似（近义、转述）
 * - Keyword 擅长精确匹配（专有名词、错误码、API 名等）
 */
@Slf4j
@Component
public class KeywordRetriever {

    private final KeywordIndexStore keywordIndexStore;

    public KeywordRetriever(KeywordIndexStore keywordIndexStore) {
        this.keywordIndexStore = keywordIndexStore;
    }

    /**
     * 关键字全文检索
     *
     * @param query 查询文本
     * @param kbId  知识库 ID（可选）
     * @param topK  返回数量
     * @return 检索结果列表，按 ts_rank 分数降序
     */
    public List<SearchResult> search(String query, UUID kbId, int topK) {
        long startTime = System.currentTimeMillis();

        try {
            List<Object[]> rawResults = keywordIndexStore.fullTextSearch(query, kbId, topK);
            List<SearchResult> results = new ArrayList<>();

            for (Object[] row : rawResults) {
                results.add(new SearchResult(
                        String.valueOf(row[0]),    // chunk_id
                        String.valueOf(row[3]),    // doc_id
                        String.valueOf(row[1]),    // content
                        String.valueOf(row[2]),    // heading_path
                        ((Number) row[4]).doubleValue(),  // score
                        "KEYWORD"
                ));
            }

            log.info("Keyword 检索完成: query={}, 召回数={}, 耗时={}ms",
                    query, results.size(), System.currentTimeMillis() - startTime);
            return results;

        } catch (Exception e) {
            log.error("Keyword 检索异常: query={}", query, e);
            return List.of();
        }
    }
}
