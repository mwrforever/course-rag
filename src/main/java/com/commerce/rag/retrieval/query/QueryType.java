package com.commerce.rag.retrieval.query;

/**
 * 查询类型枚举
 *
 * 用于 Query 重写后的子查询分类，
 * 不同类型路由到不同的检索策略。
 */
public enum QueryType {
    /** 课程信息查询 */
    COURSE_INFO,
    /** 技术知识问答 */
    TECHNICAL_QA,
    /** 启发式问题求助 */
    HEURISTIC
}
