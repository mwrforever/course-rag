package com.commerce.rag.retrieval.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * LLM 驱动的 Query 重写服务
 *
 * 将用户的自然语言 query 拆解为 0~N 条 TypedQuery。
 * - 0 条 = 无需检索（闲聊场景），直接走 LLM 对话
 * - 多条 = 多意图混合，每条独立检索后合并
 *
 * 使用轻量模型（qwen-turbo）做重写，低延迟。
 * 主对话模型（qwen-plus）不受影响。
 */
@Slf4j
@Service
public class QueryRewriter {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Query 重写提示词
     *
     * 指示 LLM 将用户 query 拆解为结构化子查询列表。
     */
    private static final String REWRITE_PROMPT = """
            你是一个查询分析器。将用户的当前消息拆解为 0~5 条独立的子查询。
            
            规则：
            1. 每条子查询有且仅有一个类型：COURSE_INFO / TECHNICAL_QA / HEURISTIC
            2. COURSE_INFO：课程相关询问（课程介绍、课程推荐、课程大纲等）
            3. TECHNICAL_QA：技术知识问答（概念解释、原理、技术细节等）
            4. HEURISTIC：技术问题求助（报错、bug、"怎么用不了"、"为什么"等）
            5. 闲聊、寒暄、确认性消息返回空数组 []
            6. 每条子查询应该是一个独立的、可检索的短语
            7. priority 1-5，1 最重要
            
            历史对话摘要：{history}
            当前用户消息：{query}
            
            输出 JSON 格式：
            {"queries": [{"query": "...", "type": "COURSE_INFO|TECHNICAL_QA|HEURISTIC", "intent": "...", "priority": 1}]}
            只输出 JSON，不要其他内容。
            """;

    public QueryRewriter(ChatModel chatModel) {
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /**
     * 重写用户 query 为多条 TypedQuery
     *
     * @param query          用户原始 query
     * @param historySummary 历史对话摘要（可为空）
     * @return 子查询列表，空列表表示无需检索
     */
    public List<TypedQuery> rewrite(String query, String historySummary) {
        log.debug("Query 重写: query={}", query);
        long startTime = System.currentTimeMillis();

        try {
            String prompt = REWRITE_PROMPT
                    .replace("{query}", query)
                    .replace("{history}", historySummary != null ? historySummary : "无");

            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            List<TypedQuery> result = parseResponse(response);

            log.info("Query 重写完成: 原query={}, 子查询数={}, 耗时={}ms",
                    query, result.size(), System.currentTimeMillis() - startTime);
            return result;

        } catch (Exception e) {
            log.warn("Query 重写失败，退化为原始 query: {}", e.getMessage());
            // 降级：用原始 query 作为单条 TECHNICAL_QA
            return List.of(new TypedQuery(query, QueryType.TECHNICAL_QA, "降级-原始query", 1));
        }
    }

    /**
     * 解析 LLM 返回的 JSON 为 TypedQuery 列表
     */
    private List<TypedQuery> parseResponse(String response) {
        try {
            // 提取 JSON 部分（去掉可能的 markdown 代码块标记）
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json?", "").replaceAll("```", "").trim();
            }

            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> queries = (List<Map<String, Object>>)
                    parsed.getOrDefault("queries", Collections.emptyList());

            List<TypedQuery> result = new ArrayList<>();
            for (Map<String, Object> q : queries) {
                QueryType type;
                try {
                    type = QueryType.valueOf((String) q.get("type"));
                } catch (Exception e) {
                    type = QueryType.TECHNICAL_QA;
                }
                result.add(new TypedQuery(
                        (String) q.get("query"),
                        type,
                        (String) q.getOrDefault("intent", ""),
                        ((Number) q.getOrDefault("priority", 1)).intValue()
                ));
            }
            return result;

        } catch (Exception e) {
            log.warn("Query 重写响应解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
