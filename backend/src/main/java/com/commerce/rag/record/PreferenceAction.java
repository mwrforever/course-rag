package com.commerce.rag.record;

import com.commerce.rag.enums.PreferenceActionType;

/**
 * 偏好决策动作（决策引擎输出 → 服务执行，纯数据载体）
 *
 * @param type            动作类型
 * @param key             偏好维度
 * @param value           偏好取值
 * @param targetRowId     命中行 id（REINFORCE/OBSERVE_REINFORCE/OBSERVE_RESET/PROMOTE 指向目标行）
 * @param supersededRowId 被替换的旧 active 行 id（UPDATE/PROMOTE 撞车时软删审计，无则 null）
 * @param explicitness    候选 explicitness（入库审计用，spec §7.2 字段）
 * @param confidence      候选 confidence（入库审计用）
 * @param writeScore      重算后的写入分
 * @param stability       重算后的稳定性
 * @param count           更新后的观察计数
 * @param version         新行/更新后的版本号（CREATE=1，UPDATE=旧+1）
 */
public record PreferenceAction(
        PreferenceActionType type,
        String key,
        String value,
        Long targetRowId,
        Long supersededRowId,
        double explicitness,
        double confidence,
        double writeScore,
        double stability,
        int count,
        int version) {}
