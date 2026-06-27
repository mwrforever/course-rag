package com.commerce.rag.bot.mode;

import com.commerce.rag.bot.prompt.PromptTemplateService;
import com.commerce.rag.retrieval.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * QA 问题解答模式（RAG 核心链路）
 *
 * 完整的 RAG 回答流程：
 * 1. 检索相关文档片段
 * 2. 将文档片段作为上下文注入 prompt
 * 3. LLM 基于上下文生成答案（支持流式输出）
 * 4. 标注信息来源引用
 *
 * 使用 PromptTemplateService 获取模板，system prompt 静态可缓存，
 * user prompt 动态注入 context/history/query。
 */
@Slf4j
@Component
public class QaMode {

    private final ChatClient chatClient;
    private final PromptTemplateService promptService;

    public QaMode(ChatModel chatModel, PromptTemplateService promptService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptService = promptService;
    }

    /**
     * 基于 RAG 检索结果生成回答（同步）
     *
     * @param query          用户问题
     * @param searchResults  检索到的相关文档片段
     * @param historySummary 对话历史摘要
     * @return AI 回答文本
     */
    public String answer(String query, List<SearchResult> searchResults, String historySummary) {
        String context = buildContext(searchResults);
        String systemPrompt = promptService.getSystemPrompt();
        String userPrompt = promptService.getUserPromptWithReminder("qa-answer",
                Map.of("query", query, "context", context, "history",
                        historySummary != null ? historySummary : "无"));

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();
    }

    /**
     * 基于 RAG 检索结果生成流式回答（SSE 打字效果）
     *
     * @param query          用户问题
     * @param searchResults  检索到的相关文档片段
     * @param historySummary 对话历史摘要
     * @return 文本增量流，每个元素是一个 token
     */
    public Flux<String> answerStream(String query, List<SearchResult> searchResults, String historySummary) {
        String context = buildContext(searchResults);
        String systemPrompt = promptService.getSystemPrompt();
        String userPrompt = promptService.getUserPromptWithReminder("qa-answer",
                Map.of("query", query, "context", context, "history",
                        historySummary != null ? historySummary : "无"));

        return chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .stream()
                .content();
    }

    /**
     * 将检索结果格式化为上下文文本
     */
    private String buildContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "（未检索到相关文档）";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(String.format("--- 文档 %d（来源: %s，相关度: %.2f）---\n%s\n\n",
                    i + 1,
                    r.headingPath() != null ? r.headingPath() : "未知",
                    r.score(),
                    r.content()));
        }
        return sb.toString();
    }
}
