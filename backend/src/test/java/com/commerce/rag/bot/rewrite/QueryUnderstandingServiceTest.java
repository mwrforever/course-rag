package com.commerce.rag.bot.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.QueryUnderstandingProperties;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * QueryUnderstandingService 单元测试 —— 意图判定 + 查询重写（LLM 调用 / JSON 解析 / 降级）
 *
 * <p>构造器内 ChatClient.builder(chatModel) 生成真实 DefaultChatClient，mock ChatModel.call(Prompt)
 * 模拟 LLM 返回；流式重载（带 ThinkingPusher）mock ChatModel.stream(Prompt) 吐
 * reasoning/content 混合 chunk，断言思考实时推送与聚合解析（2026-08-28 时间线改版）；
 * JSON 解析用 QueryPlan.intent 断言验证未知字符串降级 unknown（不拒答）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueryUnderstandingService 查询理解测试")
class QueryUnderstandingServiceTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private PromptLoader promptLoader;

    private QueryUnderstandingService service;

    @BeforeEach
    void setUp() {
        service = new QueryUnderstandingService(
                chatModel,
                promptLoader,
                new ObjectMapper(),
                "qwen3.7-flash",
                3,
                // 默认生产同量级超时；超时降级用例单独构造短超时实例
                new QueryUnderstandingProperties(Duration.ofSeconds(60)));
    }

    private void stubPrompt() {
        when(promptLoader.loadSections("query-understanding.yml"))
                .thenReturn(Map.of(
                        "query-understanding.system", "你是知识查询理解专家。",
                        "query-understanding.instruction", "<context>{context}</context>\n<query>{query}</query>"));
    }

    private void stubReply(String content) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    /** 构造 reasoning chunk（思考阶段：content 空、metadata.reasoningContent 携带片段，DashScope 映射实证形态） */
    private ChatResponse reasoningChunk(String reasoning) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .properties(Map.of("reasoningContent", reasoning))
                .build())));
    }

    /** 构造正文 delta chunk（无 reasoning，getText 即 content 增量） */
    private ChatResponse contentChunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    @DisplayName("understand — 合法 JSON 完整解析出 QueryPlan（intent/重写/filters/recall_history）")
    void understand_validJson_parsesQueryPlan() {
        stubPrompt();
        stubReply("{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"高等数学 课程大纲\"], "
                + "\"filters\": {\"course_names\": [\"高等数学\"]}, \"recall_history\": true}");

        QueryPlan plan = service.understand("高等数学讲什么", List.of(new UserMessage("高等数学讲什么")));

        assertEquals(IntentType.KNOWLEDGE_QUESTION, plan.intent());
        assertEquals(List.of("高等数学 课程大纲"), plan.rewrittenQueries());
        assertEquals(List.of("高等数学"), plan.filters().courseNames());
        assertTrue(plan.recallHistory());
    }

    @Test
    @DisplayName("understand — intent 未知字符串降级 UNKNOWN（不拒答），其余字段保留")
    void understand_unknownIntent_fallbackUnknown() {
        stubPrompt();
        stubReply("{\"intent\": \"course_info\", \"rewrittenQueries\": [\"查询\"], "
                + "\"filters\": {\"course_names\": []}, \"recall_history\": false}");

        QueryPlan plan = service.understand("课程", List.of(new UserMessage("课程")));

        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("查询"), plan.rewrittenQueries());
        assertFalse(plan.recallHistory());
    }

    @Test
    @DisplayName("understand — 重写查询超过上限截断到 maxQueries")
    void understand_exceedMaxQueries_truncates() {
        stubPrompt();
        stubReply("{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"a\", \"b\", \"c\", \"d\"], "
                + "\"filters\": {}, \"recall_history\": false}");

        QueryPlan plan = service.understand("复杂问题", List.of(new UserMessage("复杂问题")));

        assertEquals(3, plan.rewrittenQueries().size());
    }

    @Test
    @DisplayName("understand — filters 缺失时 courseNames 为空列表，recall_history 缺失默认 false")
    void understand_missingFields_useDefaults() {
        stubPrompt();
        stubReply("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}");

        QueryPlan plan = service.understand("你好", List.of(new UserMessage("你好")));

        assertEquals(IntentType.CHAT, plan.intent());
        assertTrue(plan.filters().courseNames().isEmpty());
        assertFalse(plan.recallHistory());
    }

    @Test
    @DisplayName("understand — LLM 返回 markdown 代码块包裹时剥离后解析")
    void understand_markdownWrapped_stripsCodeFence() {
        stubPrompt();
        stubReply("```json\n{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"查询一\"], "
                + "\"filters\": {}, \"recall_history\": false}\n```");

        QueryPlan plan = service.understand("问", List.of(new UserMessage("问")));

        assertEquals(IntentType.KNOWLEDGE_QUESTION, plan.intent());
        assertEquals(List.of("查询一"), plan.rewrittenQueries());
    }

    @Test
    @DisplayName("understand — LLM 异常降级 unknown + 原始查询单条 + 空 filters + recall_history false")
    void understand_modelException_fallbackPlan() {
        stubPrompt();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));

        QueryPlan plan = service.understand("原始问题原文", List.of(new UserMessage("原始问题原文")));

        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("原始问题原文"), plan.rewrittenQueries());
        assertTrue(plan.filters().courseNames().isEmpty());
        assertFalse(plan.recallHistory());
    }

    @Test
    @DisplayName("understand — 空白用户消息直接降级，不调用 LLM")
    void understand_blankQuery_skipLlm() {
        QueryPlan plan = service.understand("   ", List.of(new UserMessage("   ")));

        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("   "), plan.rewrittenQueries());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("understand — ChatClient 经 OpenAiChatOptions 指定独立模型通道（qwen3.7-flash）")
    void understand_modelOption_dashscopeOptions() {
        stubPrompt();
        stubReply("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}");

        service.understand("你好", List.of(new UserMessage("你好")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertTrue(
                captor.getValue().getOptions() instanceof OpenAiChatOptions,
                "Prompt options 应为 OpenAiChatOptions（独立模型通道）");
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) captor.getValue().getOptions()).getModel());
    }

    @Test
    @DisplayName("understand — 渲染后 prompt 注入组装 context 与用户查询原文（占位符 {context}/{query} 被替换）")
    void understand_renderedPrompt_containsContextAndQuery() {
        stubPrompt();
        stubReply("{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"高等数学 课程大纲\"], "
                + "\"filters\": {}, \"recall_history\": false}");
        List<Message> messages = List.of(
                new SystemMessage("## 对话摘要:用户询问了 Redis 缓存配置，已给出排查步骤"),
                new UserMessage("第一轮问题"),
                new AssistantMessage("第一轮回答"),
                new UserMessage("第二轮问题"),
                new UserMessage("高等数学讲什么"));
        service.understand("高等数学讲什么", messages);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        Message renderedUser = captor.getValue()
                .getInstructions()
                .get(captor.getValue().getInstructions().size() - 1);
        String rendered = renderedUser.getText();

        assertTrue(rendered.contains("Redis 缓存配置"), "组装后的会话摘要应注入 prompt");
        assertTrue(rendered.contains("第二轮问题"), "历史轮次文本应注入 prompt");
        assertTrue(rendered.contains("高等数学讲什么"), "用户当前查询原文应注入 prompt");
        assertFalse(rendered.contains("{context}"), "占位符 {context} 应被替换，不得字面残留");
        assertFalse(rendered.contains("{query}"), "占位符 {query} 应被替换，不得字面残留");
        assertTrue(
                captor.getValue().getOptions() instanceof OpenAiChatOptions,
                "Prompt options 应为 OpenAiChatOptions（独立模型通道）");
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) captor.getValue().getOptions()).getModel());
    }

    // ==================== 流式重载（2026-08-28 对话流式时间线改版） ====================

    @Test
    @DisplayName("understand(带 pusher) — reasoning 逐 chunk 推 understanding，首 content chunk 推 end，聚合文本解析 QueryPlan")
    void understand_withPusher_streamsReasoningAndParsesAggregatedPlan() {
        stubPrompt();
        String json = "{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"高等数学 教学大纲\"], "
                + "\"filters\": {\"course_names\": [\"高等数学\"]}, \"recall_history\": true}";
        // JSON 拆两 chunk 下发：验证聚合发生在解析之前（半截 JSON 不单独解析）
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(
                        reasoningChunk("先分析用户意图"),
                        reasoningChunk("命中课程查询"),
                        contentChunk(json.substring(0, 30)),
                        contentChunk(json.substring(30))));
        ThinkingPusher pusher = mock(ThinkingPusher.class);

        QueryPlan plan = service.understand("高等数学讲什么", List.of(new UserMessage("高等数学讲什么")), pusher);

        assertEquals(IntentType.KNOWLEDGE_QUESTION, plan.intent());
        assertEquals(List.of("高等数学 教学大纲"), plan.rewrittenQueries());
        assertEquals(List.of("高等数学"), plan.filters().courseNames());
        assertTrue(plan.recallHistory());
        // 思考片段逐 chunk 实时推送（understanding 阶段）
        verify(pusher).push(SseEventTransformer.STAGE_UNDERSTANDING, "先分析用户意图");
        verify(pusher).push(SseEventTransformer.STAGE_UNDERSTANDING, "命中课程查询");
        // 思考→回答边界：首 content chunk 恰好推一次 end
        verify(pusher, times(1)).end(SseEventTransformer.STAGE_UNDERSTANDING);
        // 流式路径不再走同步 .call()
        verify(chatModel, never()).call(any(Prompt.class));
        // 独立模型通道（qwen3.7-flash）在流式路径同样生效
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) captor.getValue().getOptions()).getModel());
    }

    @Test
    @DisplayName("understand(带 pusher) — 流中断降级 unknown 且补 end 关思考态，异常不向图抛出")
    void understand_withPusher_streamError_fallbackAndClosesThinking() {
        stubPrompt();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.concat(Flux.just(reasoningChunk("思考了一半")), Flux.error(new RuntimeException("流式通道中断"))));
        ThinkingPusher pusher = mock(ThinkingPusher.class);

        QueryPlan plan = service.understand("原始问题", List.of(new UserMessage("原始问题")), pusher);

        // 降级行为与流式化前一致：unknown + 原始查询单条
        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("原始问题"), plan.rewrittenQueries());
        assertFalse(plan.recallHistory());
        verify(pusher).push(SseEventTransformer.STAGE_UNDERSTANDING, "思考了一半");
        // 已推 reasoning 但流未出 content：异常上抛前补一次 end，前端不残留「思考中」
        verify(pusher, times(1)).end(SseEventTransformer.STAGE_UNDERSTANDING);
    }

    @Test
    @DisplayName("understand(带 pusher) — 无 reasoning 的纯 content 流：不推任何思考事件，解析正常")
    void understand_withPusher_noReasoning_pushesNothing() {
        stubPrompt();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(contentChunk("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}")));
        ThinkingPusher pusher = mock(ThinkingPusher.class);

        QueryPlan plan = service.understand("你好", List.of(new UserMessage("你好")), pusher);

        assertEquals(IntentType.CHAT, plan.intent());
        verify(pusher, never()).push(anyString(), anyString());
        verify(pusher, never()).end(anyString());
    }

    @Test
    @DisplayName("understand(带 pusher) — content 解析失败（非 JSON）降级 fallback，不向图抛错")
    void understand_withPusher_unparsableContent_fallback() {
        stubPrompt();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(reasoningChunk("想一下"), contentChunk("这不是 JSON")));
        ThinkingPusher pusher = mock(ThinkingPusher.class);

        QueryPlan plan = service.understand("问题", List.of(new UserMessage("问题")), pusher);

        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("问题"), plan.rewrittenQueries());
    }

    @Test
    @DisplayName("understand(带 pusher) — 流挂死超硬超时降级 fallback 且补 end 关思考态（评审 C1：内层 blockLast 必须有界）")
    void understand_withPusher_streamHangs_timeoutFallback() {
        stubPrompt();
        // reasoning 后流永不终结（模拟模型 hang 在 chunk 间静默）：无界 blockLast 会永久占死 worker 线程
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(reasoningChunk("思考到一半挂住")), Flux.never()));
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 短超时实例：50ms 即触发超时降级，避免测试等待生产量级 60s
        QueryUnderstandingService fastTimeoutService = new QueryUnderstandingService(
                chatModel,
                promptLoader,
                new ObjectMapper(),
                "qwen3.7-flash",
                3,
                new QueryUnderstandingProperties(Duration.ofMillis(50)));

        QueryPlan plan = fastTimeoutService.understand("原始问题", List.of(new UserMessage("原始问题")), pusher);

        // 超时 → IllegalStateException 落入既有降级链：unknown + 原始查询单条，不向图抛错
        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("原始问题"), plan.rewrittenQueries());
        verify(pusher).push(SseEventTransformer.STAGE_UNDERSTANDING, "思考到一半挂住");
        // 超时上抛前补一次 end，前端不残留「思考中」
        verify(pusher, times(1)).end(SseEventTransformer.STAGE_UNDERSTANDING);
    }

    @Test
    @DisplayName("understand(带 pusher) — 纯 reasoning 无 content 流正常结束：兜底补 end 恰好一次，空文本降级 fallback（评审 M2）")
    void understand_withPusher_pureReasoning_normalEnd_closesThinkingOnce() {
        stubPrompt();
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(reasoningChunk("只有思考没有回答")));
        ThinkingPusher pusher = mock(ThinkingPusher.class);

        QueryPlan plan = service.understand("问题", List.of(new UserMessage("问题")), pusher);

        // 聚合 content 为空 → JSON 解析失败降级 fallback（不向图抛错）
        assertEquals(IntentType.UNKNOWN, plan.intent());
        assertEquals(List.of("问题"), plan.rewrittenQueries());
        verify(pusher).push(SseEventTransformer.STAGE_UNDERSTANDING, "只有思考没有回答");
        // 无 content 边界可触发：流正常收尾兜底补 end，且 CAS 保证恰好一次
        verify(pusher, times(1)).end(SseEventTransformer.STAGE_UNDERSTANDING);
    }

    @Test
    @DisplayName("understand(旧两参签名) — 委托 pusher=null 走原同步 .call() 路径，不触发 stream")
    void understand_withoutPusher_usesSyncCall() {
        stubPrompt();
        stubReply("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}");

        QueryPlan plan = service.understand("你好", List.of(new UserMessage("你好")));

        assertEquals(IntentType.CHAT, plan.intent());
        verify(chatModel, never()).stream(any(Prompt.class));
    }

    // ==================== 消息实体化 QU 捕获（2026-08-29，spec §3.2） ====================

    @Test
    @DisplayName("understand(带 sink) — 流式聚合完成点捕获 thinking 全文 + query_plan payload JSON（与 SSE 事件同构）")
    void understand_withSink_capturesThinkingAndPlanPayload() {
        stubPrompt();
        String json = "{\"intent\": \"knowledge_question\", \"rewrittenQueries\": [\"高等数学 大纲\"], "
                + "\"filters\": {\"course_names\": [\"高等数学\"]}, \"recall_history\": false}";
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(reasoningChunk("先分析意图，"), reasoningChunk("再收窄到课程查询"), contentChunk(json)));
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 思考全文经 ThinkingPusher 累加缓冲（与已推送 THINKING 事件逐字一致）
        when(pusher.accumulated()).thenReturn(Map.of(SseEventTransformer.STAGE_UNDERSTANDING, "先分析意图，再收窄到课程查询"));
        AssistantMessageSink sink = new AssistantMessageSink();

        QueryPlan plan = service.understand("高等数学讲什么", List.of(new UserMessage("高等数学讲什么")), pusher, sink);

        assertEquals(IntentType.KNOWLEDGE_QUESTION, plan.intent());
        var captures = sink.snapshot();
        assertEquals(1, captures.size(), "每次 QU 调用恰好捕获一条");
        var capture = captures.get(0);
        assertEquals(SseEventTransformer.STAGE_UNDERSTANDING, capture.stage());
        assertEquals("先分析意图，再收窄到课程查询", capture.reasoning(), "捕获 thinking 全文（与已推送一致）");
        assertEquals(
                "{\"intent\":\"knowledge_question\",\"rewritten\":[\"高等数学 大纲\"],\"filters\":{\"courseNames\":[\"高等数学\"]}}",
                capture.text(),
                "text = query_plan payload JSON（前端 parse 契约 intent/rewritten/filters.courseNames）");
        assertTrue(capture.toolCalls().isEmpty(), "QU 工具调用恒空");
    }

    @Test
    @DisplayName("understand(带 sink) — 流中断降级 fallback：仍捕获已产出的思考与 fallback payload JSON（不丢行）")
    void understand_withSink_streamError_capturesPartialThinkingAndFallbackPayload() {
        stubPrompt();
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(Flux.just(reasoningChunk("思考了一半")), Flux.error(new RuntimeException("中断"))));
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(pusher.accumulated()).thenReturn(Map.of(SseEventTransformer.STAGE_UNDERSTANDING, "思考了一半"));
        AssistantMessageSink sink = new AssistantMessageSink();

        QueryPlan plan = service.understand("原始问题", List.of(new UserMessage("原始问题")), pusher, sink);

        assertEquals(IntentType.UNKNOWN, plan.intent());
        var captures = sink.snapshot();
        assertEquals(1, captures.size(), "降级路径同样捕获（与 SSE QUERY_PLAN 事件非空即推契约一致）");
        assertEquals("思考了一半", captures.get(0).reasoning());
        assertEquals(
                "{\"intent\":\"unknown\",\"rewritten\":[\"原始问题\"],\"filters\":{\"courseNames\":[]}}",
                captures.get(0).text(),
                "降级时 text = fallback payload JSON");
    }

    @Test
    @DisplayName("understand(带 sink) — sink 为 null（非 worker 驱动）时行为与三参版本一致，不捕获不抛错")
    void understand_withoutSink_noCapture() {
        stubPrompt();
        stubReply("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}");

        QueryPlan plan = service.understand("你好", List.of(new UserMessage("你好")), null, null);

        assertEquals(IntentType.CHAT, plan.intent());
        // 无 sink 不得抛错（null 安全），捕获逻辑整体旁路
    }

    @Test
    @DisplayName("understand(带 sink) — 空白用户消息直接降级且捕获 fallback payload（与 state 恒写 QueryPlan 一致）")
    void understand_withSink_blankQuery_capturesFallbackPayload() {
        AssistantMessageSink sink = new AssistantMessageSink();

        QueryPlan plan = service.understand("   ", List.of(new UserMessage("   ")), null, sink);

        assertEquals(IntentType.UNKNOWN, plan.intent());
        var captures = sink.snapshot();
        assertEquals(1, captures.size(), "空白输入也捕获（与现状 query_plan 行语义一致）");
        assertNull(captures.get(0).reasoning(), "未调 LLM 无思考");
        assertTrue(captures.get(0).text() != null && captures.get(0).text().contains("\"intent\":\"unknown\""));
    }

    @Test
    @DisplayName("buildContext — 提取摘要 + 最近三轮（仅 User/Assistant，排除 ToolResponse/System 与当前消息）")
    void buildContext_summaryAndRecentTurns() {
        // 摘要 SM + 4 对历史（8 条 User/Assistant）+ 当前消息 —— 4 对超出最近 3 对窗口，可验证窗口截断
        List<Message> messages = List.of(
                new SystemMessage("## 对话摘要:用户询问了 Redis 缓存配置，已给出排查步骤"),
                new UserMessage("第一轮问题"),
                new AssistantMessage("第一轮回答"),
                new UserMessage("第二轮问题"),
                new AssistantMessage("第二轮回答"),
                new UserMessage("第三轮问题"),
                new AssistantMessage("第三轮回答"),
                new UserMessage("第四轮问题"),
                new AssistantMessage("第四轮回答"),
                new UserMessage("当前问题"));

        String context = service.buildContext(messages);

        assertTrue(context.contains("Redis 缓存配置"), "摘要应进入 context，且前缀已剥离");
        assertTrue(context.contains("第四轮问题"), "最近一轮应进入 context");
        assertTrue(context.contains("第三轮问题"), "最近三轮内的第二轮应进入 context");
        assertTrue(context.contains("第二轮问题"), "窗口=最近6条=第二/三/四轮 3 对，第二轮应进入 context");
        assertFalse(context.contains("第一轮问题"), "超过最近三轮的第三轮之前历史不进入 context");
        assertFalse(context.contains("第一轮回答"), "窗口截断边界：第一轮回答不应进入 context");
        assertFalse(context.contains("当前问题"), "当前消息不进入 context（由 query 占位符承载）");
        assertFalse(context.contains("对话摘要:"), "摘要前缀标记应剥离");
    }
}
