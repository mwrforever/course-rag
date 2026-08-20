package com.commerce.rag.service;

import com.commerce.rag.constants.PreferenceKeys;
import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.enums.PreferenceActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.PreferenceAction;
import com.commerce.rag.record.PreferenceCandidate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 偏好决策引擎 —— write_score 纯系统规则（spec §7.3/§7.5）
 *
 * <p>零 DB 访问（纯函数可单测）：输入 = 候选 + 该 key 既有行（deleted=0），输出 = 动作。
 * 规则全表：
 * <pre>
 * 1. 同 key+同 value 命中 active   → REINFORCE（count+1，分数重算）
 * 2. 同 key+同 value 命中 observing → 达线 PROMOTE，未达 OBSERVE_REINFORCE
 *    （PROMOTE 且单值 key 已有不同 value active → 携带 supersededRowId 供替换审计）
 * 3. 单值 key 存在不同 value active（冲突）：
 *    explicitness ≥ explicitUpdate → UPDATE（旧软删审计 + version+1）
 *    否则 → 观察池（有 observing 行覆盖 value+count 重置 1 = OBSERVE_RESET；无则 CREATE_OBSERVING）
 * 4. 全新 key / 多值 key 新 value   → explicitness ≥ explicitUpdate（明确表达，用户「明确立即生效」原则）
 *    → CREATE_ACTIVE 直达；否则按 write_score：≥writeHigh CREATE_ACTIVE；
 *    [observeLow, writeHigh) CREATE_OBSERVING；<observeLow IGNORE
 * </pre>
 *
 * @author commerce-rag
 */
@Service
public class PreferenceDecisionEngine {

    private final MemoryProperties props;

    public PreferenceDecisionEngine(MemoryProperties props) {
        this.props = props;
    }

    /**
     * 对一个候选执行纯规则决策
     *
     * @param candidate  候选（key/value/explicitness/confidence，已归一化）
     * @param rowsForKey 该 (user_id, key) 的全部既有行（active + observing，deleted=0 自动过滤）
     * @return 决策动作（never null）
     */
    public PreferenceAction decide(PreferenceCandidate candidate, List<UserPreference> rowsForKey) {
        boolean multi = PreferenceKeys.isMultiValue(candidate.key());
        List<UserPreference> rows = rowsForKey == null ? List.of() : rowsForKey;

        // ── 1/2. 同 key+同 value 精确匹配（强化路径）──
        UserPreference activeSame = rows.stream()
                .filter(r -> "active".equals(r.getStatus()) && r.getValue().equals(candidate.value()))
                .findFirst()
                .orElse(null);
        if (activeSame != null) {
            return reinforce(PreferenceActionType.REINFORCE, candidate, activeSame);
        }
        UserPreference obsSame = rows.stream()
                .filter(r -> "observing".equals(r.getStatus()) && r.getValue().equals(candidate.value()))
                .findFirst()
                .orElse(null);
        if (obsSame != null) {
            int count = obsSame.getObservationCount() + 1;
            double stability = stability(count);
            double ws = writeScore(candidate, stability);
            // 晋升线：count≥promoteMinCount 且 write_score≥promoteMinScore（统一标尺，spec §7.5）
            if (count >= props.getPreference().getPromoteMinCount()
                    && ws >= props.getPreference().getPromoteMinScore()) {
                Long superseded = null;
                if (!multi) {
                    // 单值 key 撞车：同 key 已有不同 value 的 active → 替换（旧行审计软删）
                    superseded = rows.stream()
                            .filter(r -> "active".equals(r.getStatus())
                                    && !r.getValue().equals(candidate.value()))
                            .map(UserPreference::getId)
                            .findFirst()
                            .orElse(null);
                }
                return new PreferenceAction(
                        PreferenceActionType.PROMOTE,
                        candidate.key(),
                        candidate.value(),
                        obsSame.getId(),
                        superseded,
                        candidate.explicitness(),
                        candidate.confidence(),
                        ws,
                        stability,
                        count,
                        obsSame.getVersion());
            }
            return new PreferenceAction(
                    PreferenceActionType.OBSERVE_REINFORCE,
                    candidate.key(),
                    candidate.value(),
                    obsSame.getId(),
                    null,
                    candidate.explicitness(),
                    candidate.confidence(),
                    ws,
                    stability,
                    count,
                    obsSame.getVersion());
        }

        // ── 3. 单值 key 冲突（存在不同 value 的 active）──
        if (!multi) {
            UserPreference otherActive = rows.stream()
                    .filter(r -> "active".equals(r.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (otherActive != null) {
                if (candidate.explicitness() >= props.getPreference().getExplicitUpdate()) {
                    // 明确改变 → UPDATE（旧 active 软删审计，新 active version+1）
                    double stability = stability(1);
                    double ws = writeScore(candidate, stability);
                    return new PreferenceAction(
                            PreferenceActionType.UPDATE,
                            candidate.key(),
                            candidate.value(),
                            null,
                            otherActive.getId(),
                            candidate.explicitness(),
                            candidate.confidence(),
                            ws,
                            stability,
                            1,
                            otherActive.getVersion() + 1);
                }
                // 含糊表达 → 观察池（同 key 覆盖 value、count 重置 1，防方向漂移误晋升）
                UserPreference obs = rows.stream()
                        .filter(r -> "observing".equals(r.getStatus()))
                        .findFirst()
                        .orElse(null);
                double stability = stability(1);
                double ws = writeScore(candidate, stability);
                if (obs != null) {
                    return new PreferenceAction(
                            PreferenceActionType.OBSERVE_RESET,
                            candidate.key(),
                            candidate.value(),
                            obs.getId(),
                            null,
                            candidate.explicitness(),
                            candidate.confidence(),
                            ws,
                            stability,
                            1,
                            obs.getVersion());
                }
                return new PreferenceAction(
                        PreferenceActionType.CREATE_OBSERVING,
                        candidate.key(),
                        candidate.value(),
                        null,
                        null,
                        candidate.explicitness(),
                        candidate.confidence(),
                        ws,
                        stability,
                        1,
                        1);
            }
        }

        // ── 4. 全新 key / 多值 key 新 value ──
        double stability = stability(1);
        double ws = writeScore(candidate, stability);
        // 明确表达（explicitness≥explicitUpdate）直达 active——用户「明确的改变立即生效、含糊的表达进观察池」
        // 原则；不含此豁免时单次表达稳定性 0.25 封顶 ws=0.70，永远到不了 writeHigh=0.75，显式偏好会全部卡死观察池
        if (candidate.explicitness() >= props.getPreference().getExplicitUpdate()) {
            return new PreferenceAction(
                    PreferenceActionType.CREATE_ACTIVE,
                    candidate.key(),
                    candidate.value(),
                    null,
                    null,
                    candidate.explicitness(),
                    candidate.confidence(),
                    ws,
                    stability,
                    1,
                    1);
        }
        if (ws >= props.getPreference().getWriteHigh()) {
            return new PreferenceAction(
                    PreferenceActionType.CREATE_ACTIVE,
                    candidate.key(),
                    candidate.value(),
                    null,
                    null,
                    candidate.explicitness(),
                    candidate.confidence(),
                    ws,
                    stability,
                    1,
                    1);
        }
        if (ws >= props.getPreference().getObserveLow()) {
            return new PreferenceAction(
                    PreferenceActionType.CREATE_OBSERVING,
                    candidate.key(),
                    candidate.value(),
                    null,
                    null,
                    candidate.explicitness(),
                    candidate.confidence(),
                    ws,
                    stability,
                    1,
                    1);
        }
        return new PreferenceAction(
                PreferenceActionType.IGNORE,
                candidate.key(),
                candidate.value(),
                null,
                null,
                candidate.explicitness(),
                candidate.confidence(),
                ws,
                stability,
                1,
                1);
    }

    /** 既有 active 同 value 强化：count+1、stability/writeScore 重算（保持 active） */
    private PreferenceAction reinforce(PreferenceActionType type, PreferenceCandidate candidate, UserPreference row) {
        int count = row.getObservationCount() + 1;
        double stability = stability(count);
        double ws = writeScore(candidate, stability);
        return new PreferenceAction(
                type,
                candidate.key(),
                candidate.value(),
                row.getId(),
                null,
                candidate.explicitness(),
                candidate.confidence(),
                ws,
                stability,
                count,
                row.getVersion());
    }

    /** stability 线性曲线：min(1, base + count*step)（spec §7.3，1 次=0.25、5 次=0.85） */
    public double stability(int count) {
        return Math.min(
                1.0,
                props.getPreference().getStabilityBase()
                        + count * props.getPreference().getStabilityStep());
    }

    /** write_score = 0.4×explicitness + 0.4×stability + 0.2×confidence（spec §7.3，权重配置化） */
    public double writeScore(PreferenceCandidate candidate, double stability) {
        return props.getPreference().getWeightExplicitness() * candidate.explicitness()
                + props.getPreference().getWeightStability() * stability
                + props.getPreference().getWeightConfidence() * candidate.confidence();
    }
}
