package com.commerce.rag.retrieval.search;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.SearchResults;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Dense 向量检索器（基于 Milvus）
 *
 * 使用 DashScope text-embedding-v3 将 query 编码为 dense vector，
 * 在 Milvus 中执行 COSINE 相似度检索。
 */
@Slf4j
@Component
public class DenseRetriever {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${milvus.collection-name:knowledge_chunks}")
    private String collectionName;

    public DenseRetriever(MilvusServiceClient milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 向量检索：query → embedding → Milvus search
     *
     * @param query 查询文本
     * @param topK  返回数量
     * @return 检索结果列表，按 COSINE 相似度降序
     */
    public List<SearchResult> search(String query, int topK) {
        long startTime = System.currentTimeMillis();

        try {
            // 1. 将 query 编码为向量
            float[] queryVector = embeddingModel.embed(query);
            List<Float> vectorList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                vectorList.add(v);
            }

            // 2. 构建 Milvus search 请求
            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(io.milvus.param.MetricType.COSINE)
                    .withTopK(topK)
                    .withVectors(List.of(vectorList))
                    .withVectorFieldName("dense_vector")
                    .withParams("{\"nprobe\": 16}")
                    .withOutFields(List.of("chunk_id", "doc_id", "content", "heading_path"))
                    .build();

            // 3. 执行检索
            R<SearchResults> result = milvusClient.search(searchParam);
            if (result.getStatus() != R.Status.Success.getCode()) {
                log.warn("Milvus 向量检索失败: {}", result.getMessage());
                return List.of();
            }

            // 4. 解析结果
            List<SearchResult> results = parseSearchResults(result.getData());

            log.info("Dense 检索完成: query={}, 召回数={}, 耗时={}ms",
                    query, results.size(), System.currentTimeMillis() - startTime);
            return results;

        } catch (Exception e) {
            log.error("Dense 检索异常: query={}", query, e);
            return List.of();
        }
    }

    /**
     * 解析 Milvus SearchResults 为统一的 SearchResult 列表
     */
    private List<SearchResult> parseSearchResults(SearchResults searchResults) {
        List<SearchResult> results = new ArrayList<>();
        SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResults.getResults());

        for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
            SearchResultsWrapper.IDScore idScore = wrapper.getIDScore(0).get(i);
            Object chunkId = wrapper.getRowRecords(0).get(i).get("chunk_id");
            Object docId = wrapper.getRowRecords(0).get(i).get("doc_id");
            Object content = wrapper.getRowRecords(0).get(i).get("content");
            Object headingPath = wrapper.getRowRecords(0).get(i).get("heading_path");

            results.add(new SearchResult(
                    String.valueOf(chunkId),
                    String.valueOf(docId),
                    String.valueOf(content),
                    String.valueOf(headingPath),
                    idScore.getScore(),
                    "DENSE"
            ));
        }
        return results;
    }
}
