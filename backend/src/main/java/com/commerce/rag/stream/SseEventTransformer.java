package com.commerce.rag.stream;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.LeadAgentGraph;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;

/**
 * 将 SAA {@link com.alibaba.cloud.ai.graph.CompiledGraph#stream} 输出的
 * {@link NodeOutput} chunk 转换为 {@link SseEvent}。
 *
 * <p>核心映射规则（基于实际 SAA 1.1.2.0 API 实证）：
 * <ul>
 *   <li>{@link OutputType#AGENT_MODEL_STREAMING} → {@link SseEventType#DELTA} 或 {@link SseEventType#THINKING}</li>
 *   <li>{@link OutputType#AGENT_MODEL_FINISHED} → {@link SseEventType#TOOL_CALL}（如有工具调用）+ {@link SseEventType#THINKING_END}（如有思考内容）</li>
 *   <li>{@link OutputType#AGENT_TOOL_FINISHED} → {@link SseEventType#TOOL_RESULT}</li>
 *   <li>其余 OutputType（HOOK / GRAPH_NODE / AGENT_TOOL_STREAMING）→ 忽略</li>
 * </ul>
 *
 * <p>注意：实际 API 中工具调用信息附着在 {@code AGENT_MODEL_FINISHED} 的
 * {@link AssistantMessage#getToolCalls()} 上，而非独立的 AGENT_TOOL_STREAMING 事件。
 * 工具执行结果则通过 {@code AGENT_TOOL_FINISHED} 的 {@link ToolResponseMessage} 承载。
 */
@Service
public class SseEventTransformer {

    /** 工具输出摘要截断长度 */
    private static final int TOOL_OUTPUT_MAX_LENGTH = 200;

    private final ObjectMapper objectMapper;

    public SseEventTransformer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将 SAA 流式输出的单个 chunk 转换为 0~N 个 {@link SseEvent}。
     *
     * <p>返回 List 而非单个 SseEvent，因为一个 chunk 可能包含多个工具调用
     * （如 {@link AssistantMessage#getToolCalls()} 返回多个 ToolCall），
     * 每个都需要独立的 TOOL_CALL 事件。
     *
     * @param chunk     SAA 图流式输出的单个 chunk
     * @param runState  当前 run 上下文（用于递增 seqId）
     * @return 转换后的事件列表，空列表表示该 chunk 不产生事件
     */
    public List<SseEvent> transform(NodeOutput chunk, RunState runState) {
        if (chunk == null || runState == null) {
            return List.of();
        }

        // 非 StreamingOutput 的 NodeOutput 没有OutputType，直接跳过
        if (!(chunk instanceof StreamingOutput<?> streaming)) {
            return List.of();
        }

        OutputType type = streaming.getOutputType();
        if (type == null) {
            return List.of();
        }

        // 以下事件类型前端不需要感知，统一忽略：AGENT_TOOL_STREAMING（工具节点通常
        // 同步执行，不产生流式 delta）、AGENT_HOOK_*（拦截器事件）、GRAPH_NODE_*
        // （图节点级事件）。注释置于 switch 之外：palantir-java-format 对 switch
        // 体内注释的缩进判定随版本漂移（CI 与本地解析到不同 palantir 版本时
        // spotless:check 结果相反，2026-08-24 CI 实证），移出后消除分歧源。
        return switch (type) {
            case AGENT_MODEL_STREAMING -> transformModelStreaming(streaming, runState);
            case AGENT_MODEL_FINISHED -> transformModelFinished(streaming, runState);
            case AGENT_TOOL_FINISHED -> transformToolFinished(streaming, runState);
            default -> List.of();
        };
    }

    // ========================================================================
    // 事件转换：模型流式 delta
    // ========================================================================

    /**
     * 处理 {@link OutputType#AGENT_MODEL_STREAMING}：中间 delta chunk。
     *
     * <p>优先检查 reasoningContent（思考内容），有值则产出 THINKING 事件；
     * 否则取文本 delta 产出 DELTA 事件；带 toolCalls 的 chunk 产出 TOOL_CALL 事件；两者皆空则跳过。
     *
     * <p>P3-1 调研实证（SAA 1.1.2.0 源码 NodeExecutor.transformFluxToGraphResponse）：
     * AGENT_MODEL_FINISHED 事件对 agent model 节点 message 恒为 null
     * （shouldOmitMessageOnStreamCompletion 省略 message），因此 TOOL_CALL 只能在
     * STREAMING 分支提取（模型输出工具调用时最后 chunk 携带完整 toolCalls）。
     */
    private List<SseEvent> transformModelStreaming(StreamingOutput<?> chunk, RunState runState) {
        Message message = chunk.message();
        if (message == null) {
            return List.of();
        }

        // 1. 检查 reasoningContent → thinking 事件（2026-08-28 时间线改版：payload 加 stage
        //    区分思考来源，主 agent 生成路径固定 generating；图节点思考经 ThinkingPusher
        //    回调携带 understanding / attachments 等 stage，前端据此分段归组渲染）
        String reasoning = extractReasoningContent(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            runState.markThinkingSent(); // 标记已发 THINKING，text 阶段补 THINKING_END 的前置条件
            Map<String, Object> thinkingPayload = new LinkedHashMap<>();
            thinkingPayload.put("delta", reasoning);
            thinkingPayload.put("stage", STAGE_GENERATING);
            return List.of(makeEvent(SseEventType.THINKING, runState, thinkingPayload));
        }

        List<SseEvent> events = new ArrayList<>();

        // 2. 取文本 delta → 先补 THINKING_END（若有思考且未发），再发 DELTA
        String text = message.getText();
        if (text != null && !text.isEmpty()) {
            // qwen 思考模型 thinking/text 两阶段互斥：首条 text 即思考结束信号，
            // 必须补发 THINKING_END 再发 DELTA，保证前端退出"思考中"状态
            // （stage=generating 与 THINKING 事件配对，2026-08-28 时间线改版）
            if (runState.isThinkingSent() && runState.markThinkingEndSent()) {
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of("stage", STAGE_GENERATING)));
            }
            runState.markDeltaSent(); // 标记本 run 已发 DELTA，FINISHED 时不再补发
            events.add(makeEvent(SseEventType.DELTA, runState, Map.of("text", text)));
        }

        // 3. 工具调用 → TOOL_CALL 事件（P3-1 实证：FINISHED 分支 message=null 不可用，
        //    toolCalls 只在 STREAMING chunk 携带；按 chunk 去重由前端按 toolCallId 配对）
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : am.getToolCalls()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCall.id());
                payload.put("toolName", toolCall.name());
                payload.put("input", toolCall.arguments());
                events.add(makeEvent(SseEventType.TOOL_CALL, runState, payload));
            }
        }

        return events;
    }

    // ========================================================================
    // 事件转换：模型流结束
    // ========================================================================

    /**
     * 处理 {@link OutputType#AGENT_MODEL_FINISHED}：流结束的累积完整结果。
     *
     * <p>⚠️ P3-1 调研实证（SAA 1.1.2.0 源码）：本分支在真实链路恒空转——NodeExecutor
     * transformFluxToGraphResponse 对 agent model 节点的 FINISHED 事件显式省略 message
     * （shouldOmitMessageOnStreamCompletion 返回 true，message=null），故 message==null 早退。
     * THINKING_END 由 STREAMING 的 text 分支补发、TOOL_CALL 已移到 STREAMING 分支，均不受影响。
     * 本方法保留为防御性（SAA 未来版本若恢复 FINISHED message，以下逻辑仍正确）。
     *
     * <p>设计文档 §3.7：FINISHED 携带累积完整 AssistantMessage。文本内容通常已通过
     * AGENT_MODEL_STREAMING 的 DELTA 事件逐步发送。但若本次 run 未发过任何 DELTA
     * （如模型直接返回完整结果无流式 chunk），则在此补发完整 text（§3.7）。
     *
     * <p>处理：
     * <ol>
     *   <li>若存在 reasoningContent → THINKING_END 事件（CAS 去重，最多补发一次）</li>
     *   <li>若未发过 DELTA 且有累积文本 → 补发 DELTA 事件（完整 text）</li>
     *   <li>若存在 toolCalls → 每个工具调用产出独立 TOOL_CALL 事件</li>
     * </ol>
     */
    private List<SseEvent> transformModelFinished(StreamingOutput<?> chunk, RunState runState) {
        List<SseEvent> events = new ArrayList<>();
        Message message = chunk.message();

        if (message == null) {
            return events;
        }

        // 1. THINKING_END：FINISHED 累积消息仍带 reasoningContent → 补发一次
        //    （流式阶段 thinking→text 已补发过的场景由 CAS 去重，不会重复）
        String reasoning = extractReasoningContent(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            runState.markThinkingSent();
            if (runState.markThinkingEndSent()) {
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of("stage", STAGE_GENERATING)));
            }
        }

        // 2. 补发 DELTA：若本 run 未发过 DELTA（无流式 chunk），FINISHED 时补发完整 text（§3.7）
        if (!runState.isDeltaSent()) {
            String fullText = message.getText();
            if (fullText != null && !fullText.isEmpty()) {
                events.add(makeEvent(SseEventType.DELTA, runState, Map.of("text", fullText)));
                runState.markDeltaSent();
            }
        }

        // 3. TOOL_CALL：提取工具调用列表
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            for (AssistantMessage.ToolCall toolCall : am.getToolCalls()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCall.id());
                payload.put("toolName", toolCall.name());
                payload.put("input", toolCall.arguments());
                events.add(makeEvent(SseEventType.TOOL_CALL, runState, payload));
            }
        }

        return events;
    }

    // ========================================================================
    // 事件转换：工具执行完成
    // ========================================================================

    /**
     * 处理 {@link OutputType#AGENT_TOOL_FINISHED}：工具节点执行完成。
     *
     * <p>消息体为 {@link ToolResponseMessage}，包含一个或多个
     * {@link ToolResponseMessage.ToolResponse}。每个 ToolResponse 对应
     * 一个 TOOL_RESULT 事件。输出摘要截断到 {@value #TOOL_OUTPUT_MAX_LENGTH} 字符。
     */
    private List<SseEvent> transformToolFinished(StreamingOutput<?> chunk, RunState runState) {
        Message message = chunk.message();
        if (!(message instanceof ToolResponseMessage trm)) {
            return List.of();
        }

        List<SseEvent> events = new ArrayList<>();
        for (ToolResponseMessage.ToolResponse response : trm.getResponses()) {
            String output = truncate(response.responseData());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolCallId", response.id());
            payload.put("status", "success");
            payload.put("output", output);
            events.add(makeEvent(SseEventType.TOOL_RESULT, runState, payload));
        }
        return events;
    }

    // ========================================================================
    // metadata 事件工厂（首个事件）
    // ========================================================================

    /**
     * 创建首个 METADATA 事件，告知前端创建消息槽位。
     *
     * <p>payload 包含 runId、sessionId、model 等元信息。
     */
    public SseEvent createMetadataEvent(RunState runState) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runState.runId());
        payload.put("sessionId", runState.sessionId());
        payload.put("model", runState.model());
        return makeEvent(SseEventType.METADATA, runState, payload);
    }

    // ========================================================================
    // 阶段进度事件（STAGE，2026-08-27 C 端体验改版）
    // ========================================================================

    /** STAGE 阶段键：附件解析（worker 在 orchestrator.process 前后手工推送） */
    public static final String STAGE_ATTACHMENTS = "attachments";
    /** STAGE 阶段键：意图理解（worker 在图执行前手工推送，覆盖 QU 阻塞 LLM 阶段） */
    public static final String STAGE_UNDERSTANDING = "understanding";
    /** STAGE 阶段键：知识库检索（QU 完成且 intent=knowledge_question 时由本类推送） */
    public static final String STAGE_RETRIEVING = "retrieving";
    /** STAGE 阶段键：生成回答（检索完成 / chat 意图 QU 完成 / 首个模型 chunk，三者取最先） */
    public static final String STAGE_GENERATING = "generating";

    /** STAGE 阶段中文文案（前端直接展示，stage 键供阶段机逻辑消费） */
    private static final Map<String, String> STAGE_LABELS = Map.of(
            STAGE_ATTACHMENTS,
            "正在解析附件",
            STAGE_UNDERSTANDING,
            "正在理解你的问题",
            STAGE_RETRIEVING,
            "知识库查询中",
            STAGE_GENERATING,
            "正在生成回答");

    /**
     * 构建 STAGE 阶段事件（公开工厂：worker 在附件处理 / 图执行前手工推送阶段边界用）。
     *
     * @param runState run 上下文（seqId 递增）
     * @param stage    阶段键（STAGE_* 常量之一）
     * @return STAGE 事件（payload 含 stage 与 label）
     */
    public SseEvent createStageEvent(RunState runState, String stage) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        payload.put("label", STAGE_LABELS.getOrDefault(stage, stage));
        return makeEvent(SseEventType.STAGE, runState, payload);
    }

    /**
     * 将图流 chunk 映射为 STAGE 阶段事件（2026-08-27 C 端体验改版核心）。
     *
     * <p>机制（基于 SAA 1.1.2.0 实证：非流式节点完成时 NodeExecutor.handleNonStreamingResult
     * 经 {@code Flux.just(GraphResponse.of(nodeOutput))} 向流中发射普通 {@link NodeOutput}，
     * 携带 {@code node()} 与合并后 state）：
     * <ul>
     *   <li>queryUnderstandingNode 完成 chunk：读 state 的 QueryPlan——先产 QUERY_PLAN 事件
     *       （需求解析结果 {intent, rewritten, filters} 对前端即时可见，2026-08-28 改版）；
     *       再按意图分流 knowledge_question → STAGE retrieving（知识库查询中）、
     *       chat/unknown → STAGE generating（直接生成）；plan 缺失（异常降级）两者均不发，
     *       由 reactAgent 首 chunk 兜底</li>
     *   <li>retrieveNode 完成 chunk：检索阶段结束 → STAGE generating（worker 的
     *       maybePushSources 在同 chunk 已推 SOURCES，时序自然形成「来源→生成」）</li>
     *   <li>reactAgent 首个模型流式 chunk：STAGE generating 兜底（QU plan 缺失 /
     *       NodeOutput 事件缺失时不丢「生成中」阶段）</li>
     * </ul>
     *
     * <p>每个阶段（含 QUERY_PLAN）经 RunState CAS 标记只发一次（与 thinkingEndSent 同款去重）。
     *
     * @param chunk    SAA 图流式输出的单个 chunk（可为普通 NodeOutput 或 StreamingOutput）
     * @param runState 当前 run 上下文
     * @return 0~2 个事件（QU 完成 chunk 可含 QUERY_PLAN + STAGE；其余场景同 chunk 至多一个阶段跃迁）
     */
    public List<SseEvent> transformStages(NodeOutput chunk, RunState runState) {
        if (chunk == null || runState == null) {
            return List.of();
        }
        String node = chunk.node();
        // QU 完成：先产 QUERY_PLAN（需求解析结果即时可见，CAS 一次、无 plan 不发），
        // 再按意图分流到检索或直接生成阶段事件（同 chunk 至多一阶段跃迁）
        if (LeadAgentGraph.NODE_QUERY_UNDERSTANDING.equals(node)) {
            List<SseEvent> events = new ArrayList<>();
            QueryPlan plan = extractQueryPlan(chunk);
            if (plan != null && runState.markQueryPlanSent()) {
                events.add(makeEvent(SseEventType.QUERY_PLAN, runState, buildQueryPlanPayload(plan)));
            }
            String nextStage = plan == null
                    ? null
                    : plan.intent() == IntentType.KNOWLEDGE_QUESTION ? STAGE_RETRIEVING : STAGE_GENERATING;
            events.addAll(stageEventIfAbsent(runState, nextStage));
            return events;
        }
        // 检索完成：进入生成阶段
        if (LeadAgentGraph.NODE_RETRIEVE.equals(node)) {
            return stageEventIfAbsent(runState, STAGE_GENERATING);
        }
        // reactAgent 首个模型 chunk：生成阶段兜底（plan 缺失/QU chunk 缺失场景）
        if (chunk instanceof StreamingOutput<?> streaming
                && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING
                && LeadAgentGraph.NODE_REACT_AGENT.equals(node)) {
            return stageEventIfAbsent(runState, STAGE_GENERATING);
        }
        return List.of();
    }

    /**
     * 构建 QUERY_PLAN 事件 payload —— {@code {intent, rewritten, filters:{courseNames}}}。
     *
     * <p>公开静态：chat_message 的 query_plan 行（ChatRequestWorker.persistMessages）落库
     * 与本事件同款 JSON，事件与回放共用单一构造点保证契约一致。intent 用 code() 小写规范名
     * （与条件边路由键、R2 意图落库口径一致）。
     *
     * @param plan queryUnderstandingNode 签出的查询计划（非 null）
     * @return 有序 Map（LinkedHashMap，字段序稳定：intent → rewritten → filters）
     */
    public static Map<String, Object> buildQueryPlanPayload(QueryPlan plan) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put(
                "courseNames",
                plan.filters() == null ? List.of() : plan.filters().courseNames());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", plan.intent().code());
        payload.put("rewritten", plan.rewrittenQueries());
        payload.put("filters", filters);
        return payload;
    }

    /**
     * 从 QU 完成 chunk 的 state 提取 QueryPlan（2026-08-28 改版：QUERY_PLAN 事件与阶段跃迁共用）。
     *
     * @param chunk queryUnderstandingNode 完成 chunk（state 已含 QueryPlan；异常降级时无）
     * @return 查询计划；无 state / 无计划 / 类型不符返回 null
     */
    private QueryPlan extractQueryPlan(NodeOutput chunk) {
        if (chunk.state() == null) {
            return null;
        }
        return chunk.state()
                .value(KEY_QUERY_PLAN)
                .filter(QueryPlan.class::isInstance)
                .map(QueryPlan.class::cast)
                .orElse(null);
    }

    /**
     * 按阶段 CAS 标记推送 STAGE 事件（stage 为 null 或已发过时返回空列表）。
     */
    private List<SseEvent> stageEventIfAbsent(RunState runState, String stage) {
        if (stage == null) {
            return List.of();
        }
        boolean first = stage.equals(STAGE_RETRIEVING) ? runState.markRetrievingSent() : runState.markGeneratingSent();
        if (!first) {
            return List.of();
        }
        return List.of(createStageEvent(runState, stage));
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 从 {@link Message} 的 metadata 中提取 reasoningContent（DashScope 思考内容）。
     *
     * <p>DashScope ChatModel 将 reasoning 存储在 AssistantMessage.metadata["reasoningContent"]，
     * 值为 String 类型（可能为空字符串）。
     */
    private String extractReasoningContent(Message message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object value = metadata.get("reasoningContent");
        if (value instanceof String s) {
            return s;
        }
        return null;
    }

    /**
     * 截断工具输出摘要到指定长度。
     */
    private String truncate(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= TOOL_OUTPUT_MAX_LENGTH) {
            return output;
        }
        return output.substring(0, TOOL_OUTPUT_MAX_LENGTH);
    }

    /**
     * 构建单个 {@link SseEvent}，自动递增 seqId 并序列化 payload 为 JSON。
     */
    private SseEvent makeEvent(SseEventType type, RunState runState, Map<String, Object> payload) {
        long seqId = runState.nextSeq();
        String json = toJson(payload);
        return new SseEvent(type, seqId, json, System.currentTimeMillis());
    }

    /**
     * 使用 Jackson 将 Map 序列化为 JSON 字符串。
     * 序列化失败时返回空 JSON 对象 "{}"，保证不中断流。
     */
    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    // ========================================================================
    // RunState：run 级上下文
    // ========================================================================

    /**
     * Run 级上下文，在单个 chat run 生命周期内共享。
     *
     * @param runId           run 唯一标识
     * @param sessionId       会话 ID
     * @param model           模型名称（如 qwen3-max）
     * @param seqCounter      线程安全的 SSE 序号递增器
     * @param deltaSent       标记本 run 是否已发送过 DELTA 事件（用于 FINISHED 去重补发判断）
     * @param thinkingSent    标记本 run 是否已发送过 THINKING 事件（text 阶段补发 THINKING_END 的前置条件）
     * @param thinkingEndSent 标记本 run 是否已发送过 THINKING_END 事件（CAS 去重，避免重复发送）
     * @param retrievingSent  标记本 run 是否已发送过 STAGE(retrieving) 事件（CAS 去重，2026-08-27）
     * @param generatingSent  标记本 run 是否已发送过 STAGE(generating) 事件（CAS 去重，多触发点取最先）
     * @param queryPlanSent   标记本 run 是否已发送过 QUERY_PLAN 事件（CAS 去重，2026-08-28 时间线改版）
     */
    public record RunState(
            String runId,
            String sessionId,
            String model,
            AtomicLong seqCounter,
            AtomicBoolean deltaSent,
            AtomicBoolean thinkingSent,
            AtomicBoolean thinkingEndSent,
            AtomicBoolean retrievingSent,
            AtomicBoolean generatingSent,
            AtomicBoolean queryPlanSent) {

        /**
         * 递增并返回下一个 SSE 事件序号。
         */
        public long nextSeq() {
            return seqCounter.incrementAndGet();
        }

        /**
         * 标记本 run 已发送过 DELTA 事件，返回是否首次标记成功。
         */
        public boolean markDeltaSent() {
            return deltaSent.compareAndSet(false, true);
        }

        /**
         * 是否已发送过 DELTA 事件。
         */
        public boolean isDeltaSent() {
            return deltaSent.get();
        }

        /**
         * 标记本 run 已发送过 THINKING 事件（thinkingEndSent 补发的前置条件）。
         */
        public boolean markThinkingSent() {
            return thinkingSent.compareAndSet(false, true);
        }

        /**
         * 是否已发送过 THINKING 事件。
         */
        public boolean isThinkingSent() {
            return thinkingSent.get();
        }

        /**
         * 标记本 run 已发送过 THINKING_END 事件，返回是否首次标记成功（CAS 去重）。
         */
        public boolean markThinkingEndSent() {
            return thinkingEndSent.compareAndSet(false, true);
        }

        /**
         * 标记本 run 已发送过 STAGE(retrieving) 事件，返回是否首次标记成功（CAS 去重）。
         */
        public boolean markRetrievingSent() {
            return retrievingSent.compareAndSet(false, true);
        }

        /**
         * 标记本 run 已发送过 STAGE(generating) 事件，返回是否首次标记成功（CAS 去重，
         * 多触发点——chat 意图 QU 完成 / retrieveNode 完成 / reactAgent 首 chunk——取最先）。
         */
        public boolean markGeneratingSent() {
            return generatingSent.compareAndSet(false, true);
        }

        /**
         * 标记本 run 已发送过 QUERY_PLAN 事件，返回是否首次标记成功（CAS 去重，2026-08-28）。
         */
        public boolean markQueryPlanSent() {
            return queryPlanSent.compareAndSet(false, true);
        }

        /**
         * 工厂方法：创建初始 seqId=0、六标志均为 false 的 RunState。
         */
        public static RunState create(String runId, String sessionId, String model) {
            return new RunState(
                    runId,
                    sessionId,
                    model,
                    new AtomicLong(0),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false));
        }
    }
}
