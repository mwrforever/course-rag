package com.commerce.rag.bot.graph;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.hook.CoalescingInterceptor;
import com.commerce.rag.bot.hook.CustomSummarizationHook;
import com.commerce.rag.bot.hook.DocumentAssemblerInterceptor;
import com.commerce.rag.bot.hook.EpisodicInterceptor;
import com.commerce.rag.bot.hook.PreferenceInterceptor;
import com.commerce.rag.bot.hook.ReminderHook;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.rewrite.QueryPlanFilters;
import com.commerce.rag.bot.rewrite.QueryUnderstandingService;
import com.commerce.rag.bot.tool.CourseApiTool;
import com.commerce.rag.config.GraphConfig;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.stream.ThinkingPusher;
import java.lang.reflect.Field;
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
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

/**
 * LeadAgentGraph 单元测试 —— Agent 图编译（queryUnderstandingNode → 条件边 → retrieveNode/ReactAgent 接线）
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
    private QueryUnderstandingService queryUnderstandingService;

    @Mock
    private RetrieveNode retrieveNode;

    @Mock
    private CourseApiTool courseApiTool;

    @Mock
    private CustomSummarizationHook customSummarizationHook;

    @Mock
    private CoalescingInterceptor coalescingInterceptor;

    @Mock
    private DocumentAssemblerInterceptor documentAssemblerInterceptor;

    @Mock
    private EpisodicInterceptor episodicInterceptor;

    @Mock
    private PreferenceInterceptor preferenceInterceptor;

    @Mock
    private ReminderHook reminderHook;

    @Mock
    private WarningHook warningHook;

    @BeforeEach
    void setUp() {
        // ReactAgent.Builder 按 hook 全名去重（Hook.getFullHookName），mock 默认返回 null 会判定重复。
        // 用 lenient 打桩：仅实际 build ReactAgent 的用例消费这些 stub，
        // 意图路由等不 build 的用例不会触发 UnnecessaryStubbingException。
        lenient().when(customSummarizationHook.getName()).thenReturn("CustomSummarizationHook");
        lenient().when(reminderHook.getName()).thenReturn("ReminderHook");
        lenient().when(warningHook.getName()).thenReturn("WarningHook");
        // filterHooksByPosition 对 hook.getHookPositions() 做 Arrays.asList 判空，需显式打桩（默认 AFTER_MODEL）
        lenient()
                .when(customSummarizationHook.getHookPositions())
                .thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
        lenient().when(reminderHook.getHookPositions()).thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
        lenient().when(warningHook.getHookPositions()).thenReturn(new HookPosition[] {HookPosition.AFTER_MODEL});
    }

    @Test
    @DisplayName("leadAgent → 编译成功返回 START→queryUnderstandingNode→条件边→retrieveNode/ReactAgent→END 的图")
    void leadAgent_compilesGraph() throws Exception {
        // 真实构建 key 策略工厂与编译配置（不依赖外部连接）
        KeyStrategyFactory keyStrategyFactory = new GraphConfig().keyStrategyFactory();
        CompileConfig compileConfig = CompileConfig.builder().build();
        LeadAgentGraph graph = new LeadAgentGraph(
                chatModel,
                promptLoader,
                queryUnderstandingService,
                retrieveNode,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                documentAssemblerInterceptor,
                episodicInterceptor,
                preferenceInterceptor,
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
                queryUnderstandingService,
                retrieveNode,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                documentAssemblerInterceptor,
                episodicInterceptor,
                preferenceInterceptor,
                reminderHook,
                warningHook,
                keyStrategyFactory,
                compileConfig,
                5);

        assertNotNull(graph.build());
    }

    // ==================== build() 拓扑与 queryUnderstandingNode 节点行为 ====================

    /** 构建 LeadAgentGraph 实例（固定 runLimit=15） */
    private LeadAgentGraph newGraph() {
        return new LeadAgentGraph(
                chatModel,
                promptLoader,
                queryUnderstandingService,
                retrieveNode,
                courseApiTool,
                customSummarizationHook,
                coalescingInterceptor,
                documentAssemblerInterceptor,
                episodicInterceptor,
                preferenceInterceptor,
                reminderHook,
                warningHook,
                new GraphConfig().keyStrategyFactory(),
                CompileConfig.builder().build(),
                15);
    }

    /** 编译图并取出 queryUnderstandingNode 节点动作（CompiledGraph 公开 API，验证拓扑接线） */
    private AsyncNodeActionWithConfig queryUnderstandingNodeAction() throws Exception {
        CompiledGraph compiled = newGraph().build();
        AsyncNodeActionWithConfig action = compiled.getNodeAction("queryUnderstandingNode");
        assertNotNull(action, "queryUnderstandingNode 节点应注册到编译后图中");
        return action;
    }

    /** 以给定 State 直调 queryUnderstandingNode 节点动作，返回其增量 state 结果 */
    private Map<String, Object> applyUnderstanding(OverAllState state) throws Exception {
        CompletableFuture<Map<String, Object>> future = queryUnderstandingNodeAction()
                .apply(state, RunnableConfig.builder().build());
        return future.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("build → 拓扑含 queryUnderstandingNode / retrieveNode / reactAgent 三节点")
    void build_registersThreeNodes() throws Exception {
        CompiledGraph compiled = newGraph().build();

        // 三个业务节点均已注册，接线 START → queryUnderstandingNode →(条件边)→ retrieveNode/ReactAgent → END
        assertNotNull(compiled.getNodeAction("queryUnderstandingNode"), "应注册 queryUnderstandingNode 节点");
        assertNotNull(compiled.getNodeAction("retrieveNode"), "应注册 retrieveNode 节点");
        assertNotNull(compiled.getNodeAction("reactAgent"), "应注册 reactAgent 节点");
    }

    @Test
    @DisplayName("build → EpisodicInterceptor 已注册进 ReactAgent 拦截器链（4 个，含经历记忆尾部注入）")
    void build_registersEpisodicInterceptor() throws Exception {
        CompiledGraph compiled = newGraph().build();

        List<?> interceptors = reactAgentModelInterceptors(compiled);
        assertEquals(4, interceptors.size(), "ReactAgent 拦截器应为 4 个（coalescing/document/preference/episodic）");
        assertTrue(interceptors.contains(episodicInterceptor), "拦截器链应包含 EpisodicInterceptor");
    }

    /**
     * 反射读取编译图 reactAgent 节点内 ReactAgent 的 modelInterceptors 列表
     *
     * <p>SAA 未对拦截器暴露公开 getter（Builder.interceptors() 仅写入 Builder，ReactAgent
     * 构造器再复制到自身 modelInterceptors 字段），编译图亦无法直接查询；getNodeAction 返回
     * node_async 包装的 lambda（内部捕获 AgentToSubCompiledGraphNodeAdapter，其 this$0 持
     * ReactAgent），此处深度受限递归查找持有 modelInterceptors 字段的对象，注册序验证专用。
     */
    @SuppressWarnings("unchecked")
    private List<?> reactAgentModelInterceptors(CompiledGraph compiled) throws Exception {
        Object holder = findFieldHolder(compiled.getNodeAction("reactAgent"), "modelInterceptors", 0);
        assertNotNull(holder, "reactAgent 节点内应存在持有 modelInterceptors 的 ReactAgent 实例");
        Field interceptorsField = holder.getClass().getDeclaredField("modelInterceptors");
        interceptorsField.setAccessible(true);
        return (List<?>) interceptorsField.get(holder);
    }

    /** 深度受限递归：在对象图中查找声明了指定字段名的对象（跳过高水位容器与 JDK 类防环） */
    private Object findFieldHolder(Object node, String fieldName, int depth) {
        if (node == null
                || depth > 6
                || node instanceof List
                || node instanceof Map
                || node.getClass().getName().startsWith("java.")) {
            return null;
        }
        for (Field f : node.getClass().getDeclaredFields()) {
            if (f.getName().equals(fieldName)) {
                return node;
            }
        }
        for (Field f : node.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object v = f.get(node);
                if (v == null || v == node) {
                    continue;
                }
                Object found = findFieldHolder(v, fieldName, depth + 1);
                if (found != null) {
                    return found;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 不可访问字段 / 模块受限跳过（不影响注册断言）
            }
        }
        return null;
    }

    @Test
    @DisplayName("queryUnderstandingNode → 正常签出：写入 queryPlan 增量 state")
    void queryUnderstandingNode_normalUserMessage_writesQueryPlan() throws Exception {
        // 理解服务返回 knowledge_question 计划（1 条重写查询）
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("q1"), new QueryPlanFilters(List.of()), false);
        // config 未注册 ThinkingPusher → 节点以 pusher=null 调三参重载（维持同步路径语义）
        when(queryUnderstandingService.understand(anyString(), anyList(), isNull(), isNull()))
                .thenReturn(plan);

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("Java 课程怎么学"))));
        Map<String, Object> result = applyUnderstanding(state);

        // 增量 state 写入 queryPlan（KEY_QUERY_PLAN），与理解服务返回一致
        QueryPlan stored = (QueryPlan) result.get(KEY_QUERY_PLAN);
        assertSame(plan, stored);
        assertEquals(IntentType.KNOWLEDGE_QUESTION, stored.intent());
        assertEquals(List.of("q1"), stored.rewrittenQueries());
    }

    @Test
    @DisplayName("queryUnderstandingNode → 无任何消息时仍写入降级 queryPlan（不短路）")
    void queryUnderstandingNode_noMessages_writesFallbackPlan() throws Exception {
        // 无用户消息 → understand 以 null 调用，返回降级计划（unknown + 原始查询）
        QueryPlan fallback = QueryPlan.fallback(null);
        when(queryUnderstandingService.understand(isNull(), anyList(), isNull(), isNull()))
                .thenReturn(fallback);

        Map<String, Object> result = applyUnderstanding(new OverAllState(Map.of()));

        // 恒写入 queryPlan：条件边有值可路由，unknown 分支走 ReactAgent 不拒答
        assertSame(fallback, result.get(KEY_QUERY_PLAN));
        assertEquals(IntentType.UNKNOWN, fallback.intent());
        verify(queryUnderstandingService).understand(isNull(), anyList(), isNull(), isNull());
    }

    @Test
    @DisplayName("queryUnderstandingNode → 用户消息为空白时仍写入降级 queryPlan（不短路）")
    void queryUnderstandingNode_blankUserMessage_writesFallbackPlan() throws Exception {
        // 空白消息在 extractLastUserQuery 中视为无用户消息（text.isBlank() 过滤）→ understand 以 null 调用降级，
        // 节点不做提前短路，queryPlan 恒写入
        QueryPlan fallback = QueryPlan.fallback(null);
        when(queryUnderstandingService.understand(isNull(), anyList(), isNull(), isNull()))
                .thenReturn(fallback);

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("   "))));
        Map<String, Object> result = applyUnderstanding(state);

        assertSame(fallback, result.get(KEY_QUERY_PLAN));
    }

    @Test
    @DisplayName("queryUnderstandingNode → 从消息末尾倒序提取最后一条用户消息")
    void queryUnderstandingNode_earlierUserMessage_extractedFromEnd() throws Exception {
        // 末尾是 AssistantMessage，之前是用户消息 —— 倒序遍历应取到用户消息
        List<Message> messages = List.of(new UserMessage("课程价格"), new AssistantMessage("推荐课程"));
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("价格查询"), new QueryPlanFilters(List.of()), false);
        when(queryUnderstandingService.understand("课程价格", messages, null, null)).thenReturn(plan);

        Map<String, Object> result = applyUnderstanding(new OverAllState(Map.of("messages", messages)));

        assertSame(plan, result.get(KEY_QUERY_PLAN));
        verify(queryUnderstandingService).understand("课程价格", messages, null, null);
    }

    @Test
    @DisplayName(
            "queryUnderstandingNode → RunnableConfig.metadata 注册的 ThinkingPusher/AssistantMessageSink 透传（2026-08-28 改版 + 2026-08-29 实体化）")
    void queryUnderstandingNode_thinkingPusherInMetadata_passedToUnderstand() throws Exception {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        List<Message> messages = List.of(new UserMessage("高数怎么学"));
        QueryPlan plan = new QueryPlan(IntentType.CHAT, List.of("高数怎么学"), new QueryPlanFilters(List.of()), false);
        AssistantMessageSink sink = new AssistantMessageSink();
        when(queryUnderstandingService.understand("高数怎么学", messages, pusher, sink))
                .thenReturn(plan);

        // worker 驱动场景：config.metadata 携带 KEY_THINKING_CALLBACK + KEY_ASSISTANT_SINK →
        // 节点取到同一实例传入 understand（瞬时引用通道，派生副本浅拷贝穿透）
        RunnableConfig config = RunnableConfig.builder()
                .addMetadata(RetrieveNode.KEY_THINKING_CALLBACK, pusher)
                .addMetadata(RetrieveNode.KEY_ASSISTANT_SINK, sink)
                .build();
        CompletableFuture<Map<String, Object>> future =
                queryUnderstandingNodeAction().apply(new OverAllState(Map.of("messages", messages)), config);

        assertSame(plan, future.get(5, TimeUnit.SECONDS).get(KEY_QUERY_PLAN));
        verify(queryUnderstandingService).understand("高数怎么学", messages, pusher, sink);
    }

    @Test
    @DisplayName("意图路由 — knowledge_question → retrieveNode，chat/unknown → reactAgent")
    void intentRouter_routesByIntent() throws Exception {
        LeadAgentGraph graph = newGraph();
        OverAllState kq = new OverAllState(Map.of(
                KEY_QUERY_PLAN,
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("q"), new QueryPlanFilters(List.of()), false)));
        OverAllState chat = new OverAllState(Map.of(
                KEY_QUERY_PLAN, new QueryPlan(IntentType.CHAT, List.of("hi"), new QueryPlanFilters(List.of()), false)));
        OverAllState missing = new OverAllState(Map.of());

        // 路由键为 intent.code() 小写规范名，与 INTENT_ROUTES 映射键一致；queryPlan 缺失兜底 "unknown"
        assertEquals(
                "knowledge_question",
                graph.buildIntentRouter()
                        .apply(kq, RunnableConfig.builder().build())
                        .get(5, TimeUnit.SECONDS));
        assertEquals(
                "chat",
                graph.buildIntentRouter()
                        .apply(chat, RunnableConfig.builder().build())
                        .get(5, TimeUnit.SECONDS));
        assertEquals(
                "unknown",
                graph.buildIntentRouter()
                        .apply(missing, RunnableConfig.builder().build())
                        .get(5, TimeUnit.SECONDS));
    }
}
