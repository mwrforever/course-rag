package com.commerce.rag.bot.mode;

import com.commerce.rag.retrieval.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QA 问题解答模式（RAG 核心链路）
 *
 * 完整的 RAG 回答流程：
 * 1. 检索相关文档片段
 * 2. 将文档片段作为上下文注入 prompt
 * 3. LLM 基于上下文生成答案
 * 4. 标注信息来源引用
 */
@Slf4j
@Component
public class QaMode {

    private final ChatClient chatClient;

    private static final String PROMPT = """
            你是一个专业的知识库问答 AI 助手。基于提供的参考文档回答用户问题。
            
            回答规则：
            1. 严格基于参考文档内容回答，不要编造信息
            2. 如果参考文档中没有相关内容，明确告知"根据现有知识库未找到相关信息"
            3. 回答时标注信息来源，格式为 [来源: 文档标题 > 章节路径]
            4. 如果多个文档包含相关信息，综合回答并分别标注来源
            5. 回答要准确、简洁、有条理
            
            参考文档：
            {context}
            
            对话历史摘要：
            {history}
            
            用户问题：{query}
            """;

    public QaMode(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 基于 RAG 检索结果生成回答
     *
     * @param query          用户问题
     * @param searchResults  检索到的相关文档片段
     * @param historySummary 对话历史摘要
     * @return AI 回答 + 来源引用
     */
    public String answer(String query, List<SearchResult> searchResults, String historySummary) {
        // 构建上下文文本
        String context = buildContext(searchResults);

        return chatClient.prompt()
                .user(PROMPT.replace("{query}", query)
                        .replace("{context}", context)
                        .replace("{history}", historySummary != null ? historySummary : "无"))
                .call()
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
