package com.commerce.rag.bot.mode;

import com.commerce.rag.bot.prompt.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 启发式问题解答模式
 *
 * 不直接给出答案，而是引导用户分析问题原因，
 * 提供排查思路和解决方向。适合用户遇到报错、bug 等技术问题。
 * 使用 PromptTemplateService 获取模板，system prompt 静态可缓存。
 */
@Slf4j
@Component
public class HeuristicMode {

    private final ChatClient chatClient;
    private final PromptTemplateService promptService;

    public HeuristicMode(ChatModel chatModel, PromptTemplateService promptService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptService = promptService;
    }

    /**
     * 启发式解答（同步）
     *
     * @param query   用户问题描述
     * @param context 知识库检索到的相关文档（可为空）
     * @param history 对话历史摘要
     * @return 引导式回答
     */
    public String answer(String query, String context, String history) {
        return chatClient.prompt()
                .system(promptService.getSystemPrompt())
                .user(promptService.getUserPromptWithReminder("heuristic-answer",
                        Map.of("query", query,
                                "context", context != null ? context : "暂无相关文档",
                                "history", history != null ? history : "无")))
                .call()
                .content();
    }

    /**
     * 启发式解答（流式）
     *
     * @param query   用户问题描述
     * @param context 知识库检索到的相关文档（可为空）
     * @param history 对话历史摘要
     * @return 文本增量流
     */
    public Flux<String> answerStream(String query, String context, String history) {
        return chatClient.prompt()
                .system(promptService.getSystemPrompt())
                .user(promptService.getUserPromptWithReminder("heuristic-answer",
                        Map.of("query", query,
                                "context", context != null ? context : "暂无相关文档",
                                "history", history != null ? history : "无")))
                .stream()
                .content();
    }
}
