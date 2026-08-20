package com.commerce.rag.record;

import com.commerce.rag.enums.EpisodicActionType;

/**
 * 经历记忆决策动作（决策引擎输出 → 服务执行，纯数据载体，spec §8.6）
 *
 * @param type            动作类型（CREATE/UPDATE/MERGE/INVALIDATE/IGNORE）
 * @param memoryType      记忆分类
 * @param content         新行内容（CREATE/UPDATE/MERGE 为 LLM 产出内容；INVALIDATE 为目标行 content）
 * @param summary         新行摘要（INVALIDATE 保留目标行 summary）
 * @param structuredFacts 结构化事实 JSON 文本（INVALIDATE 为 null）
 * @param targetRowId     UPDATE/MERGE/INVALIDATE 命中的旧行 id（无则 null）
 * @param version         新行版本（CREATE=1；UPDATE/MERGE=目标行+1；INVALIDATE=目标行版本）
 * @param importance      系统校正后有效重要性（LLM importance × typeWeight，审计落库）
 * @param confidence      LLM 初判置信度（审计落库）
 * @param memoryScore     memory_score 决策值（不入库，审计日志用）
 */
public record EpisodicAction(
        EpisodicActionType type,
        String memoryType,
        String content,
        String summary,
        String structuredFacts,
        Long targetRowId,
        int version,
        double importance,
        double confidence,
        double memoryScore) {}
