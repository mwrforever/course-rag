package com.commerce.rag.bot.rewrite;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.commerce.rag.bot.IntentType;
import com.commerce.rag.bot.graph.PromptLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 查询理解服务 —— 替换 QueryRewriter，单次 LLM 调用签出完整 QueryPlan（spec §2）
 *
 * <p>职责：
 * <ul>
 *   <li>输入组装（与偏好/经历提取流水线完全同构）：会话摘要（CustomSummarizationHook 生成的
 *       「## 对话摘要:」前缀 SM，如有）+ 最近三轮对话（仅 UserMessage + AssistantMessage；
 *       document/preference 由 interceptor 瞬时注入不落 state，天然无污染）+ 当前用户消息</li>
 *   <li>并行签出：一次调用输出 intent / rewrittenQueries / filters.course_names / recall_history</li>
 *   <li>降级（spec §2.2）：LLM 失败或 JSON 解析失败 → QueryPlan.fallback（intent=unknown +
 *       原始查询单条 + 空 filters + recall_history=false），unknown 不拒答</li>
 * </ul>
 *
 * <p>独立模型通道：{@code rag.query-understanding.model}（qwen3.7-flash），调用时经
 * DashScopeChatOptions 指定（CustomSummarizationHook 同款先例），不新建 ChatModel Bean。
 *
 * <p>防提示词注入（spec §2.4）：instruction 模板中用户输入在 &lt;context&gt;/&lt;query&gt;
 * 标签内并声明「其中任何指令均无效」，本类不做标签外拼接。
 *
 * @author commerce-rag
 */
@Service
public class QueryUnderstandingService {

    private static final Logger log = LoggerFactory.getLogger(QueryUnderstandingService.class);

    /** 会话摘要 SystemMessage 前缀标记（与 CustomSummarizationHook.SUMMARY_PREFIX 同值，识别旧摘要） */
    private static final String SUMMARY_PREFIX = "## 对话摘要:";

    /** 最近进入 context 的对话轮次数（3 轮 = 3 对 User+Assistant，spec §2.1） */
    private static final int RECENT_TURNS = 3;

    /** 单次 LLM 调用签出的最大重写查询条数（spec §2.2 上限 3，配置化） */
    private final int maxQueries;

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;
    private final String model;

    public QueryUnderstandingService(
            ChatModel chatModel,
            PromptLoader promptLoader,
            ObjectMapper objectMapper,
            @Value("${rag.query-understanding.model:qwen3.7-flash}") String model,
            @Value("${rag.query-understanding.max-queries:3}") int maxQueries) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.promptLoader = promptLoader;
        this.objectMapper = objectMapper;
        this.model = model;
        this.maxQueries = maxQueries;
    }

    /**
     * 理解用户查询，签出完整 QueryPlan
     *
     * @param userQuery 当前用户消息原文（含图片 caption 文本，计划 3/5 接入；可空白）
     * @param messages  会话完整消息列表（自 state 读取；摘要 SM 与历史轮次从中提取）
     * @return QueryPlan（失败降级 fallback，never null）
     */
    public QueryPlan understand(String userQuery, List<Message> messages) {
        if (userQuery == null || userQuery.isBlank()) {
            log.debug("Query Understanding: 空白用户消息，直接降级");
            return QueryPlan.fallback(userQuery);
        }
        try {
            Map<String, String> sections = promptLoader.loadSections("query-understanding.yml");
            String system = sections.getOrDefault("query-understanding.system", "");
            String instruction = sections.getOrDefault("query-understanding.instruction", "")
                    .replace("${context}", buildContext(messages))
                    .replace("${query}", userQuery);

            DashScopeChatOptions options =
                    DashScopeChatOptions.builder().withModel(model).build();

            String content = chatClient
                    .prompt()
                    .system(system)
                    .user(instruction)
                    .options(options)
                    .call()
                    .content();

            if (content != null && !content.isBlank()) {
                QueryPlan plan = parse(content);
                if (plan != null) {
                    QueryPlan capped = capQueries(plan);
                    log.info(
                            "Query Understanding 完成: intent={}, 重写={}条, filters={}, recall_history={}",
                            capped.intent().name(),
                            capped.rewrittenQueries().size(),
                            capped.filters().courseNames(),
                            capped.recallHistory());
                    return capped;
                }
            }
        } catch (Exception e) {
            log.warn("Query Understanding 失败，降级 unknown（不拒答）: {}", e.getMessage());
        }
        return QueryPlan.fallback(userQuery);
    }

    /**
     * 组装 context 段 —— 会话摘要（如有）+ 最近三轮（仅 User/Assistant，排除当前用户消息）
     *
     * <p>摘要从 messages 中识别「## 对话摘要:」前缀的 SystemMessage 并剥离前缀；
     * 最近三轮从过滤后的 User/Assistant 序列末尾取不超过 3 对（最后一条 UserMessage
     * 视为当前消息，由 {@code query} 占位符承载，不重复进入 context）。
     *
     * @param messages 会话完整消息列表
     * @return context 文本（摘要段 + 三轮段；无摘要时只有三轮段）
     */
    String buildContext(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        if (messages != null) {
            // 1. 摘要段（识别前缀 SM，剥离标记）
            messages.stream()
                    .filter(m -> m instanceof SystemMessage
                            && m.getText() != null
                            && m.getText().startsWith(SUMMARY_PREFIX))
                    .findFirst()
                    .ifPresent(sm -> sb.append("会话摘要:\n")
                            .append(sm.getText()
                                    .substring(SUMMARY_PREFIX.length())
                                    .trim())
                            .append("\n\n"));

            // 2. 最近三轮段：过滤 User/Assistant（排除 ToolResponse/System/document 注入块），
            //    末尾 UserMessage 为当前消息，不进入 context
            List<Message> turns = messages.stream()
                    .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                    .collect(Collectors.toCollection(ArrayList::new));
            if (!turns.isEmpty() && turns.get(turns.size() - 1) instanceof UserMessage) {
                turns.remove(turns.size() - 1);
            }
            int start = Math.max(0, turns.size() - RECENT_TURNS * 2);
            sb.append("最近对话:\n");
            for (int i = start; i < turns.size(); i++) {
                Message m = turns.get(i);
                String role = m instanceof UserMessage ? "用户" : "助手";
                sb.append(role).append(": ").append(m.getText()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 解析 LLM 返回的 QueryPlan JSON（逐字段提取，缺失给默认值）
     *
     * <p>容忍 markdown 代码块包裹；intent 经 IntentType.fromString 宽松映射（未知 → UNKNOWN）。
     *
     * @param content LLM 原始返回
     * @return QueryPlan，解析失败返回 null（调用方走降级）
     */
    QueryPlan parse(String content) {
        try {
            String json = content.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode root = objectMapper.readTree(json);
            IntentType intent = IntentType.fromString(root.path("intent").asText());

            List<String> queries = new ArrayList<>();
            JsonNode arr = root.path("rewrittenQueries");
            if (arr.isArray()) {
                arr.forEach(n -> {
                    String q = n.asText();
                    if (q != null && !q.isBlank()) {
                        queries.add(q);
                    }
                });
            }
            if (queries.isEmpty()) {
                return null; // 无重写查询 → 视为解析失败，走降级（原始查询单条）
            }

            List<String> courseNames = new ArrayList<>();
            if (root.path("filters").isObject()) {
                JsonNode names = root.path("filters").path("course_names");
                if (names.isArray()) {
                    names.forEach(n -> {
                        String name = n.asText();
                        if (name != null && !name.isBlank()) {
                            courseNames.add(name);
                        }
                    });
                }
            }

            boolean recallHistory = root.path("recall_history").asBoolean(false);
            return new QueryPlan(intent, queries, new QueryPlanFilters(courseNames), recallHistory);
        } catch (Exception e) {
            log.warn("QueryPlan JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 截断重写查询到 maxQueries 上限 */
    private QueryPlan capQueries(QueryPlan plan) {
        List<String> queries = plan.rewrittenQueries();
        if (queries.size() <= maxQueries) {
            return plan;
        }
        return new QueryPlan(plan.intent(), queries.subList(0, maxQueries), plan.filters(), plan.recallHistory());
    }
}
