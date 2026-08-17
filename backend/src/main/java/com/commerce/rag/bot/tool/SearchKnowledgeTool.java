package com.commerce.rag.bot.tool;

import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.config.MilvusCollectionInitializer;
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
import org.springframework.ai.tool.annotation.Tool;
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

    /** Milvus 检索返回的 Top-K 数量 */
    private static final int SEARCH_TOP_K = 20;

    /** Milvus Collection 名称（引用 MilvusCollectionInitializer 公开常量） */
    private static final String COLLECTION_NAME = MilvusCollectionInitializer.COLLECTION_NAME;

    /** dense 向量字段名（引用 MilvusCollectionInitializer 公开常量） */
    private static final String DENSE_FIELD_NAME = MilvusCollectionInitializer.FIELD_DENSE_VECTOR;

    /** sparse 向量字段名（引用 MilvusCollectionInitializer 公开常量） */
    private static final String SPARSE_FIELD_NAME = MilvusCollectionInitializer.FIELD_SPARSE_VECTOR;

    /** HNSW 搜索参数 */
    private static final String HNSW_PARAMS = "{\"ef\": 64}";

    /**
     * Milvus 输出字段列表（9 个标量字段，不含 source/dense_vector/sparse_vector）
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
            MilvusCollectionInitializer.FIELD_UPDATED_AT);

    private final FusionService fusionService;
    private final RerankService rerankService;
    private final EmbeddingModel embeddingModel;
    private final MilvusClientV2 milvusClientV2;
    private final int rrfK;
    private final ExecutorService searchExecutor;

    public SearchKnowledgeTool(
            FusionService fusionService,
            RerankService rerankService,
            EmbeddingModel embeddingModel,
            MilvusClientV2 milvusClientV2,
            @Value("${milvus.sparse-bm25-k:60}") int rrfK) {
        this.fusionService = fusionService;
        this.rerankService = rerankService;
        this.embeddingModel = embeddingModel;
        this.milvusClientV2 = milvusClientV2;
        this.rrfK = rrfK;
        this.searchExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "search-knowledge-");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 统一检索入口 —— LLM Agent 直接调用
     *
     * <p>返回 KnowledgeSearchResult 对象（record DTO），非 String。
     * SAA ReactAgent 会将返回对象的 toString() / JSON 序列化结果注入上下文。
     *
     * @param queries 查询重写后的多条覆盖性查询
     * @return 去重、融合、精排后的知识检索结果
     */
    @Tool(description = "知识库检索：混合检索 Milvus 知识库（dense+sparse RRF 融合）并精排返回")
    public KnowledgeSearchResult searchKnowledge(List<TypedQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return new KnowledgeSearchResult(Collections.emptyList());
        }

        log.info("开始知识检索: queries={}", queries.size());

        // 1. 并行检索
        Map<TypedQuery, List<KnowledgeChunk>> rawResults = searchInParallel(queries);

        // 2. RRF 融合 + 去重
        List<KnowledgeChunk> fused = fusionService.fuse(rawResults);

        // 3. Rerank 精排（取第一条 query 作为 rerank anchor）
        String anchorQuery = queries.get(0).queryText();
        List<KnowledgeChunk> reranked = rerankService.rerank(anchorQuery, fused);

        log.info(
                "检索完成: 原始={}, 融合后={}, 精排后={}",
                rawResults.values().stream().mapToInt(List::size).sum(),
                fused.size(),
                reranked.size());

        return new KnowledgeSearchResult(reranked);
    }

    /**
     * 并行检索 —— 每条 TypedQuery 独立查询 Milvus，CompletableFuture.allOf 汇总
     */
    private Map<TypedQuery, List<KnowledgeChunk>> searchInParallel(List<TypedQuery> queries) {
        Map<TypedQuery, List<KnowledgeChunk>> results = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = queries.stream()
                .map(q -> CompletableFuture.supplyAsync(() -> searchSingle(q), searchExecutor)
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

    /**
     * 单条查询的 Milvus 混合检索逻辑（v2 API：hybridSearch）
     *
     * <p>流程：
     * <ol>
     *   <li>使用 EmbeddingModel 将 query.queryText() 向量化 → dense 向量</li>
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
     * @param query 类型化查询
     * @return 检索到的 KnowledgeChunk 列表（可能为空）
     */
    List<KnowledgeChunk> searchSingle(TypedQuery query) {
        try {
            // 1. 向量化查询文本（dense 向量）
            float[] denseVector = embeddingModel.embed(query.queryText());
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
                    .limit(SEARCH_TOP_K)
                    .filter(filterExpr)
                    .build();

            // 4. 构建 sparse AnnSearchReq（EmbeddedText + BM25）
            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectors(List.of(new EmbeddedText(query.queryText())))
                    .vectorFieldName(SPARSE_FIELD_NAME)
                    .metricType(IndexParam.MetricType.BM25)
                    .limit(SEARCH_TOP_K)
                    .filter(filterExpr)
                    .build();

            // 5. 构建 HybridSearchReq（RRFRanker 融合）
            HybridSearchReq hybridSearchReq = HybridSearchReq.builder()
                    .collectionName(COLLECTION_NAME)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(RRFRanker.builder().k(rrfK).build())
                    .limit(SEARCH_TOP_K)
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
                chunks.add(new KnowledgeChunk(chunkId, content, "", "", headingPath, score, null));
            }

            log.debug("单条检索完成: type={}, 结果数={}", query.collectionType(), chunks.size());
            return chunks;

        } catch (Exception e) {
            log.warn("单条检索失败(降级): type={}, error={}", query.collectionType(), e.getMessage());
            return Collections.emptyList();
        }
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
