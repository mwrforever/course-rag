package com.commerce.rag.bot.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * 意图路由器
 *
 * 根据用户消息自动判断意图类型，路由到对应的对话模式。
 * - COURSE_INFO: 课程相关询问
 * - HEURISTIC: 技术问题求助
 * - QA: 知识库问答
 */
@Slf4j
@Service
public class IntentRouter {

    private final ChatClient chatClient;

    private static final String ROUTER_PROMPT = """
            你是一个意图分类器。根据用户消息判断其意图类型，只返回以下三个值之一：
            
            - COURSE_INFO: 用户在询问课程相关信息（课程介绍、课程推荐、课程内容、课程大纲、报名等）
            - HEURISTIC: 用户遇到技术问题需要帮助（报错、bug、"怎么用不了"、"为什么不行"、"怎么办"等）
            - QA: 用户在问知识性问题（概念解释、原理、技术细节、"是什么"、"怎么做"等）
            
            如果是闲聊或无法判断，默认返回 QA。
            
            用户消息：{message}
            
            只返回类型名称（COURSE_INFO/HEURISTIC/QA），不要其他内容。
            """;

    public IntentRouter(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 判断用户消息的意图类型
     *
     * @param userMessage 用户消息
     * @return 意图类型
     */
    public IntentType route(String userMessage) {
        try {
            String response = chatClient.prompt()
                    .user(ROUTER_PROMPT.replace("{message}", userMessage))
                    .call()
                    .content()
                    .trim();

            IntentType type = IntentType.valueOf(response);
            log.info("意图路由: message={}, intent={}", userMessage, type);
            return type;

        } catch (Exception e) {
            log.warn("意图路由失败，默认为 QA: {}", e.getMessage());
            return IntentType.QA;
        }
    }
}
