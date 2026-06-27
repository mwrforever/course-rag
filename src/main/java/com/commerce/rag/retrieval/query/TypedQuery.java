package com.commerce.rag.retrieval.query;

/**
 * 带类型的子查询
 *
 * Query 重写后产出的结构化查询对象。
 * 每个子查询对应一个明确的检索意图，独立执行检索后合并。
 *
 * @param query    改写后的子查询文本
 * @param type     查询类型（COURSE_INFO / TECHNICAL_QA / HEURISTIC）
 * @param intent   意图说明（debug 用）
 * @param priority 优先级（1-5，1 最高）
 */
public record TypedQuery(
        String query,
        QueryType type,
        String intent,
        int priority
) {}
