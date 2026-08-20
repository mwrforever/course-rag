package com.commerce.rag.record;

import java.util.List;

/**
 * 偏好提取结果（一次 LLM 调用产出，spec §7.5 候选 + DELETE 双通道）
 *
 * @param candidates 偏好候选列表（可为空）
 * @param deletions  明确否定删除列表（可为空）
 */
public record PreferenceExtractionResult(List<PreferenceCandidate> candidates, List<PreferenceDeletion> deletions) {

    /** 空结果（无候选且无删除） */
    public static PreferenceExtractionResult empty() {
        return new PreferenceExtractionResult(List.of(), List.of());
    }
}
