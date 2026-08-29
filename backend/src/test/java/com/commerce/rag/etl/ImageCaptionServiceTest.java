package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // ==================== captionStreaming（2026-08-28 时间线改版 Task 4，评审 I-2 stage 级收口） ====================

    @Test
    @DisplayName("captionStreaming — reasoning 片段实时推 attachments 阶段并置批级标志，content 聚合成完整 caption（方法内不调 end）")
    void captionStreaming_pushesReasoningAndAggregatesContent() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        // 模拟 qwen3.7-flash 混合思考流：先两段 reasoning、后两段 content（两阶段互斥）
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        Flux.just(chunk("", "图中是一张图表，"), chunk("", "横轴为月份"), chunk("这是一张", null), chunk("销量图表", null)));

        String caption = service.captionStreaming(new byte[] {1, 2, 3}, "image/png", pusher, reasoningSeenAny, null);

        // 聚合文本 = content 增量拼接（与同步 caption 返回语义一致，不含 reasoning）
        assertEquals("这是一张销量图表", caption);
        // 推送契约（评审 I-2）：只推 THINKING(attachments)，END 由批完成点统一收口——
        // 方法内不再出现任何 end 调用；确实推过 reasoning → 批级标志置 true
        InOrder inOrder = inOrder(pusher);
        inOrder.verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "图中是一张图表，");
        inOrder.verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "横轴为月份");
        inOrder.verifyNoMoreInteractions();
        assertTrue(reasoningSeenAny.get(), "推过 reasoning 必须置批级标志，供批次统一补 end");
        // 流式路径 Prompt 组装与同步一致：视觉 Media + 按次覆盖 caption 模型名
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).stream(captor.capture());
        assertEquals("qwen3.7-flash", ((OpenAiChatOptions) captor.getValue().getOptions()).getModel());
    }

    @Test
    @DisplayName("captionStreaming — 全程无 reasoning（非思考响应）零思考事件且批级标志保持 false（批次不发孤儿 end）")
    void captionStreaming_withoutReasoning_pushesNothing() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("纯回答文本", null)));

        String caption = service.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, null);

        assertEquals("纯回答文本", caption);
        verifyNoInteractions(pusher);
        assertFalse(reasoningSeenAny.get(), "零 reasoning 批次标志必须保持 false");
    }

    @Test
    @DisplayName("captionStreaming — 纯 reasoning 无 content：仅推 reasoning 并置标志，end 交批次收口（方法内零 end）")
    void captionStreaming_reasoningOnly_noEndInsideMethod() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("", "只有思考没有回答")));

        String caption = service.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, null);

        assertEquals("", caption);
        verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "只有思考没有回答");
        verify(pusher, never()).end(any());
        assertTrue(reasoningSeenAny.get());
    }

    @Test
    @DisplayName("captionStreaming — 流中途异常：上抛走按图跳过降级；方法内不补 end（关态交批次完成点），但批级标志已置")
    void captionStreaming_streamErrorAfterReasoning_setsFlagAndRethrows() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just(chunk("", "思考了一半")), Flux.error(new IllegalStateException("dashscope 断流"))));

        assertThrows(
                IllegalStateException.class,
                () -> service.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, null));

        // 评审 I-2：异常路径方法内不再自行 end——批次完成点按标志统一补 end，避免多图交错；
        // 标志已置 true 保证「异常图此前推过思考」不残留思考态
        verify(pusher).push(SseEventTransformer.STAGE_ATTACHMENTS, "思考了一半");
        verify(pusher, never()).end(any());
        assertTrue(reasoningSeenAny.get(), "异常前推过 reasoning，批次完成点据此补 end");
    }

    @Test
    @DisplayName("captionStreaming — 全流总时长超过硬超时上抛（阻塞附件池线程有界，评审 C1 同款自界）")
    void captionStreaming_streamExceedsTotalBudget_timesOut() {
        // 单图超时压到 1s 的独立被测服务（Flux.never 永不完成，验证 blockLast 全流总时长有界自界）
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
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.never());

        long start = System.currentTimeMillis();
        assertThrows(
                RuntimeException.class,
                () -> timeoutService.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, null));
        // 约 1s 内返回（有界而非永久阻塞附件池线程），未推过 reasoning 故无思考事件、标志保持 false
        assertTrue(System.currentTimeMillis() - start < 3000, "超时应按全流总时长预算有界触发");
        verifyNoInteractions(pusher);
        assertFalse(reasoningSeenAny.get());
    }

    // ==================== 消息实体化 caption 捕获（2026-08-29，spec §3.2） ====================

    @Test
    @DisplayName("captionStreaming(带 sink) — 调用完成点捕获 thinking 全文 + 描述文本（stage=attachments）")
    void captionStreaming_withSink_capturesThinkingAndText() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        // 思考全文经 ThinkingPusher 累加缓冲（与已推送 THINKING 事件逐字一致）
        when(pusher.accumulated()).thenReturn(Map.of(SseEventTransformer.STAGE_ATTACHMENTS, "识别图中公式与结构"));
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chunk("", "识别图中公式与结构"), chunk("这是一张函数图像", null)));
        AssistantMessageSink sink = new AssistantMessageSink();

        String caption = service.captionStreaming(new byte[] {1, 2, 3}, "image/png", pusher, reasoningSeenAny, sink);

        assertEquals("这是一张函数图像", caption);
        var captures = sink.snapshot();
        assertEquals(1, captures.size(), "每次 caption 调用恰好捕获一条");
        var capture = captures.get(0);
        assertEquals(SseEventTransformer.STAGE_ATTACHMENTS, capture.stage());
        assertEquals("识别图中公式与结构", capture.reasoning(), "捕获 thinking 全文（与已推送一致）");
        assertEquals("这是一张函数图像", capture.text(), "捕获描述文本（仅供查看，不渲染为正文）");
        assertTrue(capture.toolCalls().isEmpty(), "caption 工具调用恒空");
    }

    @Test
    @DisplayName("captionStreaming(带 sink) — 流异常路径同样捕获已产出的思考（text 降级 null，不丢行）")
    void captionStreaming_withSink_streamError_capturesPartialThinking() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        when(pusher.accumulated()).thenReturn(Map.of(SseEventTransformer.STAGE_ATTACHMENTS, "思考了一半"));
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.concat(
                        Flux.just(chunk("", "思考了一半")), Flux.error(new IllegalStateException("dashscope 断流"))));
        AssistantMessageSink sink = new AssistantMessageSink();

        assertThrows(
                IllegalStateException.class,
                () -> service.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, sink));

        var captures = sink.snapshot();
        assertEquals(1, captures.size(), "异常路径同样捕获（与取消路径 attachments thinking 行落库语义一致）");
        assertEquals("思考了一半", captures.get(0).reasoning());
        assertNull(captures.get(0).text(), "异常路径 text 降级 null");
    }

    @Test
    @DisplayName("captionStreaming(带 sink) — sink 为 null（ETL 离线链路形态）时行为与四参版本一致，不捕获")
    void captionStreaming_withoutSink_noCapture() {
        ThinkingPusher pusher = mock(ThinkingPusher.class);
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(chunk("描述文本", null)));

        String caption = service.captionStreaming(new byte[] {1}, "image/png", pusher, reasoningSeenAny, null);

        assertEquals("描述文本", caption);
        // 无 sink 不得抛错（null 安全），捕获逻辑整体旁路
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
