package com.commerce.rag.service;

import com.commerce.rag.record.ExtractionInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 提取输入组装 —— 会话摘要 SM（如有）+ 最近三轮（User+Assistant）+ 当前 QA（spec §7.6）
 *
 * <p>与 {@link QueryUnderstandingService#buildContext} 统一口径：
 * document/preference 由 interceptor 瞬时注入不落 state，天然无污染；只取 User/Assistant；
 * 当前轮（最后一条 UserMessage 及其后的最终回答）不进 context，只进 current。
 *
 * <p>无状态工具组件，注册为 Spring Bean（{@link MemoryExtractionPipeline} 构造器注入）。
 *
 * @author commerce-rag
 */
@Component
public class MemoryExtractionInputAssembler {

    /** 会话摘要 SystemMessage 前缀标记（与 CustomSummarizationHook.SUMMARY_PREFIX 同值） */
    private static final String SUMMARY_PREFIX = "## 对话摘要:";

    /** 最近进入 context 的对话轮次数（3 轮 = 3 对 User+Assistant，spec §7.6） */
    private static final int RECENT_TURNS = 3;

    /**
     * 组装提取输入
     *
     * @param messages 本次 run 的完整消息列表（自最终 state 读取；可为空）
     * @return 提取输入（contextText=摘要+最近三轮；currentText=当前轮用户提问+助手最终回答）
     */
    public ExtractionInput build(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return new ExtractionInput("", "");
        }
        // 1. 提取 User/Assistant 序列（排除 System/ToolResponse/注入块，spec §7.6 只取 User/Assistant）
        List<Message> turns = messages.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        // 2. 摘要段（识别前缀 SM，剥离标记；如有）
        StringBuilder context = new StringBuilder();
        messages.stream()
                .filter(m -> m instanceof SystemMessage
                        && m.getText() != null
                        && m.getText().startsWith(SUMMARY_PREFIX))
                .findFirst()
                .ifPresent(sm -> context.append("会话摘要:\n")
                        .append(sm.getText().substring(SUMMARY_PREFIX.length()).trim())
                        .append("\n\n"));

        // 3. 最近三轮段：当前轮 = 最后一条 UserMessage 及其后的最终回答，整体截断不进入 context
        //    （拦截器注入块不落 state，天然无污染，只保 User/Assistant 纯对话）
        int currentStart = -1;
        for (int i = turns.size() - 1; i >= 0; i--) {
            if (turns.get(i) instanceof UserMessage) {
                currentStart = i;
                break;
            }
        }
        if (currentStart >= 0) {
            turns = new ArrayList<>(turns.subList(0, currentStart));
        }
        int start = Math.max(0, turns.size() - RECENT_TURNS * 2);
        if (!turns.isEmpty()) {
            context.append("最近对话:\n");
            for (int i = start; i < turns.size(); i++) {
                Message m = turns.get(i);
                context.append(m instanceof UserMessage ? "用户: " : "助手: ")
                        .append(m.getText() == null ? "" : m.getText())
                        .append("\n");
            }
        }

        // 4. 当前对话（最后一条 UserMessage + 其后的 AssistantMessage 最终回答）
        StringBuilder current = new StringBuilder();
        Optional<Message> lastUser = messages.stream()
                .filter(m -> m instanceof UserMessage && m.getText() != null)
                .reduce((a, b) -> b);
        lastUser.ifPresent(m -> current.append("用户: ").append(m.getText()).append("\n"));
        AssistantMessage lastAssistant = lastUserPresent(messages) ? lastAssistantAfter(messages) : null;
        // 固化为局部变量一次性判空：避免同一 getText() 二次调用结果不一致，规避 SpotBugs NP_NULL 告警
        if (lastAssistant != null) {
            String assistantText = lastAssistant.getText();
            if (assistantText != null && !assistantText.isBlank()) {
                current.append("助手: ").append(assistantText);
            }
        }

        return new ExtractionInput(context.toString().trim(), current.toString().trim());
    }

    /** 是否有 UserMessage（current 段以用户提问为必有） */
    private boolean lastUserPresent(List<Message> messages) {
        return messages.stream().anyMatch(m -> m instanceof UserMessage);
    }

    /** 取最后一条 AssistantMessage 文本（最终回答） */
    private AssistantMessage lastAssistantAfter(List<Message> messages) {
        AssistantMessage result = null;
        for (Message m : messages) {
            if (m instanceof AssistantMessage am) {
                result = am;
            }
        }
        return result;
    }
}
