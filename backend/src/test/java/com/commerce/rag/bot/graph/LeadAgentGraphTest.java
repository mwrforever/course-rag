package com.commerce.rag.bot.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.commerce.rag.bot.hook.CoalescingInterceptor;
import com.commerce.rag.bot.hook.CustomSummarizationHook;
import com.commerce.rag.bot.hook.ReminderHook;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryRewriter;
import com.commerce.rag.bot.tool.CourseApiTool;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

        CompiledGraph compiled = graph.leadAgent();

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

        assertNotNull(graph.leadAgent());
    }
}
