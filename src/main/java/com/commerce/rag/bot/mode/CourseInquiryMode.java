package com.commerce.rag.bot.mode;

import com.commerce.rag.retrieval.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 三种对话模式的处理器
 *
 * 每个模式对应不同的提示词和检索策略：
 * - CourseInquiryMode: 课程信息询问，结合课程数据生成推荐
 * - HeuristicMode: 启发式解答，分析问题原因并引导解决
 * - QaMode: QA 问答，基于 RAG 检索结果生成答案
 */
@Slf4j
@Component
public class CourseInquiryMode {

    private final ChatClient chatClient;

    private static final String PROMPT = """
            你是一个专业的课程顾问 AI 助手。根据以下信息回答用户关于课程的问题。
            
            回答要求：
            1. 提供详细的课程介绍，包括课程内容、适合人群、学习目标等
            2. 如果用户需要推荐课程，根据其需求匹配合适的课程
            3. 语气友好专业，突出课程价值
            4. 如果没有相关课程信息，诚实告知并建议联系人工客服
            
            参考信息：
            {context}
            
            用户问题：{query}
            """;

    public CourseInquiryMode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 处理课程询问
     *
     * @param query   用户问题
     * @param context 课程相关上下文信息
     * @return AI 回答
     */
    public String answer(String query, String context) {
        return chatClient.prompt()
                .user(PROMPT.replace("{query}", query)
                        .replace("{context}", context != null ? context : "暂无课程信息"))
                .call()
                .content();
    }
}
