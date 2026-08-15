package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * CustomSummarizationHook 单元测试 —— Mock ChatModel，验证摘要生成/融合/降级逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class CustomSummarizationHookTest {

    @Mock
    private ChatModel chatModel;

    private CustomSummarizationHook hook;

    @BeforeEach
    void setUp() {
        // 手动构造: maxContextTokens=128000, windowRatio=0.7, threshold=0.7, keepRecent=6, summaryModel=qwen3.7-flash
        hook = new CustomSummarizationHook(chatModel, 128000, 0.7, 0.7, 6, "qwen3.7-flash");
    }

    @Test
    @DisplayName("generateSummary 首次摘要 — 无旧摘要，生成新摘要")
    void generateSummary_noPrevious_generatesNewSummary() {
        // Given
        List<Message> messages = createTestMessages();
        // 先创建 mock 响应（避免在 when().thenReturn() 内部嵌套 stubbing）
        ChatResponse response = mockChatResponse("用户询问了Redis配置方法，我回答了Redis的基本配置步骤。");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        // When
        String result = hook.generateSummary(messages, null);

        // Then: 返回的文本以 "## 对话摘要:" 开头
        assertTrue(result.startsWith("## 对话摘要:"));
        assertFalse(result.contains("摘要待 LLM 生成"));
        assertTrue(result.contains("Redis"));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generateSummary 增量摘要 — 有旧摘要，融合生成")
    void generateSummary_withPrevious_mergesSummaries() {
        // Given
        List<Message> messages = createTestMessages();
        String previousSummary = "用户之前询问了Spring Boot配置。";
        // 先创建 mock 响应（避免在 when().thenReturn() 内部嵌套 stubbing）
        ChatResponse response = mockChatResponse("用户询问了Spring Boot和Redis的配置方法。");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        // When
        String result = hook.generateSummary(messages, previousSummary);

        // Then: 返回融合后的摘要
        assertTrue(result.startsWith("## 对话摘要:"));
        assertFalse(result.contains("摘要待 LLM 生成"));
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("generateSummary 异常 — 降级返回占位摘要")
    void generateSummary_exception_fallbackPlaceholder() {
        // Given
        List<Message> messages = createTestMessages();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM超时"));

        // When
        String result = hook.generateSummary(messages, null);

        // Then: 降级返回占位摘要
        assertTrue(result.startsWith("## 对话摘要:"));
        assertTrue(result.contains("摘要生成失败"));
        // 不应包含占位文本
        assertFalse(result.contains("摘要待 LLM 生成"));
    }

    @Test
    @DisplayName("generateSummary 异常且有旧摘要 — 降级返回占位摘要")
    void generateSummary_exceptionWithPrevious_fallbackPlaceholder() {
        List<Message> messages = createTestMessages();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM超时"));

        String result = hook.generateSummary(messages, "旧摘要内容");

        assertTrue(result.startsWith("## 对话摘要:"));
        assertTrue(result.contains("摘要生成失败"));
    }

    // ==================== 辅助方法 ====================

    /** 创建测试用消息列表 */
    private List<Message> createTestMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("如何配置Redis？"));
        messages.add(new AssistantMessage("Redis配置方法如下：1. 修改redis.conf..."));
        return messages;
    }

    /** 构造 mock ChatResponse，包含指定摘要文本 */
    @SuppressWarnings("unchecked")
    private ChatResponse mockChatResponse(String summaryText) {
        ChatResponse mockResp = mock(ChatResponse.class);
        Generation mockGen = mock(Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage mockMsg =
                mock(org.springframework.ai.chat.messages.AssistantMessage.class);
        when(mockResp.getResult()).thenReturn(mockGen);
        when(mockGen.getOutput()).thenReturn(mockMsg);
        when(mockMsg.getText()).thenReturn(summaryText);
        return mockResp;
    }
}
