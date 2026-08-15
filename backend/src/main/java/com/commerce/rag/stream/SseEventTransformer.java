package com.commerce.rag.stream;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

        return switch (type) {
            case AGENT_MODEL_STREAMING -> transformModelStreaming(streaming, runState);
            case AGENT_MODEL_FINISHED -> transformModelFinished(streaming, runState);
            case AGENT_TOOL_FINISHED -> transformToolFinished(streaming, runState);
                // AGENT_TOOL_STREAMING: 工具节点通常同步执行，不产生流式 delta，忽略
                // AGENT_HOOK_*: 拦截器事件，前端不需要
                // GRAPH_NODE_*: 图节点级别事件，前端不需要逐节点感知
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

        // 1. 检查 reasoningContent → thinking 事件
        String reasoning = extractReasoningContent(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            runState.markThinkingSent(); // 标记已发 THINKING，text 阶段补 THINKING_END 的前置条件
            return List.of(makeEvent(SseEventType.THINKING, runState, Map.of("delta", reasoning)));
        }

        List<SseEvent> events = new ArrayList<>();

        // 2. 取文本 delta → 先补 THINKING_END（若有思考且未发），再发 DELTA
        String text = message.getText();
        if (text != null && !text.isEmpty()) {
            // qwen 思考模型 thinking/text 两阶段互斥：首条 text 即思考结束信号，
            // 必须补发 THINKING_END 再发 DELTA，保证前端退出"思考中"状态
            if (runState.isThinkingSent() && runState.markThinkingEndSent()) {
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of()));
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
                events.add(makeEvent(SseEventType.THINKING_END, runState, Map.of()));
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
     *
     * <p>设计文档 §3.2 / F2-10：当工具名为 searchKnowledge 时，额外从 tool_result
     * 提取 chunkId / source / headingPath / score 构造 SOURCES 事件推送，供前端渲染
     * 知识来源引用卡片。
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

            // F2-10: searchKnowledge 工具结果 → 提取知识来源构造 SOURCES 事件
            if ("searchKnowledge".equals(response.name())) {
                SseEvent sourcesEvent = buildSourcesEvent(response.responseData(), runState);
                if (sourcesEvent != null) {
                    events.add(sourcesEvent);
                }
            }
        }
        return events;
    }

    /**
     * 从 searchKnowledge 工具结果 JSON 中提取知识来源，构造 SOURCES 事件。
     *
     * <p>KnowledgeSearchResult JSON 格式：{@code {"chunks":[{"chunkId":"...","source":"...",
     * "headingPath":"...","score":0.85,...},...]}}
     *
     * <p>提取每个 chunk 的 chunkId / source / headingPath / score，构造 sources 列表。
     * 解析失败时返回 null（不中断流）。
     *
     * @param responseData 工具返回的完整 JSON 字符串
     * @param runState     run 上下文
     * @return SOURCES 事件，解析失败返回 null
     */
    private SseEvent buildSourcesEvent(String responseData, RunState runState) {
        if (responseData == null || responseData.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(responseData);
            JsonNode chunks = root.path("chunks");
            if (!chunks.isArray() || chunks.isEmpty()) {
                return null;
            }

            List<Map<String, Object>> sources = new ArrayList<>();
            for (JsonNode chunk : chunks) {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("chunkId", chunk.path("chunkId").asText(""));
                source.put("source", chunk.path("source").asText(""));
                source.put("headingPath", chunk.path("headingPath").asText(""));
                source.put("score", chunk.path("score").asDouble(0.0));
                sources.add(source);
            }

            return makeEvent(SseEventType.SOURCES, runState, Map.of("sources", sources));
        } catch (JsonProcessingException e) {
            return null;
        }
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
     */
    public record RunState(
            String runId,
            String sessionId,
            String model,
            AtomicLong seqCounter,
            AtomicBoolean deltaSent,
            AtomicBoolean thinkingSent,
            AtomicBoolean thinkingEndSent) {

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
         * 工厂方法：创建初始 seqId=0、三标志均为 false 的 RunState。
         */
        public static RunState create(String runId, String sessionId, String model) {
            return new RunState(
                    runId,
                    sessionId,
                    model,
                    new AtomicLong(0),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false),
                    new AtomicBoolean(false));
        }
    }
}
