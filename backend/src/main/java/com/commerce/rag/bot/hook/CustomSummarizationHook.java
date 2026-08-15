package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 自定义增量摘要 Hook —— 替代 SAA 内置 SummarizationHook
 *
 * <p>与内置版的区别：
 * <ul>
 *   <li>✓ 显式排除 SM(summary旧) + SM(reminder)，不把它们压进摘要</li>
 *   <li>✓ 增量摘要（融合更新，非每次全量重做），内置版仅全量模式</li>
 *   <li>✓ 使用融合规则提示词（禁止拼接/分为两段），内置版无此约束</li>
 *   <li>✓ 自定义前缀 "## 对话摘要:"，与内置版 "## Previous conversation summary:" 区分</li>
 *   <li>✓ 使用 qwen-turbo 小模型生成摘要，节省成本</li>
 * </ul>
 *
 * <p>降级策略：LLM 调用异常时返回占位摘要，不中断对话流程。
 *
 * @author commerce-rag
 */
@Component
public class CustomSummarizationHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(CustomSummarizationHook.class);

    /** 摘要 SystemMessage 的前缀标记，用于识别旧摘要 */
    private static final String SUMMARY_PREFIX = "## 对话摘要:";

    /** 系统提醒的标记，用于识别 ReminderHook 注入的 SM */
    private static final String REMINDER_MARKER = "<system-reminder>";

    /** 降级时返回的占位摘要 */
    private static final String FALLBACK_SUMMARY = "(摘要生成失败，请参考最近消息)";

    private final ChatModel chatModel;
    private final long maxContextTokens;
    private final double windowRatio;
    private final double contextThreshold;
    private final int keepRecent;
    private final String summaryModel;

    public CustomSummarizationHook(
            ChatModel chatModel,
            @Value("${model.max-context-tokens:128000}") long maxContextTokens,
            @Value("${context.window-ratio:0.7}") double windowRatio,
            @Value("${context.threshold:0.7}") double contextThreshold,
            @Value("${context.keep-recent:6}") int keepRecent,
            @Value("${context.summary-model:qwen3.7-flash}") String summaryModel) {
        this.chatModel = chatModel;
        this.maxContextTokens = maxContextTokens;
        this.windowRatio = windowRatio;
        this.contextThreshold = contextThreshold;
        this.keepRecent = keepRecent;
        this.summaryModel = summaryModel;
    }

    @Override
    public String getName() {
        return "CustomSummarizationHook";
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        if (messages == null || messages.isEmpty()) {
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        // 估算 token 用量（简易：4 字符 ≈ 1 token）
        long estimatedTokens = messages.stream()
                .mapToLong(m -> m.getText() != null ? m.getText().length() / 4 : 0)
                .sum();

        // 压缩目标窗口 = 模型上下文上限 × 安全余量；未达到目标窗口的 threshold 比例，无需压缩
        double contextWindow = maxContextTokens * windowRatio;
        if (estimatedTokens < contextWindow * contextThreshold) {
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        log.info("触发增量摘要压缩: messages={}, estimatedTokens={}", messages.size(), estimatedTokens);

        // 1. 识别旧摘要
        Message oldSummary = findOldSummary(messages);

        // 2. 构建待摘要消息列表（排除旧摘要 + reminder）
        List<Message> toSummarize = filterSummarizable(messages, oldSummary);

        // 3. 生成新摘要（增量融合）
        String previousSummaryText = oldSummary != null ? extractSummaryText(oldSummary) : null;
        String newSummaryText = generateSummary(toSummarize, previousSummaryText);

        // 4. 组装新 messages 列表
        List<Message> newMessages = buildNewMessages(messages, oldSummary, newSummaryText);

        log.info(
                "增量摘要完成: 旧摘要存在={}, 新摘要长度={}, 新消息数={}",
                previousSummaryText != null,
                newSummaryText.length(),
                newMessages.size());

        // 5. AgentCommand：替换全部消息（REPLACE 策略）
        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    /**
     * 识别旧摘要 SystemMessage（通过 summaryPrefix 标记）
     */
    private Message findOldSummary(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof SystemMessage)
                .filter(m -> m.getText() != null && m.getText().startsWith(SUMMARY_PREFIX))
                .findFirst()
                .orElse(null);
    }

    /**
     * 过滤掉不可摘要的消息：旧摘要 + reminder
     */
    private List<Message> filterSummarizable(List<Message> messages, Message oldSummary) {
        List<Message> result = new ArrayList<>();
        for (Message m : messages) {
            if (m == oldSummary) continue;
            if (m.getText() != null && m.getText().contains(REMINDER_MARKER)) continue;
            result.add(m);
        }
        return result;
    }

    /**
     * 提取旧摘要的纯文本（去掉前缀标记）
     */
    private String extractSummaryText(Message oldSummary) {
        String text = oldSummary.getText();
        if (text.startsWith(SUMMARY_PREFIX)) {
            return text.substring(SUMMARY_PREFIX.length()).trim();
        }
        return text;
    }

    /**
     * 生成增量摘要 —— 调用 LLM (qwen-turbo) 生成/融合摘要
     *
     * <p>流程：
     * <ol>
     *   <li>构建 DashScopeChatOptions 指定 summaryModel（qwen-turbo）</li>
     *   <li>构建 Prompt：SystemMessage（摘要指令）+ UserMessage（待摘要消息 + 旧摘要）</li>
     *   <li>调用 chatModel.call(prompt) 获取摘要文本</li>
     *   <li>返回 SUMMARY_PREFIX + summaryText</li>
     * </ol>
     *
     * <p>降级：异常时返回 SUMMARY_PREFIX + FALLBACK_SUMMARY
     *
     * @param messages         待摘要的消息列表
     * @param previousSummary  旧摘要文本（null 表示首次摘要）
     * @return 带前缀标记的摘要文本
     */
    String generateSummary(List<Message> messages, String previousSummary) {
        try {
            // 1. 构建 DashScopeChatOptions 指定小模型
            DashScopeChatOptions options =
                    DashScopeChatOptions.builder().withModel(summaryModel).build();

            // 2. 构建消息列表
            List<Message> promptMessages = new ArrayList<>();

            // SystemMessage: 摘要指令（区分首次/增量）
            String systemPrompt;
            if (previousSummary != null) {
                systemPrompt = "你是一个对话摘要专家。请将以下旧摘要与新对话内容融合为一份更新后的摘要。"
                        + "要求：\n"
                        + "1. 以第三人称叙述（如\"用户询问了…\"，而非\"你说了…\"）\n"
                        + "2. 保留关键信息（用户意图、重要决策、技术结论、未完成任务）\n"
                        + "3. 删除冗余和重复内容\n"
                        + "4. 融合为一段连续文本，不要分为两段（不要分别输出旧摘要和新内容）\n"
                        + "5. 禁止来源标记（不要标注内容来自旧摘要还是新对话）\n"
                        + "6. 必须保留课程数据（课程名称、价格、排期等）与诊断结论\n"
                        + "7. 可丢弃寒暄、技术细节和过期信息\n"
                        + "8. 总长度不超过800字\n"
                        + "直接输出摘要内容，不要添加额外说明。";
            } else {
                systemPrompt = "你是一个对话摘要专家。请将以下对话内容压缩为简洁摘要。"
                        + "要求：\n"
                        + "1. 以第三人称叙述（如\"用户询问了…\"，而非\"你说了…\"）\n"
                        + "2. 保留关键信息和上下文（用户意图、重要决策、技术结论）\n"
                        + "3. 删除冗余内容\n"
                        + "4. 输出为一段连续文本\n"
                        + "5. 总长度不超过800字\n"
                        + "直接输出摘要内容，不要添加额外说明。";
            }
            promptMessages.add(new SystemMessage(systemPrompt));

            // UserMessage: 待摘要内容 + 旧摘要（如果有）
            StringBuilder userContent = new StringBuilder();
            if (previousSummary != null) {
                userContent.append("【旧摘要】\n").append(previousSummary).append("\n\n");
            }
            userContent.append("【新对话内容】\n");
            for (Message m : messages) {
                if (m.getText() != null) {
                    userContent.append(m.getText()).append("\n");
                }
            }
            promptMessages.add(new UserMessage(userContent.toString()));

            // 3. 调用 LLM 生成摘要
            Prompt prompt = new Prompt(promptMessages, options);
            ChatResponse response = chatModel.call(prompt);
            String summaryText = response.getResult().getOutput().getText();

            if (summaryText == null || summaryText.isBlank()) {
                log.warn("LLM 返回空摘要，使用降级占位");
                return SUMMARY_PREFIX + FALLBACK_SUMMARY;
            }

            log.info("摘要生成成功: 模型={}, 长度={}", summaryModel, summaryText.length());
            return SUMMARY_PREFIX + summaryText.trim();

        } catch (Exception e) {
            log.warn("摘要生成异常(降级): {}", e.getMessage());
            return SUMMARY_PREFIX + FALLBACK_SUMMARY;
        }
    }

    /**
     * 构建新 messages 列表
     *
     * <p>拼装顺序（对照设计 §2.8）：
     * <pre>
     * [SM(摘要新), SM(reminder)?, firstUM?, ...recent]
     * </pre>
     * SM(base) 不在 messages 中（由 ModelRequest.systemMessage 独立字段承载），无需处理。
     * firstUM：从 messages 找第一条 UserMessage，若不在 recent N 条内则插入。
     *
     * @param messages       原始消息列表
     * @param oldSummary     旧摘要（已排除）
     * @param newSummaryText 新摘要文本
     * @return 拼装后的新消息列表
     */
    private List<Message> buildNewMessages(List<Message> messages, Message oldSummary, String newSummaryText) {
        List<Message> newMessages = new ArrayList<>();

        // 1. 新摘要 SystemMessage（放第一位，LLM 最先看到）
        newMessages.add(new SystemMessage(newSummaryText));

        // 2. 保留 reminder（如果有）
        messages.stream()
                .filter(m -> m.getText() != null && m.getText().contains(REMINDER_MARKER))
                .findFirst()
                .ifPresent(newMessages::add);

        // 3. 保留最近 N 条非摘要、非 reminder 消息
        List<Message> recentMessages = new ArrayList<>();
        int keepCount = 0;
        for (int i = messages.size() - 1; i >= 0 && keepCount < keepRecent; i--) {
            Message m = messages.get(i);
            if (m == oldSummary) continue;
            if (m.getText() != null && m.getText().contains(REMINDER_MARKER)) continue;
            recentMessages.add(m);
            keepCount++;
        }

        // 4. 查找第一条 UserMessage（firstUM），若不在 recent 列表中则单独插入
        Message firstUserMessage = messages.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst()
                .orElse(null);

        boolean firstUMInRecent = firstUserMessage != null && recentMessages.contains(firstUserMessage);

        // 按时间顺序排列 recent（之前是逆序收集的）
        Collections.reverse(recentMessages);

        if (firstUserMessage != null && !firstUMInRecent) {
            // firstUM 不在 recent N 条内，插入到 reminder 之后、recent 之前
            newMessages.add(firstUserMessage);
        }

        // 5. 追加 recent 消息
        newMessages.addAll(recentMessages);

        return newMessages;
    }
}
