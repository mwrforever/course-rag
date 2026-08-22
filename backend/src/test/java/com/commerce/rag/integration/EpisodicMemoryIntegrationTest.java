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
import io.milvus.v2.service.vector.request.UpsertReq;
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

        // 查询向量由 RetrieveNode 预嵌入传入（方案 3-1-a：集成直调传向量，不再内部 embed）
        List<EpisodicMemoryRef> refs = episodicMemoryService.recall(userId, new float[] {0.1f, 0.2f, 0.3f}, false, 5);
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
        // embedding 供索引同步组装（旧行直接复用批内视图行，SQL 段 + wiring 兜底）
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

        // 查询向量由 RetrieveNode 预嵌入传入（方案 3-1-a：集成直调传向量，不再内部 embed）
        List<EpisodicMemoryRef> refs = episodicMemoryService.recall(userId, new float[] {0.1f, 0.2f}, true, 5);

        assertEquals(2, refs.size(), "recallHistory=true 历史行放行");
        assertTrue(refs.stream().anyMatch(r -> "superseded".equals(r.validity())), "含历史态行");
        // 硬隔离不后退：recall_history=true 过滤表达式仍须携带 user_id
        verify(milvusClientV2, times(1))
                .search(argThat(req -> req != null
                        && req.getFilter() != null
                        && req.getFilter().contains(MilvusCollectionInitializer.FIELD_MEMORY_USER_ID)
                        && req.getFilter().contains(String.valueOf(userId))));
    }

    @Test
    @DisplayName("applyExtraction — 同批同 type 同 content 两条 CREATE → 仅落一行 active（BUG-02 批内去重）")
    void applyExtraction_sameBatchDuplicateCreate_deduped() {
        Long userId = registerUser("epi_test_8", "STUDENT");
        // 同批两条完全相同的 CREATE：第二条应看到批内已写入行 → 重复 IGNORE（修复前快照决策会落两行）
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(
                        new EpisodicMemoryExtraction(
                                true, "CREATE", "learning_goal", "三个月内转行 Python", "转行目标", null, 0.85, 0.9, 0.9, null),
                        new EpisodicMemoryExtraction(
                                true,
                                "CREATE",
                                "learning_goal",
                                "三个月内转行 Python",
                                "转行目标",
                                null,
                                0.85,
                                0.9,
                                0.9,
                                null))));
        assertEquals(1, written, "第二条重复 CREATE 应 IGNORE，生效动作计 1");
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE user_id=? AND validity='active' AND deleted=0",
                Long.class,
                userId);
        assertEquals(1L, activeCount, "同批重复条目只留一行 active（去重不被绕过）");
    }

    @Test
    @DisplayName("applyExtraction — 批内 CREATE 后 INVALIDATE 同内容：目标行可见并成功否定（BUG-02 批内前后关联）")
    void applyExtraction_batchCreateThenInvalidate_targetVisible() {
        Long userId = registerUser("epi_test_9", "STUDENT");
        // 用户同轮先陈述（CREATE）后否认（INVALIDATE 同 content）：INVALIDATE 决策必须看到本批 CREATE 的行
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(
                        new EpisodicMemoryExtraction(
                                true,
                                "CREATE",
                                "learning_progress",
                                "正在学 Django 框架",
                                "Django",
                                null,
                                0.85,
                                0.9,
                                0.9,
                                null),
                        new EpisodicMemoryExtraction(
                                true,
                                "INVALIDATE",
                                "learning_progress",
                                "正在学 Django 框架",
                                null,
                                null,
                                0.8,
                                0.8,
                                0.8,
                                "正在学 Django 框架"))));
        assertEquals(2, written, "CREATE + INVALIDATE 各计 1 个生效动作");
        Long activeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE user_id=? AND validity='active' AND deleted=0",
                Long.class,
                userId);
        assertEquals(0L, activeCount, "被用户明确否定的事实不得以 active 留存");
        Long invalidated = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_episodic_memory WHERE user_id=? AND validity='invalidated' AND deleted=0",
                Long.class,
                userId);
        assertEquals(1L, invalidated, "批内 CREATE 的行被 INVALIDATE 置否定态");
    }

    @Test
    @DisplayName("applyExtraction — 批内链式 UPDATE：后一条定位前一条的新行，version 依次递增（BUG-02/BUG-12 版本链）")
    void applyExtraction_batchSequentialUpdate_versionChain() {
        Long userId = registerUser("epi_test_10", "STUDENT");
        // 预置 active 旧行 v1；批内两条 UPDATE：第二条的 merge_target 指向第一条产出的新行内容
        jdbcTemplate.update(
                "INSERT INTO user_episodic_memory (id, user_id, type, content, summary, validity, version, deleted)"
                        + " VALUES (?, ?, 'learning_goal', '旧目标', '旧摘要', 'active', 1, 0)",
                800801L,
                userId);
        int written = episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(
                        new EpisodicMemoryExtraction(
                                true, "UPDATE", "learning_goal", "新目标", "新摘要", null, 0.85, 0.9, 0.9, "旧目标"),
                        new EpisodicMemoryExtraction(
                                true, "UPDATE", "learning_goal", "更新目标", "更新摘要", null, 0.85, 0.9, 0.9, "新目标"))));
        assertEquals(2, written, "两条 UPDATE 各计 1 个生效动作");

        // 版本链 1→2→3：旧行 superseded、中间行 superseded、末行 active v3（无 version 重复）
        assertEquals(
                "superseded",
                jdbcTemplate.queryForObject(
                        "SELECT validity FROM user_episodic_memory WHERE id=?", String.class, 800801L));
        Map<String, Object> last = jdbcTemplate.queryForMap(
                "SELECT content, validity, version FROM user_episodic_memory"
                        + " WHERE user_id=? AND type='learning_goal' AND validity='active' AND deleted=0",
                userId);
        assertEquals("更新目标", last.get("content"), "链式 UPDATE 最终落最新内容");
        assertEquals(3, ((Number) last.get("version")).intValue(), "末行 version = 旧行+1 链式递增");

        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT version FROM user_episodic_memory WHERE user_id=? AND deleted=0 ORDER BY version",
                Integer.class,
                userId);
        assertEquals(List.of(1, 2, 3), versions, "批内版本链无重复（各次 UPDATE 基于最新视图演算）");
    }

    @Test
    @DisplayName("applyExtraction — 索引同步在事务提交后执行（BUG-06：embedding/Milvus 不持有 DB 连接）")
    void applyExtraction_indexSyncRunsAfterCommit() {
        Long userId = registerUser("epi_test_11", "STUDENT");
        // 索引同步需要非空 embedding 向量才会构造 UpsertReq 并调 Milvus
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(new EpisodicMemoryExtraction(
                        true, "CREATE", "learning_progress", "完成 Milvus 集成", "Milvus", null, 0.85, 0.9, 0.9, null))));

        // 事务提交后 afterCommit 路径应执行索引 upsert（Milvus 仅索引，PG 事实源不受影响）
        verify(milvusClientV2, times(1)).upsert(any(UpsertReq.class));
    }

    @Test
    @DisplayName("applyExtraction — 同批多条生效动作合并为单次 upsert（data 含多行，O(N)→O(1) 远程调用）")
    void applyExtraction_batchIndexSync_singleUpsertWithMultipleRows() {
        Long userId = registerUser("epi_test_13", "STUDENT");
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        episodicMemoryService.applyExtraction(
                userId,
                null,
                new EpisodicExtractionResult(List.of(
                        new EpisodicMemoryExtraction(
                                true, "CREATE", "learning_progress", "已完成 Spring 事务", "事务", null, 0.85, 0.9, 0.9, null),
                        new EpisodicMemoryExtraction(
                                true, "CREATE", "resolved_question", "解决索引失效问题", "索引", null, 0.85, 0.9, 0.9, null))));

        // 两条 CREATE 合并进同一次 upsert（2 行 data），仅 1 次 Milvus gRPC 往返
        verify(milvusClientV2, times(1))
                .upsert(argThat(req ->
                        req != null && req.getData() != null && req.getData().size() == 2));
    }

    @Test
    @DisplayName("findActiveMemoriesText — 超 existing-text-limit 截断（BUG-05：{existing} 无界膨胀）")
    void findActiveMemoriesText_limitedRows() {
        Long userId = registerUser("epi_test_12", "STUDENT");
        // 预置 105 行 active（超过默认 limit 100）：{existing} 注入文本必须被截断，防止记忆量增长后 prompt 膨胀；
        // updated_at 显式递增（i 越大越新），保证 orderByDesc(updatedAt) 截断结果确定
        for (int i = 1; i <= 105; i++) {
            jdbcTemplate.update(
                    "INSERT INTO user_episodic_memory"
                            + " (id, user_id, type, content, summary, validity, version, deleted, updated_at)"
                            + " VALUES (?, ?, 'learning_progress', ?, '摘要', 'active', 1, 0, now() - (? * interval '1 minute'))",
                    810000L + i,
                    userId,
                    "进度内容" + i,
                    105 - i);
        }

        String text = episodicMemoryService.findActiveMemoriesText(userId);

        assertEquals(100, text.split("\n").length, "注入提取 prompt 的已有记忆行数受 limit 截断");
        assertTrue(text.contains("进度内容105"), "截断保留最近更新行（orderByDesc updatedAt）");
        // 行级精确匹配：子串「进度内容1」会误命中 10/100/105，需按行尾断言最旧行被截断
        assertTrue(!text.contains("进度内容1\n"), "最旧行被截断");
    }
}
