package com.commerce.rag.bot.router;

/**
 * 意图类型枚举
 *
 * 对应 AI 机器人的三种工作模式。
 */
public enum IntentType {
    /** 课程信息询问（课程介绍、课程匹配） */
    COURSE_INFO,
    /** 启发式问题解答（分析原因、引导解决） */
    HEURISTIC,
    /** QA 问题解答（RAG 检索 + 回答） */
    QA
}
