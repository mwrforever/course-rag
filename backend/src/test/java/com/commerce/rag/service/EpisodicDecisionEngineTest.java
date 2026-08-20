package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.commerce.rag.constants.EpisodicTypes;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.enums.EpisodicActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicAction;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 经历记忆决策引擎测试 —— memory_score 纯规则 + action 状态机全分支（spec §8.3/§8.6），行覆盖 100% */
class EpisodicDecisionEngineTest {

    private final MemoryProperties props = new MemoryProperties();
    private final EpisodicDecisionEngine engine = new EpisodicDecisionEngine(props);

    /** 构造提取候选（isMemory/action/type/content/.../mergeTarget 全参数） */
    private static EpisodicMemoryExtraction cand(
            boolean isMemory,
            String action,
            String type,
            String content,
            String summary,
            String facts,
            double importance,
            double explicitness,
            double confidence,
            String mergeTarget) {
        return new EpisodicMemoryExtraction(
                isMemory, action, type, content, summary, facts, importance, explicitness, confidence, mergeTarget);
    }

    /** 构造既有行（含 summary 版） */
    private static UserEpisodicMemory row(
            long id, String type, String content, String summary, int version, String validity) {
        UserEpisodicMemory r = new UserEpisodicMemory();
        r.setId(id);
        r.setType(type);
        r.setContent(content);
        r.setSummary(summary);
        r.setVersion(version);
        r.setValidity(validity);
        return r;
    }

    /** 构造既有行（无 summary 简版） */
    private static UserEpisodicMemory row(long id, String type, String content, int version, String validity) {
        return row(id, type, content, null, version, validity);
    }

    @Test
    @DisplayName("无既有行 + action=CREATE + 高分 → CREATE（version=1，targetRowId=null）")
    void create_newMemory_writesActiveRow() {
        // e=0.8/c=0.8/i=0.8 ×learning_goal(1.0) → score=0.32+0.24+0.24=0.80 ≥ 0.7
        EpisodicAction action = engine.decide(
                cand(
                        true,
                        "CREATE",
                        EpisodicTypes.LEARNING_GOAL,
                        "已掌握 Java 集合泛型",
                        "摘",
                        "{\"topic\":\"java\"}",
                        0.8,
                        0.8,
                        0.8,
                        "   "),
                List.of());
        assertEquals(EpisodicActionType.CREATE, action.type());
        assertEquals(1, action.version());
        assertNull(action.targetRowId());
        assertEquals(EpisodicTypes.LEARNING_GOAL, action.memoryType());
        assertEquals("已掌握 Java 集合泛型", action.content());
        assertEquals(0.80, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName("rowsForType=null → 视为无既有行（防御性），高分 CREATE 返回 CREATE")
    void create_nullRowsForType_handledAsEmpty() {
        // e=0.8/c=0.8/i=0.8 ×learning_progress(0.9) → score=0.32+0.24+0.216=0.776 ≥ 0.7
        EpisodicAction action = engine.decide(
                cand(true, "CREATE", EpisodicTypes.LEARNING_PROGRESS, "已学会 Java 集合", "摘", null, 0.8, 0.8, 0.8, null),
                null);
        assertEquals(EpisodicActionType.CREATE, action.type(), "null 既有行按空列表处理，首见事实走 CREATE");
        assertEquals(1, action.version());
    }

    @Test
    @DisplayName("同 type+同 content 已有 active 行 → CREATE 重复 IGNORE")
    void create_duplicateContent_ignored() {
        EpisodicAction action = engine.decide(
                cand(true, "CREATE", EpisodicTypes.LEARNING_GOAL, "已学会 Java 集合", "摘", null, 0.8, 0.8, 0.8, null),
                List.of(row(10, EpisodicTypes.LEARNING_GOAL, "已学会 Java 集合", 1, "active")));
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertNull(action.targetRowId());
    }

    @Test
    @DisplayName("低分（score=0.30<0.7）→ IGNORE（门槛统一前置）")
    void create_lowScore_ignored() {
        EpisodicAction action = engine.decide(
                cand(true, "CREATE", EpisodicTypes.LEARNING_GOAL, "低分事实", "摘", null, 0.3, 0.3, 0.3, null), List.of());
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertEquals(0.30, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName("action=UPDATE + merge_target 命中 → UPDATE（targetRowId=旧行，version=旧+1；superseded 行被过滤）")
    void update_foundTarget_supersedesAndBumpsVersion() {
        // 附带同 content 的 superseded 行，验证仅 active 行参与匹配
        List<UserEpisodicMemory> rows = List.of(
                row(100, EpisodicTypes.LEARNING_GOAL, "旧内容", "旧摘要", 2, "active"),
                row(101, EpisodicTypes.LEARNING_GOAL, "旧内容", 1, "superseded"));
        EpisodicAction action = engine.decide(
                cand(true, "UPDATE", EpisodicTypes.LEARNING_GOAL, "新内容", "新摘要", "{}", 0.8, 0.8, 0.8, "旧内容"), rows);
        assertEquals(EpisodicActionType.UPDATE, action.type());
        assertEquals(100L, action.targetRowId());
        assertEquals(3, action.version(), "新行版本 = 目标行版本+1");
        assertEquals("新内容", action.content());
    }

    @Test
    @DisplayName("action=UPDATE + merge_target 未命中 → 降级 CREATE（version=1，日志留痕）")
    void update_targetMissed_degradesToCreate() {
        // 含 content=null 的行，覆盖同 content 匹配的 null 安全分支
        List<UserEpisodicMemory> rows = List.of(
                row(1, EpisodicTypes.LEARNING_GOAL, null, 1, "active"),
                row(2, EpisodicTypes.LEARNING_GOAL, "其他内容", 1, "active"));
        EpisodicAction action = engine.decide(
                cand(true, "UPDATE", EpisodicTypes.LEARNING_GOAL, "新内容", "新摘要", "{}", 0.8, 0.8, 0.8, "不存在的目标"), rows);
        assertEquals(EpisodicActionType.CREATE, action.type());
        assertEquals(1, action.version());
        assertNull(action.targetRowId());
    }

    @Test
    @DisplayName("action=MERGE + 命中 → MERGE（targetRowId=旧行，version=旧+1）")
    void merge_foundTarget_mergedValidity() {
        EpisodicAction action = engine.decide(
                cand(true, "MERGE", EpisodicTypes.LEARNING_PROGRESS, "合并陈述", "合摘", "{}", 0.8, 0.8, 0.8, "旧内容"),
                List.of(row(200, EpisodicTypes.LEARNING_PROGRESS, "旧内容", "旧摘要", 1, "active")));
        assertEquals(EpisodicActionType.MERGE, action.type());
        assertEquals(200L, action.targetRowId());
        assertEquals(2, action.version());
        assertEquals("合并陈述", action.content());
    }

    @Test
    @DisplayName("action=MERGE + 未命中 → 降级 CREATE（version=1）")
    void merge_targetMissed_degradesToCreate() {
        EpisodicAction action = engine.decide(
                cand(true, "MERGE", EpisodicTypes.RESOLVED_QUESTION, "合并陈述", "合摘", "{}", 0.8, 0.8, 0.8, "不存在"),
                List.of(row(3, EpisodicTypes.RESOLVED_QUESTION, "其他内容", 1, "active")));
        assertEquals(EpisodicActionType.CREATE, action.type());
        assertEquals(1, action.version());
        assertNull(action.targetRowId());
    }

    @Test
    @DisplayName("action=INVALIDATE + 命中 → INVALIDATE（content=目标行、summary 保留、无新行、version=目标版本）")
    void invalidate_foundTarget_setsInvalidated() {
        EpisodicAction action = engine.decide(
                cand(true, "INVALIDATE", EpisodicTypes.PERSONAL_CONTEXT, "任意内容", "任意摘", null, 0.8, 0.8, 0.8, "旧内容"),
                List.of(row(300, EpisodicTypes.PERSONAL_CONTEXT, "旧内容", "旧摘要", 2, "active")));
        assertEquals(EpisodicActionType.INVALIDATE, action.type());
        assertEquals(300L, action.targetRowId());
        assertEquals("旧内容", action.content(), "INVALIDATE 走目标行 content");
        assertEquals("旧摘要", action.summary(), "INVALIDATE 保留目标行 summary");
        assertNull(action.structuredFacts());
        assertEquals(2, action.version(), "INVALIDATE 无新行，version=目标行版本");
    }

    @Test
    @DisplayName("action=INVALIDATE + 未命中 → IGNORE（无目标可否定）")
    void invalidate_targetMissed_ignored() {
        EpisodicAction action = engine.decide(
                cand(true, "INVALIDATE", EpisodicTypes.LEARNING_GOAL, "x", "s", null, 0.8, 0.8, 0.8, "不存在"),
                List.of(row(4, EpisodicTypes.LEARNING_GOAL, "其他内容", 1, "active")));
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertNull(action.targetRowId());
    }

    @Test
    @DisplayName("is_memory=false → IGNORE（无事实不产生行，score=0）")
    void isMemoryFalse_ignored() {
        EpisodicAction action = engine.decide(
                cand(false, "CREATE", EpisodicTypes.LEARNING_GOAL, "x", "s", null, 0.9, 0.9, 0.9, null), List.of());
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertEquals(0.0, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName("未知 action → IGNORE（LLM 输出容错，不计分修正）")
    void unknownAction_ignored() {
        EpisodicAction action = engine.decide(
                cand(true, "foo", EpisodicTypes.LEARNING_GOAL, "x", "s", null, 0.8, 0.8, 0.8, null), List.of());
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertEquals(0.80, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName("action=INVALIDATE + 命中目标但低分 → IGNORE（门槛先于 action 判定，冲突不混入打分）")
    void invalidate_lowScore_ignoredThresholdFirst() {
        EpisodicAction action = engine.decide(
                cand(true, "INVALIDATE", EpisodicTypes.LEARNING_GOAL, "x", "s", null, 0.3, 0.3, 0.3, "旧内容"),
                List.of(row(400, EpisodicTypes.LEARNING_GOAL, "旧内容", 1, "active")));
        assertEquals(EpisodicActionType.IGNORE, action.type());
        assertEquals(0.30, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName(
            "importance×typeWeight 生效 —— same 参数 type=personal_context(×0.8)→0.69 IGNORE / learning_goal(×1.0)→0.75 CREATE")
    void importanceWeightedByType() {
        // e=0.6/c=0.7/i=1.0：personal_context → 0.24+0.21+0.3×(1.0×0.8)=0.69 <0.7 → IGNORE
        EpisodicAction low = engine.decide(
                cand(true, "CREATE", EpisodicTypes.PERSONAL_CONTEXT, "背景事实", "摘", null, 1.0, 0.6, 0.7, null),
                List.of());
        assertEquals(EpisodicActionType.IGNORE, low.type());
        assertEquals(0.69, low.memoryScore(), 0.0001);
        // 同参数 learning_goal → 0.24+0.21+0.3×1.0=0.75 ≥0.7 → CREATE（同分异动作证明 typeWeight 生效）
        EpisodicAction high = engine.decide(
                cand(true, "CREATE", EpisodicTypes.LEARNING_GOAL, "目标事实", "摘", null, 1.0, 0.6, 0.7, null), List.of());
        assertEquals(EpisodicActionType.CREATE, high.type());
        assertEquals(0.75, high.memoryScore(), 0.0001);
        assertEquals(0.8, engine.typeWeight(EpisodicTypes.PERSONAL_CONTEXT), 0.0001);
        assertEquals(1.0, engine.typeWeight(EpisodicTypes.LEARNING_GOAL), 0.0001);
    }

    @Test
    @DisplayName("配置缺该 type → EpisodicTypes 默认权重兜底（learning_progress 0.9）")
    void typeWeight_missingInProps_usesDefault() {
        props.getEpisodic().getTypeWeights().clear();
        assertEquals(0.9, engine.typeWeight(EpisodicTypes.LEARNING_PROGRESS), 0.0001, "配置缺失回退常量默认值");
        // decide 路径同样兜底：e=0.7/c=0.7/i=1.0 → 0.28+0.21+0.3×(1.0×0.9)=0.76 → CREATE
        EpisodicAction action = engine.decide(
                cand(true, "CREATE", EpisodicTypes.LEARNING_PROGRESS, "进度事实", "摘", null, 1.0, 0.7, 0.7, null),
                List.of());
        assertEquals(EpisodicActionType.CREATE, action.type());
        assertEquals(0.76, action.memoryScore(), 0.0001);
    }

    @Test
    @DisplayName("memory_score 公式 —— e=0.8/c=0.7/i=0.9(×1.0) → 0.32+0.21+0.27=0.80")
    void memoryScore_formula() {
        EpisodicMemoryExtraction cand =
                cand(true, "CREATE", EpisodicTypes.LEARNING_GOAL, "公式验证", "摘", null, 0.9, 0.8, 0.7, null);
        assertEquals(0.80, engine.memoryScore(cand, 0.9), 0.0001);
    }
}
