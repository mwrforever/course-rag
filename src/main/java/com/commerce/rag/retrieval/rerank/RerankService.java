package com.commerce.rag.retrieval.rerank;

import com.commerce.rag.retrieval.search.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-encoder Rerank 服务
 *
 * 对检索结果做精排，提升最终回答质量。
 *
 * 设计要点：
 * - 仅在 THINKING 模式开启（QUICK 模式跳过，省 50ms 延迟）
 * - 必须 fallback：rerank 失败时退化为 RRF 原始分数
 * - 输入用 chunk 摘要（前 100 token）而非全文，既快又不浪费
 * - 使用 LLM 做相关性打分（生产环境可替换为专用 rerank 模型）
 */
@Slf4j
@Service
public class RerankService {

    private final ChatClient chatClient;

    /** Rerank 提示词：让 LLM 判断查询和文档的相关性 */
    private static final String RERANK_PROMPT = """
            你是一个文档相关性评分器。给定一个查询和多个文档片段，
            为每个文档片段评分（0.0-1.0），表示它与查询的相关程度。
            
            评分标准：
            - 1.0: 完全相关，直接回答了查询
            - 0.7: 高度相关，包含查询的核心信息
            - 0.4: 部分相关，包含一些有用信息
            - 0.1: 基本不相关
            
            查询：{query}
            
            文档列表：
            {documents}
            
            输出 JSON 格式（只输出 JSON）：
            {"scores": [0.9, 0.7, 0.3, ...]}
            """;

    public RerankService(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 对检索结果做 rerank 精排
     *
     * @param query   用户查询
     * @param results 待排序的检索结果
     * @return rerank 后的结果（按新分数降序）
     */
    public List<SearchResult> rerank(String query, List<SearchResult> results) {
        if (results == null || results.size() <= 1) {
            return results;
        }

        long startTime = System.currentTimeMillis();
        try {
            // 构建文档列表（用摘要而非全文）
            StringBuilder docList = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                String abstract_text = truncate(results.get(i).content(), 200);
                docList.append(String.format("[%d] %s（来源: %s）%n",
                        i + 1, abstract_text, results.get(i).headingPath()));
            }

            String prompt = RERANK_PROMPT
                    .replace("{query}", query)
                    .replace("{documents}", docList.toString());

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<Double> scores = parseScores(response, results.size());

            // 用 rerank 分数替换原始分数
            List<SearchResult> reranked = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                reranked.add(results.get(i).withScore(scores.get(i)));
            }

            // 按新分数排序
            reranked.sort((a, b) -> Double.compare(b.score(), a.score()));

            log.info("Rerank 完成: 候选数={}, 耗时={}ms",
                    results.size(), System.currentTimeMillis() - startTime);
            return reranked;

        } catch (Exception e) {
            // Rerank 失败必须 fallback，不能拖垮整个 RAG
            log.warn("Rerank 失败，fallback 到原始分数: {}", e.getMessage());
            return results;
        }
    }

    /**
     * 解析 LLM 返回的分数列表
     */
    private List<Double> parseScores(String response, int expectedSize) {
        try {
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json?", "").replaceAll("```", "").trim();
            }

            // 简单解析 JSON 中的分数数组
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String scoresStr = json.substring(start + 1, end);
                String[] parts = scoresStr.split(",");
                List<Double> scores = new ArrayList<>();
                for (String part : parts) {
                    scores.add(Double.parseDouble(part.trim()));
                }
                // 补齐不足的分数
                while (scores.size() < expectedSize) {
                    scores.add(0.5);
                }
                return scores.subList(0, expectedSize);
            }
        } catch (Exception e) {
            log.warn("Rerank 分数解析失败: {}", e.getMessage());
        }

        // 解析失败时返回原始降序分数
        List<Double> fallback = new ArrayList<>();
        for (int i = 0; i < expectedSize; i++) {
            fallback.add(1.0 - (double) i / expectedSize);
        }
        return fallback;
    }

    /**
     * 截断文本为摘要（取前 N 个字符）
     */
    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
    }
}
