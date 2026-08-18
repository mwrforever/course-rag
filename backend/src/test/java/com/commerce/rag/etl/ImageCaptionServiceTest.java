package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

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
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2));
        service = new ImageCaptionService(chatModel, promptLoader, props);
    }

    @Test
    @DisplayName("caption — 图片以 Media 传入（base64 data URL 路径），模型名按次覆盖为 caption 模型")
    void caption_sendsMediaAndCaptionModel() {
        AssistantMessage output = new AssistantMessage("这是图片描述内容");
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(output));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String caption = service.caption(new byte[] {1, 2, 3}, "image/png");

        assertEquals("这是图片描述内容", caption);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        Prompt prompt = captor.getValue();
        // 消息序列：SystemMessage + UserMessage（带 1 个 Media）
        assertEquals(2, prompt.getInstructions().size());
        UserMessage user = (UserMessage) prompt.getInstructions().get(1);
        assertEquals(1, user.getMedia().size());
        // 模型名按次覆盖（DashScopeChatOptions.model = caption 模型）
        assertTrue(prompt.getOptions() != null);
    }

    @Test
    @DisplayName("caption — 模型调用失败上抛（调用方按图片跳过）")
    void caption_failure_throws() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("dashscope 不可用"));

        assertThrows(RuntimeException.class, () -> service.caption(new byte[] {1}, "image/png"));
    }
}
