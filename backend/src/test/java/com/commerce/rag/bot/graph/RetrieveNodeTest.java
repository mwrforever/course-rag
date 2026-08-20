package com.commerce.rag.bot.graph;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.hook.DocumentAssemblerInterceptor;
import com.commerce.rag.bot.hook.EpisodicInterceptor;
import com.commerce.rag.bot.hook.PreferenceInterceptor;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.rewrite.QueryPlanFilters;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult;
import com.commerce.rag.bot.tool.dto.KnowledgeSearchResult.KnowledgeChunk;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.retrieval.ContextBuilderService;
import com.commerce.rag.retrieval.CourseNameMapper;
import com.commerce.rag.service.AttachmentLocalSearchService;
import com.commerce.rag.service.IEpisodicMemoryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * RetrieveNode 单元测试 —— 检索编排（意图分支 / 课程过滤 / document 写出 / 失败降级）
 *
 * <p>注：本类与项目接口 {@link OverAllState}（KEY_QUERY_PLAN 定义处）同包，但显式 import 了
 * 框架的 {@code com.alibaba.cloud.ai.graph.OverAllState}（JLS 6.4.1 单类型 import 遮蔽同包同名
 * 类型），故常量以静态 import（{@code KEY_QUERY_PLAN}）方式引用项目接口成员，避免遮蔽冲突。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetrieveNode 检索编排测试")
class RetrieveNodeTest {

    /** 真实属性对象：recallTopK 取默认 5，供 recall 断言精确匹配 */
    private MemoryProperties memoryProperties = new MemoryProperties();

    @Mock
    private SearchKnowledgeTool searchKnowledgeTool;

    @Mock
    private CourseNameMapper courseNameMapper;

    @Mock
    private ContextBuilderService contextBuilderService;

    @Mock
    private AttachmentLocalSearchService localSearchService;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private IEpisodicMemoryService episodicMemoryService;

    /**
     * 组装被测节点 —— 注入全部依赖（episodicMemoryService mock + memoryProperties 真实默认配置）
     */
    private RetrieveNode newRetrieveNode() {
        return new RetrieveNode(
                searchKnowledgeTool,
                courseNameMapper,
                contextBuilderService,
                localSearchService,
                embeddingModel,
                episodicMemoryService,
                memoryProperties);
    }

    @Test
    @DisplayName("apply — knowledge_question：映射课程 → 构建 TypedQuery → 检索 → document 写入 metadata")
    void apply_knowledgeQuestion_pipesToDocument() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("高等数学 学习方法"), new QueryPlanFilters(List.of("高等数学")), false);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("s1")
                .addMetadata("userId", "u1")
                .build();

        when(courseNameMapper.mapCourseNames(List.of("高等数学"))).thenReturn(List.of("101"));
        KnowledgeChunk k =
                new KnowledgeChunk("c1", "内容", "", "讲义", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64));
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of(k)));
        when(contextBuilderService.buildDocument("高等数学怎么学", List.of("高等数学 学习方法"), List.of(k)))
                .thenReturn("<document>D</document>");

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        // 不写 state（检索结果不落 checkpoint）
        assertTrue(result.isEmpty());
        // document 写入 metadata
        assertEquals(
                "<document>D</document>",
                config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT));
        // TypedQuery 携带 courseIds 过滤
        verify(searchKnowledgeTool)
                .searchKnowledge(argThat(queries ->
                        queries.size() == 1 && queries.get(0).courseIds().equals(List.of("101"))));
    }

    @Test
    @DisplayName("apply — courseNames 映射为空 → courseIds null（全局检索）；空检索结果不写 document")
    void apply_noMatchedCourse_globalSearch() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("查询"), new QueryPlanFilters(List.of("未知课程")), false);
        OverAllState state = new OverAllState(Map.of(KEY_QUERY_PLAN, plan));
        RunnableConfig config =
                RunnableConfig.builder().addMetadata("userId", "u1").build();
        when(courseNameMapper.mapCourseNames(List.of("未知课程"))).thenReturn(List.of());
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of()));

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        verify(searchKnowledgeTool)
                .searchKnowledge(argThat(queries -> queries.get(0).courseIds() == null));
        // 空结果不写 document、不调 ContextBuilder（实现短路）
        verify(contextBuilderService, never()).buildDocument(any(), any(), any());
        assertTrue(config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT) == null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("apply — 系统检索为空但附件上下文存在：注入仅含 <user-document> 的 document shell")
    void apply_emptySystem_withAttachmentContext_injectsUserDocumentShell() throws Exception {
        // 附件上下文：1 张图片 caption + 1 个文档局部语料（系统库无命中场景）
        AttachmentContext attachmentContext = new AttachmentContext(
                List.of(new ImageCaptionResult("图片1:红色图表", "a.png")),
                Map.of("0/doc.pdf", List.of(new DocumentLocalChunk("图表纵轴为销量", new float[] {1.0f}, 0))));
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("红色图表含义"), new QueryPlanFilters(List.of()), false);
        OverAllState state = new OverAllState(
                Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("图片1:[红色图表] 高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("s1")
                .addMetadata("attachmentContext", attachmentContext)
                .addMetadata("userId", "u1")
                .build();

        // 系统检索为空 → 系统侧组装不触发，仅执行附件局部检索链路
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of()));
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {1.0f, 0.0f});
        when(localSearchService.search(anyList(), any(), anyInt()))
                .thenReturn(List.of(new DocumentLocalChunk("图表纵轴为销量", new float[] {1.0f}, 0)));
        when(contextBuilderService.buildUserDocument(anyList(), anyMap()))
                .thenReturn("<user-document>\n  [图片1:红色图表]\n</user-document>");
        // 以空 <document> 壳为底合并 user-document（buildEmptySystemDocument 的装配路径）
        when(contextBuilderService.appendUserDocument(anyString(), anyString()))
                .thenReturn("<document>\n<user-document>\n  [图片1:红色图表]\n</user-document>\n</document>");

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        // 检索结果不写 state
        assertTrue(result.isEmpty());
        // 系统检索为空 → 不调 system 侧组装
        verify(contextBuilderService, never()).buildDocument(any(), any(), any());
        // document_context 被写入且含 <user-document>
        String written = String.valueOf(config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT));
        assertEquals("<document>\n<user-document>\n  [图片1:红色图表]\n</user-document>\n</document>", written);
        assertTrue(written.contains("<user-document>"));
    }

    @Test
    @DisplayName("apply — chat/unknown 不检索、不写 document")
    void apply_nonKnowledgeIntent_skipsSearch() throws Exception {
        QueryPlan chat = new QueryPlan(IntentType.CHAT, List.of("你好"), new QueryPlanFilters(List.of()), false);
        OverAllState state = new OverAllState(Map.of(KEY_QUERY_PLAN, chat));
        RunnableConfig config =
                RunnableConfig.builder().addMetadata("userId", "u1").build();

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        verify(searchKnowledgeTool, never()).searchKnowledge(any());
        verify(courseNameMapper, never()).mapCourseNames(any());
        assertTrue(config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT) == null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("apply — state 无 queryPlan 时安全跳过（不 NPE）")
    void apply_missingPlan_skipSafely() throws Exception {
        OverAllState state = new OverAllState(Map.of());
        RunnableConfig config =
                RunnableConfig.builder().addMetadata("userId", "u1").build();

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        verify(searchKnowledgeTool, never()).searchKnowledge(any());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("apply — metadata 含附件上下文：局部检索合并 user-document 后写 document_context")
    void apply_withAttachmentContext_mergesUserDocument() throws Exception {
        // 附件上下文：1 张图片 caption + 1 个文档局部语料（分片 2 条）
        AttachmentContext attachmentContext = new AttachmentContext(
                List.of(new ImageCaptionResult("图片1:红色图表", "a.png")),
                Map.of(
                        "0/doc.pdf",
                        List.of(
                                new DocumentLocalChunk("图表纵轴为销量", new float[] {1.0f}, 0),
                                new DocumentLocalChunk("红色代表本季度", new float[] {2.0f}, 1))));
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("红色图表含义"), new QueryPlanFilters(List.of()), false);
        // 图输入 UserMessage 含 Task 9 拼装的 "图片N:[caption]" 语境前缀 → originalQuery 含附件语境
        OverAllState state = new OverAllState(
                Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("图片1:[红色图表] 高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("s1")
                .addMetadata("attachmentContext", attachmentContext)
                .addMetadata("userId", "u1")
                .build();

        // 系统检索（与既有用例一致）
        KnowledgeChunk k =
                new KnowledgeChunk("c1", "内容", "", "讲义", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64));
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of(k)));
        when(contextBuilderService.buildDocument(anyString(), any(), any()))
                .thenReturn("<document>\n检索说明:...\n</system-document>\n</document>");
        // 局部检索链：embed(原问题) → search → buildUserDocument → appendUserDocument
        when(embeddingModel.embed(anyString())).thenReturn(new float[] {1.0f, 0.0f});
        when(localSearchService.search(anyList(), any(), anyInt()))
                .thenReturn(List.of(new DocumentLocalChunk("红色代表本季度", new float[] {2.0f}, 1)));
        when(contextBuilderService.buildUserDocument(anyList(), anyMap()))
                .thenReturn("<user-document>\n  [图片1:红色图表]\n  [文件1] 0/doc.pdf：\n    - 红色代表本季度\n</user-document>");
        String merged =
                "<document>\n检索说明:...\n</system-document>\n<user-document>\n  [图片1:红色图表]\n  [文件1] 0/doc.pdf：\n    - 红色代表本季度\n</user-document>\n</document>";
        when(contextBuilderService.appendUserDocument(anyString(), anyString())).thenReturn(merged);

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty(), "检索结果不写 state");
        // 局部检索以用户原问题（含 caption 语境前缀）为查询向量
        verify(embeddingModel).embed("图片1:[红色图表] 高等数学怎么学");
        // 文档附件局部检索被调（Top-K 3）
        verify(localSearchService).search(anyList(), any(), anyInt());
        // buildUserDocument 收到 caption 与文档命中（objectKey "0/doc.pdf" 在 docHits 中）
        verify(contextBuilderService)
                .buildUserDocument(
                        argThat(captions -> captions != null
                                && captions.size() == 1
                                && "图片1:红色图表".equals(captions.get(0).caption())),
                        argThat(hits -> hits.containsKey("0/doc.pdf")));
        // appendUserDocument 合并结果写入 document_context
        verify(contextBuilderService).appendUserDocument(anyString(), anyString());
        assertEquals(merged, config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT));
        assertTrue(String.valueOf(config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT))
                .contains("<user-document>"));
    }

    @Test
    @DisplayName("recallEpisodic — knowledge_question + recall_history=false：recall 命中写入 episodic_context")
    void recallsEpisodic_whenKnowledgeQuestion() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("高等数学 学习方法"), new QueryPlanFilters(List.of("高等数学")), false);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .threadId("s1")
                .addMetadata(PreferenceInterceptor.KEY_USER_ID, "42")
                .build();

        when(courseNameMapper.mapCourseNames(List.of("高等数学"))).thenReturn(List.of("101"));
        KnowledgeChunk k =
                new KnowledgeChunk("c1", "内容", "", "讲义", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64));
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of(k)));
        when(contextBuilderService.buildDocument(anyString(), any(), any())).thenReturn("<document>D</document>");
        EpisodicMemoryRef ref = new EpisodicMemoryRef(1L, "resolved_question", "上次用夹逼定理求过极限", "夹逼定理", "active", 0.9);
        when(episodicMemoryService.recall(42L, "高等数学 学习方法", false, 5)).thenReturn(List.of(ref));

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty());
        // recall_history=false → recall 被调用（active validity 过滤，spec §8.7），查询取重写查询首条
        verify(episodicMemoryService).recall(42L, "高等数学 学习方法", false, 5);
        // 命中 → episodic_context 写入 metadata（与 document 同通道，不落 state）
        assertEquals(List.of(ref), config.metadata().get().get(EpisodicInterceptor.KEY_EPISODIC_CONTEXT));
    }

    @Test
    @DisplayName("recallEpisodic — recall_history=true：全量召回（不带 validity 过滤，历史记忆可命中）")
    void recallsEpisodic_fullHistory_whenRecallHistoryTrue() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("之前说的红黑树旋转"), new QueryPlanFilters(List.of()), true);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("我之前说的红黑树是什么"))));
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(PreferenceInterceptor.KEY_USER_ID, "42")
                .build();
        // 系统检索为空：验证 recall 独立于 document 分支执行（公共段先于检索空/非空两分支）
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of()));
        EpisodicMemoryRef ref = new EpisodicMemoryRef(2L, "learning_progress", "之前学到红黑树旋转", "红黑树", "superseded", 0.8);
        when(episodicMemoryService.recall(42L, "之前说的红黑树旋转", true, 5)).thenReturn(List.of(ref));

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty());
        // recall_history=true → 全量召回（无 active 过滤，superseded 历史记忆也可命中）
        verify(episodicMemoryService).recall(42L, "之前说的红黑树旋转", true, 5);
        assertEquals(List.of(ref), config.metadata().get().get(EpisodicInterceptor.KEY_EPISODIC_CONTEXT));
    }

    @Test
    @DisplayName("recallEpisodic — chat 意图：不触发经历记忆召回")
    void skipsEpisodic_whenChatIntent() throws Exception {
        QueryPlan plan = new QueryPlan(IntentType.CHAT, List.of("你好"), new QueryPlanFilters(List.of()), false);
        OverAllState state = new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("你好"))));
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(PreferenceInterceptor.KEY_USER_ID, "42")
                .build();

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty());
        // 非 knowledge_question 分支不召回（spec §8.7 仅知识问题触发）
        verify(episodicMemoryService, never()).recall(anyLong(), anyString(), anyBoolean(), anyInt());
    }

    @Test
    @DisplayName("recallEpisodic — metadata 无 userId：全链路硬隔离，跳过召回、不中断主流程")
    void skipsEpisodic_whenNoUserId() throws Exception {
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("极限怎么求"), new QueryPlanFilters(List.of()), false);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("极限怎么求"))));
        // metadata 不含 userId（不 addMetadata）→ spec §10-6 硬隔离，跳过召回
        RunnableConfig config = RunnableConfig.builder().threadId("s1").build();
        when(searchKnowledgeTool.searchKnowledge(any())).thenReturn(new KnowledgeSearchResult(List.of()));

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty());
        // 无 userId → 不调用 recall；主文档检索照常执行（不中断）
        verify(episodicMemoryService, never()).recall(anyLong(), anyString(), anyBoolean(), anyInt());
        verify(searchKnowledgeTool).searchKnowledge(any());
    }

    @Test
    @DisplayName("recallEpisodic — 召回异常：降级不写 metadata，主流程不中断（document 仍注入）")
    void degrades_whenRecallFails() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("高等数学 学习方法"), new QueryPlanFilters(List.of()), false);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(PreferenceInterceptor.KEY_USER_ID, "42")
                .build();

        when(searchKnowledgeTool.searchKnowledge(any()))
                .thenReturn(new KnowledgeSearchResult(List.of(new KnowledgeChunk(
                        "c1", "内容", "", "讲义", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64)))));
        when(contextBuilderService.buildDocument(anyString(), any(), any())).thenReturn("<document>D</document>");
        // 召回服务异常 → 降级（仅记日志），主文档检索与回答不中断
        when(episodicMemoryService.recall(42L, "高等数学 学习方法", false, 5)).thenThrow(new RuntimeException("召回服务异常"));

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        // 主流程不中断：异常未向上抛，document 照常注入
        assertTrue(result.isEmpty());
        assertEquals(
                "<document>D</document>",
                config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT));
        // 降级不写 episodic_context（EpisodicInterceptor 原样透传）
        assertTrue(config.metadata().get().get(EpisodicInterceptor.KEY_EPISODIC_CONTEXT) == null);
    }

    @Test
    @DisplayName("recallEpisodic — 无命中：不写 episodic_context，document 正常注入")
    void skipsEpisodic_noHits() throws Exception {
        QueryPlan plan = new QueryPlan(
                IntentType.KNOWLEDGE_QUESTION, List.of("高等数学 学习方法"), new QueryPlanFilters(List.of()), false);
        OverAllState state =
                new OverAllState(Map.of(KEY_QUERY_PLAN, plan, "messages", List.of(new UserMessage("高等数学怎么学"))));
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(PreferenceInterceptor.KEY_USER_ID, "42")
                .build();

        when(searchKnowledgeTool.searchKnowledge(any()))
                .thenReturn(new KnowledgeSearchResult(List.of(new KnowledgeChunk(
                        "c1", "内容", "", "讲义", "第一章", 0.9, IntentType.KNOWLEDGE_QUESTION, "h".repeat(64)))));
        when(contextBuilderService.buildDocument(anyString(), any(), any())).thenReturn("<document>D</document>");
        // 无命中 → 空列表
        when(episodicMemoryService.recall(42L, "高等数学 学习方法", false, 5)).thenReturn(List.of());

        Map<String, Object> result = RetrieveNodeTestUtil.apply(newRetrieveNode(), state, config);

        assertTrue(result.isEmpty());
        // 主流程不受影响：document 正常注入
        assertEquals(
                "<document>D</document>",
                config.metadata().get().get(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT));
        // 无命中 → 不写 episodic_context（EpisodicInterceptor 原样透传）
        assertTrue(config.metadata().get().get(EpisodicInterceptor.KEY_EPISODIC_CONTEXT) == null);
    }
}
