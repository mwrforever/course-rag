package com.commerce.rag.stream;

import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        assertEquals("{}", result.get(0).payload());
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
}
