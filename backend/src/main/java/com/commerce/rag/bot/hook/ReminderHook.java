package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.commerce.rag.bot.graph.PromptLoader;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

/**
 * 动态上下文注入 Hook —— 每 turn 注入当前时间提醒
 *
 * <p><b>职责：</b>双通道设计中的"动态通道"——静态通道 (systemPrompt) 在 builder 设置一次，
 * 此 Hook 负责每 turn 变化的内容（当前时间）。S1 检索链路重构后，重写查询由
 * RetrieveNode 消费，agent 无检索工具、无需感知，提醒仅保留时间语义。
 *
 * <p>注入方式：SystemMessage 带 {@code <system-reminder>} 包裹层，
 * 与静态 SystemMessage(base) 区分开 —— CustomSummarizationHook 和 CoalescingInterceptor
 * 通过此标记识别并排除它。
 *
 * <p><b>重要：</b>此 Hook 是 MessagesModelHook（持久，改 State），非 Interceptor（瞬时）。
 * 注入的 reminder SM 会进入 checkpoint。
 *
 * @author commerce-rag
 */
@Component
public class ReminderHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(ReminderHook.class);

    /** system-reminder 标记 —— 供 CustomSummarizationHook 识别并排除 */
    public static final String REMINDER_MARKER = "<system-reminder>";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    private final PromptLoader promptLoader;

    public ReminderHook(PromptLoader promptLoader) {
        this.promptLoader = promptLoader;
    }

    @Override
    public String getName() {
        return "ReminderHook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        // 1. 构建 system-reminder 文本（S1 检索链路重构：重写查询由 RetrieveNode 消费，
        //    agent 无检索工具、无需感知，提醒仅保留当前时间语义）
        String reminderText = buildReminderText();

        // 2. 检查是否已存在 reminder（避免重复注入）
        boolean hasReminder = messages.stream()
                .anyMatch(m -> m.getText() != null && m.getText().contains(REMINDER_MARKER));

        if (hasReminder) {
            // 替换旧 reminder
            List<Message> newMessages = new ArrayList<>();
            boolean replaced = false;
            for (Message m : messages) {
                if (!replaced && m.getText() != null && m.getText().contains(REMINDER_MARKER)) {
                    newMessages.add(new SystemMessage(reminderText));
                    replaced = true;
                } else {
                    newMessages.add(m);
                }
            }
            log.debug("已更新 system-reminder（当前时间提醒）");
            return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
        }

        // 3. 注入新 reminder（放在 messages 开头，紧接 SystemMessage(base) 之后）
        List<Message> newMessages = new ArrayList<>(messages);
        // 找到最后一个 SystemMessage 的位置，在其后插入
        int insertPos = 0;
        for (int i = 0; i < newMessages.size(); i++) {
            if (newMessages.get(i) instanceof SystemMessage) {
                insertPos = i + 1;
            }
        }
        newMessages.add(insertPos, new SystemMessage(reminderText));

        log.debug("已注入 system-reminder（当前时间提醒）");
        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    /**
     * 构建 system-reminder 文本 —— 使用 dynamic-context.yml 模板 + 当前时间占位符替换
     *
     * <p>通过 {@link PromptLoader#loadRawAndReplace} 加载 prompts/dynamic-context.yml 模板，
     * 替换 ${current_time} 占位符（模板已无 rewritten_queries 占位符）。
     */
    private String buildReminderText() {
        String currentTime = ZonedDateTime.now().format(TIME_FORMATTER);

        // 使用 PromptLoader 加载原始模板（loadRaw，不加 key 前缀）并替换占位符
        return promptLoader.loadRawAndReplace("dynamic-context.yml", Map.of("current_time", currentTime));
    }
}
