package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
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
import org.springframework.ai.chat.messages.SystemMessage;
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

    @Test
    @DisplayName("generateSummary LLM 返回空白文本 — 降级返回占位摘要")
    void generateSummary_blankResponse_usesFallback() {
        List<Message> messages = createTestMessages();
        // 先创建 mock 响应（避免在 when().thenReturn() 内部嵌套 stubbing）
        ChatResponse response = mockChatResponse("   ");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String result = hook.generateSummary(messages, null);

        assertTrue(result.contains("摘要生成失败"));
    }

    // ==================== beforeModel 主流程 ====================

    @Test
    @DisplayName("beforeModel 空消息 → 原样返回，不触发摘要")
    void beforeModel_emptyMessages_returnsAsIs() throws Exception {
        AgentCommand cmd = hook.beforeModel(List.of(), null);

        assertTrue(getMessagesFromCommand(cmd).isEmpty());
        assertEquals(UpdatePolicy.REPLACE, getUpdatePolicyFromCommand(cmd));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("beforeModel token 未达阈值 → 不压缩，原列表直通")
    void beforeModel_belowThreshold_skipsCompression() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("短消息"));

        AgentCommand cmd = hook.beforeModel(messages, null);

        assertSame(messages, getMessagesFromCommand(cmd));
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("beforeModel 达阈值 → 生成摘要并重组：新摘要置顶 + reminder/firstUM 保留 + 旧摘要剔除")
    void beforeModel_aboveThreshold_compressesMessages() throws Exception {
        // 构造超阈值长对话（30 条 × 1 万字符 ≈ 7.5 万 token > 阈值 6.3 万）
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("## 对话摘要:旧摘要内容")); // 旧摘要（应被剔除）
        messages.add(new UserMessage("第一条用户问题")); // firstUM（不在 recent 内，应单独保留）
        messages.add(new SystemMessage("<system-reminder>今日提醒</system-reminder>")); // reminder（应保留）
        for (int i = 0; i < 30; i++) {
            messages.add(new UserMessage("A".repeat(10000) + i));
        }
        // 先创建 mock 响应（避免在 when().thenReturn() 内部嵌套 stubbing）
        ChatResponse response = mockChatResponse("融合后的新摘要");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        AgentCommand cmd = hook.beforeModel(messages, null);

        List<Message> result = getMessagesFromCommand(cmd);
        // 新摘要 SystemMessage 置顶（前缀标记识别）
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertTrue(result.get(0).getText().startsWith("## 对话摘要:"));
        assertTrue(result.get(0).getText().contains("融合后的新摘要"));
        // reminder 保留
        assertTrue(
                result.stream().anyMatch(m -> m.getText() != null && m.getText().contains("<system-reminder>")));
        // firstUM 保留
        assertTrue(
                result.stream().anyMatch(m -> m.getText() != null && m.getText().contains("第一条用户问题")));
        // 旧摘要剔除（recent 内不允许再出现旧摘要文本）
        assertFalse(
                result.stream().anyMatch(m -> m.getText() != null && m.getText().equals("## 对话摘要:旧摘要内容")));
        // 结构：1 新摘要 + 1 reminder + 1 firstUM + keepRecent(6) 条最近消息
        assertEquals(9, result.size());
        // 最近消息在尾部且按时间正序（最后一条是最近消息）
        assertEquals(
                messages.get(messages.size() - 1).getText(),
                result.get(result.size() - 1).getText());
    }

    @Test
    @DisplayName("beforeModel 达阈值但无旧摘要/无 reminder → 仅新摘要 + firstUM + recent")
    void beforeModel_aboveThreshold_withoutOldSummaryAndReminder() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("第一条问题"));
        for (int i = 0; i < 30; i++) {
            messages.add(new UserMessage("B".repeat(10000) + i));
        }
        // 先创建 mock 响应（避免在 when().thenReturn() 内部嵌套 stubbing）
        ChatResponse response = mockChatResponse("新摘要");
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        AgentCommand cmd = hook.beforeModel(messages, null);

        List<Message> result = getMessagesFromCommand(cmd);
        // 1 新摘要 + 1 firstUM（"第一条问题"不在尾部 6 条内）+ 6 recent
        assertEquals(8, result.size());
        assertTrue(result.get(0).getText().startsWith("## 对话摘要:"));
        assertTrue(result.stream().anyMatch(m -> m.getText().contains("第一条问题")));
    }

    // ==================== 辅助方法 ====================

    /** 创建测试用消息列表 */
    private List<Message> createTestMessages() {
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage("如何配置Redis？"));
        messages.add(new AssistantMessage("Redis配置方法如下：1. 修改redis.conf..."));
        return messages;
    }

    /** AgentCommand 内部字段包级可见，通过反射读取（同 ReminderHookTest 用法） */
    @SuppressWarnings("unchecked")
    private List<Message> getMessagesFromCommand(AgentCommand command) throws Exception {
        java.lang.reflect.Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }

    private UpdatePolicy getUpdatePolicyFromCommand(AgentCommand command) throws Exception {
        java.lang.reflect.Field field = AgentCommand.class.getDeclaredField("updatePolicy");
        field.setAccessible(true);
        return (UpdatePolicy) field.get(command);
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
