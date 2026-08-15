package com.commerce.rag.bot.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.retrieval.FusionService;
import com.commerce.rag.retrieval.RerankService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.HybridSearchReq;
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
        tool = new SearchKnowledgeTool(fusionService, rerankService, embeddingModel, milvusClientV2, 60);
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
                        "collection_type", "TECHNICAL_QA"))
                .build();

        SearchResp searchResp = mock(SearchResp.class);
        when(searchResp.getSearchResults()).thenReturn(List.of(List.of(sr)));
        return searchResp;
    }

    @Test
    @DisplayName("searchSingle 正常检索 — 返回 KnowledgeChunk 列表")
    void searchSingle_normal_returnsChunks() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "如何配置Redis", null);

        // Mock embedding
        when(embeddingModel.embed("如何配置Redis")).thenReturn(new float[] {0.1f, 0.2f, 0.3f});

        // 先创建 mock 响应对象，再 stub（避免 UnfinishedStubbing）
        SearchResp searchResp = mockSearchResp();
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query);

        // Then
        assertEquals(1, result.size());
        KnowledgeChunk chunk = result.get(0);
        assertEquals("chunk_001", chunk.chunkId());
        assertEquals("Redis配置方法...", chunk.content());
        assertEquals("", chunk.source()); // source 已废弃，设为空字符串
        assertEquals("Ch3 > 3.2", chunk.headingPath());
        assertEquals(0.95, chunk.score(), 0.001);
        assertEquals(IntentType.TECHNICAL_QA, chunk.collectionType());
    }

    @Test
    @DisplayName("searchSingle 带 courseIds — 过滤表达式包含 in 和 DEFAULT")
    void searchSingle_withCourseIds_filterIncludesInAndDefault() {
        TypedQuery query = new TypedQuery(IntentType.COURSE_INFO, "课程大纲", List.of("COURSE_123", "COURSE_456"));

        when(embeddingModel.embed("课程大纲")).thenReturn(new float[] {0.1f, 0.2f});

        // Mock Milvus hybridSearch 返回空结果
        SearchResp searchResp = mock(SearchResp.class);
        when(searchResp.getSearchResults()).thenReturn(List.of(Collections.emptyList()));
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(searchResp);

        // 捕获 HybridSearchReq 以验证过滤表达式
        ArgumentCaptor<HybridSearchReq> reqCaptor = ArgumentCaptor.forClass(HybridSearchReq.class);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query);

        // Then
        verify(milvusClientV2).hybridSearch(reqCaptor.capture());
        HybridSearchReq capturedReq = reqCaptor.getValue();

        // 验证 searchRequests 中的 filter 包含正确的表达式
        // 由于 filter 在 AnnSearchReq 上，我们通过 buildFilterExpression 间接验证
        // 这里直接验证 buildFilterExpression 方法
        String expr = tool.buildFilterExpression(query);
        assertNotNull(expr);
        assertTrue(expr.contains("course_id in ["), "过滤表达式应包含 course_id in [");
        assertTrue(expr.contains("DEFAULT"), "过滤表达式应包含 DEFAULT");
        assertTrue(expr.contains("COURSE_123"), "过滤表达式应包含 COURSE_123");
        assertTrue(expr.contains("COURSE_456"), "过滤表达式应包含 COURSE_456");
        assertTrue(expr.contains("COURSE_INFO"), "过滤表达式应包含 collection_type");

        // 空结果
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle Milvus异常 — 降级返回空列表")
    void searchSingle_milvusException_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "测试查询", null);

        when(embeddingModel.embed("测试查询")).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenThrow(new RuntimeException("连接超时"));

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query);

        // Then: 降级返回空列表，不抛异常
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle Embedding异常 — 降级返回空列表")
    void searchSingle_embeddingException_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "测试查询", null);

        when(embeddingModel.embed("测试查询")).thenThrow(new RuntimeException("Embedding服务不可用"));

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query);

        // Then: 降级返回空列表
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("searchSingle 结果为null — 降级返回空列表")
    void searchSingle_nullResult_returnsEmptyList() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "测试", null);

        when(embeddingModel.embed("测试")).thenReturn(new float[] {0.1f});
        when(milvusClientV2.hybridSearch(any(HybridSearchReq.class))).thenReturn(null);

        // When
        List<KnowledgeChunk> result = tool.searchSingle(query);

        // Then
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("buildFilterExpression — 无 courseIds 时仅 collection_type")
    void buildFilterExpression_noCourseIds_onlyCollectionType() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "test", null);

        String expr = tool.buildFilterExpression(query);

        assertEquals("collection_type == \"TECHNICAL_QA\"", expr);
    }

    @Test
    @DisplayName("buildFilterExpression — 有 courseIds 时包含 in + DEFAULT OR")
    void buildFilterExpression_withCourseIds_includesInAndDefault() {
        TypedQuery query = new TypedQuery(IntentType.COURSE_INFO, "test", List.of("C1", "C2"));

        String expr = tool.buildFilterExpression(query);

        assertTrue(expr.contains("collection_type == \"COURSE_INFO\""));
        assertTrue(expr.contains("course_id == \"DEFAULT\""));
        assertTrue(expr.contains("course_id in [\"C1\", \"C2\"]"));
        assertTrue(expr.contains(" and ("));
    }
}
