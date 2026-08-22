package com.commerce.rag.bot.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.bot.IntentType;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * SearchKnowledgeTool 单元测试 —— Mock EmbeddingModel + MilvusClientV2（v2 API）
 *
 * <p>使用 Mockito mock SearchResp 返回结构，验证 hybridSearch 调用
 * 和过滤表达式（IN 列表 + DEFAULT OR 逻辑）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class SearchKnowledgeToolTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private MilvusClientV2 milvusClientV2;

    @Mock
    private FusionService fusionService;

    @Mock
    private RerankService rerankService;

    private SearchKnowledgeTool tool;

    @BeforeEach
    void setUp() {
        tool = new SearchKnowledgeTool(fusionService, rerankService, embeddingModel, milvusClientV2, 60, 20, false);
    }

    /**
     * 构建 mock SearchResp（包含一条搜索结果）
     */
    @SuppressWarnings("unchecked")
    private SearchResp mockSearchResp() {
        SearchResp.SearchResult sr = SearchResp.SearchResult.builder()
                .score(0.95f)
                .entity(Map.of(
                        "chunk_id", "chunk_001",
                        "content", "Redis配置方法...",
                        "heading_path", "Ch3 > 3.2",
                        "sha256", "a".repeat(64)))
                .build();
        SearchResp searchResp = mock(SearchResp.class);
        when(searchResp.getSearchResults()).thenReturn(List.of(List.of(sr)));
        return searchResp;
    }

    @Test
    @DisplayName("searchSingle 正常检索 — 返回 KnowledgeChunk 列表")
    void searchSingle_normal_returnsChunks() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "如何配置Redis", null);

        // Mock embedding
        when(embeddingModel.embed("如何配置Redis")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        // 先创建 mock 响应对象，再 stub（避免 UnfinishedStubbing）
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query, null);

        // Then
        assertEquals(1, result.size());
        KnowledgeChunk chunk = result.get(0);
        assertEquals("chunk_001", chunk.chunkId());
        assertEquals("Redis配置方法...", chunk.content());
        assertEquals("", chunk.source()); // source 已废弃，设为空字符串
        assertEquals("Ch3 > 3.2", chunk.headingPath());
        assertEquals(0.95, chunk.score(), 0.001);
        assertNull(chunk.collectionType()); // S1 意图-检索解耦：检索结果不再携带 collection_type 意图
        assertEquals("a".repeat(64), chunk.sha256(), "Milvus 返回的 sha256 应透传到 KnowledgeChunk");
    }

    @Test
    @DisplayName("searchSingle 带 courseIds — 过滤表达式包含 in 和 DEFAULT")
    void searchSingle_withCourseIds_filterIncludesInAndDefault() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "课程大纲", List.of("COURSE_123", "COURSE_456"));

        when(embeddingModel.embed("课程大纲")).thenReturn(new float[] {0.1f, 0.2f});

        // Mock Milvus hybridSearch 返回空结果
        SearchResp searchResp = mock(SearchResp.class);
        when(searchResp.getSearchResults()).thenReturn(List.of(Collections.emptyList()));
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        // 捕获 HybridSearchReq 以验证过滤表达式
        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query, null);

        // Then
        verify(milvusClientV2).hybridSearch(reqCaptor.capture());
        HybridSearchReq capturedReq = reqCaptor.getValue();

        // 验证 searchRequests 中的 filter 包含正确的表达式
        // 由于 filter 在 AnnSearchReq 上，我们通过 buildFilterExpression 间接验证
        // 这里直接验证 buildFilterExpression 方法
        String expr = tool.buildFilterExpression(query);
        assertEquals("(course_id == \"DEFAULT\" or course_id in [\"COURSE_123\", \"COURSE_456\"])", expr);
        assertFalse(expr.contains("collection_type"), "过滤表达式不应含 collection_type（S1 意图-检索解耦）");

        // 空结果
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle Milvus异常 — 降级返回空列表")
    void searchSingle_milvusException_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "测试查询", null);

        when(embeddingModel.embed("测试查询")).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenThrow(new RuntimeException("连接超时"));

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query, null);

        // Then: 降级返回空列表，不抛异常
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle Embedding异常 — 降级返回空列表")
    void searchSingle_embeddingException_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "测试查询", null);

        when(embeddingModel.embed("测试查询")).thenThrow(new RuntimeException("Embedding服务不可用"));

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query, null);

        // Then: 降级返回空列表
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle 结果为null — 降级返回空列表")
    void searchSingle_nullResult_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "测试", null);

        when(embeddingModel.embed("测试")).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(null);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query, null);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildFilterExpression — 无 courseIds 时不设过滤（返回 null，全局检索）")
    void buildFilterExpression_noCourseIds_returnsNull() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "test", null);

        assertNull(tool.buildFilterExpression(query));
    }

    @Test
    @DisplayName("buildFilterExpression — 有 courseIds 时仅 course_id 子句（in + DEFAULT OR，不含 collection_type）")
    void buildFilterExpression_withCourseIds_onlyCourseClause() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "test", List.of("C1", "C2"));

        String expr = tool.buildFilterExpression(query);

        assertEquals("(course_id == \"DEFAULT\" or course_id in [\"C1\", \"C2\"])", expr);
        assertFalse(expr.contains("collection_type"), "过滤表达式不应含 collection_type（S1 意图-检索解耦）");
    }

    // ==================== A2-2 补测：searchKnowledge 入口 / 并行 / 降级 / 双路检索 ====================

    @Test
    @DisplayName("buildFilterExpression — courseIds 为空列表时不设过滤（返回 null）")
    void buildFilterExpression_emptyCourseIds_returnsNull() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "test", List.of());

        assertNull(tool.buildFilterExpression(query));
    }

    @Test
    @DisplayName("searchKnowledge 空查询列表 — 直接返回空结果，不触达 Milvus/融合/精排")
    void searchKnowledge_emptyQueries_returnsEmpty() {
        assertTrue(tool.searchKnowledge(null, null).chunks().isEmpty());
        assertTrue(tool.searchKnowledge(List.of(), null).chunks().isEmpty());

        verifyNoInteractions(milvusClientV2, fusionService, rerankService);
    }

    @Test
    @DisplayName("searchKnowledge 完整链路 — 并行检索 → RRF 融合 → rerank 精排")
    void searchKnowledge_fullFlow_fuseAndRerank() {
        TypedQuery q1 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 配置", null);
        TypedQuery q2 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 哨兵", null);
        when(embeddingModel.embed("Redis 配置")).thenReturn(new float[] {0.1f});
        when(embeddingModel.embed("Redis 哨兵")).thenReturn(new float[] {0.2f});
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        KnowledgeChunk k1 = new KnowledgeChunk("c1", "内容1", "", "", "h1", 0.9, IntentType.KNOWLEDGE_QUESTION, null);
        KnowledgeChunk k2 = new KnowledgeChunk("c2", "内容2", "", "", "h2", 0.8, IntentType.KNOWLEDGE_QUESTION, null);
        when(fusionService.fuse(anyMap())).thenReturn(List.of(k1, k2));
        when(rerankService.rerank("Redis 配置", List.of(k1, k2))).thenReturn(List.of(k2, k1));

        KnowledgeSearchResult result = tool.searchKnowledge(List.of(q1, q2), null);

        // 精排结果即最终输出（k2 在前）
        assertEquals(2, result.chunks().size());
        assertEquals("c2", result.chunks().get(0).chunkId());
        assertEquals("c1", result.chunks().get(1).chunkId());
        // 融合入参：并行检索结果按查询分组，两条查询均有命中
        ArgumentCaptor<Map<TypedQuery, List<KnowledgeChunk>>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fusionService).fuse(mapCaptor.capture());
        Map<TypedQuery, List<KnowledgeChunk>> raw = mapCaptor.getValue();
        assertEquals(2, raw.size());
        assertEquals("chunk_001", raw.get(q1).get(0).chunkId());
        assertEquals("chunk_001", raw.get(q2).get(0).chunkId());
        // 精排 anchor 取第一条查询文本
        verify(rerankService).rerank("Redis 配置", List.of(k1, k2));
    }

    @Test
    @DisplayName("searchKnowledge — SHA256 同内容去重：保留 RRF 融合分数最高一条")
    void searchKnowledge_sameSha256_deduplicates() {
        TypedQuery q1 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 配置", null);
        TypedQuery q2 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 哨兵", null);
        // 两条候选内容归一化后同 hash（防缺陷：计划 1/5 ETL 全库唯一，防御性兜底场景）
        KnowledgeChunk high = new KnowledgeChunk(
                "c1", "Redis 配置方法说明。", "", "", "h1", 0.0, IntentType.KNOWLEDGE_QUESTION, "f".repeat(64));
        KnowledgeChunk low = new KnowledgeChunk(
                "c2", "Redis 配置方法说明。", "", "", "h2", 0.0, IntentType.KNOWLEDGE_QUESTION, "f".repeat(64));
        // 本用例未 stub Embedding/Milvus（searchSingle 返回空），与既有 fullFlow 用例一致用 anyMap() 匹配，
        // 专注验证「融合 → 去重 → rerank」链路上去重确实落在两者之间
        when(fusionService.fuse(anyMap())).thenReturn(List.of(high, low)); // RRF 降序：high 在前
        when(rerankService.rerank("Redis 配置", List.of(high))).thenReturn(List.of(high));

        KnowledgeSearchResult result = tool.searchKnowledge(List.of(q1, q2), null);

        // rerank 仅收到去重后的 1 条（同 hash 保留首次出现的 high）
        verify(rerankService)
                .rerank(
                        eq("Redis 配置"),
                        argThat(list ->
                                list.size() == 1 && list.get(0).chunkId().equals("c1")));
        assertEquals(1, result.chunks().size());
    }

    @Test
    @DisplayName("searchKnowledge — sha256 为空时按归一化内容哈希保底去重，仍不可得按 chunkId")
    void searchKnowledge_nullSha256_fallsBackToContentHash() {
        TypedQuery q = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        // sha256 为 null：走 ContentHash.of(content) 归一化哈希保底（同内容文本 → 同 hash）
        when(fusionService.fuse(anyMap()))
                .thenReturn(List.of(
                        new KnowledgeChunk("c1", "相同内容文本。", "", "", "h1", 0.0, IntentType.KNOWLEDGE_QUESTION, null),
                        new KnowledgeChunk("c2", "相同内容文本。", "", "", "h2", 0.0, IntentType.KNOWLEDGE_QUESTION, null)));
        when(rerankService.rerank(
                        "查询",
                        List.of(new KnowledgeChunk(
                                "c1", "相同内容文本。", "", "", "h1", 0.0, IntentType.KNOWLEDGE_QUESTION, null))))
                .thenReturn(List.of(
                        new KnowledgeChunk("c1", "相同内容文本。", "", "", "h1", 0.0, IntentType.KNOWLEDGE_QUESTION, null)));

        KnowledgeSearchResult result = tool.searchKnowledge(List.of(q), null);

        // 无 sha256 时 ContentHash.of(content) 归一化相同 → 也去重为 1 条
        verify(rerankService).rerank(eq("查询"), argThat(list -> list.size() == 1));
        assertEquals(1, result.chunks().size());
    }

    @Test
    @DisplayName("searchKnowledge 单查询失败降级 — 返回空结果不抛异常")
    void searchKnowledge_queryFailure_degradedEmpty() {
        TypedQuery q = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenThrow(new RuntimeException("Milvus 连接失败"));

        KnowledgeSearchResult result = tool.searchKnowledge(List.of(q), null);

        assertTrue(result.chunks().isEmpty());
        // 降级后融合/精排在空结果上正常走通
        verify(fusionService).fuse(anyMap());
        verify(rerankService).rerank(eq("查询"), anyList());
    }

    @Test
    @DisplayName("searchInParallel 单查询超时 — 10s 超时后返回已完成部分，不阻塞整体")
    void searchKnowledge_timeout_returnsPartial() throws InterruptedException {
        TypedQuery fast = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "快速查询", null);
        TypedQuery slow = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "慢速查询", null);
        when(embeddingModel.embed("快速查询")).thenReturn(new float[] {0.1f});
        when(embeddingModel.embed("慢速查询")).thenAnswer(inv -> {
            // 慢查询远超 10s 超时阈值（30s），保证超时时刻仍未完成
            Thread.sleep(30_000);
            return new float[] {0.2f};
        });
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        long start = System.currentTimeMillis();
        KnowledgeSearchResult result = tool.searchKnowledge(List.of(fast, slow), null);
        long elapsed = System.currentTimeMillis() - start;

        // 约 10s 超时即返回，不等待慢查询完成
        assertTrue(elapsed >= 9_000 && elapsed < 25_000, "实际耗时(ms): " + elapsed);
        assertTrue(result.chunks().isEmpty());
        // 超时时刻融合入参仅含已完成的快查询
        ArgumentCaptor<Map<TypedQuery, List<KnowledgeChunk>>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(fusionService).fuse(mapCaptor.capture());
        assertTrue(mapCaptor.getValue().containsKey(fast));
    }

    @Test
    @DisplayName("searchSingle 空向量 — 降级返回空列表，不触达 Milvus")
    void searchSingle_emptyEmbedding_returnsEmpty() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenReturn(new float[0]);

        assertTrue(tool.searchSingle(query, null).isEmpty());
        verify(milvusClientV2, never()).hybridSearch(any());
    }

    @Test
    @DisplayName("searchSingle null 向量 — 降级返回空列表")
    void searchSingle_nullEmbedding_returnsEmpty() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenReturn(null);

        assertTrue(tool.searchSingle(query, null).isEmpty());
    }

    @Test
    @DisplayName("searchSingle 检索结果外层列表为 null — 降级返回空列表")
    void searchSingle_nullSearchResults_returnsEmpty() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenReturn(new float[] {0.1f});
        SearchResp resp = mock(SearchResp.class);
        when(resp.getSearchResults()).thenReturn(null);
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(resp);

        assertTrue(tool.searchSingle(query, null).isEmpty());
    }

    @Test
    @DisplayName("searchSingle 检索结果外层列表为空 — 降级返回空列表")
    void searchSingle_emptySearchResults_returnsEmpty() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenReturn(new float[] {0.1f});
        SearchResp resp = mock(SearchResp.class);
        when(resp.getSearchResults()).thenReturn(List.of());
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(resp);

        assertTrue(tool.searchSingle(query, null).isEmpty());
    }

    @Test
    @DisplayName("searchSingle 混合检索请求 — sparse 开关关闭时仅 dense 单路（EmbeddedText SDK bug 降级）")
    void searchSingle_hybridRequest_sparseDisabled() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "如何配置Redis", null);
        when(embeddingModel.embed("如何配置Redis")).thenReturn(new float[] {0.1f, 0.2f});
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        tool.searchSingle(query, null);

        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(milvusClientV2).hybridSearch(reqCaptor.capture());
        HybridSearchReq req = reqCaptor.getValue();
        assertEquals(MilvusCollectionInitializer.COLLECTION_NAME, req.getCollectionName());
        assertEquals(1, req.getSearchRequests().size(), "sparse 关闭时只发 dense 单路");
        // dense 路：FloatVec + COSINE
        AnnSearchReq dense = req.getSearchRequests().get(0);
        assertEquals(MilvusCollectionInitializer.FIELD_DENSE_VECTOR, dense.getVectorFieldName());
        assertEquals(IndexParam.MetricType.COSINE, dense.getMetricType());
        assertTrue(dense.getVectors().get(0) instanceof FloatVec);
    }

    @Test
    @DisplayName("searchSingle 混合检索请求 — sparse 开关开启时 dense + sparse 双路 + RRF 融合")
    void searchSingle_hybridRequest_denseAndSparse() {
        tool = new SearchKnowledgeTool(fusionService, rerankService, embeddingModel, milvusClientV2, 60, 20, true);
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "如何配置Redis", null);
        when(embeddingModel.embed("如何配置Redis")).thenReturn(new float[] {0.1f, 0.2f});
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        tool.searchSingle(query, null);

        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(milvusClientV2).hybridSearch(reqCaptor.capture());
        HybridSearchReq req = reqCaptor.getValue();
        assertEquals(MilvusCollectionInitializer.COLLECTION_NAME, req.getCollectionName());
        assertEquals(2, req.getSearchRequests().size());
        // dense 路：FloatVec + COSINE
        AnnSearchReq dense = req.getSearchRequests().get(0);
        assertEquals(MilvusCollectionInitializer.FIELD_DENSE_VECTOR, dense.getVectorFieldName());
        assertEquals(IndexParam.MetricType.COSINE, dense.getMetricType());
        assertTrue(dense.getVectors().get(0) instanceof FloatVec);
        // sparse 路：EmbeddedText + BM25
        AnnSearchReq sparse = req.getSearchRequests().get(1);
        assertEquals(MilvusCollectionInitializer.FIELD_SPARSE_VECTOR, sparse.getVectorFieldName());
        assertEquals(IndexParam.MetricType.BM25, sparse.getMetricType());
        assertTrue(sparse.getVectors().get(0) instanceof EmbeddedText);
        // 双路过滤表达式一致
        assertEquals(dense.getFilter(), sparse.getFilter());
        assertTrue(req.getRanker() instanceof RRFRanker);
    }

    @Test
    @DisplayName("searchSingle 结果含 null entity / null score / 缺字段 — 空实体跳过、分数归 0、缺失字段空串兜底")
    void searchSingle_nullEntityAndScore_handled() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "查询", null);
        when(embeddingModel.embed("查询")).thenReturn(new float[] {0.1f});

        // 第一条 entity 为 null（跳过）；第二条 score 为 null、缺 heading_path（getStr 空串兜底）
        SearchResp.SearchResult withNullEntity =
                SearchResp.SearchResult.builder().score(0.5f).entity(null).build();
        Map<String, Object> entity = Map.of(
                "chunk_id", "chunk_002",
                "content", "内容2");
        SearchResp.SearchResult partial =
                SearchResp.SearchResult.builder().entity(entity).build();
        SearchResp resp = mock(SearchResp.class);
        when(resp.getSearchResults()).thenReturn(List.of(List.of(withNullEntity, partial)));
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(resp);

        List<KnowledgeChunk> chunks = tool.searchSingle(query, null);

        assertEquals(1, chunks.size());
        assertEquals("chunk_002", chunks.get(0).chunkId());
        assertEquals(0.0, chunks.get(0).score(), 0.001);
        assertEquals("", chunks.get(0).headingPath());
    }

    @Test
    @DisplayName("searchSingle queryText 为 null 且 Milvus 返回 null — truncate 空串兜底不抛异常")
    void searchSingle_nullQueryText_milvusNull_ok() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, null, null);
        when(embeddingModel.embed((String) null)).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(null);

        assertTrue(tool.searchSingle(query, null).isEmpty());
    }

    @Test
    @DisplayName("searchSingle 长 queryText — 日志截断逻辑不抛异常")
    void searchSingle_longQueryText_truncate() {
        String longText = "这是一段超过三十个字符的非常长的查询文本用于触发截断逻辑ABCDEFG";
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, longText, null);
        when(embeddingModel.embed(longText)).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenThrow(new RuntimeException("连接失败"));

        assertTrue(tool.searchSingle(query, null).isEmpty());
    }

    @Test
    @DisplayName("searchSingle — 传入预向量时不再调用 embed（方案 3-1-a 首条消重）")
    void searchSingle_withPrecomputedVector_skipsEmbed() {
        TypedQuery query = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "如何配置Redis", null);
        float[] pre = new float[] {0.5f, 0.6f};
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        List<KnowledgeChunk> result = tool.searchSingle(query, pre);

        assertEquals(1, result.size());
        // 预向量非空 → 内部不再 embed（同文本两次远程调用收敛为一次）
        verify(embeddingModel, never()).embed(anyString());
        // 预向量透传进 dense 检索请求
        ArgumentCaptor<HybridSearchReq> cap = ArgumentCaptor.forClass(HybridSearchReq.class);
        verify(milvusClientV2).hybridSearch(cap.capture());
        AnnSearchReq dense = cap.getValue().getSearchRequests().get(0);
        FloatVec vec = (FloatVec) dense.getVectors().get(0);
        // FloatVec 内部以 List<Float> 承载向量（float[] 构造器转 List），逐元素断言透传正确
        @SuppressWarnings("unchecked")
        List<Float> vectorData = (List<Float>) vec.getData();
        assertEquals(pre[0], vectorData.get(0), 1e-4f);
        assertEquals(pre[1], vectorData.get(1), 1e-4f);
    }

    @Test
    @DisplayName("searchKnowledge — 预向量仅作用于首条查询，其余查询仍内部 embed")
    void searchKnowledge_preVectorOnlyFirstQuery() {
        TypedQuery q1 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 配置", null);
        TypedQuery q2 = new TypedQuery(IntentType.KNOWLEDGE_QUESTION, "Redis 哨兵", null);
        // 首条有预向量 → "Redis 配置" 不再 embed；第二条仍需 self-embed
        when(embeddingModel.embed("Redis 哨兵")).thenReturn(new float[] {0.2f});
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);
        KnowledgeChunk k1 = new KnowledgeChunk("c1", "内容1", "", "", "h1", 0.9, IntentType.KNOWLEDGE_QUESTION, null);
        when(fusionService.fuse(anyMap())).thenReturn(List.of(k1));
        when(rerankService.rerank(eq("Redis 配置"), any())).thenReturn(List.of(k1));

        KnowledgeSearchResult result = tool.searchKnowledge(List.of(q1, q2), new float[] {0.1f, 0.2f});

        assertEquals(1, result.chunks().size());
        // 首条不重复 embed；第二条照常 embed
        verify(embeddingModel, never()).embed("Redis 配置");
        verify(embeddingModel, times(1)).embed("Redis 哨兵");
    }
}
