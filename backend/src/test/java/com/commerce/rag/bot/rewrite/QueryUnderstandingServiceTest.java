package com.commerce.rag.bot.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * QueryUnderstandingService 单元测试 —— 意图判定 + 查询重写（LLM 调用 / JSON 解析 / 降级）
 *
 * <p>构造器内 ChatClient.builder(chatModel) 生成真实 DefaultChatClient，mock ChatModel.call(Prompt)
 * 模拟 LLM 返回；JSON 解析用 QueryPlan.intent 断言验证未知字符串降级 unknown（不拒答）。
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
        service = new QueryUnderstandingService(chatModel, promptLoader, new ObjectMapper(), "qwen3.7-flash", 3);
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
    @DisplayName("understand — ChatClient 经 DashScopeChatOptions 指定独立模型通道（qwen3.7-flash）")
    void understand_modelOption_dashscopeOptions() {
        stubPrompt();
        stubReply("{\"intent\": \"chat\", \"rewrittenQueries\": [\"你好\"]}");

        service.understand("你好", List.of(new UserMessage("你好")));

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertTrue(
                captor.getValue().getOptions() instanceof DashScopeChatOptions,
                "Prompt options 应为 DashScopeChatOptions（独立模型通道）");
        assertEquals("qwen3.7-flash", ((DashScopeChatOptions) captor.getValue().getOptions()).getModel());
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
