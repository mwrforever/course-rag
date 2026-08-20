package com.commerce.rag.constants;

import java.util.List;
import java.util.Map;

/**
 * 偏好维度 key 常量 —— 偏好记忆的维度枚举（spec §7.2/§7.4「key 枚举约束」）
 *
 * <p>LLM 候选提取时只能从 {@link #ALL_KEYS} 选择 key，禁止自由生成（未知 key 候选直接作废）；
 * 新增维度 = 扩本接口常量 + application.yml memory.preference.value-synonyms（编译期枚举约束）。
 *
 * @author commerce-rag
 */
public interface PreferenceKeys {

    /** 回答语言（单值 key） */
    String RESPONSE_LANGUAGE = "response_language";
    /** 回答详细度（单值 key） */
    String RESPONSE_VERBOSITY = "response_verbosity";
    /** 解释深度（单值 key） */
    String EXPLAIN_DEPTH = "explain_depth";
    /** 课程方向（多值 key，可并列） */
    String COURSE_DIRECTION = "course_direction";
    /** 技术栈（多值 key，可并列） */
    String TECH_STACK = "tech_stack";
    /** 回答风格（多值 key，可并列） */
    String RESPONSE_STYLE = "response_style";

    /** 单值 key：同一 user+key 仅一个 active 值，冲突走 UPDATE/观察池覆盖（spec §7.5） */
    List<String> SINGLE_VALUE_KEYS = List.of(RESPONSE_LANGUAGE, RESPONSE_VERBOSITY, EXPLAIN_DEPTH);

    /** 多值 key：同 key 可多行 active 值并存，新 value 直接 CREATE（spec §7.5） */
    List<String> MULTI_VALUE_KEYS = List.of(COURSE_DIRECTION, TECH_STACK, RESPONSE_STYLE);

    /** 全部已知 key（LLM 提取白名单，spec §7.4-①） */
    List<String> ALL_KEYS =
            List.of(RESPONSE_LANGUAGE, RESPONSE_VERBOSITY, EXPLAIN_DEPTH, COURSE_DIRECTION, TECH_STACK, RESPONSE_STYLE);

    /** 保证注入的硬偏好 key（spec §7.8：guaranteed 500 token 保底先注入） */
    List<String> GUARANTEED_KEYS = List.of(RESPONSE_LANGUAGE, RESPONSE_VERBOSITY, EXPLAIN_DEPTH);

    /** 偏好块显示标签（key → 中文标签，spec §7.7 块格式） */
    Map<String, String> LABELS = Map.of(
            RESPONSE_LANGUAGE, "回答语言",
            RESPONSE_VERBOSITY, "回答详细度",
            EXPLAIN_DEPTH, "解释深度",
            COURSE_DIRECTION, "课程方向",
            TECH_STACK, "技术栈",
            RESPONSE_STYLE, "回答风格");

    /** 该 key 是否多值（true=可并列多行；false=单值冲突分析） */
    static boolean isMultiValue(String key) {
        return MULTI_VALUE_KEYS.contains(key);
    }

    /** 该 key 是否在白名单内（未知 key 候选直接作废） */
    static boolean isKnown(String key) {
        return ALL_KEYS.contains(key);
    }
}
