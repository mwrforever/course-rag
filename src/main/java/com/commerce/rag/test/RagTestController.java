package com.commerce.rag.test;

import com.commerce.rag.retrieval.search.HybridSearchService;
import com.commerce.rag.retrieval.search.SearchResult;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * RAG 测试 API 控制器
 *
 * 提供独立的测试接口，用于验证 RAG 各环节质量：
 * - 检索测试：验证召回率和排名
 * - 端到端测试：评估完整问答质量
 * - 批量测试：从 JSON 加载测试集批量跑分
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
public class RagTestController {

    private final HybridSearchService hybridSearch;
    private final RagTestRunner testRunner;

    public RagTestController(HybridSearchService hybridSearch, RagTestRunner testRunner) {
        this.hybridSearch = hybridSearch;
        this.testRunner = testRunner;
    }

    /**
     * 测试检索结果
     *
     * @param request 包含 query 和期望的 chunk ID 列表
     * @return 检索结果和评估指标
     */
    @PostMapping("/retrieval")
    public RetrievalTestResult testRetrieval(@RequestBody RetrievalTestRequest request) {
        log.info("检索测试: query={}", request.getQuery());

        List<SearchResult> results = hybridSearch.search(
                request.getQuery(), null, request.getTopK(), "thinking");

        // 计算 Recall@K
        Set<String> expectedIds = new HashSet<>(request.getExpectedChunkIds());
        Set<String> actualIds = results.stream().map(SearchResult::chunkId).collect(java.util.stream.Collectors.toSet());
        actualIds.retainAll(expectedIds);
        double recall = expectedIds.isEmpty() ? 0 : (double) actualIds.size() / expectedIds.size();

        // 计算 MRR（期望 ID 在结果中的最高排名的倒数）
        double mrr = 0;
        for (int i = 0; i < results.size(); i++) {
            if (expectedIds.contains(results.get(i).chunkId())) {
                mrr = 1.0 / (i + 1);
                break;
            }
        }

        return new RetrievalTestResult(
                request.getQuery(),
                recall,
                mrr,
                results.size(),
                results.stream().map(r -> Map.<String, Object>of(
                        "chunkId", r.chunkId(),
                        "headingPath", r.headingPath() != null ? r.headingPath() : "",
                        "score", r.score()
                )).toList()
        );
    }

    /**
     * 批量测试
     *
     * @param testCases 测试用例列表
     * @return 批量测试结果
     */
    @PostMapping("/batch")
    public BatchTestResult batchTest(@RequestBody List<RetrievalTestRequest> testCases) {
        log.info("批量测试开始: 用例数={}", testCases.size());
        List<RetrievalTestResult> results = new ArrayList<>();

        for (RetrievalTestRequest tc : testCases) {
            results.add(testRetrieval(tc));
        }

        double avgRecall = results.stream().mapToDouble(RetrievalTestResult::recall).average().orElse(0);
        double avgMrr = results.stream().mapToDouble(RetrievalTestResult::mrr).average().orElse(0);

        return new BatchTestResult(testCases.size(), avgRecall, avgMrr, results);
    }

    // ── DTO 定义 ──

    @Data
    public static class RetrievalTestRequest {
        private String query;
        private List<String> expectedChunkIds = List.of();
        private int topK = 5;
    }

    public record RetrievalTestResult(
            String query,
            double recall,
            double mrr,
            int resultCount,
            List<Map<String, Object>> results
    ) {}

    public record BatchTestResult(
            int totalCases,
            double avgRecall,
            double avgMrr,
            List<RetrievalTestResult> details
    ) {}
}
