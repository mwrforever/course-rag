package com.commerce.rag.bot.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.commerce.rag.bot.hook.CoalescingInterceptor;
import com.commerce.rag.bot.hook.CustomSummarizationHook;
import com.commerce.rag.bot.hook.ReminderHook;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryRewriter;
import com.commerce.rag.bot.tool.CourseApiTool;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import com.commerce.rag.config.GraphConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

/**
 * LeadAgentGraph 单元测试 —— Agent 图编译（queryRewriteNode → ReactAgent 接线）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LeadAgentGraph 图编排测试")
class LeadAgentGraphTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private PromptLoader promptLoader;

    @Mock
    private QueryRewriter queryRewriter;

    @Mock
    private SearchKnowledgeTool searchKnowledgeTool;

    @Mock
    private CourseApiTool courseApiTool;

    @Mock
    private CustomSummarizationHook customSummarizationHook;

    @Mock
    private CoalescingInterceptor coalescingInterceptor;

    @Mock
    private ReminderHook reminderHook;

    @Mock
    private WarningHook warningHook;

    @BeforeEach
    void setUp() {
        // ReactAgent.Builder 按 hook 全名去重（Hook.getFullHookName），mock 默认返回 null 会判定重复
        when(customSummarizationHook.getName()).thenReturn("CustomSummarizationHook");
        when(reminderHook.getName()).thenReturn("ReminderHook");
        when(warningHook.getName()).thenReturn("WarningHook");
        // filterHooksByPosition 对 hook.getHookPositions() 做 Arrays.asList 判空，需显式打桩（默认 AFTER_MODEL）
        when(customSummarizationHook.getHookPositions()).thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
        when(reminderHook.getHookPositions()).thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
        when(warningHook.getHookPositions()).thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
    }

    @Test
    @DisplayName("leadAgent → 编译成功返回 START→queryRewriteNode→ReactAgent→END 的图")
    void leadAgent_compilesGraph() throws Exception {
        // 真实构建 key 策略工厂与编译配置（不依赖外部连接）
        KeyStrategyFactory keyStrategyFactory = new GraphConfig().keyStrategyFactory();
        CompileConfig compileConfig = CompileConfig.builder().build();
        LeadAgentGraph graph = new LeadAgentGraph(
                chatModel,
                promptLoader,
                queryRewriter,
                searchKnowledgeTool,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                reminderHook,
                warningHook,
                keyStrategyFactory,
                compileConfig,
                15);

        CompiledGraph compiled = graph.build();

        assertNotNull(compiled);
    }

    @Test
    @DisplayName("leadAgent → 自定义 runLimit 生效")
    void leadAgent_customRunLimit() throws Exception {
        KeyStrategyFactory keyStrategyFactory = new GraphConfig().keyStrategyFactory();
        CompileConfig compileConfig = CompileConfig.builder().build();
        LeadAgentGraph graph = new LeadAgentGraph(
                chatModel,
                promptLoader,
                queryRewriter,
                searchKnowledgeTool,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                reminderHook,
                warningHook,
                keyStrategyFactory,
                compileConfig,
                5);

        assertNotNull(graph.build());
    }

    // ==================== build() 拓扑与 queryRewriteNode 节点行为 ====================

    /** 构建 LeadAgentGraph 实例（固定 runLimit=15） */
    private LeadAgentGraph newGraph() {
        return new LeadAgentGraph(
                chatModel,
                promptLoader,
                queryRewriter,
                searchKnowledgeTool,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                reminderHook,
                warningHook,
                new GraphConfig().keyStrategyFactory(),
                CompileConfig.builder().build(),
                15);
    }

    /** 编译图并取出 queryRewriteNode 节点动作（CompiledGraph 公开 API，验证拓扑接线） */
    private AsyncNodeActionWithConfig queryRewriteNodeAction() throws Exception {
        CompiledGraph compiled = newGraph().build();
        AsyncNodeActionWithConfig action = compiled.getNodeAction("queryRewriteNode");
        assertNotNull(action, "queryRewriteNode 节点应注册到编译后图中");
        return action;
    }

    /** 以给定 State 直调 queryRewriteNode 节点动作，返回其增量 state 结果 */
    private Map<String, Object> applyRewrite(OverAllState state) throws Exception {
        CompletableFuture<Map<String, Object>> future =
                queryRewriteNodeAction().apply(state, RunnableConfig.builder().build());
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("build → 拓扑含 queryRewriteNode 与 reactAgent 两个节点")
    void build_registersBothNodes() throws Exception {
        CompiledGraph compiled = newGraph().build();

        // 两个业务节点均已注册，接线 START → queryRewriteNode → reactAgent → END
        assertNotNull(compiled.getNodeAction("queryRewriteNode"), "应注册 queryRewriteNode 节点");
        assertNotNull(compiled.getNodeAction("reactAgent"), "应注册 reactAgent 节点");
    }

    @Test
    @DisplayName("queryRewriteNode → 正常重写：最后一条用户消息被重写并写入 rewrittenQueries")
    void queryRewriteNode_normalUserMessage_writesRewrittenQueries() throws Exception {
        // 重写器返回 3 条覆盖性查询（不超过上限）
        when(queryRewriter.rewrite("Java 课程怎么学")).thenReturn(List.of("q1", "q2", "q3"));

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("Java 课程怎么学"))));
        Map<String, Object> result = applyRewrite(state);

        // 增量 state 写入 rewrittenQueries，条数与重写器返回一致
        assertEquals(List.of("q1", "q2", "q3"), result.get("rewrittenQueries"));
    }

    @Test
    @DisplayName("queryRewriteNode → 重写结果超过 3 条时截断到 3 条")
    void queryRewriteNode_exceedLimit_truncatesTo3() throws Exception {
        // 重写器返回 5 条，超出 REWRITE_COUNT=3 上限
        when(queryRewriter.rewrite("多个主题问题")).thenReturn(List.of("q1", "q2", "q3", "q4", "q5"));

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("多个主题问题"))));
        Map<String, Object> result = applyRewrite(state);

        // 只保留前 3 条
        assertEquals(3, ((List<?>) result.get("rewrittenQueries")).size());
    }

    @Test
    @DisplayName("queryRewriteNode → 无任何消息时跳过重写，返回空增量")
    void queryRewriteNode_noMessages_skipsRewrite() throws Exception {
        OverAllState emptyState = new OverAllState(Map.of());
        Map<String, Object> result = applyRewrite(emptyState);

        // 无用户消息 → 不调用重写器，无增量输出
        assertTrue(result.isEmpty());
        verify(queryRewriter, never()).rewrite(anyString());
    }

    @Test
    @DisplayName("queryRewriteNode → 用户消息为空白时跳过重写")
    void queryRewriteNode_blankUserMessage_skipsRewrite() throws Exception {
        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("   "))));
        Map<String, Object> result = applyRewrite(state);

        assertTrue(result.isEmpty());
        verify(queryRewriter, never()).rewrite(anyString());
    }

    @Test
    @DisplayName("queryRewriteNode → 仅非用户消息时跳过重写")
    void queryRewriteNode_nonUserMessageOnly_skipsRewrite() throws Exception {
        OverAllState state = new OverAllState(Map.of("messages", List.of(new AssistantMessage("模型回答"))));
        Map<String, Object> result = applyRewrite(state);

        // 倒序查找不到 UserMessage → 跳过重写
        assertTrue(result.isEmpty());
        verify(queryRewriter, never()).rewrite(anyString());
    }

    @Test
    @DisplayName("queryRewriteNode → 从消息末尾倒序提取最后一条用户消息")
    void queryRewriteNode_earlierUserMessage_extractedFromEnd() throws Exception {
        // 末尾是 AssistantMessage，之前是用户消息 —— 倒序遍历应取到用户消息
        when(queryRewriter.rewrite("课程价格")).thenReturn(List.of("价格查询"));

        OverAllState state =
                new OverAllState(Map.of("messages", List.of(new UserMessage("课程价格"), new AssistantMessage("推荐课程"))));
        Map<String, Object> result = applyRewrite(state);

        assertEquals(List.of("价格查询"), result.get("rewrittenQueries"));
        verify(queryRewriter).rewrite("课程价格");
    }

    @Test
    @DisplayName("queryRewriteNode → 超长用户消息在日志中截断展示，不影响重写结果")
    void queryRewriteNode_longQuery_truncatesLogOnly() throws Exception {
        // 40 字符长问题触发 truncate 截断日志展示（仅日志影响，业务结果不变）
        String longQuery = "这是一个超过三十个字符的非常非常非常非常长的课程咨询问题测试用例文本内容";
        when(queryRewriter.rewrite(longQuery)).thenReturn(List.of("截断查询"));

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage(longQuery))));
        Map<String, Object> result = applyRewrite(state);

        assertEquals(List.of("截断查询"), result.get("rewrittenQueries"));
    }
}
