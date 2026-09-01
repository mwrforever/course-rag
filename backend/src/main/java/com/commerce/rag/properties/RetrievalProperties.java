package com.commerce.rag.properties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 检索链路参数配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code retrieval.*} 配置项。
 *
 * <p>原 {@code retrieval/FusionService}（rrf-k）、{@code retrieval/RerankService}
 * （rerank-threshold）、{@code bot/tool/SearchKnowledgeTool}（prefetch-top-k /
 * sparse-enabled）、{@code bot/graph/RetrieveNode}（retrieve-node-parallelism）
 * 各自经 {@code @Value} 散落注入，现收敛为本属性类统一强类型绑定（宪法 A.2.2）。
 * 默认值与原 {@code @Value} 兜底值逐一相同，行为零变化。
 *
 * <pre>
 * retrieval:
 *   rrf-k: 60
 *   rerank-threshold: 0.30
 *   prefetch-top-k: 20
 *   sparse-enabled: true
 *   retrieve-node-parallelism: 3
 * </pre>
 *
 * @param rrfK                   RRF 融合常数 k（宪法 D.5.3：官方推荐 [10,100] 区间实测调优；默认 60）
 * @param rerankThreshold        rerank 分数过滤阈值（0~1，低于阈值的候选丢弃；默认 0.30）
 * @param prefetchTopK           每条重写查询的 Milvus 预取数量（与注入 Top-N 构成 rerank 缓冲；默认 20）
 * @param sparseEnabled          sparse（BM25 全文检索）开关（默认 false=dense-only 降级兜底；
 *                               当前 application.yml 显式 true，回退预案见 TASK.md §4）
 * @param retrieveNodeParallelism RetrieveNode 三段远程 IO 并行线程数（默认 3，三任务各有线程可占）
 */
@Validated
@ConfigurationProperties(prefix = "retrieval")
public record RetrievalProperties(
        @DefaultValue("60") @Min(1) int rrfK,
        @DefaultValue("0.30") @DecimalMin("0.0") @DecimalMax("1.0") double rerankThreshold,
        @DefaultValue("20") @Min(1) int prefetchTopK,
        @DefaultValue("false") boolean sparseEnabled,
        @DefaultValue("3") @Min(1) int retrieveNodeParallelism) {}
