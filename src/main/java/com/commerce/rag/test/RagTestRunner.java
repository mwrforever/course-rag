package com.commerce.rag.test;

import com.commerce.rag.bot.chat.ChatService;
import com.commerce.rag.retrieval.search.HybridSearchService;
import com.commerce.rag.retrieval.search.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 批量测试执行器
 *
 * 支持：
 * 1. 检索质量评估（Recall@K, MRR）
 * 2. 端到端回答质量评估（LLM-as-Judge）
 */
@Slf4j
@Component
public class RagTestRunner {

    private final HybridSearchService hybridSearch;
    private final ChatClient judgeClient;

    /** LLM-as-Judge 评分提示词 */
    private static final String JUDGE_PROMPT = """
            你是一个回答质量评估器。请评估以下问答的质量。
            
            用户问题：{query}
            AI 回答：{answer}
            参考答案：{expected}
            
            评分维度（每项 0-10 分）：
            1. 准确性：回答是否正确
            2. 完整性：是否覆盖了参考答案的关键信息
            3. 清晰度：表达是否清楚有条理
            
            输出 JSON：{"accuracy": 8, "completeness": 7, "clarity": 9, "total": 8.0, "reason": "..."}
            """;

    public RagTestRunner(HybridSearchService hybridSearch, ChatModel chatModel) {
        this.hybridSearch = hybridSearch;
        this.judgeClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 评估单个回答的质量（LLM-as-Judge）
     *
     * @param query    用户问题
     * @param answer   AI 生成的回答
     * @param expected 期望的参考答案
     * @return 评分 JSON 字符串
     */
    public String evaluateAnswer(String query, String answer, String expected) {
        String prompt = JUDGE_PROMPT
                .replace("{query}", query)
                .replace("{answer}", answer)
                .replace("{expected}", expected != null ? expected : "无参考答案");

        return judgeClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
}
