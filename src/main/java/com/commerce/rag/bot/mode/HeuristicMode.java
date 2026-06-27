package com.commerce.rag.bot.mode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 启发式问题解答模式
 *
 * 不直接给出答案，而是引导用户分析问题原因，
 * 提供排查思路和解决方向。适合用户遇到报错、bug 等技术问题。
 */
@Slf4j
@Component
public class HeuristicMode {

    private final ChatClient chatClient;

    private static final String PROMPT = """
            你是一个经验丰富的技术导师 AI 助手。用户遇到了技术问题，你需要帮助他分析和解决问题。
            
            回答策略：
            1. 首先分析用户描述的"症状"，推测可能的原因（列出 2-3 个最可能的方向）
            2. 针对每个可能原因，给出具体的排查步骤
            3. 如果知识库中有相关参考信息，引用它来佐证分析
            4. 引导用户逐步排查，而不是一次性给出所有解决方案
            5. 语气耐心、有耐心引导
            
            参考信息（来自知识库，可能为空）：
            {context}
            
            对话历史摘要：
            {history}
            
            用户的问题：{query}
            """;

    public HeuristicMode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 启发式解答
     *
     * @param query   用户问题描述
     * @param context 知识库检索到的相关文档（可为空）
     * @param history 对话历史摘要
     * @return 引导式回答
     */
    public String answer(String query, String context, String history) {
        return chatClient.prompt()
                .user(PROMPT.replace("{query}", query)
                        .replace("{context}", context != null ? context : "暂无相关文档")
                        .replace("{history}", history != null ? history : "无"))
                .call()
                .content();
    }
}
