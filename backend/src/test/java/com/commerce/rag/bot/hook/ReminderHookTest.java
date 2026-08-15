package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.commerce.rag.bot.graph.PromptLoader;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
        // hook 未 attach agent 时 getRewrittenQueries 短路返回空列表，reminder 文本固定
        when(promptLoader.loadRawAndReplace(eq("dynamic-context.yml"), anyMap()))
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
        List<Message> messages =
                List.of(new SystemMessage("base"), new SystemMessage("<system-reminder>旧提醒</system-reminder>"), new UserMessage("你好"));

        AgentCommand command = hook.beforeModel(messages, mock(RunnableConfig.class));

        List<Message> result = getMessagesFromCommand(command);
        assertEquals(3, result.size());
        // 旧 reminder 被替换为新内容
        assertEquals("<system-reminder>当前时间: 2026-08-15</system-reminder>", result.get(1).getText());
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

    /** AgentCommand.getMessages() 包级可见，通过反射读取（同 WarningHookTest 用法） */
    @SuppressWarnings("unchecked")
    private List<Message> getMessagesFromCommand(AgentCommand command) throws Exception {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}
