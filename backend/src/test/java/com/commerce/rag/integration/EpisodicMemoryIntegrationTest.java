package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.service.IEpisodicMemoryService;
import com.commerce.rag.test.IntegrationTestBase;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 经历记忆服务集成测试 —— 真实 PG 落库/状态机流转 + mock Milvus 召回（spec §8.5-§8.7）
 *
 * <p>兜底价值：本类 useContext 全覆盖 SQL 段（this.lambdaQuery/save/lambdaUpdate/getById/listByIds）
 * 与 Spring wiring（@Service + @RequiredArgsConstructor 装配，基准类 C-1 教训：新 @Service 必须过一条
 * 集成测试兜 wiring，缺 @Component 会静默挂全量 @SpringBootTest）。
 *
 * <p>数据隔离：IntegrationTestBase.setUpBase() 前置清理业务表/Redis + 模型 stub；本类 @BeforeEach
 * 额外清 user_episodic_memory（基类 cleanupBusinessTables 不含该表），防跨用例残留。
 */
class EpisodicMemoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private IEpisodicMemoryService episodicMemoryService;

    @BeforeEach
    void setUpEpisodic() {
        jdbcTemplate.update("DELETE FROM user_episodic_memory");
    }

    @Test
    @DisplayName("applyExtraction — CREATE 条目落库 active 行（含非 null structuredFacts 回读，SQL 段与 wiring 兜底）")
    void applyExtraction_createRoundTrip_persistsRow() {
        Long userId = registerUser("epi_test_1", "STUDENT");
        int written = episodicMemoryService.applyExtraction(
                userId,
                99L,
                new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                        // structuredFacts 携带非 null JSON 原文：R1 修复（V12 JSONB→TEXT，V10 先例）后
                        // MP String 直插 TEXT 列可用，本用例是 Critical 回归护栏（曾因 String→jsonb 绑定报错）
                        true,
                        "CREATE",
                        "learning_progress",
                        "已完成 Java 集合泛型",
                        "集合摘要",
                        "{\"topic\":\"java\"}",
                        0.8,
                        0.8,
                        0.8,
                        null))));
        assertEquals(1, written, "CREATE 生效动作计 1");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT content, summary, validity, version, source_session_id, importance, structured_facts"
                        + " FROM user_episodic_memory WHERE user_id=? AND deleted=0",
                userId);
        assertEquals("已完成 Java 集合泛型", row.get("content"));
        assertEquals("集合摘要", row.get("summary"));
        assertEquals("active", row.get("validity"));
        assertEquals(1, ((Number) row.get("version")).intValue());
        assertEquals(99L, ((Number) row.get("source_session_id")).longValue());
        // importance = 0.8 × typeWeight(learning_progress=0.9) = 0.72 → NUMERIC(4,3)
        assertEquals(0, new BigDecimal("0.720").compareTo((BigDecimal) row.get("importance")));
        // Critical 回归关键断言：非 null structuredFacts 经 MP TEXT 写入后原文回读
        assertEquals("{\"topic\":\"java\"}", row.get("structured_facts"));
    }

    @Test
    @DisplayName("applyExtraction — UPDATE 旧行 superseded + 新行 active version=2")
    void applyExtraction_update_supersedesOldCreatesNew() {
        Long userId = registerUser("epi_test_2", "STUDENT");
        // 预置 active 行（v1），UPDATE 条目 merge_target 命中其 content
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_goal', '旧目标', '旧摘要', 'active', 1, 0)",
                800201L,
                userId);
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                        true, "UPDATE", "learning_goal", "新目标", "新摘要", null, 0.8, 0.8, 0.8, "旧目标"))));
        assertEquals(1, written, "UPDATE 生效动作计 1");

        String oldValidity = jdbcTemplate.queryForObject(
                "SELECT validity FROM user_episodic_memory WHERE id=?", String.class, 800201L);
        assertEquals("superseded", oldValidity, "旧行状态流转 superseded");
        Map<String, Object> newRow = jdbcTemplate.queryForMap(
                "SELECT content, validity, version FROM user_episodic_memory"
                        + " WHERE user_id=? AND type='learning_goal' AND validity='active' AND deleted=0",
                userId);
        assertEquals("新目标", newRow.get("content"));
        assertEquals("active", newRow.get("validity"));
        assertEquals(2, ((Number) newRow.get("version")).intValue(), "新行版本 = 旧行 + 1");
    }

    @Test
    @DisplayName("recall — mock Milvus 定位(memory_id+user_id 过滤) → PG 取数 → 按分降序 ref 列表")
    void recall_returnsRefs() {
        Long userId = registerUser("epi_test_3", "STUDENT");
        // 预置两条 active 行（真实 PG，供 listByIds 取数）
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_progress', '完成 Python 基础', 'Py 摘要', 'active', 1, 0)",
                800301L,
                userId);
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'resolved_question', 'SQL 索引优化', 'SQL 摘要', 'active', 1, 0)",
                800302L,
                userId);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f, 0.3f});
        // mock Milvus 召回：高分为 800302、低分为 800301（实体带 memory_id 字符串）
        when(milvusClientV2.search(any(SearchReq.class)))
                .thenReturn(SearchResp.builder()
                        .searchResults(List.of(List.of(
                                SearchResp.SearchResult.builder()
                                        .entity(Map.of(MilvusCollectionInitializer.FIELD_MEMORY_ID, "800302"))
                                        .score(0.9f)
                                        .build(),
                                SearchResp.SearchResult.builder()
                                        .entity(Map.of(MilvusCollectionInitializer.FIELD_MEMORY_ID, "800301"))
                                        .score(0.5f)
                                        .build())))
                        .build());

        List<EpisodicMemoryRef> refs = episodicMemoryService.recall(userId, "查询", false, 5);
        assertEquals(2, refs.size());
        assertEquals(Long.valueOf(800302L), refs.get(0).id(), "按分降序最高分在前");
        assertEquals("SQL 索引优化", refs.get(0).content());
        assertEquals(Long.valueOf(800301L), refs.get(1).id());
        // 硬隔离：Milvus 过滤表达式必须携带 user_id
        verify(milvusClientV2, times(1))
                .search(argThat(req -> req != null
                        && req.getFilter() != null
                        && req.getFilter().contains(MilvusCollectionInitializer.FIELD_MEMORY_USER_ID)
                        && req.getFilter().contains(String.valueOf(userId))));
    }

    @Test
    @DisplayName("applyExtraction — INVALIDATE 目标行置 invalidated + 无新行（spec §8.6 否定态）")
    void applyExtraction_invalidate_marksTargetInvalidated() {
        Long userId = registerUser("epi_test_4", "STUDENT");
        // 预置 active 行（v1），INVALIDATE 条目 merge_target 命中其 content
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_progress', '已学会 Java 泛型', '泛型摘要', 'active', 1, 0)",
                800401L,
                userId);
        // embedding 供 buildUpsertById 反查旧行组装索引（SQL 段 + wiring 兜底）
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                        // INVALIDATE：score=0.776 ≥ 0.7 过门槛，命中目标行
                        true,
                        "INVALIDATE",
                        "learning_progress",
                        "已学会 Java 泛型",
                        null,
                        null,
                        0.8,
                        0.8,
                        0.8,
                        "已学会 Java 泛型"))));
        assertEquals(1, written, "INVALIDATE 生效动作计 1");
        assertEquals(
                "invalidated",
                jdbcTemplate.queryForObject(
                        "SELECT validity FROM user_episodic_memory WHERE id=?", String.class, 800401L),
                "目标行状态流转 invalidated");
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE user_id=? AND validity='active' AND deleted=0",
                Long.class,
                userId);
        assertEquals(0L, activeCount, "INVALIDATE 不产生新 active 行");
    }

    @Test
    @DisplayName("applyExtraction — 低分条目 IGNORE 不写库（spec §8.3 门槛统一前置）")
    void applyExtraction_lowScore_ignoredNoWrite() {
        Long userId = registerUser("epi_test_5", "STUDENT");
        // score=0.4×0.2+0.3×0.2+0.3×(0.2×0.9)=0.194 < writeHigh(0.7) → 决策 IGNORE，无写操作
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                        true, "CREATE", "learning_progress", "低分内容", "摘要", null, 0.2, 0.2, 0.2, null))));
        assertEquals(0, written, "低于 writeHigh 门槛 → IGNORE 计 0");
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE user_id=?", Long.class, userId);
        assertEquals(0L, cnt, "库中无任何行");
    }

    @Test
    @DisplayName("findActiveMemoriesText — active 行 → 「标签:内容」多行文本（spec §8.4 提取参考输入）")
    void findActiveMemoriesText_returnsLabeledLines() {
        Long userId = registerUser("epi_test_6", "STUDENT");
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_progress', '学会 Redis 持久化', '摘要', 'active', 1, 0)",
                800601L,
                userId);
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'resolved_question', '解决 N+1 查询问题', '摘要', 'active', 1, 0)",
                800602L,
                userId);

        String text = episodicMemoryService.findActiveMemoriesText(userId);

        assertTrue(text.contains("学习进度: 学会 Redis 持久化"), "active 行按标签组装");
        assertTrue(text.contains("已解决问题: 解决 N+1 查询问题"), "多行按换行拼接");
    }

    @Test
    @DisplayName("recallHistory=true — 过滤表达式不含 active 条件 + superseded 行放行（spec §8.7）")
    void recall_withHistory_includesSuperseded() {
        Long userId = registerUser("epi_test_7", "STUDENT");
        // active 行 + superseded 历史行，recallHistory=true 时两者都应被召回
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_progress', '当前进度', '摘要', 'active', 1, 0)",
                800701L,
                userId);
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_progress', '历史进度', '摘要', 'superseded', 1, 0)",
                800702L,
                userId);
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(milvusClientV2.search(any(SearchReq.class)))
                .thenReturn(SearchResp.builder()
                        .searchResults(List.of(List.of(
                                SearchResp.SearchResult.builder()
                                        .entity(Map.of(MilvusCollectionInitializer.FIELD_MEMORY_ID, "800701"))
                                        .score(0.8f)
                                        .build(),
                                SearchResp.SearchResult.builder()
                                        .entity(Map.of(MilvusCollectionInitializer.FIELD_MEMORY_ID, "800702"))
                                        .score(0.6f)
                                        .build())))
                        .build());

        List<EpisodicMemoryRef> refs = episodicMemoryService.recall(userId, "查询", true, 5);

        assertEquals(2, refs.size(), "recallHistory=true 历史行放行");
        assertTrue(refs.stream().anyMatch(r -> "superseded".equals(r.validity())), "含历史态行");
        // 硬隔离不后退：recall_history=true 过滤表达式仍须携带 user_id
        verify(milvusClientV2, times(1))
                .search(argThat(req -> req != null
                        && req.getFilter() != null
                        && req.getFilter().contains(MilvusCollectionInitializer.FIELD_MEMORY_USER_ID)
                        && req.getFilter().contains(String.valueOf(userId))));
    }
}
