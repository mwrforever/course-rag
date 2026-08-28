package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/**
 * ImageCaptionService 单元测试 —— VLM caption 调用组装
 *
 * @author commerce-rag
 */
class ImageCaptionServiceTest {

    private ChatModel chatModel;
    private PromptLoader promptLoader;
    private ImageCaptionService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        promptLoader = mock(PromptLoader.class);
        when(promptLoader.loadSections("caption.yml"))
                .thenReturn(Map.of(
                        "caption.system", "系统规则",
                        "caption.instruction", "输出格式"));
        EtlProperties props = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.ImageExecutor(3, 3, 20, "etl-image-", 60),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2),
                500);
        service = new ImageCaptionService(chatModel, promptLoader, props);
    }

    @Test
    @DisplayName("caption — 图片以 Media 传入（base64 data URL 路径），模型名按次覆盖为 caption 模型")
    void caption_sendsMediaAndCaptionModel() {
        byte[] imageBytes = new byte[] {1, 2, 3};
        AssistantMessage output = new AssistantMessage("这是图片描述内容");
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(output));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String caption = service.caption(imageBytes, "image/png");

        assertEquals("这是图片描述内容", caption);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        Prompt prompt = captor.getValue();
        // 消息序列：SystemMessage + UserMessage（带 1 个 Media）
        assertEquals(2, prompt.getInstructions().size());
        UserMessage user = (UserMessage) prompt.getInstructions().get(1);
        assertEquals(1, user.getMedia().size());
        // 图片字节原样进入 Media.data（SAA 发送时转 base64 data URL，模型侧收到图片内容）
        assertArrayEquals(imageBytes, (byte[]) user.getMedia().get(0).getData());
        // 模型名按次覆盖：OpenAiChatOptions.model = etl.caption-model（qwen3.7-flash）
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) prompt.getOptions()).getModel());
    }

    @Test
    @DisplayName("caption — 模型调用失败上抛（调用方按图片跳过）")
    void caption_failure_throws() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("dashscope 不可用"));

        assertThrows(RuntimeException.class, () -> service.caption(new byte[] {1}, "image/png"));
    }

    // ==================== captionStreaming（2026-08-28 时间线改版 Task 4） ====================

    @Test
    @DisplayName("captionStreaming — reasoning 片段实时推 attachments 阶段，content 聚合成完整 caption，end 恰好一次")
    void captionStreaming_pushesReasoningAndAggregatesContent() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 模拟 qwen3.7-flash 混合思考流：先两段 reasoning、后两段 content（两阶段互斥）
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(chunk("", "图中是一张图表，"), chunk("", "横轴为月份"), chunk("这是一张", null), chunk("销量图表", null)));

        String caption = service.captionStreaming(new byte[] {1, 2, 3}, "image/png", pusher);

        // 聚合文本 = content 增量拼接（与同步 caption 返回语义一致，不含 reasoning）
        assertEquals("这是一张销量图表", caption);
        // 推送契约：两次 THINKING(attachments) → 首个 content chunk 处成对一次 THINKING_END(attachments)
        InOrder inOrder = inOrder(pusher);
        inOrder.verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "图中是一张图表，");
        inOrder.verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "横轴为月份");
        inOrder.verify(pusher).end(SseEventTransformer.STAGE_ATTACHMENTS);
        inOrder.verifyNoMoreInteractions();
        // 流式路径 Prompt 组装与同步一致：视觉 Media + 按次覆盖 caption 模型名
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) captor.getValue().getOptions()).getModel());
    }

    @Test
    @DisplayName("captionStreaming — 全程无 reasoning（非思考响应）不产生任何思考事件（成对契约）")
    void captionStreaming_withoutReasoning_pushesNothing() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("纯回答文本", null)));

        String caption = service.captionStreaming(new byte[] {1}, "image/png", pusher);

        assertEquals("纯回答文本", caption);
        verifyNoInteractions(pusher);
    }

    @Test
    @DisplayName("captionStreaming — 纯 reasoning 无 content 结束：仍补一次 end 关思考态，聚合文本为空串")
    void captionStreaming_reasoningOnly_stillEndsThinking() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("", "只有思考没有回答")));

        String caption = service.captionStreaming(new byte[] {1}, "image/png", pusher);

        assertEquals("", caption);
        verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "只有思考没有回答");
        verify(pusher).end(SseEventTransformer.STAGE_ATTACHMENTS);
    }

    @Test
    @DisplayName("captionStreaming — 流中途异常：已推 reasoning 先补 end 再上抛（调用方按图跳过降级不变）")
    void captionStreaming_streamErrorAfterReasoning_endsAndRethrows() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just(chunk("", "思考了一半")), Flux.error(new IllegalStateException("dashscope 断流"))));

        assertThrows(IllegalStateException.class, () -> service.captionStreaming(new byte[] {1}, "image/png", pusher));

        // 异常前已推的 THINKING 必须成对补 THINKING_END，避免前端停留「思考中」
        verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "思考了一半");
        verify(pusher).end(SseEventTransformer.STAGE_ATTACHMENTS);
    }

    @Test
    @DisplayName("captionStreaming — chunk 间静默超过流式硬超时上抛（阻塞附件池线程有界，评审 C1 同款自界）")
    void captionStreaming_silentStream_timesOut() {
        // 单图超时压到 1s 的独立被测服务（Flux.never 永不产生 chunk，验证 blockLast 有界自界）
        EtlProperties oneSecondProps = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.ImageExecutor(3, 3, 20, "etl-image-", 1),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2),
                500);
        ImageCaptionService timeoutService = new ImageCaptionService(chatModel, promptLoader, oneSecondProps);
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        long start = System.currentTimeMillis();
        assertThrows(
                RuntimeException.class, () -> timeoutService.captionStreaming(new byte[] {1}, "image/png", pusher));
        // 约 1s 内返回（有界而非永久阻塞附件池线程），未推过 reasoning 故无思考事件
        assertTrue(System.currentTimeMillis() - start < 3000, "超时应按流式预算有界触发");
        verifyNoInteractions(pusher);
    }

    /**
     * 构造流式 chunk：text 为 content 增量、reasoning 非 null 时进 metadata['reasoningContent']
     * （DashScope OpenAI 兼容流式思考字段映射，与 QueryUnderstandingServiceTest 同款桩结构）
     */
    private static ChatResponse chunk(String text, String reasoning) {
        AssistantMessage message = reasoning == null
                ? new AssistantMessage(text == null ? "" : text)
                : AssistantMessage.builder()
                        .content(text == null ? "" : text)
                        .properties(Map.of("reasoningContent", reasoning))
                        .build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
