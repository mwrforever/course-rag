package com.commerce.rag.bot.graph;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.commerce.rag.bot.hook.CoalescingInterceptor;
import com.commerce.rag.bot.hook.CustomSummarizationHook;
import com.commerce.rag.bot.hook.DocumentAssemblerInterceptor;
import com.commerce.rag.bot.hook.EpisodicInterceptor;
import com.commerce.rag.bot.hook.PreferenceInterceptor;
import com.commerce.rag.bot.hook.ReminderHook;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.rewrite.QueryUnderstandingService;
import com.commerce.rag.bot.tool.CourseApiTool;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * F#1 Agent 图编排器 —— 图构建业务组件
 *
 * <p>工程宪法「配置与注册规范」：业务逻辑类不迁入 config/，
 * 本类以 {@code @Component} 注册，图实例（CompiledGraph）的 Bean 注册
 * 统一由 {@link com.commerce.rag.config.GraphConfig#leadAgent} 完成。
 *
 * <p><b>编排拓扑（S1 三节点图）：</b>
 * <pre>
 * START → queryUnderstandingNode →(条件边)
 *         ├─ knowledge_question → retrieveNode → ReactAgent → END
 *         └─ chat / unknown → ReactAgent → END
 * </pre>
 *
 * <p>查询理解节点单次 LLM 调用签出 QueryPlan（intent + 重写查询 + filters + recall_history）
 * 写入 State.KEY_QUERY_PLAN；条件边按 intent.code() 小写规范名路由（chat/unknown 同路不检索，
 * spec §1）；retrieveNode 仅 knowledge_question 分支触发，检索结果经 metadata 瞬时注入
 * （DocumentAssemblerInterceptor 消费，不落 state/checkpoint）。
 *
 * <p><b>关键参数：</b>
 * <ul>
 *   <li>ReactAgent.asNode(true, false) — includeContents=true, returnReasoningContents=false</li>
 *   <li>outputKey="agent_output" — Builder 独立字段，非 asNode 第三参（⚠️ 漂移看板 D1）</li>
 *   <li>ModelCallLimitHook(runLimit=15) — 框架无 maxIterations 常量（⚠️ 漂移看板 D2）</li>
 * </ul>
 *
 * <p><b>Hook 注册顺序（重要）：</b>
 * AFTER_MODEL 逆序执行 —— 注册越晚越先跑。
 * 推荐顺序：warningHook(最后注册, AFTER_MODEL 最先执行)
 *         → reminderHook → customSummarizationHook(最先注册, AFTER_MODEL 最后执行)
 *
 * <p><b>CompiledGraph 编译一次复用：</b>
 * 不同请求通过 RunnableConfig.threadId 隔离状态。
 * session_id（BIGINT 雪花）直接作为 thread_id（toString()）。
 *
 * @author commerce-rag
 * @see com.commerce.rag.bot.IntentType
 */
@Component
public class LeadAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(LeadAgentGraph.class);

    /** 图节点名：查询理解（Query Understanding，intent + 重写 + filters 单次签出） */
    private static final String NODE_QUERY_UNDERSTANDING = "queryUnderstandingNode";

    /** 图节点名：检索编排（仅 knowledge_question 分支触发） */
    private static final String NODE_RETRIEVE = "retrieveNode";

    /** 图节点名：ReactAgent */
    private static final String NODE_REACT_AGENT = "reactAgent";

    /** ReactAgent outputKey */
    private static final String OUTPUT_KEY = "agent_output";

    /** 条件边结果 → 下一节点映射（spec §1：chat/unknown 同路不检索） */
    private static final Map<String, String> INTENT_ROUTES = Map.of(
            "knowledge_question", NODE_RETRIEVE,
            "chat", NODE_REACT_AGENT,
            "unknown", NODE_REACT_AGENT);

    private final ChatModel chatModel;
    private final PromptLoader promptLoader;
    private final QueryUnderstandingService queryUnderstandingService;
    private final RetrieveNode retrieveNode;
    private final CourseApiTool courseApiTool;
    private final CustomSummarizationHook customSummarizationHook;
    private final CoalescingInterceptor coalescingInterceptor;
    private final DocumentAssemblerInterceptor documentAssemblerInterceptor;
    private final EpisodicInterceptor episodicInterceptor;
    private final PreferenceInterceptor preferenceInterceptor;
    private final ReminderHook reminderHook;
    private final WarningHook warningHook;
    private final KeyStrategyFactory keyStrategyFactory;
    private final CompileConfig compileConfig;
    private final int runLimit;

    public LeadAgentGraph(
            ChatModel chatModel,
            PromptLoader promptLoader,
            QueryUnderstandingService queryUnderstandingService,
            RetrieveNode retrieveNode,
            CourseApiTool courseApiTool,
            CustomSummarizationHook customSummarizationHook,
            CoalescingInterceptor coalescingInterceptor,
            DocumentAssemblerInterceptor documentAssemblerInterceptor,
            EpisodicInterceptor episodicInterceptor,
            PreferenceInterceptor preferenceInterceptor,
            ReminderHook reminderHook,
            WarningHook warningHook,
            KeyStrategyFactory keyStrategyFactory,
            CompileConfig compileConfig,
            @Value("${rag.agent.run-limit:15}") int runLimit) {
        this.chatModel = chatModel;
        this.promptLoader = promptLoader;
        this.queryUnderstandingService = queryUnderstandingService;
        this.retrieveNode = retrieveNode;
        this.courseApiTool = courseApiTool;
        this.customSummarizationHook = customSummarizationHook;
        this.coalescingInterceptor = coalescingInterceptor;
        this.documentAssemblerInterceptor = documentAssemblerInterceptor;
        this.episodicInterceptor = episodicInterceptor;
        this.preferenceInterceptor = preferenceInterceptor;
        this.reminderHook = reminderHook;
        this.warningHook = warningHook;
        this.keyStrategyFactory = keyStrategyFactory;
        this.compileConfig = compileConfig;
        this.runLimit = runLimit;
    }

    /**
     * 构建并编译 Agent 图 —— 单例，所有请求复用（注册由 config/GraphConfig 完成）
     */
    public CompiledGraph build() throws Exception {
        // 1. 创建 StateGraph
        StateGraph stateGraph = new StateGraph(keyStrategyFactory);

        // 2. 添加查询理解节点
        stateGraph.addNode(NODE_QUERY_UNDERSTANDING, buildQueryUnderstandingNode());

        // 3. 添加检索编排节点（仅 knowledge_question 分支触发）
        stateGraph.addNode(NODE_RETRIEVE, retrieveNode);

        // 4. 构建 ReactAgent 子图
        ReactAgent reactAgent = buildReactAgent();

        // 5. 添加 ReactAgent 为子图节点
        stateGraph.addNode(NODE_REACT_AGENT, reactAgent.asNode(true, false));

        // 6. 接线: START → queryUnderstandingNode →(条件边)→ retrieveNode/ReactAgent → END
        stateGraph.addEdge(StateGraph.START, NODE_QUERY_UNDERSTANDING);
        stateGraph.addConditionalEdges(NODE_QUERY_UNDERSTANDING, buildIntentRouter(), INTENT_ROUTES);
        stateGraph.addEdge(NODE_RETRIEVE, NODE_REACT_AGENT);
        stateGraph.addEdge(NODE_REACT_AGENT, StateGraph.END);

        // 7. 编译
        CompiledGraph compiled = stateGraph.compile(compileConfig);
        log.info("LeadAgentGraph 编译完成: nodes={}, hooks={}, interceptors={}, runLimit={}", 3, 4, 4, runLimit);
        return compiled;
    }

    /**
     * 构建查询理解节点 —— 调用 QueryUnderstandingService 单次签出 QueryPlan，写入 State
     *
     * <p>AsyncNodeActionWithConfig 签名：
     * {@code CompletableFuture<Map<String, Object>> apply(OverAllState, RunnableConfig)}
     *
     * <p>userQuery 为 null/blank 时 understand 自身降级 fallback，节点不提前短路——
     * 保证 queryPlan 恒写入 state，条件边有值可路由。
     */
    private AsyncNodeActionWithConfig buildQueryUnderstandingNode() {
        return (overAllState, config) -> {
            // 1. 从 State 读取 messages
            @SuppressWarnings("unchecked")
            List<Message> messages =
                    (List<Message>) overAllState.value("messages").orElse(List.of());

            // 2. 提取当前用户消息
            String userQuery = extractLastUserQuery(messages);

            // 3. 调用 QueryUnderstandingService（含降级：失败 → unknown + 原始查询，不拒答）
            QueryPlan plan = queryUnderstandingService.understand(userQuery, messages);

            // 4. 返回增量更新 Map（只写入 queryPlan，不返回完整 state）
            log.info(
                    "queryUnderstandingNode 完成: intent={}, 重写={}条, filters={}, recall_history={}",
                    plan.intent().name(),
                    plan.rewrittenQueries().size(),
                    plan.filters().courseNames(),
                    plan.recallHistory());
            return CompletableFuture.completedFuture(Map.of(KEY_QUERY_PLAN, plan));
        };
    }

    /**
     * 意图路由条件边 —— 读取 QueryPlan.intent 决定下一节点（spec §1）
     *
     * <p>返回值为 INTENT_ROUTES 的 key（intent.code() 小写规范名）；queryPlan 缺失时兜底 "unknown"（不 NPE）。
     *
     * <p>包内可见（package-private）供单元测试直调（LeadAgentGraphTest.intentRouter_routesByIntent）。
     */
    AsyncEdgeActionWithConfig buildIntentRouter() {
        return (overAllState, config) -> {
            Optional<Object> planOpt = overAllState.value(KEY_QUERY_PLAN);
            if (planOpt.isPresent() && planOpt.get() instanceof QueryPlan qp) {
                return CompletableFuture.completedFuture(qp.intent().code());
            }
            return CompletableFuture.completedFuture("unknown");
        };
    }

    /**
     * 构建 ReactAgent —— 配备工具 + 4 个 Hook + 4 个 Interceptor + systemPrompt
     */
    private ReactAgent buildReactAgent() {
        // 加载 systemPrompt（静态通道）—— loadRaw 直接返回 YAML 叶子值原始文本，不加 key 前缀
        String systemPrompt = promptLoader.loadRaw("system-base.yml");

        // 加载 agent instruction（作为 UserMessage prepend）—— 同上
        String instruction = promptLoader.loadRaw("agent-instruction.yml");

        // ModelCallLimitHook(runLimit=15) — 使用 Builder 模式
        ModelCallLimitHook limitHook =
                ModelCallLimitHook.builder().runLimit(runLimit).build();

        return ReactAgent.builder()
                // name 必须与 addNode 的节点 id 一致（SAA asNode 以 agent name 作为 node id）
                .name(NODE_REACT_AGENT)
                .description("在线教育平台 AI 学习助手，提供课程信息查询和技术问答支持")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .instruction(instruction)
                // 工具注册：仅课程结构化信息工具（系统检索已由 retrieveNode 编排，不再注册检索工具）
                .methodTools(courseApiTool)
                // 输出 Key
                .outputKey(OUTPUT_KEY)
                .outputKeyStrategy(new ReplaceStrategy())
                // Hook 注册（注意顺序：AFTER_MODEL 逆序执行）
                .hooks(
                        customSummarizationHook, // 最先注册 → AFTER_MODEL 最后执行
                        reminderHook, // 中间
                        warningHook, // 后面注册 → AFTER_MODEL 先执行
                        limitHook // 最后注册 → AFTER_MODEL 最先执行
                        )
                // Interceptor 注册（顺序无冲突：coalescing 合并请求、document/episodic 末尾注入、preference 前置注入）
                .interceptors(
                        coalescingInterceptor, documentAssemblerInterceptor, preferenceInterceptor, episodicInterceptor)
                .includeContents(true)
                .returnReasoningContents(false)
                .build();
    }

    /**
     * 从 messages 列表中提取最后一条用户消息的文本
     */
    private String extractLastUserQuery(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof UserMessage && m.getText() != null && !m.getText().isBlank()) {
                return m.getText();
            }
        }
        return null;
    }
}
