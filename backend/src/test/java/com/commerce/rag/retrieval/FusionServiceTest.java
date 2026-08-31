package com.commerce.rag.retrieval;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.tool.TypedQuery;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.properties.RetrievalProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FusionService 单元测试 —— RRF 融合排序与同源去重
 *
 * @author commerce-rag
 */
@DisplayName("FusionService RRF 融合测试")
class FusionServiceTest {

    /** rrfK=60（与 application.yml 显式值一致）经属性类注入 */
    private final FusionService fusionService = new FusionService(new RetrievalProperties(60, 0.30, 20, false, 3));

    private KnowledgeChunk chunk(String chunkId) {
        return new KnowledgeChunk(
                chunkId, "内容-" + chunkId, "source.md", "文档标题", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, null);
    }

    private KnowledgeChunk chunk(String chunkId, double score) {
        return new KnowledgeChunk(
                chunkId, "内容-" + chunkId, "source.md", "文档标题", "第一章", score, IntentType.KNOWLEDGE_QUESTION, null);
    }

    private TypedQuery query(String text) {
        return new TypedQuery(IntentType.KNOWLEDGE_QUESTION, text, List.of());
    }

    @Test
    @DisplayName("fuse → 空输入返回空列表")
    void fuse_nullOrEmpty_returnsEmpty() {
        assertTrue(fusionService.fuse(null).isEmpty());
        assertTrue(fusionService.fuse(Map.of()).isEmpty());
    }

    @Test
    @DisplayName("fuse → 单查询结果按排名计算 RRF 分并降序")
    void fuse_singleQuery_ranksByRRF() {
        Map<TypedQuery, List<KnowledgeChunk>> results =
                Map.of(query("q1"), List.of(chunk("a"), chunk("b"), chunk("c")));

        List<KnowledgeChunk> fused = fusionService.fuse(results);

        // 排名越靠前分越高：a(1/61) > b(1/62) > c(1/63)
        assertEquals(
                List.of("a", "b", "c"),
                fused.stream().map(KnowledgeChunk::chunkId).toList());
    }

    @Test
    @DisplayName("fuse → 多查询同 chunk 分数累加，靠前出现实例被保留")
    void fuse_multiQuery_accumulatesScores() {
        Map<TypedQuery, List<KnowledgeChunk>> results = Map.of(
                query("q1"), List.of(chunk("a"), chunk("b")),
                query("q2"), List.of(chunk("b"), chunk("c")));
        // b 的分 = 1/61 + 1/61（两个查询都是第 1 名）= 0.0328 > a(1/61)
        List<KnowledgeChunk> fused = fusionService.fuse(results);

        assertEquals(
                List.of("b", "a", "c"),
                fused.stream().map(KnowledgeChunk::chunkId).toList());
        assertEquals("b", fused.get(0).chunkId());
    }

    @Test
    @DisplayName("fuse → 同 chunk 跨查询出现时保留首次出现的实例")
    void fuse_duplicateChunk_keepsFirstInstance() {
        KnowledgeChunk first = chunk("a");
        KnowledgeChunk second = chunk("a", 0.5);
        // 用 LinkedHashMap 保证 q1 先于 q2 遍历（Map.of 不保证顺序）
        Map<TypedQuery, List<KnowledgeChunk>> results = new LinkedHashMap<>();
        results.put(query("q1"), List.of(first));
        results.put(query("q2"), List.of(second));

        List<KnowledgeChunk> fused = fusionService.fuse(results);

        assertEquals(1, fused.size());
        assertSame(first, fused.get(0));
    }

    @Test
    @DisplayName("deduplicate → 空输入返回空列表")
    void deduplicate_nullOrEmpty_returnsEmpty() {
        assertTrue(fusionService.deduplicate(null).isEmpty());
        assertTrue(fusionService.deduplicate(List.of()).isEmpty());
    }

    @Test
    @DisplayName("deduplicate → 按 chunkId 去重且保持首次出现顺序")
    void deduplicate_removesDuplicatesKeepOrder() {
        KnowledgeChunk a1 = chunk("a");
        KnowledgeChunk a2 = chunk("a");
        KnowledgeChunk b = chunk("b");

        List<KnowledgeChunk> result = fusionService.deduplicate(List.of(a1, b, a2));

        assertEquals(2, result.size());
        assertSame(a1, result.get(0));
        assertSame(b, result.get(1));
    }
}
