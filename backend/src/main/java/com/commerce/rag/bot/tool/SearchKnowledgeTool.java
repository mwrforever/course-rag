package com.commerce.rag.bot.tool;

import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.record.ContentHash;
import com.commerce.rag.retrieval.FusionService;
import com.commerce.rag.retrieval.RerankService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 统一知识检索工具（v2 API）—— 单 Collection + 意图检索解耦 + 混合检索
 *
 * <p>核心架构：
 * <ul>
 *   <li>单 Milvus Collection {@code knowledge_chunks}，S1 意图-检索解耦：
 *       意图判定由上游节点完成，检索不再按 {@code collection_type} 过滤，
 *       仅按课程归属 {@code course_id} 收窄检索范围</li>
 *   <li><b>混合检索</b>：dense（FloatVec + COSINE）+ sparse（EmbeddedText + BM25），
 *       由 RRFRanker(k=60) 融合</li>
 *   <li>并行检索：接收 {@code List<TypedQuery>}，CompletableFuture.allOf 并发执行</li>
 *   <li>容错：单个 query 失败 catch 返回空列表，不中断其他</li>
 *   <li>融合：RRF 跨查询融合 → 去重 → rerank → 返回 Top-K</li>
 *   <li>降级：Milvus 不可用时返回空 + {@code degraded=true}</li>
 * </ul>
 *
 * @author commerce-rag
 * @see TypedQuery
 * @see KnowledgeSearchResult
 */
@Component
public class SearchKnowledgeTool {

    private static final Logger log = LoggerFactory.getLogger(SearchKnowledgeTool.class);

    /** Milvus Collection 名称（引用 MilvusCollectionInitializer 公开常量） */
    private static final String COLLECTION_NAME = MilvusCollectionInitializer.COLLECTION_NAME;

    /** dense 向量字段名（引用 MilvusCollectionInitializer 公开常量） */
    private static final String DENSE_FIELD_NAME = MilvusCollectionInitializer.FIELD_DENSE_VECTOR;

    /** sparse 向量字段名（引用 MilvusCollectionInitializer 公开常量） */
    private static final String SPARSE_FIELD_NAME = MilvusCollectionInitializer.FIELD_SPARSE_VECTOR;

    /** HNSW 搜索参数 */
    private static final String HNSW_PARAMS = "{\"ef\": 64}";

    /**
     * Milvus 输出字段列表（10 个标量字段，不含 source/dense_vector/sparse_vector）
     * 字段名全部引用 MilvusCollectionInitializer 公开常量，确保一致
     */
    private static final List<String> OUTPUT_FIELDS = List.of(
            MilvusCollectionInitializer.FIELD_CHUNK_ID,
            MilvusCollectionInitializer.FIELD_CONTENT,
            MilvusCollectionInitializer.FIELD_HEADING_PATH,
            MilvusCollectionInitializer.FIELD_COURSE_ID,
            MilvusCollectionInitializer.FIELD_DOC_ID,
            MilvusCollectionInitializer.FIELD_KB_ID,
            MilvusCollectionInitializer.FIELD_CHUNK_INDEX,
            MilvusCollectionInitializer.FIELD_TOKEN_COUNT,
            MilvusCollectionInitializer.FIELD_UPDATED_AT,
            MilvusCollectionInitializer.FIELD_SHA256);

    private final FusionService fusionService;
    private final RerankService rerankService;
    private final EmbeddingModel embeddingModel;
    private final MilvusClientV2 milvusClientV2;
    private final int rrfK;
    /** Milvus 混合检索返回的 Top-K 数量（每条重写查询的预取量，spec §3.1 配置化） */
    private final int prefetchTopK;
    /**
     * sparse（BM25 全文检索）开关：milvus-sdk-java 的 EmbeddedText 在 sparse/混合检索存在
     * 未修复 bug（issue #1402，服务端 INTERNAL 后 SDK 无限重试至超时），默认关闭降级为
     * dense-only 混合检索；SDK 修复后置 true 恢复全文检索能力
     */
    private final boolean sparseEnabled;

    private final ExecutorService searchExecutor;

    public SearchKnowledgeTool(
            FusionService fusionService,
            RerankService rerankService,
            EmbeddingModel embeddingModel,
            MilvusClientV2 milvusClientV2,
            @Value("${milvus.sparse-bm25-k:60}") int rrfK,
            @Value("${retrieval.prefetch-top-k:20}") int prefetchTopK,
            @Value("${retrieval.sparse-enabled:false}") boolean sparseEnabled) {
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.embeddingModel = embeddingModel;
        this.milvusClientV2 = milvusClientV2;
        this.rrfK = rrfK;
        this.prefetchTopK = prefetchTopK;
        this.sparseEnabled = sparseEnabled;
        this.searchExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "search-knowledge-");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 统一检索入口 —— RetrieveNode 编排调用
     *
     * <p>返回 KnowledgeSearchResult 对象（record DTO），非 String。
     * S1 检索链路重构：本方法由 RetrieveNode（图节点）调用，检索结果不直接进入模型上下文。
     *
     * <p>预向量消重（方案 3-1-a）：首条查询若是经历记忆召回重复使用的文本，其向量由
     * RetrieveNode 预嵌入 {@code firstQueryVector} 传入——query[0] 不再次远程 embed，
     * 其余查询仍内部 embed；null/空则 query[0] 走内部自嵌兜底（与改造前行为等价）。
     *
     * @param queries           查询重写后的多条覆盖性查询
     * @param firstQueryVector  首条查询的预嵌入向量（null/空 → 内部自嵌兜底）
     * @return 去重、融合、精排后的知识检索结果
     */
    public KnowledgeSearchResult searchKnowledge(List<TypedQuery> queries, float[] firstQueryVector) {
        if (queries == null || queries.isEmpty()) {
            return new KnowledgeSearchResult(Collections.emptyList());
        }

        log.info("开始知识检索: queries={}", queries.size());

        // 1. 并行检索
        Map<TypedQuery, List<KnowledgeChunk>> rawResults = searchInParallel(queries, firstQueryVector);

        // 2. RRF 融合 + chunk_id 去重
        List<KnowledgeChunk> fused = fusionService.fuse(rawResults);

        // 3. SHA256 内容去重（spec §3.1：去重在 rerank 之前，同 hash 保留 RRF 分数最高一条，
        //    不为重复内容付 rerank 费用）
        List<KnowledgeChunk> deduped = deduplicateBySha256(fused);

        // 4. Rerank 精排（取第一条 query 作为 rerank anchor）
        String anchorQuery = queries.get(0).queryText();
        List<KnowledgeChunk> reranked = rerankService.rerank(anchorQuery, deduped);

        log.info(
                "检索完成: 原始={}, 融合后={}, 内容去重后={}, 精排后={}",
                rawResults.values().stream().mapToInt(List::size).sum(),
                fused.size(),
                deduped.size(),
                reranked.size());

        return new KnowledgeSearchResult(reranked);
    }

    /**
     * 并行检索 —— 每条 TypedQuery 独立查询 Milvus，CompletableFuture.allOf 汇总
     *
     * @param queries           查询列表
     * @param firstQueryVector  首条查询的预嵌入向量（null/空 → 首条内部自嵌兜底）
     */
    private Map<TypedQuery, List<KnowledgeChunk>> searchInParallel(List<TypedQuery> queries, float[] firstQueryVector) {
        Map<TypedQuery, List<KnowledgeChunk>> results = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(
                                () -> searchSingle(q, isFirst(q, queries) ? firstQueryVector : null), searchExecutor)
                        .thenAccept(chunks -> {
                            synchronized (results) {
                                results.put(q, chunks);
                            }
                        }))
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("并行检索超时: {}", e.getMessage());
        }

        return results;
    }

    /** 判断查询是否为列表首条（与调用方传入的预向量对应） */
    private static boolean isFirst(TypedQuery candidate, List<TypedQuery> queries) {
        return !queries.isEmpty() && queries.get(0) == candidate;
    }

    /**
     * 单条查询的 Milvus 混合检索逻辑（v2 API：hybridSearch）
     *
     * <p>流程：
     * <ol>
     *   <li>查询向量化：{@code precomputedVector} 非空（首条查询预嵌入，方案 3-1-a 消重）
     *       则直接复用，否则使用 EmbeddingModel 将 query.queryText() 向量化 → dense 向量</li>
     *   <li>构建 Milvus 过滤表达式（仅 course_id 子句，无 courseIds 时不设过滤）</li>
     *   <li>构建 dense AnnSearchReq（FloatVec + COSINE）</li>
     *   <li>构建 sparse AnnSearchReq（EmbeddedText + BM25）</li>
     *   <li>构建 HybridSearchReq（RRFRanker(k=60) 融合）</li>
     *   <li>调用 milvusClientV2.hybridSearch() 执行混合检索</li>
     *   <li>解析 SearchResp.getSearchResults() → List&lt;KnowledgeChunk&gt;</li>
     * </ol>
     *
     * <p>降级：任何异常时返回空列表，不中断主流程。
     *
     * @param query              类型化查询
     * @param precomputedVector  预嵌入向量（首条查询传入；null/空 → 内部自嵌兜底）
     * @return 检索到的 KnowledgeChunk 列表（可能为空）
     */
    List<KnowledgeChunk> searchSingle(TypedQuery query, float[] precomputedVector) {
        try {
            // 1. 向量化查询文本（dense 向量）：优先复用首条查询的预嵌入向量（不重复远程调用）
            float[] denseVector = (precomputedVector != null && precomputedVector.length > 0)
                    ? precomputedVector
                    : embeddingModel.embed(query.queryText());
            if (denseVector == null || denseVector.length == 0) {
                log.warn("Embedding 返回空向量: query={}", truncate(query.queryText(), 30));
                return Collections.emptyList();
            }

            // 2. 构建过滤表达式
            String filterExpr = buildFilterExpression(query);

            log.debug(
                    "Milvus 混合检索: type={}, query={}, courseIds={}, expr={}",
                    query.collectionType(),
                    truncate(query.queryText(), 30),
                    query.courseIds(),
                    filterExpr);

            // 3. 构建 dense AnnSearchReq（FloatVec + COSINE）
            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectors(List.of(new FloatVec(denseVector)))
                    .vectorFieldName(DENSE_FIELD_NAME)
                    .metricType(IndexParam.MetricType.COSINE)
                    .params(HNSW_PARAMS)
                    .limit(prefetchTopK)
                    .filter(filterExpr)
                    .build();

            // 4. 构建 sparse AnnSearchReq（EmbeddedText + BM25，全文检索）
            //    sparseEnabled=false 时省略（milvus-sdk-java EmbeddedText bug，见字段注释）
            List<AnnSearchReq> searchRequests = new ArrayList<>(2);
            searchRequests.add(denseReq);
            if (sparseEnabled) {
                searchRequests.add(AnnSearchReq.builder()
                        .vectors(List.of(new EmbeddedText(query.queryText())))
                        .vectorFieldName(SPARSE_FIELD_NAME)
                        .metricType(IndexParam.MetricType.BM25)
                        .limit(prefetchTopK)
                        .filter(filterExpr)
                        .build());
            }

            // 5. 构建 HybridSearchReq（RRFRanker 融合；sparse 关闭时等价于单路 dense 检索）
            HybridSearchReq hybridSearchReq = HybridSearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .searchRequests(searchRequests)
                    .ranker(RRFRanker.builder().k(rrfK).build())
                    .limit(prefetchTopK)
                    .outFields(OUTPUT_FIELDS)
                    .build();

            // 6. 执行混合检索
            SearchResp searchResp = milvusClientV2.hybridSearch(hybridSearchReq);
            if (searchResp == null) {
                log.warn("Milvus 返回空结果: query={}", truncate(query.queryText(), 30));
                return Collections.emptyList();
            }

            // 7. 解析结果
            // SearchResp.getSearchResults() 返回 List<List<SearchResult>>
            // 对于单次混合检索，取第一个查询的结果列表
            List<List<SearchResp.SearchResult>> searchResults = searchResp.getSearchResults();
            if (searchResults == null || searchResults.isEmpty()) {
                log.debug("Milvus 无匹配结果: query={}", truncate(query.queryText(), 30));
                return Collections.emptyList();
            }

            List<SearchResp.SearchResult> results = searchResults.get(0);
            if (results == null || results.isEmpty()) {
                log.debug("Milvus 无匹配结果: query={}", truncate(query.queryText(), 30));
                return Collections.emptyList();
            }

            // 8. 映射为 KnowledgeChunk 列表
            List<KnowledgeChunk> chunks = new ArrayList<>(results.size());
            for (SearchResp.SearchResult sr : results) {
                Map<String, Object> entity = sr.getEntity();
                if (entity == null) {
                    continue;
                }

                float score = sr.getScore() != null ? sr.getScore() : 0.0f;
                String chunkId = getStr(entity, MilvusCollectionInitializer.FIELD_CHUNK_ID);
                String content = getStr(entity, MilvusCollectionInitializer.FIELD_CONTENT);
                String headingPath = getStr(entity, MilvusCollectionInitializer.FIELD_HEADING_PATH);

                // S1 意图-检索解耦：不再从 collection_type 解析意图，构造传 null（上游节点判定）
                String sha256 = getStr(entity, MilvusCollectionInitializer.FIELD_SHA256);
                chunks.add(new KnowledgeChunk(
                        chunkId, content, "", "", headingPath, score, null, sha256.isBlank() ? null : sha256));
            }

            log.debug("单条检索完成: type={}, 结果数={}", query.collectionType(), chunks.size());
            return chunks;

        } catch (Exception e) {
            log.warn("单条检索失败(降级): type={}, error={}", query.collectionType(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * SHA256 内容去重 —— 同归一化内容哈希只保留一条（spec §3.1 防御性兜底）
     *
     * <p>入参为 FusionService 输出的 RRF 分数降序列表，首个出现即分数最高，
     * 因此 LinkedHashMap putIfAbsent 保留首个即为「保留 RRF 融合分数最高一条」。
     *
     * <p>sha256 取 Milvus 返回字段（计划 1/5 ETL 全量写入）；空值时用
     * {@link com.commerce.rag.record.ContentHash#of(String)} 对 content 归一化计算保底，
     * 仍不可得（内容本身为空）则退化为按 chunkId 保底（不误删）。
     *
     * @param chunks RRF 融合后按分数降序的候选（可为空）
     * @return 按 sha256 去重后的候选列表（保持原降序）
     */
    List<KnowledgeChunk> deduplicateBySha256(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks == null ? Collections.emptyList() : chunks;
        }
        Map<String, KnowledgeChunk> seen = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks) {
            String hash = chunk.sha256();
            if (hash == null || hash.isBlank()) {
                hash = ContentHash.of(chunk.content()).sha256();
            }
            if (hash == null || hash.isBlank()) {
                hash = "chunk:" + chunk.chunkId();
            }
            seen.putIfAbsent(hash, chunk);
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * 构建 Milvus 标量过滤表达式（S1 意图-检索解耦：不再按 collection_type 过滤）
     *
     * <p>格式：
     * <ul>
     *   <li>无 courseIds：返回 null（不设过滤，全局检索）</li>
     *   <li>有 courseIds：{@code (course_id == "DEFAULT" or course_id in ["C1", "C2"])}</li>
     * </ul>
     *
     * @param query 类型化查询
     * @return Milvus 过滤表达式字符串，无过滤条件时为 null
     */
    String buildFilterExpression(TypedQuery query) {
        if (query.courseIds() == null || query.courseIds().isEmpty()) {
            return null;
        }
        String courseList =
                query.courseIds().stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        return "(course_id == \"DEFAULT\" or course_id in [" + courseList + "])";
    }

    /**
     * 从 entity Map 中安全获取字符串值
     */
    private static String getStr(Map<String, Object> entity, String key) {
        Object val = entity.get(key);
        if (val == null) return "";
        return String.valueOf(val);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
