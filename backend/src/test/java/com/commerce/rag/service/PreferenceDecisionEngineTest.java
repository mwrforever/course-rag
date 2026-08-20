package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.enums.PreferenceActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.PreferenceAction;
import com.commerce.rag.record.PreferenceCandidate;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 偏好决策引擎测试 —— write_score 纯规则全分支（spec §7.3/§7.5） */
class PreferenceDecisionEngineTest {

    private final MemoryProperties props = new MemoryProperties();
    private final PreferenceDecisionEngine engine = new PreferenceDecisionEngine(props);

    /** 构造既有行（status/observationCount/version/value） */
    private static UserPreference row(String status, int count, int version, String value) {
        UserPreference r = new UserPreference();
        r.setId((long) version * 100L);
        r.setStatus(status);
        r.setObservationCount(count);
        r.setVersion(version);
        r.setValue(value);
        r.setWriteScore(BigDecimal.ZERO);
        return r;
    }

    @Test
    @DisplayName("全新单值 key + 高 explicitness → write_score≥0.75 → CREATE_ACTIVE")
    void newSingleKeyHighScore_createsActive() {
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "中文", 1.0, 0.9), List.of());
        assertEquals(PreferenceActionType.CREATE_ACTIVE, action.type());
        assertEquals(1, action.version());
        assertEquals(1.0, action.explicitness(), 0.0001);
        assertNull(action.supersededRowId());
    }

    @Test
    @DisplayName("全新单值 key + 中分数 → write_score in [0.50,0.75) → CREATE_OBSERVING")
    void newKeyMidScore_createsObserving() {
        // explicitness 0.7, confidence 0.8 → ws = 0.4*0.7 + 0.4*0.25 + 0.2*0.8 = 0.54 ∈ [0.50, 0.75)
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "英文", 0.7, 0.8), List.of());
        assertEquals(PreferenceActionType.CREATE_OBSERVING, action.type());
        assertEquals(1, action.count());
        assertEquals(1, action.version());
    }

    @Test
    @DisplayName("同 key+同 value 命中 active → REINFORCE（count+1、分数重算、保持 active）")
    void sameValueActive_reinforces() {
        UserPreference active = row("active", 3, 2, "简洁");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_verbosity", "简洁", 0.9, 0.9), List.of(active));
        assertEquals(PreferenceActionType.REINFORCE, action.type());
        assertEquals(active.getId(), action.targetRowId());
        assertEquals(4, action.count(), "观察计数 +1");
        // stability(4)=0.1+4*0.15=0.70；ws=0.4*0.9+0.4*0.7+0.2*0.9=0.82
        assertEquals(0.70, action.stability(), 0.0001);
        assertEquals(0.82, action.writeScore(), 0.0001);
    }

    @Test
    @DisplayName("同 key+同 value 命中 observing（count=2）+ 高分 → PROMOTE（升 active，无替换）")
    void observingPromoted_onThreshold() {
        UserPreference obs = row("observing", 4, 1, "简洁");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_verbosity", "简洁", 0.95, 0.95), List.of(obs));
        assertEquals(PreferenceActionType.PROMOTE, action.type());
        assertEquals(obs.getId(), action.targetRowId());
        assertEquals(5, action.count());
        assertNull(action.supersededRowId());
    }

    @Test
    @DisplayName("observing 未达线 → OBSERVE_REINFORCE（count+1，不晋升）")
    void observingNotThreshold_observesReinforce() {
        UserPreference obs = row("observing", 1, 1, "详尽");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_verbosity", "详尽", 0.8, 0.8), List.of(obs));
        assertEquals(PreferenceActionType.OBSERVE_REINFORCE, action.type());
        assertEquals(2, action.count());
    }

    @Test
    @DisplayName("单值 key 晋升撞车 → PROMOTE 携带被替换的旧 active 行 id（审计软删）")
    void observePromote_replacesOtherActive() {
        UserPreference activeOther = row("active", 1, 1, "详尽"); // 已有不同值 active
        UserPreference obs = row("observing", 4, 1, "简洁");
        PreferenceAction action = engine.decide(
                new PreferenceCandidate("response_verbosity", "简洁", 0.95, 0.95), List.of(activeOther, obs));
        assertEquals(PreferenceActionType.PROMOTE, action.type());
        assertEquals(activeOther.getId(), action.supersededRowId(), "晋升应替换旧 active（审计）");
    }

    @Test
    @DisplayName("单值 key 冲突 + explicitness≥0.8 → UPDATE（旧 active 软删审计 + version+1）")
    void singleConflictExplicit_updates() {
        UserPreference active = row("active", 1, 1, "中文");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "英文", 0.9, 0.9), List.of(active));
        assertEquals(PreferenceActionType.UPDATE, action.type());
        assertEquals(active.getId(), action.supersededRowId());
        assertEquals(2, action.version(), "新 active version = 旧+1");
    }

    @Test
    @DisplayName("单值 key 冲突 + 含糊（explicitness<0.8）→ 观察池覆盖 value、count 重置 1")
    void singleConflictVague_observesReset() {
        UserPreference active = row("active", 1, 1, "中文");
        UserPreference obs = row("observing", 3, 1, "直白");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "英文", 0.6, 0.7), List.of(active, obs));
        assertEquals(PreferenceActionType.OBSERVE_RESET, action.type());
        assertEquals(obs.getId(), action.targetRowId());
        assertEquals(1, action.count(), "不同 value 观察覆盖后 count 重置 1");
    }

    @Test
    @DisplayName("多值 key 新 value → 直接 CREATE_ACTIVE（同 key 已有其他 value 不冲突）")
    void multiValueNewValue_createsActive() {
        UserPreference existing = row("active", 1, 1, "Python 开发");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("course_direction", "数据分析", 0.95, 0.95), List.of(existing));
        assertEquals(PreferenceActionType.CREATE_ACTIVE, action.type());
        assertNull(action.supersededRowId(), "多值 key 不替换已有行");
    }

    @Test
    @DisplayName("多值 key 同 value 命中 active → REINFORCE（不是 CREATE 重复行）")
    void multiValueSameValue_reinforces() {
        UserPreference active = row("active", 2, 1, "Java");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("tech_stack", "Java", 0.85, 0.85), List.of(active));
        assertEquals(PreferenceActionType.REINFORCE, action.type());
    }

    @Test
    @DisplayName("全新低分 → IGNORE（write_score < 0.50）")
    void newLowScore_ignored() {
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "中文", 0.3, 0.3), List.of());
        assertEquals(PreferenceActionType.IGNORE, action.type());
    }

    @Test
    @DisplayName("rowsForKey 为 null（防御）→ 等同空行集，走规则链不抛异常")
    void nullRowsKey_defensive() {
        PreferenceAction action = engine.decide(new PreferenceCandidate("response_language", "中文", 0.3, 0.3), null);
        assertEquals(PreferenceActionType.IGNORE, action.type());
    }

    @Test
    @DisplayName("observing 达 count 线但 write_score 未达 promoteMinScore → 仍 OBSERVE_REINFORCE（分数门控）")
    void observingReachedCount_butScoreGated_staysObserving() {
        UserPreference obs = row("observing", 4, 1, "简洁");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_verbosity", "简洁", 0.5, 0.5), List.of(obs));
        assertEquals(PreferenceActionType.OBSERVE_REINFORCE, action.type());
        assertEquals(5, action.count(), "count 已到 5 但仍被 write_score 门控");
    }

    @Test
    @DisplayName("多值 key 观测同 value 达线 → PROMOTE 且不替换任何行（superseded 为 null）")
    void multiValueObservingPromoted_noSuperseded() {
        UserPreference obs = row("observing", 4, 1, "Java");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("tech_stack", "Java", 0.95, 0.95), List.of(obs));
        assertEquals(PreferenceActionType.PROMOTE, action.type());
        assertNull(action.supersededRowId(), "多值 key 晋升不替换");
    }

    @Test
    @DisplayName("单值 key 含糊冲突且无 observing 行 → 新开观察池行 CREATE_OBSERVING")
    void singleConflictVague_noObs_createsObserving() {
        UserPreference active = row("active", 1, 1, "中文");
        PreferenceAction action =
                engine.decide(new PreferenceCandidate("response_language", "英文", 0.6, 0.7), List.of(active));
        assertEquals(PreferenceActionType.CREATE_OBSERVING, action.type());
        assertNull(action.targetRowId());
        assertNull(action.supersededRowId());
        assertEquals(1, action.count());
        assertEquals(1, action.version());
    }

    @Test
    @DisplayName("非明确高分（explicitness<0.8 但 write_score≥writeHigh）→ CREATE_ACTIVE（config 调低 writeHigh 命中该分支）")
    void nonExplicitHighWriteScore_createsActive() {
        MemoryProperties lowWriteHigh = new MemoryProperties();
        lowWriteHigh.getPreference().setWriteHigh(0.50);
        PreferenceDecisionEngine lowHighEngine = new PreferenceDecisionEngine(lowWriteHigh);
        PreferenceAction action =
                lowHighEngine.decide(new PreferenceCandidate("response_language", "英文", 0.7, 1.0), List.of());
        // ws = 0.4*0.7 + 0.4*0.25 + 0.2*1.0 = 0.58 ≥ 0.50
        assertEquals(PreferenceActionType.CREATE_ACTIVE, action.type());
        assertEquals(0.58, action.writeScore(), 0.0001);
    }

    @Test
    @DisplayName("stability 曲线 —— 1 次=0.25、5 次=0.85、10 次封顶 1.0")
    void stabilityCurve() {
        assertEquals(0.25, engine.stability(1), 0.0001);
        assertEquals(0.85, engine.stability(5), 0.0001);
        assertEquals(1.0, engine.stability(10), 0.0001);
    }

    @Test
    @DisplayName("write_score —— 公式 0.4e+0.4s+0.2c 权重生效")
    void writeScoreFormula() {
        assertEquals(0.82, engine.writeScore(new PreferenceCandidate("k", "v", 0.9, 0.9), 0.7), 0.0001);
    }
}
