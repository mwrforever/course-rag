package com.commerce.rag.record;

/**
 * 单条经历记忆提取产物（spec §8.4 输出 JSON 字段，本计划补齐 explicitness——§8.3 打分必需）
 *
 * @param isMemory        是否为记忆（is_memory=true 才产生行，spec §8.6）
 * @param action          动作 CREATE/UPDATE/MERGE/INVALIDATE（LLM 输出，系统执行状态机）
 * @param type            记忆分类（必须命中 EpisodicTypes.ALL_TYPES，否则作废）
 * @param content         提炼后的原子事实陈述（非对话原文拷贝）
 * @param summary         一句话摘要（与 content 合并做 embedding）
 * @param structuredFacts 结构化事实 JSON 文本（LLM 输出对象序列化，可为 null）
 * @param importance      LLM 初判重要性 0~1（系统 × typeWeight 后再打分）
 * @param explicitness    LLM 初判语义明确度 0~1（本计划补齐字段）
 * @param confidence      LLM 初判置信度 0~1
 * @param mergeTarget     UPDATE/MERGE/INVALIDATE 的目标记忆 content 文本（CREATE 为 null）
 */
public record EpisodicMemoryExtraction(
        boolean isMemory,
        String action,
        String type,
        String content,
        String summary,
        String structuredFacts,
        double importance,
        double explicitness,
        double confidence,
        String mergeTarget) {}
