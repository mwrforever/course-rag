package com.commerce.rag.bot.rewrite;

import com.commerce.rag.bot.graph.PromptLoader;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

/**
 * 查询重写器 —— 调用 LLM 将用户原始问题拆解为多条覆盖性查询
 *
 * <p>所有意图模式必须使用（TECHNICAL_QA / COURSE_INFO 均经过此节点）。
 * 默认生成 3 条覆盖性查询，可通过配置调整。
 *
 * <p>输入：用户原始问题文本
 * 输出：List&lt;String&gt; — 3 条覆盖性查询，写入 State.rewrittenQueries
 *
 * @author commerce-rag
 */
@Service
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public QueryRewriter(ChatModel chatModel, PromptLoader promptLoader, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
    }

    /**
     * 重写查询 —— 将用户问题分解为多条覆盖性查询
     *
     * @param originalQuery 用户原始问题
     * @return 3 条覆盖性查询列表
     */
    public List<String> rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return Collections.emptyList();
        }

        try {
            String template = promptLoader.load("query-rewrite.yml");
            // 提取 system 和 instruction 部分
            String systemPrompt = extractSection(template, "system:", "instruction:");
            String instruction = extractSection(template, "instruction:", null);

            String rewritten = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(instruction + "\n\n" + "## 用户问题\n" + originalQuery)
                    .call()
                    .content();

            if (rewritten != null && !rewritten.isBlank()) {
                List<String> queries = parseJsonArray(rewritten);
                if (!queries.isEmpty()) {
                    log.info("查询重写完成: 原始={} → 重写={}", truncate(originalQuery, 30), queries);
                    return queries;
                }
            }
        } catch (Exception e) {
            log.warn("查询重写失败，降级使用原始查询: {}", e.getMessage());
        }

        // 降级：返回原始查询的单元素列表
        return Collections.singletonList(originalQuery);
    }

    /**
     * 解析 LLM 返回的 JSON 数组
     */
    private List<String> parseJsonArray(String content) {
        try {
            // 去除可能的 markdown 代码块包裹
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("[");
                int end = json.lastIndexOf("]");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 从模板中提取指定 section 的内容
     */
    private String extractSection(String template, String startMarker, String endMarker) {
        int start = template.indexOf(startMarker);
        if (start < 0) return "";
        start += startMarker.length();

        int end = endMarker != null ? template.indexOf(endMarker, start) : template.length();
        if (end < 0) end = template.length();

        return template.substring(start, end).trim();
    }

    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
