package com.commerce.rag.bot.rewrite;

import com.commerce.rag.bot.IntentType;
import java.util.List;

/**
 * 查询计划 —— Query Understanding 单次 LLM 调用的一次性签出结果（spec §2.2）
 *
 * <p>字段与 LLM 输出 JSON 对应：intent / rewrittenQueries / filters.course_names / recall_history。
 * 由 QueryUnderstandingService 解析产出，写入 State.KEY_QUERY_PLAN（ReplaceStrategy，
 * 每次 run 覆盖）；RetrieveNode 消费 intent 与 rewrittenQueries+filters，条件边消费 intent。
 *
 * @param intent         意图（knowledge_question / chat / unknown）
 * @param rewrittenQueries 理解用户需求后重写出的检索友好查询（默认 1 条，上限 3）
 * @param filters         元数据过滤（首版仅 course_names）
 * @param recallHistory   用户是否回溯历史（"之前/以前/上次"等，供 Episodic 动态召回用，计划 5/5 消费）
 *
 * @author commerce-rag
 */
public record QueryPlan(
        IntentType intent, List<String> rewrittenQueries, QueryPlanFilters filters, boolean recallHistory) {

    /**
     * 降级计划 —— QU 失败/空白输入时使用（spec §2.2：intent=unknown + 原始查询单条 +
     * 空 filters + recall_history=false；unknown 不拒答）
     *
     * @param originalQuery 用户原始查询文本（可为空白）
     * @return 降级 QueryPlan
     */
    public static QueryPlan fallback(String originalQuery) {
        return new QueryPlan(
                IntentType.UNKNOWN,
                List.of(originalQuery == null ? "" : originalQuery),
                new QueryPlanFilters(List.of()),
                false);
    }
}
