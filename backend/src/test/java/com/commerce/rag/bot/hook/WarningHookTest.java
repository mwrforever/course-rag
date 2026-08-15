package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.commerce.rag.config.LoopDetectionProperties;
import com.commerce.rag.config.TokenBudgetProperties;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * WarningHook 单元测试 —— F#3 死循环/Token 预算防护层 Mock 测试
 *
 * <p>测试三套检测逻辑：Token Budget、Loop Hash、Loop Tool Frequency，
 * 以及软停行为、优先级、自然结束清理、beforeModel 告警注入。
 *
 * <p>注意事项：
 * <ul>
 *   <li>WarningHook 继承 MessagesModelHook，测试中 getAgent() 返回 null，
 *       告警降级写入 fallbackWarnings（私有字段，通过反射读取）</li>
 *   <li>AgentCommand.getMessages() 包级可见，通过反射访问</li>
 *   <li>使用 lenient() 避免 UnnecessaryStubbingException</li>
 * </ul>
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class WarningHookTest {

    /** 被测 Hook 实例 */
    private WarningHook hook;

    /** Mock RunnableConfig，threadId 固定为 "test-thread-1" */
    private RunnableConfig config;

    @BeforeEach
    void setUp() {
        // 构造小阈值配置便于测试
        // hash: windowSize=5, warn=2, hardStop=3
        // perTool: default warn=3 hardStop=5; searchKnowledge override warn=5 hardStop=8; listCourses override warn=2
        // hardStop=4
        LoopDetectionProperties loopProps = new LoopDetectionProperties(
                new LoopDetectionProperties.HashConfig(5, 2, 3),
                new LoopDetectionProperties.PerToolConfig(
                        new LoopDetectionProperties.ToolThreshold(3, 5),
                        Map.of(
                                "searchKnowledge", new LoopDetectionProperties.ToolThreshold(5, 8),
                                "listCourses", new LoopDetectionProperties.ToolThreshold(2, 4))));
        // 小预算便于测试：maxTokens=1000, warnRatio=0.8(->800), hardStopRatio=1.0(->1000)
        TokenBudgetProperties tokenProps = new TokenBudgetProperties(1000L, 0.8, 1.0);
        hook = new WarningHook(loopProps, tokenProps);

        // Mock RunnableConfig —— lenient 避免 beforeModel 测试中未调用时报错
        config = mock(RunnableConfig.class);
        lenient().when(config.threadId()).thenReturn(Optional.of("test-thread-1"));
    }

    // ==================== afterModel 基础测试 ====================

    @Test
    @DisplayName("afterModel 空消息列表 — 返回空 messages")
    void afterModel_emptyMessages_returnsEmpty() throws Exception {
        AgentCommand cmd = hook.afterModel(List.of(), config);
        List<Message> messages = getMessagesFromCommand(cmd);
        assertNotNull(messages);
        assertTrue(messages.isEmpty());
    }

    @Test
    @DisplayName("afterModel 末尾非 AssistantMessage — 返回原 messages 不变")
    void afterModel_nonAssistantLast_returnsOriginal() throws Exception {
        List<Message> messages = List.of(new UserMessage("用户提问"));
        AgentCommand cmd = hook.afterModel(messages, config);
        List<Message> result = getMessagesFromCommand(cmd);
        assertEquals(1, result.size());
        assertInstanceOf(UserMessage.class, result.get(0));
    }

    // ==================== Loop Hash 检测测试 ====================

    @Test
    @DisplayName("Loop Hash 告警 — 同一内容调用 2 次触发告警（warn=2）")
    void loopHash_warn_sameContentTwice() throws Exception {
        // 使用带 toolCalls 的 AssistantMessage，避免无 toolCalls 时触发自然结束清理
        // 工具名 "hashTool" 不在 overrides 中，走默认阈值 warn=3，2 次调用不会触发工具告警
        // 第 1 次：hash count=1，无告警
        hook.afterModel(List.of(new UserMessage("q"), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        assertTrue(getFallbackWarnings(hook).isEmpty(), "第 1 次不应有告警");

        // 第 2 次：hash count=2，触发告警（hash warn=2）
        hook.afterModel(List.of(new UserMessage("q"), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        List<String> warnings = getFallbackWarnings(hook);
        assertFalse(warnings.isEmpty(), "第 2 次应触发 hash 告警");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("hash")), "告警内容应包含 hash");
    }

    @Test
    @DisplayName("Loop Hash 硬停 — 同一内容调用 3 次触发软停（hardStop=3）")
    void loopHash_hardStop_sameContentThreeTimes() throws Exception {
        // 使用带 toolCalls 的 AssistantMessage，避免自然结束清理导致状态丢失
        // 前 2 次：告警，无硬停
        for (int i = 0; i < 2; i++) {
            hook.afterModel(List.of(new UserMessage("q"), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        }

        // 第 3 次：hash count=3，硬停（hash hardStop=3）
        AgentCommand cmd =
                hook.afterModel(List.of(new UserMessage("q"), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        List<Message> result = getMessagesFromCommand(cmd);

        // 验证：最后一条消息含 [FORCED STOP]
        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        assertTrue(((AssistantMessage) lastMsg).getText().contains("[FORCED STOP]"), "应含 [FORCED STOP] 标记");
    }

    // ==================== Loop Tool Freq 检测测试 ====================

    @Test
    @DisplayName("Loop Tool Freq 告警 — 默认阈值工具调用 3 次触发告警（default warn=3）")
    void loopToolFreq_warn_defaultThreshold() throws Exception {
        // 使用不在 overrides 中的工具名，走默认阈值 warn=3
        for (int i = 0; i < 3; i++) {
            AssistantMessage msg = buildAssistantWithToolCall("defaultTool", "调用" + i);
            hook.afterModel(List.of(new UserMessage("q"), msg), config);
        }
        List<String> warnings = getFallbackWarnings(hook);
        assertFalse(warnings.isEmpty(), "第 3 次应触发工具频率告警");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("defaultTool")), "告警内容应包含工具名 defaultTool");
    }

    @Test
    @DisplayName("Loop Tool Freq 硬停 — 默认阈值工具调用 5 次触发软停（default hardStop=5）")
    void loopToolFreq_hardStop_defaultThreshold() throws Exception {
        // 前 4 次：告警，无硬停
        for (int i = 0; i < 4; i++) {
            AssistantMessage msg = buildAssistantWithToolCall("defaultTool", "调用" + i);
            hook.afterModel(List.of(new UserMessage("q"), msg), config);
        }

        // 第 5 次：硬停
        AssistantMessage msg = buildAssistantWithToolCall("defaultTool", "调用4");
        AgentCommand cmd = hook.afterModel(List.of(new UserMessage("q"), msg), config);
        List<Message> result = getMessagesFromCommand(cmd);

        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        assertTrue(((AssistantMessage) lastMsg).getText().contains("[FORCED STOP]"), "应含 [FORCED STOP] 标记");
    }

    @Test
    @DisplayName("Loop Tool Freq override — listCourses 调用 2 次触发告警（override warn=2）")
    void loopToolFreq_override_listCourses() throws Exception {
        // listCourses override: warn=2, hardStop=4
        // 第 1 次：count=1，无告警
        AssistantMessage msg1 = buildAssistantWithToolCall("listCourses", "查询1");
        hook.afterModel(List.of(new UserMessage("q"), msg1), config);
        assertTrue(getFallbackWarnings(hook).isEmpty(), "第 1 次不应有告警");

        // 第 2 次：count=2，触发告警（override warn=2）
        AssistantMessage msg2 = buildAssistantWithToolCall("listCourses", "查询2");
        hook.afterModel(List.of(new UserMessage("q"), msg2), config);
        List<String> warnings = getFallbackWarnings(hook);
        assertFalse(warnings.isEmpty(), "第 2 次应触发 listCourses 告警");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("listCourses")), "告警内容应包含 listCourses");
    }

    // ==================== Token Budget 检测测试 ====================

    @Test
    @DisplayName("Token Budget 告警 — token 估算超过 800（warnRatio=0.8）")
    void tokenBudget_warn_exceedsWarnThreshold() throws Exception {
        // 3600 字符 / 4 = 900 tokens（800 < 900 < 1000）
        String longText = "a".repeat(3600);
        List<Message> msgs = List.of(new UserMessage(longText), new AssistantMessage("回答"));
        hook.afterModel(msgs, config);

        List<String> warnings = getFallbackWarnings(hook);
        assertFalse(warnings.isEmpty(), "应触发 Token 告警");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Token")), "告警内容应包含 Token");
    }

    @Test
    @DisplayName("Token Budget 硬停 — token 估算超过 1000（hardStopRatio=1.0）")
    void tokenBudget_hardStop_exceedsMaxTokens() throws Exception {
        // 4000 字符 / 4 = 1000 tokens + "回答" 2 tokens = 1002 > 1000
        String longText = "a".repeat(4000);
        List<Message> msgs = List.of(new UserMessage(longText), new AssistantMessage("回答"));
        AgentCommand cmd = hook.afterModel(msgs, config);
        List<Message> result = getMessagesFromCommand(cmd);

        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        String text = ((AssistantMessage) lastMsg).getText();
        assertTrue(text.contains("[FORCED STOP]"), "应含 [FORCED STOP] 标记");
        assertTrue(text.contains("Token"), "软停原因应包含 Token");
    }

    // ==================== 优先级测试 ====================

    @Test
    @DisplayName("优先级 — Token 硬停优先于 Loop Hash 硬停")
    void priority_tokenHardStop_overLoopHash() throws Exception {
        // 使用带 toolCalls 的 AssistantMessage，避免自然结束清理导致状态丢失
        // 前 2 次用短消息，累积 hash count=2（触发 warn），不触发 Token
        for (int i = 0; i < 2; i++) {
            hook.afterModel(List.of(new UserMessage("q"), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        }

        // 第 3 次：长消息触发 Token 硬停，同时 hash count=3 也会硬停
        // 但 Token 优先级更高，应先触发
        String longText = "a".repeat(4000);
        AgentCommand cmd = hook.afterModel(
                List.of(new UserMessage(longText), buildAssistantWithToolCall("hashTool", "重复内容")), config);
        List<Message> result = getMessagesFromCommand(cmd);

        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        String text = ((AssistantMessage) lastMsg).getText();
        assertTrue(text.contains("[FORCED STOP]"), "应含 [FORCED STOP] 标记");
        assertTrue(text.contains("Token"), "软停原因应含 Token（优先级高于 Loop Hash）");
        assertFalse(text.contains("hash"), "不应含 hash 原因（Token 优先级更高）");
    }

    // ==================== 软停行为测试 ====================

    @Test
    @DisplayName("软停后 toolCalls 被清空")
    void softStop_toolCallsCleared() throws Exception {
        // 构造带 toolCalls 的 AssistantMessage + 长消息触发 Token 硬停
        AssistantMessage msg = buildAssistantWithToolCall("anyTool", "回答");
        String longText = "a".repeat(4000);
        List<Message> msgs = List.of(new UserMessage(longText), msg);

        AgentCommand cmd = hook.afterModel(msgs, config);
        List<Message> result = getMessagesFromCommand(cmd);

        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        assertFalse(((AssistantMessage) lastMsg).hasToolCalls(), "软停后 toolCalls 应被清空");
    }

    @Test
    @DisplayName("软停后 finish_reason = stop")
    void softStop_finishReasonIsStop() throws Exception {
        // 触发 Token 硬停
        AssistantMessage msg = buildAssistantWithToolCall("anyTool", "回答");
        String longText = "a".repeat(4000);
        List<Message> msgs = List.of(new UserMessage(longText), msg);

        AgentCommand cmd = hook.afterModel(msgs, config);
        List<Message> result = getMessagesFromCommand(cmd);

        Message lastMsg = result.get(result.size() - 1);
        assertInstanceOf(AssistantMessage.class, lastMsg);
        AssistantMessage assistantMsg = (AssistantMessage) lastMsg;
        assertEquals("stop", assistantMsg.getMetadata().get("finish_reason"), "finish_reason 应为 stop");
    }

    // ==================== 自然结束清理测试 ====================

    @Test
    @DisplayName("自然结束清理 — AssistantMessage 无 toolCalls 后状态被清理")
    void naturalEndCleanup_stateClearedAfterNoToolCalls() throws Exception {
        // 第 1 次：AssistantMessage 无 toolCalls → 状态被清理
        hook.afterModel(List.of(new UserMessage("q"), new AssistantMessage("内容A")), config);
        assertTrue(getFallbackWarnings(hook).isEmpty(), "第 1 次不应有告警");

        // 第 2 次：相同内容，但状态已清理 → hash count=1，无告警
        // 若状态未清理，hash count=2 会触发告警（warn=2）
        hook.afterModel(List.of(new UserMessage("q"), new AssistantMessage("内容A")), config);
        assertTrue(getFallbackWarnings(hook).isEmpty(), "第 2 次不应有告警（状态已被清理，hash count 重新从 1 开始）");
    }

    // ==================== beforeModel 测试 ====================

    @Test
    @DisplayName("beforeModel 无告警 — 返回原 messages 不变")
    void beforeModel_noWarnings_returnsOriginal() throws Exception {
        List<Message> messages = List.of(new UserMessage("用户提问"));
        AgentCommand cmd = hook.beforeModel(messages, config);
        List<Message> result = getMessagesFromCommand(cmd);
        assertEquals(1, result.size());
        assertEquals("用户提问", result.get(0).getText());
    }

    @Test
    @DisplayName("beforeModel 有告警 — 注入告警 UserMessage")
    void beforeModel_withWarnings_injectsUserMessage() throws Exception {
        // 先写入告警到 fallbackWarnings
        hook.addWarningFallback("测试告警");

        List<Message> messages = List.of(new UserMessage("用户提问"));
        AgentCommand cmd = hook.beforeModel(messages, config);
        List<Message> result = getMessagesFromCommand(cmd);

        // 验证：原消息 + 1 条注入的告警消息
        assertEquals(2, result.size(), "应含原始消息 + 1 条告警消息");
        assertEquals("用户提问", result.get(0).getText());

        // 注入的消息应为 UserMessage，内容含 "⚠️ [" 和告警文本
        Message injected = result.get(1);
        assertInstanceOf(UserMessage.class, injected);
        assertTrue(injected.getText().contains("⚠️ ["), "应含告警前缀");
        assertTrue(injected.getText().contains("测试告警"), "应含告警内容");

        // 验证 fallbackWarnings 已被排空
        assertTrue(getFallbackWarnings(hook).isEmpty(), "drainWarnings 后 fallbackWarnings 应为空");
    }

    // ==================== 辅助方法 ====================

    /**
     * 构造带单个 ToolCall 的 AssistantMessage
     *
     * @param toolName 工具名称
     * @param content  消息文本内容
     * @return AssistantMessage 实例
     */
    private AssistantMessage buildAssistantWithToolCall(String toolName, String content) {
        AssistantMessage.ToolCall toolCall =
                new AssistantMessage.ToolCall("call-1", "function", toolName, "{\"q\":\"test\"}");
        return AssistantMessage.builder()
                .content(content)
                .toolCalls(List.of(toolCall))
                .build();
    }

    /**
     * 通过反射获取 AgentCommand 中的 messages 列表
     * （getMessages() 为包级可见，跨包需反射访问）
     *
     * @param command AgentCommand 实例
     * @return 消息列表
     */
    @SuppressWarnings("unchecked")
    private List<Message> getMessagesFromCommand(AgentCommand command) throws Exception {
        Method method = AgentCommand.class.getDeclaredMethod("getMessages");
        method.setAccessible(true);
        return (List<Message>) method.invoke(command);
    }

    /**
     * 通过反射获取 WarningHook 的 fallbackWarnings 列表
     * （私有字段，需反射访问）
     *
     * @param hook WarningHook 实例
     * @return fallbackWarnings 列表
     */
    @SuppressWarnings("unchecked")
    private List<String> getFallbackWarnings(WarningHook hook) throws Exception {
        Field field = WarningHook.class.getDeclaredField("fallbackWarnings");
        field.setAccessible(true);
        return (List<String>) field.get(hook);
    }
}
