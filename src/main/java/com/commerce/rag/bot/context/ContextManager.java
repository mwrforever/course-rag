package com.commerce.rag.bot.context;

import com.commerce.rag.bot.chat.ChatMessage;
import com.commerce.rag.bot.chat.ChatRepository;
import com.commerce.rag.bot.chat.ChatSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文窗口管理器
 *
 * 负责构建对话上下文、估算 token 用量、触发历史压缩。
 * 核心策略：当上下文 token 达到模型窗口的 70% 时，将旧消息压缩为摘要，
 * 保留最近 N 条消息作为 anchor，摘要存入 ChatSession.contextSummary。
 */
@Slf4j
@Service
public class ContextManager {

    private final ChatRepository chatRepository;
    private final ChatClient summaryClient;

    /** 模型上下文窗口总 token 数 */
    @Value("${context.max-tokens:32000}")
    private int maxTokens;

    /** 压缩触发阈值（0~1），达到此比例时触发压缩 */
    @Value("${context.threshold:0.7}")
    private double threshold;

    /** 压缩后保留的最近消息条数 */
    @Value("${context.keep-recent:6}")
    private int keepRecent;

    private static final String SUMMARY_PROMPT = """
            请将以下对话历史压缩为一段简洁的摘要，保留关键信息点（用户的核心问题、AI 的重要回答结论）。
            摘要长度不超过原文的 30%。直接输出摘要内容，不要加任何前缀。
            
            对话历史：
            {history}
            """;

    public ContextManager(ChatRepository chatRepository, ChatModel chatModel) {
        this.chatRepository = chatRepository;
        this.summaryClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 构建对话上下文，必要时触发压缩
     *
     * @param session        当前会话
     * @param systemPrompt   系统提示词（用于 token 估算）
     * @param estimatedContextTokens 预估的 RAG 检索上下文 token 数
     * @return 构建好的历史上下文文本（可能已被压缩）
     */
    public String buildContext(ChatSession session, String systemPrompt, int estimatedContextTokens) {
        List<ChatMessage> messages = chatRepository.findRecentMessages(session.getId());
        if (messages.isEmpty()) {
            return "";
        }

        // 按时间正序排列（findRecentMessages 返回的是倒序）
        List<ChatMessage> sorted = new ArrayList<>(messages);
        Collections.reverse(sorted);

        // 估算当前 token 用量 = 系统提示 + 历史消息 + 预估检索上下文
        int systemTokens = TokenEstimator.estimate(systemPrompt);
        int historyTokens = sorted.stream()
                .mapToInt(m -> TokenEstimator.estimate(m.getContent()))
                .sum();
        int totalTokens = systemTokens + historyTokens + estimatedContextTokens;

        int thresholdTokens = (int) (maxTokens * threshold);
        log.debug("上下文 token 估算: system={}, history={}, context={}, total={}, threshold={}",
                systemTokens, historyTokens, estimatedContextTokens, totalTokens, thresholdTokens);

        // 未达阈值 -> 直接使用已有摘要 + 最近消息拼接
        if (totalTokens <= thresholdTokens) {
            return buildHistoryText(session.getContextSummary(), sorted);
        }

        // 达到阈值 -> 触发压缩
        log.info("上下文达到压缩阈值: total={} >= threshold={}, sessionId={}",
                totalTokens, thresholdTokens, session.getId());
        return compressAndBuild(session, sorted);
    }

    /**
     * 压缩旧消息为摘要，保留最近 N 条
     */
    private String compressAndBuild(ChatSession session, List<ChatMessage> sortedMessages) {
        // 分离：需要压缩的旧消息 vs 保留的最近消息
        int splitIndex = Math.max(0, sortedMessages.size() - keepRecent);
        List<ChatMessage> toCompress = sortedMessages.subList(0, splitIndex);
        List<ChatMessage> toKeep = sortedMessages.subList(splitIndex, sortedMessages.size());

        // 合并已有摘要 + 新压缩内容
        String existingSummary = session.getContextSummary();
        String newRawHistory = toCompress.stream()
                .map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));

        String toSummarize = (existingSummary != null && !existingSummary.isEmpty())
                ? existingSummary + "\n\n" + newRawHistory
                : newRawHistory;

        // 调用 LLM 生成摘要
        String compressed = summaryClient.prompt()
                .user(SUMMARY_PROMPT.replace("{history}", toSummarize))
                .call()
                .content();

        // 持久化摘要到 session
        chatRepository.updateContextSummary(session.getId(), compressed);
        log.info("上下文压缩完成: sessionId={}, 压缩前消息数={}, 保留消息数={}",
                session.getId(), toCompress.size(), toKeep.size());

        return buildHistoryText(compressed, toKeep);
    }

    /**
     * 拼接历史上下文文本：摘要 + 最近消息
     */
    private String buildHistoryText(String summary, List<ChatMessage> recentMessages) {
        StringBuilder sb = new StringBuilder();

        if (summary != null && !summary.isEmpty()) {
            sb.append("[历史摘要]\n").append(summary).append("\n\n");
        }

        if (!recentMessages.isEmpty()) {
            sb.append("[最近对话]\n");
            for (ChatMessage m : recentMessages) {
                sb.append(m.getRole()).append(": ").append(m.getContent()).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 获取当前配置的最大 token 数（供外部估算用）
     */
    public int getMaxTokens() {
        return maxTokens;
    }
}
