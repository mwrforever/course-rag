package com.commerce.rag.stream;

import static com.commerce.rag.bot.graph.OverAllState.KEY_QUERY_PLAN;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.LeadAgentGraph;
import com.commerce.rag.bot.rewrite.QueryPlan;
import com.commerce.rag.bot.rewrite.QueryPlanFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * SseEventTransformer 单元测试 —— Mock StreamingOutput，验证 OutputType → SseEvent 映射
 *
 * @author commerce-rag
 */
@DisplayName("SseEventTransformer 状态机测试")
class SseEventTransformerTest {

    private SseEventTransformer transformer;

    @BeforeEach
    void setUp() {
        // 使用真实 ObjectMapper，确保 JSON 序列化可验证
        transformer = new SseEventTransformer(new ObjectMapper());
    }

    // ==================== AGENT_MODEL_STREAMING ====================

    @Test
    @DisplayName("AGENT_MODEL_STREAMING + 有 text → 1 个 DELTA 事件")
    void transform_modelStreamingWithText_returnsDeltaEvent() {
        // Given: 先创建所有 mock 对象
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        // 再做 stubbing
        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getText()).thenReturn("你好");
        when(mockMsg.getMetadata()).thenReturn(Map.of());

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(1, result.size());
        assertEquals(SseEventType.DELTA, result.get(0).type());
        assertTrue(result.get(0).payload().contains("你好"));
        assertTrue(result.get(0).payload().contains("\"text\""));
        assertEquals(1, result.get(0).seqId());
    }

    @Test
    @DisplayName("AGENT_MODEL_STREAMING + 有 reasoningContent → 1 个 THINKING 事件")
    void transform_modelStreamingWithReasoning_returnsThinkingEvent() {
        // Given
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考中"));

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(1, result.size());
        assertEquals(SseEventType.THINKING, result.get(0).type());
        assertTrue(result.get(0).payload().contains("思考中"));
        assertTrue(result.get(0).payload().contains("\"delta\""));
        // 2026-08-28 时间线改版：主 agent 生成路径 THINKING 固定 stage=generating
        assertTrue(result.get(0).payload().contains("\"stage\":\"generating\""));
    }

    @Test
    @DisplayName("AGENT_MODEL_STREAMING + 空 text → 空列表（跳过空 delta）")
    void transform_modelStreamingEmptyText_returnsEmptyList() {
        // Given
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getText()).thenReturn("");
        when(mockMsg.getMetadata()).thenReturn(Map.of());

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("AGENT_MODEL_STREAMING + message 为 null → 空列表")
    void transform_modelStreamingNullMessage_returnsEmptyList() {
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(null);

        List<SseEvent> result = transformer.transform(mockOutput, runState);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("thinking→text 切换：补发 THINKING_END，且位于首条 DELTA 之前")
    void transform_thinkingThenText_emitsThinkingEndBeforeDelta() {
        // Given: 先推 thinking chunk，再推 text chunk（同一 runState）
        AssistantMessage thinkingMsg = mock(AssistantMessage.class);
        StreamingOutput<?> thinkingOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(thinkingOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(thinkingOutput.message()).thenReturn(thinkingMsg);
        when(thinkingMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考中"));

        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("答案是42");

        // When
        List<SseEvent> first = transformer.transform(thinkingOutput, runState);
        List<SseEvent> second = transformer.transform(textOutput, runState);

        // Then: THINKING → THINKING_END → DELTA
        assertEquals(1, first.size());
        assertEquals(SseEventType.THINKING, first.get(0).type());
        assertEquals(2, second.size());
        assertEquals(SseEventType.THINKING_END, second.get(0).type());
        // THINKING_END 携带 stage=generating（与 THINKING 配对，2026-08-28 时间线改版）
        assertEquals("{\"stage\":\"generating\"}", second.get(0).payload());
        assertEquals(SseEventType.DELTA, second.get(1).type());
    }

    @Test
    @DisplayName("纯文本流（无 thinking）：text 与 FINISHED 均不产生 THINKING_END")
    void transform_textOnly_noThinkingEnd() {
        // Given: 先推 text chunk（无 thinking 历史），再推 FINISHED（无 reasoning）
        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("直接回答");

        AssistantMessage finMsg = mock(AssistantMessage.class);
        StreamingOutput<?> finOutput = mock(StreamingOutput.class);
        when(finOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(finOutput.message()).thenReturn(finMsg);
        when(finMsg.getMetadata()).thenReturn(Map.of());
        lenient().when(finMsg.hasToolCalls()).thenReturn(false);

        // When
        List<SseEvent> streamingEvents = transformer.transform(textOutput, runState);
        List<SseEvent> finishedEvents = transformer.transform(finOutput, runState);

        // Then: 无 THINKING_END
        assertEquals(1, streamingEvents.size());
        assertEquals(SseEventType.DELTA, streamingEvents.get(0).type());
        assertTrue(finishedEvents.stream().noneMatch(e -> e.type() == SseEventType.THINKING_END));
    }

    @Test
    @DisplayName("流式已补发 THINKING_END 后，FINISHED 带 reasoning 不重复发")
    void transform_finishedAfterStreamingThinkingEnd_noDuplicate() {
        // Given: thinking → text（已补发 THINKING_END）→ FINISHED 仍带 reasoningContent
        AssistantMessage thinkingMsg = mock(AssistantMessage.class);
        StreamingOutput<?> thinkingOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        when(thinkingOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(thinkingOutput.message()).thenReturn(thinkingMsg);
        when(thinkingMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考中"));

        AssistantMessage textMsg = mock(AssistantMessage.class);
        StreamingOutput<?> textOutput = mock(StreamingOutput.class);
        when(textOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(textOutput.message()).thenReturn(textMsg);
        when(textMsg.getMetadata()).thenReturn(Map.of());
        when(textMsg.getText()).thenReturn("答案");

        AssistantMessage finMsg = mock(AssistantMessage.class);
        StreamingOutput<?> finOutput = mock(StreamingOutput.class);
        when(finOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(finOutput.message()).thenReturn(finMsg);
        when(finMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考完毕"));
        lenient().when(finMsg.hasToolCalls()).thenReturn(false);

        // When
        transformer.transform(thinkingOutput, runState);
        transformer.transform(textOutput, runState);
        List<SseEvent> finishedEvents = transformer.transform(finOutput, runState);

        // Then: FINISHED 不再重复发 THINKING_END（无事件）
        assertTrue(finishedEvents.isEmpty());
    }

    @Test
    @DisplayName("P3-1 AGENT_MODEL_STREAMING + 带 toolCalls → TOOL_CALL 事件（FINISHED message=null 实证后 TOOL_CALL 移至流式分支）")
    void transform_modelStreamingWithToolCalls_returnsToolCallEvent() {
        // Given: 流式 chunk 携带完整 toolCall（模型输出工具调用时的最后 chunk）
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getText()).thenReturn("");
        when(mockMsg.getMetadata()).thenReturn(Map.of());
        when(mockMsg.hasToolCalls()).thenReturn(true);
        when(mockMsg.getToolCalls())
                .thenReturn(List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "searchKnowledge", "{\"query\":\"课程\"}")));

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then: TOOL_CALL 事件，payload 与实时契约一致（toolCallId/toolName/input）
        assertEquals(1, result.size());
        assertEquals(SseEventType.TOOL_CALL, result.get(0).type());
        assertTrue(result.get(0).payload().contains("\"toolCallId\":\"call-1\""));
        assertTrue(result.get(0).payload().contains("\"toolName\":\"searchKnowledge\""));
    }

    // ==================== AGENT_MODEL_FINISHED ====================

    @Test
    @DisplayName("AGENT_MODEL_FINISHED + 有 reasoningContent → THINKING_END 事件")
    void transform_modelFinishedWithReasoning_returnsThinkingEndEvent() {
        // Given
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "思考完毕"));
        lenient().when(mockMsg.hasToolCalls()).thenReturn(false);

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(1, result.size());
        assertEquals(SseEventType.THINKING_END, result.get(0).type());
        // THINKING_END 携带 stage=generating（与 THINKING 配对，2026-08-28 时间线改版）
        assertEquals("{\"stage\":\"generating\"}", result.get(0).payload());
    }

    @Test
    @DisplayName("AGENT_MODEL_FINISHED + 有 toolCalls → TOOL_CALL 事件")
    void transform_modelFinishedWithToolCalls_returnsToolCallEvent() {
        // Given: 先创建 ToolCall mock
        AssistantMessage.ToolCall mockToolCall = mock(AssistantMessage.ToolCall.class);
        when(mockToolCall.id()).thenReturn("call-001");
        when(mockToolCall.name()).thenReturn("searchKnowledge");
        when(mockToolCall.arguments()).thenReturn("{\"query\":\"test\"}");

        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getMetadata()).thenReturn(Map.of());
        when(mockMsg.hasToolCalls()).thenReturn(true);
        when(mockMsg.getToolCalls()).thenReturn(List.of(mockToolCall));

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(1, result.size());
        assertEquals(SseEventType.TOOL_CALL, result.get(0).type());
        assertTrue(result.get(0).payload().contains("call-001"));
        assertTrue(result.get(0).payload().contains("searchKnowledge"));
        assertTrue(result.get(0).payload().contains("\"input\""));
    }

    @Test
    @DisplayName("AGENT_MODEL_FINISHED + 多个 toolCalls → 多个 TOOL_CALL 事件")
    void transform_modelFinishedMultipleToolCalls_returnsMultipleToolCallEvents() {
        // Given
        AssistantMessage.ToolCall mockToolCall1 = mock(AssistantMessage.ToolCall.class);
        when(mockToolCall1.id()).thenReturn("call-001");
        when(mockToolCall1.name()).thenReturn("searchKnowledge");
        when(mockToolCall1.arguments()).thenReturn("{\"q\":\"a\"}");

        AssistantMessage.ToolCall mockToolCall2 = mock(AssistantMessage.ToolCall.class);
        when(mockToolCall2.id()).thenReturn("call-002");
        when(mockToolCall2.name()).thenReturn("courseApi");
        when(mockToolCall2.arguments()).thenReturn("{\"id\":1}");

        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getMetadata()).thenReturn(Map.of());
        when(mockMsg.hasToolCalls()).thenReturn(true);
        when(mockMsg.getToolCalls()).thenReturn(List.of(mockToolCall1, mockToolCall2));

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(2, result.size());
        assertEquals(SseEventType.TOOL_CALL, result.get(0).type());
        assertTrue(result.get(0).payload().contains("call-001"));
        assertEquals(SseEventType.TOOL_CALL, result.get(1).type());
        assertTrue(result.get(1).payload().contains("call-002"));
    }

    @Test
    @DisplayName("AGENT_MODEL_FINISHED + reasoningContent + toolCalls → THINKING_END + TOOL_CALL")
    void transform_modelFinishedWithReasoningAndToolCalls_returnsMultipleEvents() {
        // Given
        AssistantMessage.ToolCall mockToolCall = mock(AssistantMessage.ToolCall.class);
        when(mockToolCall.id()).thenReturn("call-001");
        when(mockToolCall.name()).thenReturn("searchKnowledge");
        when(mockToolCall.arguments()).thenReturn("{}");

        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getMetadata()).thenReturn(Map.of("reasoningContent", "需要搜索"));
        when(mockMsg.hasToolCalls()).thenReturn(true);
        when(mockMsg.getToolCalls()).thenReturn(List.of(mockToolCall));

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then: THINKING_END 在前，TOOL_CALL 在后
        assertEquals(2, result.size());
        assertEquals(SseEventType.THINKING_END, result.get(0).type());
        assertEquals(SseEventType.TOOL_CALL, result.get(1).type());
    }

    // ==================== AGENT_TOOL_FINISHED ====================

    @Test
    @DisplayName("AGENT_TOOL_FINISHED + 1 个 ToolResponse → TOOL_RESULT 事件")
    void transform_toolFinished_returnsToolResultEvent() {
        // Given
        ToolResponseMessage.ToolResponse mockResponse = mock(ToolResponseMessage.ToolResponse.class);
        when(mockResponse.id()).thenReturn("call-001");
        when(mockResponse.responseData()).thenReturn("搜索结果内容");

        ToolResponseMessage mockTrm = mock(ToolResponseMessage.class);
        when(mockTrm.getResponses()).thenReturn(List.of(mockResponse));

        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_TOOL_FINISHED);
        when(mockOutput.message()).thenReturn(mockTrm);

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then
        assertEquals(1, result.size());
        assertEquals(SseEventType.TOOL_RESULT, result.get(0).type());
        assertTrue(result.get(0).payload().contains("call-001"));
        assertTrue(result.get(0).payload().contains("搜索结果内容"));
        assertTrue(result.get(0).payload().contains("\"status\":\"success\""));
    }

    @Test
    @DisplayName("AGENT_TOOL_FINISHED + 非 ToolResponseMessage → 空列表")
    void transform_toolFinishedNotToolResponse_returnsEmptyList() {
        Message mockMsg = mock(Message.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_TOOL_FINISHED);
        when(mockOutput.message()).thenReturn(mockMsg);

        List<SseEvent> result = transformer.transform(mockOutput, runState);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("AGENT_TOOL_FINISHED + 长输出截断到 200 字符")
    void transform_toolFinishedLongOutput_truncated() {
        // Given
        String longOutput = "x".repeat(300);
        ToolResponseMessage.ToolResponse mockResponse = mock(ToolResponseMessage.ToolResponse.class);
        when(mockResponse.id()).thenReturn("call-001");
        when(mockResponse.responseData()).thenReturn(longOutput);

        ToolResponseMessage mockTrm = mock(ToolResponseMessage.class);
        when(mockTrm.getResponses()).thenReturn(List.of(mockResponse));

        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_TOOL_FINISHED);
        when(mockOutput.message()).thenReturn(mockTrm);

        // When
        List<SseEvent> result = transformer.transform(mockOutput, runState);

        // Then: 输出被截断到 200 字符
        assertTrue(result.get(0).payload().contains("x".repeat(200)));
        assertFalse(result.get(0).payload().contains("x".repeat(201)));
    }

    // ==================== createMetadataEvent ====================

    @Test
    @DisplayName("createMetadataEvent → METADATA 事件包含 runId/sessionId/model")
    void createMetadataEvent_returnsCorrectMetadata() {
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run100", "sess200", "qwen3-max");

        SseEvent event = transformer.createMetadataEvent(runState);

        assertEquals(SseEventType.METADATA, event.type());
        assertEquals(1, event.seqId());
        assertTrue(event.payload().contains("\"runId\":\"run100\""));
        assertTrue(event.payload().contains("\"sessionId\":\"sess200\""));
        assertTrue(event.payload().contains("\"model\":\"qwen3-max\""));
    }

    // ==================== 边界场景 ====================

    @Test
    @DisplayName("transform null chunk → 空列表")
    void transform_nullChunk_returnsEmptyList() {
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");
        assertTrue(transformer.transform(null, runState).isEmpty());
    }

    @Test
    @DisplayName("transform null runState → 空列表")
    void transform_nullRunState_returnsEmptyList() {
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        assertTrue(transformer.transform(mockOutput, null).isEmpty());
    }

    @Test
    @DisplayName("transform 非 StreamingOutput 的 NodeOutput → 空列表")
    void transform_nonStreamingOutput_returnsEmptyList() {
        // NodeOutput mock（不是 StreamingOutput）
        com.alibaba.cloud.ai.graph.NodeOutput mockNode = mock(com.alibaba.cloud.ai.graph.NodeOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        List<SseEvent> result = transformer.transform(mockNode, runState);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("transform getOutputType 为 null → 空列表")
    void transform_nullOutputType_returnsEmptyList() {
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(null);

        assertTrue(transformer.transform(mockOutput, runState).isEmpty());
    }

    @Test
    @DisplayName("seqId 递增验证 — 多次 transform 后 seqId 连续递增")
    void transform_multipleCalls_seqIdIncrements() {
        AssistantMessage mockMsg = mock(AssistantMessage.class);
        StreamingOutput<?> mockOutput = mock(StreamingOutput.class);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        when(mockOutput.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(mockOutput.message()).thenReturn(mockMsg);
        when(mockMsg.getText()).thenReturn("hello");
        when(mockMsg.getMetadata()).thenReturn(Map.of());

        // 第一次调用
        List<SseEvent> result1 = transformer.transform(mockOutput, runState);
        // 第二次调用
        List<SseEvent> result2 = transformer.transform(mockOutput, runState);

        assertEquals(1, result1.get(0).seqId());
        assertEquals(2, result2.get(0).seqId());
    }

    // ==================== transformStages（STAGE 阶段事件，2026-08-27） ====================

    @Test
    @DisplayName("QU 完成 chunk + knowledge_question 计划 → STAGE(retrieving)，仅一次")
    void transformStages_quFinishedKnowledgeQuestion_retrievingOnce() {
        // Given: QU 完成 chunk（普通 NodeOutput，state 携带 knowledge_question 计划）
        QueryPlan plan =
                new QueryPlan(IntentType.KNOWLEDGE_QUESTION, List.of("查询"), new QueryPlanFilters(List.of()), false);
        NodeOutput quChunk = mock(NodeOutput.class);
        when(quChunk.node()).thenReturn(LeadAgentGraph.NODE_QUERY_UNDERSTANDING);
        when(quChunk.state()).thenReturn(new OverAllState(Map.of(KEY_QUERY_PLAN, plan)));
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        // When: 同一 chunk 触发两次（CAS 去重）
        List<SseEvent> first = transformer.transformStages(quChunk, runState);
        List<SseEvent> second = transformer.transformStages(quChunk, runState);

        // Then: 首次 1 个 STAGE(retrieving)，payload 含中文文案；第二次空
        assertEquals(1, first.size());
        assertEquals(SseEventType.STAGE, first.get(0).type());
        assertTrue(first.get(0).payload().contains("\"stage\":\"retrieving\""));
        assertTrue(first.get(0).payload().contains("知识库查询中"));
        assertTrue(second.isEmpty());
    }

    @Test
    @DisplayName("QU 完成 chunk + chat 计划 → STAGE(generating)（不检索直接生成）")
    void transformStages_quFinishedChat_generating() {
        QueryPlan plan = new QueryPlan(IntentType.CHAT, List.of("闲聊"), new QueryPlanFilters(List.of()), false);
        NodeOutput quChunk = mock(NodeOutput.class);
        when(quChunk.node()).thenReturn(LeadAgentGraph.NODE_QUERY_UNDERSTANDING);
        when(quChunk.state()).thenReturn(new OverAllState(Map.of(KEY_QUERY_PLAN, plan)));
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        List<SseEvent> events = transformer.transformStages(quChunk, runState);

        assertEquals(1, events.size());
        assertEquals(SseEventType.STAGE, events.get(0).type());
        assertTrue(events.get(0).payload().contains("\"stage\":\"generating\""));
    }

    @Test
    @DisplayName("QU 完成 chunk + 无计划（异常降级）→ 不发阶段（reactAgent 首 chunk 兜底）")
    void transformStages_quFinishedNoPlan_noEvent() {
        NodeOutput quChunk = mock(NodeOutput.class);
        when(quChunk.node()).thenReturn(LeadAgentGraph.NODE_QUERY_UNDERSTANDING);
        when(quChunk.state()).thenReturn(new OverAllState(Map.of()));
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        assertTrue(transformer.transformStages(quChunk, runState).isEmpty());
    }

    @Test
    @DisplayName("retrieveNode 完成 chunk → STAGE(generating)（检索阶段结束）")
    void transformStages_retrieveFinished_generating() {
        NodeOutput retrieveChunk = mock(NodeOutput.class);
        when(retrieveChunk.node()).thenReturn(LeadAgentGraph.NODE_RETRIEVE);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        List<SseEvent> events = transformer.transformStages(retrieveChunk, runState);

        assertEquals(1, events.size());
        assertTrue(events.get(0).payload().contains("\"stage\":\"generating\""));
    }

    @Test
    @DisplayName("reactAgent 首个模型流式 chunk → STAGE(generating) 兜底（与 retrieveNode 完成互斥去重）")
    void transformStages_reactAgentFirstChunk_generatingFallback() {
        // Given: QU chunk 无计划（跳过）→ reactAgent 首个 STREAMING chunk 兜底
        StreamingOutput<?> modelChunk = mock(StreamingOutput.class);
        when(modelChunk.node()).thenReturn(LeadAgentGraph.NODE_REACT_AGENT);
        when(modelChunk.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        List<SseEvent> events = transformer.transformStages(modelChunk, runState);

        assertEquals(1, events.size());
        assertTrue(events.get(0).payload().contains("\"stage\":\"generating\""));
        // 同 run 内 retrieveNode 完成后再触发不再重复（CAS）
        NodeOutput retrieveChunk = mock(NodeOutput.class);
        when(retrieveChunk.node()).thenReturn(LeadAgentGraph.NODE_RETRIEVE);
        assertTrue(transformer.transformStages(retrieveChunk, runState).isEmpty());
    }

    @Test
    @DisplayName("无关节点 / 非 reactAgent 流式 chunk → 无阶段事件")
    void transformStages_unrelatedNode_noEvent() {
        NodeOutput otherChunk = mock(NodeOutput.class);
        when(otherChunk.node()).thenReturn("__START__");
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        assertTrue(transformer.transformStages(otherChunk, runState).isEmpty());

        // QU 节点的 AGENT_MODEL_STREAMING（不该出现的组合）也不触发
        StreamingOutput<?> quStreaming = mock(StreamingOutput.class);
        when(quStreaming.node()).thenReturn(LeadAgentGraph.NODE_QUERY_UNDERSTANDING);
        when(quStreaming.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        assertTrue(transformer.transformStages(quStreaming, runState).isEmpty());
    }

    @Test
    @DisplayName("createStageEvent → STAGE 事件包含 stage 与中文 label")
    void createStageEvent_containsStageAndLabel() {
        SseEventTransformer.RunState runState = SseEventTransformer.RunState.create("run1", "sess1", "qwen3-max");

        SseEvent event = transformer.createStageEvent(runState, SseEventTransformer.STAGE_UNDERSTANDING);

        assertEquals(SseEventType.STAGE, event.type());
        assertTrue(event.payload().contains("\"stage\":\"understanding\""));
        assertTrue(event.payload().contains("正在理解你的问题"));
    }
}
