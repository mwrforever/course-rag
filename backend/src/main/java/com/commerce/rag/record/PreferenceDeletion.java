package com.commerce.rag.record;

/**
 * 明确否定删除意图（LLM 提 action，系统执行软删，spec §7.5）
 *
 * @param key   被否定的偏好维度
 * @param value 被否定的偏好取值（与候选提取同 key 集合约束）
 */
public record PreferenceDeletion(String key, String value) {}
