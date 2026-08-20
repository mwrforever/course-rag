package com.commerce.rag.record;

import java.util.List;

/**
 * 经历记忆提取结果（一次 LLM 调用可产出多条事实，spec §8.4「只提取 4 类 type 相关事实」）
 *
 * @param memories 记忆提取条目列表（可为空；is_memory=false 条目由决策侧过滤）
 */
public record EpisodicExtractionResult(List<EpisodicMemoryExtraction> memories) {

    /** 空结果（无任何记忆） */
    public static EpisodicExtractionResult empty() {
        return new EpisodicExtractionResult(List.of());
    }
}
