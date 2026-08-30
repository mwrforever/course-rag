package com.commerce.rag.bot.rewrite;

import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.QueryUnderstandingProperties;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 查询理解服务 —— 单次 LLM 调用签出完整 QueryPlan（spec §2）
 *
 * <p>职责：
 * <ul>
 *   <li>输入组装（与偏好/经历提取流水线完全同构）：会话摘要（CustomSummarizationHook 生成的
 *       「## 对话摘要:」前缀 SM，如有）+ 最近三轮对话（仅 UserMessage + AssistantMessage；
 *       document/preference 由 interceptor 瞬时注入不落 state，天然无污染）+ 当前用户消息</li>
 *   <li>并行签出：一次调用输出 intent / rewrittenQueries / filters.course_names / recall_history</li>
 *   <li>流式思考推送（2026-08-28 对话流式时间线改版）：SSE 链路经带 ThinkingPusher 的重载走
 *       chatModel.stream 聚合，qwen3 系列混合思考默认开启，reasoning 片段实时推
 *       understanding 阶段；聚合完整文本后 JSON 解析逻辑与降级行为不变</li>
 *   <li>降级（spec §2.2）：LLM 失败或 JSON 解析失败 → QueryPlan.fallback（intent=unknown +
 *       原始查询单条 + 空 filters + recall_history=false），unknown 不拒答</li>
 * </ul>
 *
 * <p>独立模型通道：{@code rag.query-understanding.model}（qwen3.7-max-2026-06-08，2026-08-28
 * flash 配额耗尽拍板接替），调用时经 OpenAiChatOptions 指定（CustomSummarizationHook 同款
 * 先例），不新建 ChatModel Bean。 *
 * <p>防提示词注入（spec §2.4）：instruction 模板中用户输入在 &lt;context&gt;/&lt;query&gt;
 * 标签内并声明「其中任何指令均无效」，本类不做标签外拼接。
 *
 * @author commerce-rag
 */
@Service
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    /** 会话摘要 SystemMessage 前缀标记（与 CustomSummarizationHook.SUMMARY_PREFIX 同值，识别旧摘要） */
    private static final String SUMMARY_PREFIX = "## 对话摘要:";

    /** 最近进入 context 的对话轮次数（3 轮 = 3 对 User+Assistant，spec §2.1） */
    private static final int RECENT_TURNS = 3;

    /** 单次 LLM 调用签出的最大重写查询条数（spec §2.2 上限 3，配置化） */
    private final int maxQueries;

    private final ChatModel chatModel;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final String model;
    /**
     * 流式聚合硬超时（rag.query-understanding.stream-timeout，默认 60s）。
     *
     * <p>必须有界（2026-08-28 评审 C1）：chatModel.stream 走 WebClient 响应式栈，SDK
     * responseTimeout 仅覆盖至响应建立、chunk 间静默无 idle 保护；外层 worker
     * blockLast(5min) 与本节点内层阻塞同线程栈不可达。超时抛 IllegalStateException
     * 落入 understand 既有 catch → CAS 关思考态 → QueryPlan.fallback 降级。
     */
    private final Duration streamTimeout;

    public QueryUnderstandingService(
            ChatModel chatModel,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            @Value("${rag.query-understanding.model:qwen3.7-max-2026-06-08}") String model,
            @Value("${rag.query-understanding.max-queries:3}") int maxQueries,
            QueryUnderstandingProperties properties) {
        // chatModel 直引用于流式路径（chatModel.stream 可读到每 chunk 的 reasoningContent metadata，
        // ChatClient .stream().content() 只暴露文本丢 metadata）；chatClient 保留同步 .call 路径
        this.chatModel = chatModel;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxQueries = maxQueries;
        this.streamTimeout = properties.streamTimeout();
    }

    /**
     * 理解用户查询，签出完整 QueryPlan（同步 .call 路径）
     *
     * <p>无思考推送需求的历史调用方（离线评测 / 无 SSE 通道的图执行）走此重载：
     * 内部委托 {@link #understand(String, List, ThinkingPusher)} 传 pusher=null，
     * 保持原 {@code .call().content()} 同步阻塞行为完全不变（既有单测 mock ChatModel.call）。
     *
     * @param userQuery 当前用户消息原文（含图片 caption 文本，计划 3/5 接入；可空白）
     * @param messages  会话完整消息列表（自 state 读取；摘要 SM 与历史轮次从中提取）
     * @return QueryPlan（失败降级 fallback，never null）
     */
    public QueryPlan understand(String userQuery, List<Message> messages) {
        return understand(userQuery, messages, null);
    }

    /**
     * 理解用户查询并实时推送思考片段，签出完整 QueryPlan
     *
     * <p>核心流程：
     * <ol>
     *   <li>pusher 非空 → 走流式路径 {@link #streamContent}：{@code chatModel.stream(Prompt)}
     *       逐 chunk 聚合，每 chunk 的 reasoningContent 非空即经 pusher 实时推 understanding
     *       阶段思考（qwen3 系列混合思考模式默认开启，reasoning_content 经 OpenAI 兼容
     *       流式 chunk 的 AssistantMessage.metadata['reasoningContent'] 返回，spring-ai-openai
     *       1.1.2 已映射）；首个 content chunk 到达（思考→回答边界）补 pusher.end 退出思考态；
     *       流式在图节点内同步 blockLast 聚合，聚合完整文本后走与同步路径同款 JSON 解析</li>
     *   <li>pusher 为空 → 走原同步 {@code .call().content()} 路径（行为零变化）</li>
     *   <li>解析失败 / LLM 或流异常 → 降级 {@link QueryPlan#fallback}（unknown 不拒答），
     *       降级行为与流式化前完全一致，异常不向图抛出</li>
     * </ol>
     *
     * <p>并发/事务：本方法在 queryUnderstandingNode 节点线程内阻塞聚合（与原 .call() 同步语义等价），
     * 不开事务；思考推送经 ThinkingPusher 内部锁保证 seq 与入队序一致。
     *
     * @param userQuery 当前用户消息原文（含图片 caption 文本；可空白，空白直接降级不调 LLM）
     * @param messages  会话完整消息列表（自 state 读取；摘要 SM 与历史轮次从中提取）
     * @param pusher    per-run 思考推送通道（可为 null——null 时走原同步路径不推思考）
     * @return QueryPlan（失败降级 fallback，never null）
     */
    public QueryPlan understand(String userQuery, List<Message> messages, ThinkingPusher pusher) {
        return understand(userQuery, messages, pusher, null);
    }

    /**
     * 理解用户查询并实时推送思考片段，签出完整 QueryPlan（消息实体化重载，spec §3.2 QU 捕获点）。
     *
     * <p>与三参版本的区别：sink 非空时在流式聚合完成点捕获本次 LLM 调用的完整消息
     * （thinking 全文 + query_plan payload JSON）——LLM 成功/降级（fallback）均捕获：
     * 思考全文取 ThinkingPusher 按阶段累加缓冲（与已推送 THINKING 事件逐字一致），text 取
     * {@code buildQueryPlanPayload(plan)} payload JSON（与 SSE query_plan 事件、现状 query_plan
     * 行同一构造点，前端 parse 契约 intent/rewritten/filters.courseNames 不变）；run 终结时
     * worker 经 sink 落 {@code message_type='assistant'} 实体行。
     *
     * @param userQuery 当前用户消息原文（含图片 caption 文本；可空白，空白直接降级不调 LLM）
     * @param messages  会话完整消息列表（自 state 读取；摘要 SM 与历史轮次从中提取）
     * @param pusher    per-run 思考推送通道（可为 null——null 时走原同步路径不推思考）
     * @param sink      per-run LLM 调用捕获容器（可为 null——null 时行为与三参版本一致，不捕获）
     * @return QueryPlan（失败降级 fallback，never null）
     */
    public QueryPlan understand(
            String userQuery, List<Message> messages, ThinkingPusher pusher, AssistantMessageSink sink) {
        if (userQuery == null || userQuery.isBlank()) {
            log.debug("Query Understanding: 空白用户消息，直接降级");
            // 消息实体化：空白输入同样签出 fallback 计划（与 state 恒写 QueryPlan 一致），
            // 捕获 fallback payload JSON 供实体行落库（与现状 query_plan 行语义一致）
            QueryPlan fallback = QueryPlan.fallback(userQuery);
            captureQuCall(pusher, sink, fallback);
            return fallback;
        }
        QueryPlan result = QueryPlan.fallback(userQuery);
        try {
            Map<String, String> sections = promptLoader.loadSections("query-understanding.yml");
            String system = sections.getOrDefault("query-understanding.system", "");
            String instruction = sections.getOrDefault("query-understanding.instruction", "")
                    .replace("{context}", buildContext(messages))
                    .replace("{query}", userQuery);

            OpenAiChatOptions options = OpenAiChatOptions.builder().model(model).build();
            // LLM 调用可观测性（dev 定位）：输入 instruction 字符数 + 预览截断，输出截断预览（禁打完整响应体）
            log.info(
                    "Query Understanding LLM 输入: system={}字, instruction={}字, 预览={}",
                    system.length(),
                    instruction.length(),
                    truncate(instruction, 120));
            // pusher 非空 → 流式（思考实时推送）；否则维持原 .call() 同步路径（既有单测依赖）
            String content = pusher != null
                    ? streamContent(system, instruction, options, pusher)
                    : callContent(system, instruction, options);

            if (content != null && !content.isBlank()) {
                log.info("Query Understanding LLM 输出: {}", truncate(content, 300));
                QueryPlan plan = parse(content);
                if (plan != null) {
                    result = capQueries(plan);
                    log.info(
                            "Query Understanding 完成: intent={}, 重写={}条, filters={}, recall_history={}",
                            result.intent().name(),
                            result.rewrittenQueries().size(),
                            result.filters().courseNames(),
                            result.recallHistory());
                }
            }
        } catch (Exception e) {
            // 网关异常响应体摘要补打（如 DashScope 欠费 Arrearage——仅有状态码无法定位根因，2026-08-30 实证）
            log.warn("Query Understanding 失败，降级 unknown（不拒答）: {}{}", e.getMessage(), responseBodyOf(e));
        }
        // 消息实体化：流式聚合完成点捕获（spec §3.2 QU）——LLM 成功/降级均捕获已产出的
        // 思考与计划 JSON（失败时 plan=fallback，与 SSE QUERY_PLAN 事件非空即推的契约一致）
        captureQuCall(pusher, sink, result);
        return result;
    }

    /**
     * 捕获 QU 调用完整消息到 sink（thinking 全文 + query_plan payload JSON）。
     *
     * @param pusher 思考推送通道（可为 null——null 时思考全文为 null）
     * @param sink   捕获容器（可为 null——null 时不捕获）
     * @param plan   签出的查询计划（恒非 null：成功结果或 fallback）
     */
    private void captureQuCall(ThinkingPusher pusher, AssistantMessageSink sink, QueryPlan plan) {
        if (sink == null) {
            return;
        }
        // 思考全文 = ThinkingPusher 按阶段累加缓冲（与已推送 THINKING 事件逐字一致）
        String reasoning = pusher == null ? null : pusher.accumulated().get(SseEventTransformer.STAGE_UNDERSTANDING);
        // text = query_plan payload JSON（与 SSE 事件/现状 query_plan 行同一构造点，序列化失败降级 null）
        String planJson;
        try {
            planJson = objectMapper.writeValueAsString(SseEventTransformer.buildQueryPlanPayload(plan));
        } catch (JsonProcessingException e) {
            log.warn("QU 实体捕获 query_plan JSON 序列化失败，text 降级 null: {}", e.getMessage());
            planJson = null;
        }
        sink.capture(SseEventTransformer.STAGE_UNDERSTANDING, reasoning, planJson, List.of());
    }

    /**
     * 同步调用 LLM 返回完整文本（原路径，行为零变化）。
     *
     * @param system     system prompt 文本
     * @param instruction 渲染后的 user instruction 文本
     * @param options    DashScope OpenAiChatOptions（指定 rag.query-understanding.model 通道）
     * @return LLM 完整返回文本（可为 null/空，调用方走降级）
     */
    private String callContent(String system, String instruction, OpenAiChatOptions options) {
        return chatClient
                .prompt()
                .system(system)
                .user(instruction)
                .options(options)
                .call()
                .content();
    }

    /**
     * 流式调用 LLM：逐 chunk 实时推送 reasoning 片段 + 聚合 content 文本，同步阻塞至流结束。
     *
     * <p>设计要点：
     * <ul>
     *   <li>ChatClient stream API 项目内无先例（其余调用点均 .call().content()），按简报约定改用
     *       {@code chatModel.stream(Prompt)} 拿 {@code Flux<ChatResponse>}，方能读到每 chunk 的
     *       AssistantMessage.metadata['reasoningContent']（.stream().content() 只暴露文本丢 metadata）</li>
     *   <li>reasoning 与 content 在 qwen 思考模型流里两阶段互斥：先 reasoning chunk（content 空），
     *       后 content chunk（reasoning 空）；确实推过 reasoning 后，首个 content chunk 即
     *       思考→回答边界，CAS 保证 pusher.end('understanding') 恰好一次（无 reasoning 则不发
     *       孤儿 THINKING_END，保持与 THINKING 成对契约）</li>
     *   <li>聚合文本交回调用方走原有 {@link #parse} JSON 解析逻辑，解析/降级完全不变</li>
     *   <li>流中途异常：blockLast 抛出向上传播由 understand 统一 catch 降级（与原 .call() 异常一致）；
     *       但若已推过 reasoning，则异常上抛前先补 pusher.end 关思考态，避免前端停留「思考中」</li>
     * </ul>
     *
     * @param system      system prompt 文本
     * @param instruction 渲染后的 user instruction 文本
     * @param options     DashScope OpenAiChatOptions
     * @param pusher      per-run 思考推送通道（非空）
     * @return 聚合后的完整 content 文本（不含 reasoning）
     */
    private String streamContent(String system, String instruction, OpenAiChatOptions options, ThinkingPusher pusher) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(instruction)), options);
        StringBuilder contentBuf = new StringBuilder();
        // 思考→回答边界只推一次 end；并记录是否已推过 reasoning（异常兜底关态判断用）
        AtomicBoolean thinkingEnded = new AtomicBoolean(false);
        AtomicBoolean reasoningSeen = new AtomicBoolean(false);
        try {
            chatModel.stream(prompt)
                    .doOnNext(chatResponse -> {
                        Generation generation = chatResponse.getResult();
                        if (generation == null || generation.getOutput() == null) {
                            return;
                        }
                        AssistantMessage message = generation.getOutput();
                        // 1. reasoning 片段实时推送（DashScope 思考内容在 metadata['reasoningContent']）
                        String reasoning = extractReasoningContent(message);
                        if (reasoning != null && !reasoning.isEmpty()) {
                            reasoningSeen.set(true);
                            pusher.push(SseEventTransformer.STAGE_UNDERSTANDING, reasoning);
                        }
                        // 2. content 片段聚合；首个 content chunk = 思考结束边界，补一次 THINKING_END
                        //    （仅在此前确实推过 reasoning 时——THINKING_END 与 THINKING 成对契约，
                        //    非思考模型全程无 reasoning，发孤儿 end 会让前端阶段机收到无配对事件）
                        String text = message.getText();
                        if (text != null && !text.isEmpty()) {
                            if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
                                pusher.end(SseEventTransformer.STAGE_UNDERSTANDING);
                            }
                            contentBuf.append(text);
                        }
                    })
                    // 硬超时自界（评审 C1）：响应式栈 chunk 间静默无 transport idle 保护，
                    // 超时抛 IllegalStateException 由下方 catch 补 end 后上抛，understand 统一降级
                    .blockLast(streamTimeout);
        } catch (RuntimeException e) {
            // 流异常：已推过 reasoning 但尚未推过 end → 关思考态后再上抛，由 understand 统一降级
            if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
                pusher.end(SseEventTransformer.STAGE_UNDERSTANDING);
            }
            throw e;
        }
        // 流正常结束但全程无 content（纯 reasoning / 空响应）：若已推 reasoning 仍须关思考态
        if (reasoningSeen.get() && thinkingEnded.compareAndSet(false, true)) {
            pusher.end(SseEventTransformer.STAGE_UNDERSTANDING);
        }
        return contentBuf.toString();
    }

    /**
     * 从 AssistantMessage.metadata 提取 DashScope reasoningContent（与 SseEventTransformer 同源）。
     *
     * @param message 图流式 chunk 的输出消息
     * @return reasoning 文本，无值/非字符串返回 null
     */
    private String extractReasoningContent(AssistantMessage message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("reasoningContent");
        return value instanceof String s ? s : null;
    }

    /**
     * 组装 context 段 —— 会话摘要（如有）+ 最近三轮（仅 User/Assistant，排除当前用户消息）
     *
     * <p>摘要从 messages 中识别「## 对话摘要:」前缀的 SystemMessage 并剥离前缀；
     * 最近三轮从过滤后的 User/Assistant 序列末尾取不超过 3 对（最后一条 UserMessage
     * 视为当前消息，由 {@code query} 占位符承载，不重复进入 context）。
     *
     * @param messages 会话完整消息列表
     * @return context 文本（摘要段 + 三轮段；无摘要时只有三轮段）
     */
    String buildContext(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages != null) {
            // 1. 摘要段（识别前缀 SM，剥离标记）
            messages.stream()
                    .filter(m -> m instanceof SystemMessage
                            && m.getText() != null
                            && m.getText().startsWith(SUMMARY_PREFIX))
                    .findFirst()
                    .ifPresent(sm -> sb.append("会话摘要:\n")
                            .append(sm.getText()
                                    .substring(SUMMARY_PREFIX.length())
                                    .trim())
                            .append("\n\n"));

            // 2. 最近三轮段：过滤 User/Assistant（排除 ToolResponse/System/document 注入块），
            //    末尾 UserMessage 为当前消息，不进入 context
            List<Message> turns = messages.stream()
                    .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!turns.isEmpty() && turns.get(turns.size() - 1) instanceof UserMessage) {
                turns.remove(turns.size() - 1);
            }
            int start = Math.max(0, turns.size() - RECENT_TURNS * 2);
            sb.append("最近对话:\n");
            for (int i = start; i < turns.size(); i++) {
                Message m = turns.get(i);
                String role = m instanceof UserMessage ? "用户" : "助手";
                sb.append(role).append(": ").append(m.getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 解析 LLM 返回的 QueryPlan JSON（逐字段提取，缺失给默认值）
     *
     * <p>容忍 markdown 代码块包裹；intent 经 IntentType.fromString 宽松映射（未知 → UNKNOWN）。
     *
     * @param content LLM 原始返回
     * @return QueryPlan，解析失败返回 null（调用方走降级）
     */
    QueryPlan parse(String content) {
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode root = objectMapper.readTree(json);
            IntentType intent = IntentType.fromString(root.path("intent").asText());

            List<String> queries = new ArrayList<>();
            JsonNode arr = root.path("rewrittenQueries");
            if (arr.isArray()) {
                arr.forEach(n -> {
                    String q = n.asText();
                    if (q != null && !q.isBlank()) {
                        queries.add(q);
                    }
                });
            }
            if (queries.isEmpty()) {
                return null; // 无重写查询 → 视为解析失败，走降级（原始查询单条）
            }

            List<String> courseNames = new ArrayList<>();
            if (root.path("filters").isObject()) {
                JsonNode names = root.path("filters").path("course_names");
                if (names.isArray()) {
                    names.forEach(n -> {
                        String name = n.asText();
                        if (name != null && !name.isBlank()) {
                            courseNames.add(name);
                        }
                    });
                }
            }

            boolean recallHistory = root.path("recall_history").asBoolean(false);
            return new QueryPlan(intent, queries, new QueryPlanFilters(courseNames), recallHistory);
        } catch (Exception e) {
            log.warn("QueryPlan JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 截断重写查询到 maxQueries 上限 */
    private QueryPlan capQueries(QueryPlan plan) {
        List<String> queries = plan.rewrittenQueries();
        if (queries.size() <= maxQueries) {
            return plan;
        }
        return new QueryPlan(plan.intent(), queries.subList(0, maxQueries), plan.filters(), plan.recallHistory());
    }

    /** 日志文本摘要（超长截断加省略号，dev 定位用，禁止完整响应体入日志） */
    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 提取 LLM 网关异常响应体摘要（WebClientResponseException 携带业务错误详情；非网关异常返回空串） */
    private static String responseBodyOf(Throwable e) {
        if (e instanceof WebClientResponseException wcre) {
            String body = wcre.getResponseBodyAsString();
            if (body == null || body.isBlank()) {
                return "";
            }
            return " 响应体=" + (body.length() <= 300 ? body : body.substring(0, 300) + "...");
        }
        return "";
    }
}
