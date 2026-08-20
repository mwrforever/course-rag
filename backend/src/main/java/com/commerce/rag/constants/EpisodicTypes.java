package com.commerce.rag.constants;

import java.util.List;
import java.util.Map;

/**
 * 经历记忆分类 type 常量 —— 4 类记忆分类白名单（spec §8.2）
 *
 * <p>LLM 候选提取时 type 只能从 {@link #ALL_TYPES} 选择，未知 type 候选直接作废；
 * 类型权重系统校正在 {@code memory.episodic.type-weights} 配置（spec §8.3，全配置化）。
 *
 * @author commerce-rag
 */
public interface EpisodicTypes {

    /** 学习目标/动机 */
    String LEARNING_GOAL = "learning_goal";
    /** 学习进度/阶段 */
    String LEARNING_PROGRESS = "learning_progress";
    /** 已解决问题+方案 */
    String RESOLVED_QUESTION = "resolved_question";
    /** 个人背景 */
    String PERSONAL_CONTEXT = "personal_context";

    /** 全部已知 type（LLM 提取白名单，spec §8.2） */
    List<String> ALL_TYPES = List.of(LEARNING_GOAL, LEARNING_PROGRESS, RESOLVED_QUESTION, PERSONAL_CONTEXT);

    /** 记忆块显示标签（type → 中文标签，spec §8.7 注入标注用） */
    Map<String, String> LABELS = Map.of(
            LEARNING_GOAL, "学习目标",
            LEARNING_PROGRESS, "学习进度",
            RESOLVED_QUESTION, "已解决问题",
            PERSONAL_CONTEXT, "个人背景");

    /** 类型权重（默认值；实际以 {@code memory.episodic.type-weights} 配置为准，常量兜底防未配置） */
    Map<String, Double> DEFAULT_TYPE_WEIGHTS = Map.of(
            LEARNING_GOAL, 1.0,
            RESOLVED_QUESTION, 0.95,
            LEARNING_PROGRESS, 0.9,
            PERSONAL_CONTEXT, 0.8);

    /** 该 type 是否在白名单内（未知 type 候选直接作废） */
    static boolean isKnown(String type) {
        return ALL_TYPES.contains(type);
    }

    /** 该 type 的默认权重（防配置缺失兜底） */
    static double defaultWeight(String type) {
        return DEFAULT_TYPE_WEIGHTS.getOrDefault(type, 1.0);
    }
}
