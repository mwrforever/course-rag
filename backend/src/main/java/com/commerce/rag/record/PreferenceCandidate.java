package com.commerce.rag.record;

/**
 * 偏好候选（LLM 提取产物，spec §7.3/§7.5）
 *
 * @param key         偏好维度（必须命中 PreferenceKeys.ALL_KEYS，否则作废）
 * @param value       LLM 取值（系统侧 value 归一化后用于决策/注入）
 * @param explicitness LLM 初判语义明确度 0~1（"以后都用中文"≈1.0）
 * @param confidence  LLM 初判置信度 0~1
 */
public record PreferenceCandidate(String key, String value, double explicitness, double confidence) {}
