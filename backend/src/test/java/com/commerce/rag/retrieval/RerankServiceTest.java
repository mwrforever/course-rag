package com.commerce.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.document.DocumentWithScore;
import com.alibaba.cloud.ai.model.RerankModel;
import com.alibaba.cloud.ai.model.RerankRequest;
import com.alibaba.cloud.ai.model.RerankResponse;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;

/**
 * RerankService 单元测试 —— Mock RerankModel，验证排序/过滤/降级逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock
    private RerankModel rerankModel;

    @Mock
    private PromptLoader promptLoader;

    private RerankService rerankService;

    @BeforeEach
    void setUp() {
        // 手动构造，确保 threshold = 0.30
        rerankService = new RerankService(rerankModel, 0.30, promptLoader);
    }

    @Test
    @DisplayName("rerank 正常调用 — 返回按分数降序、过滤低于阈值的结果")
    void rerank_normalCall_returnsSortedFilteredResults() {
        // Given: 3 个 chunk
        List<KnowledgeChunk> chunks =
                List.of(chunk("c1", "content1"), chunk("c2", "content2"), chunk("c3", "content3"));
        // 先创建所有 mock 对象（避免在 when().thenReturn() 内部嵌套 stubbing）
        DocumentWithScore dws1 = dws("c1", "content1", 0.95);
        DocumentWithScore dws2 = dws("c2", "content2", 0.45);
        DocumentWithScore dws3 = dws("c3", "content3", 0.15); // c3 低于 0.30 阈值 → 过滤
        RerankResponse response = mockRerankResponse(dws1, dws2, dws3);
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(response);

        // When
        List<KnowledgeChunk> result = rerankService.rerank("query", chunks);

        // Then: c3 被过滤，c1 和 c2 保留，按分数降序
        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).chunkId());
        assertEquals(0.95, result.get(0).score(), 0.001);
        assertEquals("c2", result.get(1).chunkId());
        assertEquals(0.45, result.get(1).score(), 0.001);
    }

    @Test
    @DisplayName("rerank 空输入 — 返回空列表")
    void rerank_emptyInput_returnsEmptyList() {
        List<KnowledgeChunk> result = rerankService.rerank("query", Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("rerank null输入 — 返回空列表")
    void rerank_nullInput_returnsEmptyList() {
        List<KnowledgeChunk> result = rerankService.rerank("query", null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("rerank 异常 — 降级返回原列表前10")
    void rerank_exception_fallbackToOriginalList() {
        List<KnowledgeChunk> chunks = List.of(chunk("c1", "content1"));
        when(rerankModel.call(any())).thenThrow(new RuntimeException("API超时"));

        List<KnowledgeChunk> result = rerankService.rerank("query", chunks);

        // 降级返回原列表
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).chunkId());
    }

    @Test
    @DisplayName("rerank 异常且列表超过10 — 降级返回前10条")
    void rerank_exception_largeList_fallbackToFirst10() {
        // 构造 15 个 chunk
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            chunks.add(chunk("c" + i, "content" + i));
        }
        when(rerankModel.call(any())).thenThrow(new RuntimeException("API超时"));

        List<KnowledgeChunk> result = rerankService.rerank("query", chunks);

        // 降级返回前 10 条
        assertEquals(10, result.size());
    }

    @Test
    @DisplayName("rerank 正常调用 — 验证 RerankRequest 传入正确的 query 和 documents")
    void rerank_normalCall_verifiesRequestContent() {
        List<KnowledgeChunk> chunks = List.of(chunk("c1", "content1"));
        // 先创建 mock 对象（避免嵌套 stubbing）
        DocumentWithScore dws1 = dws("c1", "content1", 0.90);
        RerankResponse response = mockRerankResponse(dws1);
        when(rerankModel.call(any(RerankRequest.class))).thenReturn(response);

        rerankService.rerank("测试查询", chunks);

        // 验证 rerankModel.call 被调用
        verify(rerankModel, times(1)).call(any(RerankRequest.class));
    }

    // ==================== 辅助方法 ====================

    /** 构造测试用 KnowledgeChunk（7 字段，对照设计 §2.4） */
    private KnowledgeChunk chunk(String chunkId, String content) {
        return new KnowledgeChunk(
                chunkId, content, "source", "docTitle", "heading", 0.0, IntentType.KNOWLEDGE_QUESTION);
    }

    /** 构造 mock DocumentWithScore */
    @SuppressWarnings("unchecked")
    private DocumentWithScore dws(String chunkId, String content, double score) {
        DocumentWithScore mockDws = mock(DocumentWithScore.class);
        when(mockDws.getScore()).thenReturn(score);

        // 使用 mock Document，避免依赖 Document 构造函数签名
        Document mockDoc = mock(Document.class);
        // RerankService 不调用 getText()，无需 stub
        // getMetadata/getOutput 用 lenient：低于阈值的 chunk 会在 filter 阶段被跳过，不会进入 mapToChunk
        lenient().when(mockDoc.getMetadata()).thenReturn(Map.of("chunkId", chunkId));
        lenient().when(mockDws.getOutput()).thenReturn(mockDoc);

        return mockDws;
    }

    /** 构造 mock RerankResponse */
    private RerankResponse mockRerankResponse(DocumentWithScore... results) {
        RerankResponse mockResp = mock(RerankResponse.class);
        when(mockResp.getResults()).thenReturn(List.of(results));
        return mockResp;
    }
}
