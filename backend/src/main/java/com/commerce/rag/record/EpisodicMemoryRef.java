package com.commerce.rag.record;

/**
 * 经历记忆召回引用（spec §8.7 召回结果 → 注入块组装载体）
 *
 * @param id       PG 主键（注入块不展示，预留）
 * @param type     记忆分类
 * @param content  完整记忆内容（事实源，注入展示）
 * @param summary  摘要（注入展示）
 * @param validity 状态机（active→「(当前)」；其它→「(历史记录)」，spec §8.7 标注）
 * @param score    Milvus COSINE 召回分（排序用）
 */
public record EpisodicMemoryRef(Long id, String type, String content, String summary, String validity, double score) {}
