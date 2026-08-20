package com.commerce.rag.enums;

/**
 * 偏好决策动作类型 —— 决策引擎输出，PreferenceServiceImpl 执行（spec §7.5）
 *
 * @author commerce-rag
 */
public enum PreferenceActionType {
    /** 新建 active（write_score≥writeHigh 直接写 / 多值 key 新 value） */
    CREATE_ACTIVE,
    /** 新建 observing（write_score in [observeLow, writeHigh) 进观察池） */
    CREATE_OBSERVING,
    /** 既有 active 同 value 强化：observation_count+1、分数重算，保持 active */
    REINFORCE,
    /** 既有 observing 同 value：count+1、分数重算（未达晋升线保持 observing） */
    OBSERVE_REINFORCE,
    /** 单值 key 含糊冲突：观察池覆盖 value、count 重置 1 */
    OBSERVE_RESET,
    /** observing 晋升 active（count≥promoteMinCount 且 write_score≥promoteMinScore；单值撞车替换旧 active 审计） */
    PROMOTE,
    /** 单值 key 明确冲突（explicitness≥explicitUpdate）：旧 active 软删审计 + 新 active version+1 */
    UPDATE,
    /** 忽略（write_score < observeLow，无操作） */
    IGNORE
}
