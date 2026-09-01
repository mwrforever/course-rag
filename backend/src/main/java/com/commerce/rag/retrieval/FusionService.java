package com.commerce.rag.retrieval;

import com.commerce.rag.bot.tool.TypedQuery;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.properties.RetrievalProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * RRF（Reciprocal Rank Fusion）融合服务 —— 跨多条查询结果融合排序
 *
 * <p>对多条 rewritten query 各自检索的结果进行 RRF 融合，消除查询偏差，
 * 再用 rerank 精排。RRF 公式：score(d) = Σ(1 / (k + rank_i(d)))
 *
 * <p>RRF 融合常数 k 经 {@link RetrievalProperties}（retrieval.rrf-k）强类型注入
 * （BUG-12 @Value 收敛，宪法 A.2.2）。
 *
 * @author commerce-rag
 */
@Service
public class FusionService {

    private final int rrfK;

    public FusionService(RetrievalProperties retrievalProperties) {
        this.rrfK = retrievalProperties.rrfK();
    }

    /**
     * 跨查询结果融合 → 合并去重 → 按 RRF 分数排序
     *
     * @param queryResults 按 query 分组的检索结果
     * @return 融合后按 RRF 分数降序排列的 chunk 列表
     */
    public List<KnowledgeChunk> fuse(Map<TypedQuery, List<KnowledgeChunk>> queryResults) {
        if (queryResults == null || queryResults.isEmpty()) {
            return Collections.emptyList();
        }

        // chunkId → 累积 RRF 分数
        Map<String, Double> rrfScores = new LinkedHashMap<>();
        // chunkId → chunk 对象（保留首次出现的实例）
        Map<String, KnowledgeChunk> chunkMap = new LinkedHashMap<>();

        for (Map.Entry<TypedQuery, List<KnowledgeChunk>> entry : queryResults.entrySet()) {
            List<KnowledgeChunk> ranked = entry.getValue();
            for (int rank = 0; rank < ranked.size(); rank++) {
                KnowledgeChunk chunk = ranked.get(rank);
                String id = chunk.chunkId();
                double score = 1.0 / (rrfK + rank + 1);
                rrfScores.merge(id, score, Double::sum);
                chunkMap.putIfAbsent(id, chunk);
            }
        }

        // 按 RRF 分数降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> chunkMap.get(e.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * 同源结果去重 —— 单 query 多结果按 chunkId 去重
     */
    public List<KnowledgeChunk> deduplicate(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, KnowledgeChunk> seen = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            seen.putIfAbsent(chunk.chunkId(), chunk);
        }
        return new ArrayList<>(seen.values());
    }
}
