package com.commerce.rag.bot.mode;

import com.commerce.rag.bot.prompt.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 课程询问模式
 *
 * 针对课程相关问题，结合检索到的课程信息生成专业、友好的回答。
 * 使用 PromptTemplateService 获取模板，system prompt 静态可缓存。
 */
@Slf4j
@Component
public class CourseInquiryMode {

    private final ChatClient chatClient;
    private final PromptTemplateService promptService;

    public CourseInquiryMode(ChatModel chatModel, PromptTemplateService promptService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptService = promptService;
    }

    /**
     * 处理课程询问（同步）
     *
     * @param query   用户问题
     * @param context 课程相关上下文信息
     * @return AI 回答
     */
    public String answer(String query, String context) {
        return chatClient.prompt()
                .system(promptService.getSystemPrompt())
                .user(promptService.getUserPromptWithReminder("course-inquiry",
                        Map.of("query", query, "context", context != null ? context : "暂无课程信息")))
                .call()
                .content();
    }

    /**
     * 处理课程询问（流式）
     *
     * @param query   用户问题
     * @param context 课程相关上下文信息
     * @return 文本增量流
     */
    public Flux<String> answerStream(String query, String context) {
        return chatClient.prompt()
                .system(promptService.getSystemPrompt())
                .user(promptService.getUserPromptWithReminder("course-inquiry",
                        Map.of("query", query, "context", context != null ? context : "暂无课程信息")))
                .stream()
                .content();
    }
}
