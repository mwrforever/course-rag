package com.commerce.rag.bot.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.commerce.rag.bot.hook.CoalescingInterceptor;
import com.commerce.rag.bot.hook.CustomSummarizationHook;
import com.commerce.rag.bot.hook.ReminderHook;
import com.commerce.rag.bot.hook.WarningHook;
import com.commerce.rag.bot.rewrite.QueryRewriter;
import com.commerce.rag.bot.tool.CourseApiTool;
import com.commerce.rag.bot.tool.SearchKnowledgeTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * F#1 Agent 图编排器 —— Spring @Configuration Builder 类
 *
 * <p><b>编排拓扑：</b>
 * <pre>
 * START → queryRewriteNode → ReactAgent(asNode) → END
 * </pre>
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
@Configuration
public class LeadAgentGraph {

    private static final Logger log = LoggerFactory.getLogger(LeadAgentGraph.class);

    /** 图节点名：查询重写 */
    private static final String NODE_QUERY_REWRITE = "queryRewriteNode";

    /** 图节点名：ReactAgent */
    private static final String NODE_REACT_AGENT = "reactAgent";

    /** ReactAgent outputKey */
    private static final String OUTPUT_KEY = "agent_output";

    /** 查询重写数量 */
    private static final int REWRITE_COUNT = 3;

    private final ChatModel chatModel;
    private final PromptLoader promptLoader;
    private final QueryRewriter queryRewriter;
    private final SearchKnowledgeTool searchKnowledgeTool;
    private final CourseApiTool courseApiTool;
    private final CustomSummarizationHook customSummarizationHook;
    private final CoalescingInterceptor coalescingInterceptor;
    private final ReminderHook reminderHook;
    private final WarningHook warningHook;
    private final KeyStrategyFactory keyStrategyFactory;
    private final CompileConfig compileConfig;
    private final int runLimit;

    public LeadAgentGraph(
            ChatModel chatModel,
            PromptLoader promptLoader,
            QueryRewriter queryRewriter,
            SearchKnowledgeTool searchKnowledgeTool,
            CourseApiTool courseApiTool,
            CustomSummarizationHook customSummarizationHook,
            CoalescingInterceptor coalescingInterceptor,
            ReminderHook reminderHook,
            WarningHook warningHook,
            KeyStrategyFactory keyStrategyFactory,
            CompileConfig compileConfig,
            @Value("${rag.agent.run-limit:15}") int runLimit) {
        this.chatModel = chatModel;
        this.promptLoader = promptLoader;
        this.queryRewriter = queryRewriter;
        this.searchKnowledgeTool = searchKnowledgeTool;
        this.courseApiTool = courseApiTool;
        this.customSummarizationHook = customSummarizationHook;
        this.coalescingInterceptor = coalescingInterceptor;
        this.reminderHook = reminderHook;
        this.warningHook = warningHook;
        this.keyStrategyFactory = keyStrategyFactory;
        this.compileConfig = compileConfig;
        this.runLimit = runLimit;
    }

    /**
     * 编译后的 Agent 图 —— 单例，所有请求复用
     */
    @Bean
    public CompiledGraph leadAgent() throws Exception {
        // 1. 创建 StateGraph
        StateGraph stateGraph = new StateGraph(keyStrategyFactory);

        // 2. 添加查询重写节点
        stateGraph.addNode(NODE_QUERY_REWRITE, buildQueryRewriteNode());

        // 3. 构建 ReactAgent 子图
        ReactAgent reactAgent = buildReactAgent();

        // 4. 添加 ReactAgent 为子图节点
        stateGraph.addNode(NODE_REACT_AGENT, reactAgent.asNode(true, false));

        // 5. 接线: START → queryRewriteNode → ReactAgent → END
        stateGraph.addEdge(StateGraph.START, NODE_QUERY_REWRITE);
        stateGraph.addEdge(NODE_QUERY_REWRITE, NODE_REACT_AGENT);
        stateGraph.addEdge(NODE_REACT_AGENT, StateGraph.END);

        // 6. 编译
        CompiledGraph compiled = stateGraph.compile(compileConfig);
        log.info("LeadAgentGraph 编译完成: nodes={}, hooks={}, interceptors={}, runLimit={}", 3, 4, 1, runLimit);
        return compiled;
    }

    /**
     * 构建查询重写节点 —— 调用 QueryRewriter，将结果写入 State
     *
     * <p>AsyncNodeActionWithConfig 签名：
     * {@code CompletableFuture<Map<String, Object>> apply(OverAllState, RunnableConfig)}
     */
    private AsyncNodeActionWithConfig buildQueryRewriteNode() {
        return (overAllState, config) -> {
            // 1. 从 State 读取最后一条用户消息
            @SuppressWarnings("unchecked")
            List<org.springframework.ai.chat.messages.Message> messages =
                    (List<org.springframework.ai.chat.messages.Message>)
                            overAllState.value("messages").orElse(List.of());

            String userQuery = extractLastUserQuery(messages);
            if (userQuery == null || userQuery.isBlank()) {
                log.debug("queryRewriteNode: 无用户消息，跳过重写");
                return CompletableFuture.completedFuture(Map.of());
            }

            // 2. 调用 QueryRewriter
            List<String> rewritten = queryRewriter.rewrite(userQuery);
            if (rewritten.size() > REWRITE_COUNT) {
                rewritten = rewritten.subList(0, REWRITE_COUNT);
            }

            // 3. 返回增量更新 Map（只写入 rewrittenQueries，不返回完整 state）
            log.info("queryRewriteNode 完成: 原始={} → 重写={}条", truncate(userQuery, 30), rewritten.size());
            return CompletableFuture.completedFuture(Map.of("rewrittenQueries", rewritten));
        };
    }

    /**
     * 构建 ReactAgent —— 配备工具 + 4 个 Hook/Interceptor + systemPrompt
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
                // 工具注册：使用 methodTools 自动包装公开方法
                .methodTools(searchKnowledgeTool, courseApiTool)
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
                // Interceptor 注册
                .interceptors(coalescingInterceptor)
                .includeContents(true)
                .returnReasoningContents(false)
                .build();
    }

    /**
     * 从 messages 列表中提取最后一条用户消息的文本
     */
    private String extractLastUserQuery(List<org.springframework.ai.chat.messages.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            org.springframework.ai.chat.messages.Message m = messages.get(i);
            if (m instanceof org.springframework.ai.chat.messages.UserMessage
                    && m.getText() != null
                    && !m.getText().isBlank()) {
                return m.getText();
            }
        }
        return null;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
