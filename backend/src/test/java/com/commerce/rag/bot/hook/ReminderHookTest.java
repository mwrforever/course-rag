package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.commerce.rag.bot.graph.PromptLoader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * ReminderHook 单元测试 —— 动态上下文注入（新注入 / 替换旧 reminder）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReminderHook 动态上下文注入测试")
class ReminderHookTest {

    @Mock
    private PromptLoader promptLoader;

    private ReminderHook hook;

    @BeforeEach
    void setUp() {
        hook = new ReminderHook(promptLoader);
        // hook 未 attach agent 时 getRewrittenQueries 短路返回空列表，reminder 文本固定；
        // lenient：getName 等不触发 beforeModel 的用例不消费此桩
        lenient()
                .when(promptLoader.loadRawAndReplace(eq("dynamic-context.yml"), anyMap()))
                .thenReturn("<system-reminder>当前时间: 2026-08-15</system-reminder>");
    }

    @Test
    @DisplayName("beforeModel → 无已有 reminder 时插入到最后一个 SystemMessage 之后")
    void beforeModel_noReminder_insertsAfterLastSystemMessage() throws Exception {
        List<Message> messages = List.of(new SystemMessage("base prompt"), new UserMessage("你好"));

        AgentCommand command = hook.beforeModel(messages, mock(RunnableConfig.class));

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(3, result.size());
        // 顺序：base SM → reminder SM → 用户消息
        assertInstanceOf(SystemMessage.class, result.get(0));
        assertTrue(result.get(1).getText().contains(ReminderHook.REMINDER_MARKER));
        assertInstanceOf(UserMessage.class, result.get(2));
    }

    @Test
    @DisplayName("beforeModel → 已有 reminder 时原位替换")
    void beforeModel_existingReminder_replacesInPlace() throws Exception {
        List<Message> messages = List.of(
                new SystemMessage("base"),
                new SystemMessage("<system-reminder>旧提醒</system-reminder>"),
                new UserMessage("你好"));

        AgentCommand command = hook.beforeModel(messages, mock(RunnableConfig.class));

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(3, result.size());
        // 旧 reminder 被替换为新内容
        assertEquals(
                "<system-reminder>当前时间: 2026-08-15</system-reminder>",
                result.get(1).getText());
        // 其余消息保持原顺序
        assertEquals("base", result.get(0).getText());
        assertInstanceOf(UserMessage.class, result.get(2));
    }

    @Test
    @DisplayName("beforeModel → 无 SystemMessage 时插入到最前")
    void beforeModel_noSystemMessage_insertsAtFront() throws Exception {
        List<Message> messages = List.of(new UserMessage("你好"));

        AgentCommand command = hook.beforeModel(messages, mock(RunnableConfig.class));

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getText().contains(ReminderHook.REMINDER_MARKER));
    }

    @Test
    @DisplayName("beforeModel → 线程状态含 rewrittenQueries 时按编号展开并传入模板，时间按格式输出")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void beforeModel_rewrittenQueriesPresent_numberedQueriesInReminder() throws Exception {
        // 模拟 agent 已 attach 且线程状态中携带 2 条重写查询
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.getThreadState("test-thread-1")).thenReturn(Map.of("rewrittenQueries", List.of("查询A", "查询B")));
        hook.setAgent(agent);

        RunnableConfig config = mock(RunnableConfig.class);
        when(config.threadId()).thenReturn(Optional.of("test-thread-1"));

        AgentCommand command = hook.beforeModel(List.of(new UserMessage("你好")), config);

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getText().contains(ReminderHook.REMINDER_MARKER));

        // 断言传给模板的占位符映射：rewritten_queries 按 "1. 查询A\n2. 查询B" 展开
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptLoader).loadRawAndReplace(eq("dynamic-context.yml"), captor.capture());
        Map<String, String> replacements = captor.getValue();
        assertEquals("1. 查询A\n2. 查询B", replacements.get("rewritten_queries"));
        // 时间参数按 yyyy-MM-dd HH:mm:ss Z 格式化（如 2026-08-16 10:00:00 +0800）
        assertTrue(
                replacements.get("current_time").matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} [+-]\\d{4}"),
                "current_time 应按 yyyy-MM-dd HH:mm:ss Z 格式输出");
    }

    @Test
    @DisplayName("beforeModel → 线程状态缺失 rewrittenQueries 时注入空查询提醒，不阻断")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void beforeModel_rewrittenQueriesMissing_emptyQueriesText() throws Exception {
        // 线程状态存在但不含 rewrittenQueries 键
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.getThreadState("test-thread-1")).thenReturn(Map.of());
        hook.setAgent(agent);

        RunnableConfig config = mock(RunnableConfig.class);
        when(config.threadId()).thenReturn(Optional.of("test-thread-1"));

        AgentCommand command = hook.beforeModel(List.of(new UserMessage("你好")), config);

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(2, result.size());

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(promptLoader).loadRawAndReplace(eq("dynamic-context.yml"), captor.capture());
        assertEquals("", captor.getValue().get("rewritten_queries"), "无重写查询时查询文本应为空串");
    }

    @Test
    @DisplayName("beforeModel → 线程状态读取异常时降级为空查询，提醒正常注入")
    void beforeModel_threadStateReadFails_degradesToEmptyQueries() throws Exception {
        ReactAgent agent = mock(ReactAgent.class);
        when(agent.getThreadState("test-thread-1")).thenThrow(new RuntimeException("state unavailable"));
        hook.setAgent(agent);

        RunnableConfig config = mock(RunnableConfig.class);
        when(config.threadId()).thenReturn(Optional.of("test-thread-1"));

        AgentCommand command = hook.beforeModel(List.of(new UserMessage("你好")), config);

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(2, result.size());
        assertTrue(result.get(0).getText().contains(ReminderHook.REMINDER_MARKER));
    }

    @Test
    @DisplayName("getName → 返回 ReminderHook 标识")
    void getName_returnsHookName() {
        assertEquals("ReminderHook", hook.getName());
    }

    /** AgentCommand.getMessages() 包级可见，通过反射读取（同 WarningHookTest 用法） */
    @SuppressWarnings("unchecked")
    private List<Message> getMessagesFromCommand(AgentCommand command) throws Exception {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}
