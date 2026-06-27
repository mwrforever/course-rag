package com.commerce.rag.retrieval.search;

import com.commerce.rag.config.RetrievalConfigProperties;
import com.commerce.rag.retrieval.rerank.RerankService;
import com.commerce.rag.retrieval.scoring.FinalScoreCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 混合检索服务（Dense + Keyword + RRF 融合）
 *
 * 核心检索链路，串联：
 * 1. Dense 向量检索（语义相似）
 * 2. Keyword 全文检索（精确匹配）
 * 3. RRF 融合两路结果（只看 rank 不看绝对分数）
 * 4. Rerank 精排（可选，THINKING 模式）
 * 5. 多因子评分（语义 + 热度）
 *
 * RRF 融合公式：score(d) = Σ 1/(k + rank_i(d))
 * 优势：Dense cosine ∈ [-1,1] 和 Keyword ts_rank ∈ [0,1] 量纲不可比，
 * RRF 只用 rank 规避了归一化问题。
 */
@Slf4j
@Service
public class HybridSearchService {

    private final DenseRetriever denseRetriever;
    private final KeywordRetriever keywordRetriever;
    private final RerankService rerankService;
    private final FinalScoreCalculator scoreCalculator;
    private final RetrievalConfigProperties config;

    public HybridSearchService(DenseRetriever denseRetriever,
                               KeywordRetriever keywordRetriever,
                               RerankService rerankService,
                               FinalScoreCalculator scoreCalculator,
                               RetrievalConfigProperties config) {
        this.denseRetriever = denseRetriever;
        this.keywordRetriever = keywordRetriever;
        this.rerankService = rerankService;
        this.scoreCalculator = scoreCalculator;
        this.config = config;
    }

    /**
     * 执行混合检索
     *
     * @param query   用户查询
     * @param kbId    知识库 ID（可选）
     * @param topK    最终返回数量
     * @param mode    检索模式：thinking（启用 rerank）/ quick（跳过 rerank）
     * @return 融合排序后的检索结果
     */
    public List<SearchResult> search(String query, UUID kbId, int topK, String mode) {
        long startTime = System.currentTimeMillis();

        // 预取数量：比 final_k 大几倍，RRF 才有充足候选
        int prefetchN = Math.max(topK * config.getPrefetchMultiplier(), topK + 8);

        // ── 1. 并行执行两路检索 ──
        List<SearchResult> denseResults = denseRetriever.search(query, prefetchN);
        List<SearchResult> keywordResults = keywordRetriever.search(query, kbId, prefetchN);

        log.info("两路检索完成: dense={}, keyword={}", denseResults.size(), keywordResults.size());

        // ── 2. RRF 融合 ──
        List<SearchResult> fusedResults = rrfFusion(denseResults, keywordResults, topK);

        if (fusedResults.isEmpty()) {
            log.warn("RRF 融合结果为空: query={}", query);
            return fusedResults;
        }

        // ── 3. Rerank（THINKING 模式） ──
        if ("thinking".equalsIgnoreCase(mode)) {
            fusedResults = rerankService.rerank(query, fusedResults);
        }

        // ── 4. 多因子评分 ──
        fusedResults = fusedResults.stream()
                .map(r -> r.withScore(scoreCalculator.calculate(r.score())))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();

        log.info("混合检索完成: query={}, 最终结果数={}, 总耗时={}ms",
                query, fusedResults.size(), System.currentTimeMillis() - startTime);
        return fusedResults;
    }

    /**
     * Reciprocal Rank Fusion (RRF) 融合
     *
     * 公式：score(d) = Σ_i 1/(k + rank_i(d))
     * - i ∈ {dense, keyword}
     * - k 为常数（默认 60），控制排名衰减速度
     * - 只看 rank 不看绝对分数，天然跨模态安全
     *
     * @param denseResults    dense 检索结果（已按分数排序）
     * @param keywordResults  keyword 检索结果（已按分数排序）
     * @param topK            融合后返回数量
     * @return 融合排序结果
     */
    private List<SearchResult> rrfFusion(List<SearchResult> denseResults,
                                          List<SearchResult> keywordResults,
                                          int topK) {
        int k = config.getRrfK();

        // 按 chunkId 累积 RRF 分数
        Map<String, SearchResult> resultMap = new LinkedHashMap<>();
        Map<String, Double> rrfScores = new HashMap<>();

        // Dense 通道：rank 从 1 开始
        for (int rank = 0; rank < denseResults.size(); rank++) {
            SearchResult r = denseResults.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            resultMap.put(r.chunkId(), r);
            rrfScores.merge(r.chunkId(), rrfScore, Double::sum);
        }

        // Keyword 通道：rank 从 1 开始
        for (int rank = 0; rank < keywordResults.size(); rank++) {
            SearchResult r = keywordResults.get(rank);
            double rrfScore = 1.0 / (k + rank + 1);
            // 如果 chunkId 已存在于 dense 结果中，保留 dense 的 content（通常更完整）
            resultMap.putIfAbsent(r.chunkId(), r);
            rrfScores.merge(r.chunkId(), rrfScore, Double::sum);
        }

        // 按 RRF 分数排序
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(entry -> {
                    SearchResult original = resultMap.get(entry.getKey());
                    return original.withScore(entry.getValue()).withSource("HYBRID");
                })
                .toList();
    }
}
