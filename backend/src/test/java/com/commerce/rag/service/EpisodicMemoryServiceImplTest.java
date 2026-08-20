package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.enums.EpisodicActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicAction;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.service.impl.EpisodicMemoryServiceImpl;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.SearchResp;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 经历记忆服务纯函数测试 —— 注：this.lambdaQuery()/save/update 不可 Mockito 直测（MP 实证），
 * SQL 装配段由 Testcontainers 集成覆盖（见 EpisodicMemoryIntegrationTest）；本类只测纯函数：
 * toExistingMemoriesText / toWriteRow / syncIndexBestEffort 决策 / recall 降级 / buildRefs 召回筛选
 * （召回 PG 取数下沉集成，筛选/排序/截断下沉 public buildRefs 直测，brief 的 spy 覆写 listByIds 不再适用——
 * 见 EpisodicMemoryServiceImpl 类注释 ③）。
 */
class EpisodicMemoryServiceImplTest {

    private final MemoryProperties props = new MemoryProperties();
    private final EpisodicDecisionEngine engine = new EpisodicDecisionEngine(props);
    private final MilvusClientV2 milvus = mock(MilvusClientV2.class);
    private final EmbeddingModel embedding = mock(EmbeddingModel.class);
    private final EpisodicMemoryServiceImpl service = new EpisodicMemoryServiceImpl(engine, milvus, embedding, props);

    @Test
    @DisplayName("toExistingMemoriesText — active 记忆行转「标签:内容」行，空返回「无」")
    void toExistingMemoriesText_formatsLabelAndContent() {
        String text = service.toExistingMemoriesText(
                List.of(memory("learning_progress", "已完成 Java 集合泛型"), memory("resolved_question", "SQL 索引优化方案")));
        assertEquals("学习进度: 已完成 Java 集合泛型\n已解决问题: SQL 索引优化方案", text);
        // 未知 type 回退原始 type
        assertEquals("custom_type: 内容", service.toExistingMemoriesText(List.of(memory("custom_type", "内容"))));
        assertEquals("无", service.toExistingMemoriesText(List.of()));
        assertEquals("无", service.toExistingMemoriesText(null));
    }

    @Test
    @DisplayName("toWriteRow — CREATE 动作映射完整实体（importance/confidence 保留 3 位小数）")
    void toWriteRow_mapsActionFields() {
        EpisodicAction action = new EpisodicAction(
                EpisodicActionType.CREATE,
                "learning_progress",
                "已完成 Java 集合",
                "一句话摘要",
                "{\"topic\":\"java\"}",
                null,
                1,
                0.8555,
                0.7333,
                0.9);
        UserEpisodicMemory row = service.toWriteRow(7L, 77L, action, "active");
        assertEquals(Long.valueOf(7L), row.getUserId());
        assertEquals("learning_progress", row.getType());
        assertEquals("已完成 Java 集合", row.getContent());
        assertEquals("一句话摘要", row.getSummary());
        assertEquals("{\"topic\":\"java\"}", row.getStructuredFacts());
        assertEquals("active", row.getValidity());
        assertEquals(Integer.valueOf(1), row.getVersion());
        assertEquals(Long.valueOf(77L), row.getSourceSessionId());
        assertEquals(0, new BigDecimal("0.856").compareTo(row.getImportance()), "0.8555 四舍五入到 0.856");
        assertEquals(0, new BigDecimal("0.733").compareTo(row.getConfidence()), "0.7333 四舍五入到 0.733");
    }

    @Test
    @DisplayName("syncIndexBestEffort — Milvus upsert 抛异常仅吞掉，不向调用方抛出")
    void syncIndexBestEffort_swallowsMilvusFailure() {
        UserEpisodicMemory row = memory("learning_progress", "已完成 Java 集合");
        row.setId(11L);
        row.setUserId(7L);
        row.setSummary("摘要");
        // 注入非空 embedding（buildUpsert 空向量时跳过生成请求，需先保证请求可被构建）
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        doThrow(new RuntimeException("milvus down")).when(milvus).upsert(any(UpsertReq.class));
        assertDoesNotThrow(() -> service.syncIndexBestEffort(() -> service.buildUpsert(row, "active")));
        verify(milvus, times(1)).upsert(any(UpsertReq.class));
    }

    @Test
    @DisplayName("recall — Milvus 故障降级返回空列表（不抛异常）")
    void recall_milvusFailure_returnsEmpty() {
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(milvus.search(any(SearchReq.class))).thenThrow(new RuntimeException("milvus down"));
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty());
    }

    @Test
    @DisplayName("recall — userId/query 空守卫 → 空列表（不进检索链路）")
    void recall_nullGuard_returnsEmpty() {
        assertTrue(service.recall(null, "查询", false, 5).isEmpty(), "userId 为空不进检索");
        assertTrue(service.recall(7L, null, false, 5).isEmpty(), "query 为空不进检索");
        assertTrue(service.recall(7L, "   ", false, 5).isEmpty(), "query 空白不进检索");
    }

    @Test
    @DisplayName("recall — embedding 空向量 → 降级空列表（无向量不索引检索）")
    void recall_emptyEmbedding_returnsEmpty() {
        when(embedding.embed(anyString())).thenReturn(new float[0]);
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty());
        // null 向量同样降级
        when(embedding.embed(anyString())).thenReturn(null);
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty());
    }

    @Test
    @DisplayName("recall — Milvus 无召回结果 → 空列表（searchResults 空 / 内层第一行空）")
    void recall_emptySearchResults_returnsEmpty() {
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(milvus.search(any(SearchReq.class)))
                .thenReturn(SearchResp.builder().searchResults(List.of()).build());
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty(), "searchResults 整体为空降级空列表");

        when(milvus.search(any(SearchReq.class)))
                .thenReturn(
                        SearchResp.builder().searchResults(List.of(List.of())).build());
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty(), "内层召回列表为空降级空列表");
    }

    @Test
    @DisplayName("recall — memory_id 缺失/脏数据逐条跳过 → ids 空降级空列表（不阻断召回）")
    void recall_malformedMemoryIds_skipsAndDegrades() {
        when(embedding.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        // 第一条 entity=null（memory_id 缺失 → continue）、第二条 memory_id 非数字（NumberFormatException → 跳过），
        // 两条均被丢弃 → ids 空 → 降级空列表（不进入 PG 取数，无需 MP 上下文）
        when(milvus.search(any(SearchReq.class)))
                .thenReturn(SearchResp.builder()
                        .searchResults(List.of(List.of(
                                SearchResp.SearchResult.builder()
                                        .entity(null)
                                        .score(0.9f)
                                        .build(),
                                SearchResp.SearchResult.builder()
                                        .entity(Map.of(MilvusCollectionInitializer.FIELD_MEMORY_ID, "abc"))
                                        .score(0.9f)
                                        .build())))
                        .build());
        assertTrue(service.recall(7L, "查询", false, 5).isEmpty(), "脏 memory_id 全部跳过降级空列表");
    }

    @Test
    @DisplayName("buildRefs — 分数阈值过滤 + active 兜底 + 按分降序 + topK 截断（召回纯函数直测）")
    void buildRefs_filtersAndSorts() {
        Map<Long, Double> scoreById = new HashMap<>();
        scoreById.put(11L, 0.9);
        scoreById.put(12L, 0.5);
        scoreById.put(13L, 0.2); // 低于 recall-min-score(0.30) → 分数过滤丢弃
        scoreById.put(14L, 0.7); // superseded 历史行，recallHistory=false 时被 active 兜底丢弃
        List<UserEpisodicMemory> rows = List.of(
                activeRow(11L, "学习进度", "内容A", "摘A"),
                activeRow(12L, "已解决问题", "内容B", "摘B"),
                activeRow(13L, "学习目标", "低分内容", "摘C"),
                supersededRow(14L, "学习进度", "历史内容", "摘D"));

        // recallHistory=false：superseded 被 active 兜底过滤、低分行被分数过滤，剩余按分降序 topK=2
        List<EpisodicMemoryRef> refs = service.buildRefs(rows, scoreById, false, 2, 0.30);
        assertEquals(List.of(11L, 12L), refs.stream().map(EpisodicMemoryRef::id).toList());
        assertEquals("active", refs.get(0).validity());
        assertEquals(0.9, refs.get(0).score(), 1e-4);
        assertEquals(0.5, refs.get(1).score(), 1e-4);

        // recallHistory=true：superseded 行放行（分数过滤仍生效），全量按分降序：11(0.9)→14(0.7)→12(0.5)
        List<EpisodicMemoryRef> hist = service.buildRefs(rows, scoreById, true, 5, 0.30);
        assertEquals(
                List.of(11L, 14L, 12L), hist.stream().map(EpisodicMemoryRef::id).toList());
        assertEquals("superseded", hist.get(1).validity());

        // topK=1 截断到最高分
        assertEquals(1, service.buildRefs(rows, scoreById, true, 1, 0.30).size());
        // 空输入返回空列表
        assertTrue(service.buildRefs(null, scoreById, true, 5, 0.30).isEmpty());
        assertTrue(service.buildRefs(List.of(), scoreById, true, 5, 0.30).isEmpty());
    }

    @Test
    @DisplayName("applyExtraction — userId=null / result=null / memories 空 → 返回 0 且不触发决策")
    void applyExtraction_nullGuard_returnsZero() {
        EpisodicDecisionEngine mockEngine = mock(EpisodicDecisionEngine.class);
        EpisodicMemoryServiceImpl svc = new EpisodicMemoryServiceImpl(mockEngine, milvus, embedding, props);
        var result = new EpisodicExtractionResult(List.of(
                new EpisodicMemoryExtraction(true, "CREATE", "learning_goal", "内容", "摘要", null, 0.8, 0.8, 0.8, null)));
        assertEquals(0, svc.applyExtraction(null, 1L, result), "userId 为空不写库");
        assertEquals(0, svc.applyExtraction(7L, 1L, null), "result 为空不写库");
        assertEquals(0, svc.applyExtraction(7L, 1L, EpisodicExtractionResult.empty()), "memories 空不写库");
        verify(mockEngine, never()).decide(any(), any());
    }

    /** 构造纯 type/content 记忆行（toExistingMemoriesText 用） */
    private static UserEpisodicMemory memory(String type, String content) {
        UserEpisodicMemory r = new UserEpisodicMemory();
        r.setType(type);
        r.setContent(content);
        return r;
    }

    /** 构造带 type/content/summary/validity 的行（buildRefs 用，id/score 由 scoreById 对应） */
    private static UserEpisodicMemory activeRow(long id, String type, String content, String summary) {
        UserEpisodicMemory r = new UserEpisodicMemory();
        r.setId(id);
        r.setType(type);
        r.setContent(content);
        r.setSummary(summary);
        r.setValidity("active");
        return r;
    }

    private static UserEpisodicMemory supersededRow(long id, String type, String content, String summary) {
        UserEpisodicMemory r = activeRow(id, type, content, summary);
        r.setValidity("superseded");
        return r;
    }
}
