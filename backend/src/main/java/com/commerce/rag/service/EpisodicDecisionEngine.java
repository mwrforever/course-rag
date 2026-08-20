package com.commerce.rag.service;

import com.commerce.rag.constants.EpisodicTypes;
import com.commerce.rag.entity.UserEpisodicMemory;
import com.commerce.rag.enums.EpisodicActionType;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicAction;
import com.commerce.rag.record.EpisodicMemoryExtraction;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 经历记忆决策引擎 —— memory_score 纯系统规则 + action 状态机（spec §8.3/§8.6）
 *
 * <p>零 DB 访问（纯函数可单测）：输入 = 提取条目 + 该用户同 type 的 active 既有行，输出 = 动作。
 * 规则全表：
 * <pre>
 * 0. 门槛统一前置：memory_score = 0.4e + 0.3c + 0.3×(importance×typeWeight)
 *    &lt; writeHigh(0.7) → IGNORE（无观察池，任何 action 均不豁免，spec §8.3/§8.6 冲突不混入打分）
 * 1. CREATE：同 type+同 content 已有 active 行 → 重复 IGNORE；否则 CREATE(version=1)
 * 2. UPDATE/MERGE：merge_target 按「同 type + content 逐字匹配」定位目标行
 *    ├─ 命中 → UPDATE（supersede 旧行 + 新行 version+1）/ MERGE（merged 旧行 + 合并内容新行 version+1）
 *    └─ 未命中 → 首见演进降级 CREATE（版本 1）
 * 3. INVALIDATE：merge_target 定位目标行 → 命中则 INVALIDATE（无新行）；未命中 → IGNORE
 * </pre>
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class EpisodicDecisionEngine {

    private final MemoryProperties props;

    public EpisodicDecisionEngine(MemoryProperties props) {
        this.props = props;
    }

    /**
     * 对一个提取条目执行纯规则决策
     *
     * @param candidate   提取条目（type 已过白名单）
     * @param rowsForType 该 (user_id, type) 的全部 active 既有行（deleted=0 自动过滤）
     * @return 决策动作（never null）
     */
    public EpisodicAction decide(EpisodicMemoryExtraction candidate, List<UserEpisodicMemory> rowsForType) {
        List<UserEpisodicMemory> rows = rowsForType == null
                ? List.of()
                : rowsForType.stream()
                        .filter(r -> "active".equals(r.getValidity()))
                        .toList();
        if (!candidate.isMemory()) {
            // is_memory=false：无事实，不产生行（spec §8.6 无 action）
            return ignore(candidate, 0.0);
        }
        double weightedImportance = clamp(candidate.importance()) * typeWeight(candidate.type());
        double score = memoryScore(candidate, weightedImportance);
        // 门槛统一前置（spec §8.3：<0.7 → IGNORE，无观察池；§8.6 冲突不混入打分修正）
        if (score < props.getEpisodic().getWriteHigh()) {
            return ignore(candidate, score);
        }

        String action = candidate.action() == null ? "" : candidate.action();
        UserEpisodicMemory target = matchTarget(rows, candidate.mergeTarget());
        switch (action) {
            case "CREATE" -> {
                // 重复：同 type + 同 content 已有 active 行 → 无新事实（防止重复堆积）
                boolean dup = rows.stream().anyMatch(r -> sameContent(r.getContent(), candidate.content()));
                if (dup) {
                    return ignore(candidate, score);
                }
                return new EpisodicAction(
                        EpisodicActionType.CREATE,
                        candidate.type(),
                        candidate.content(),
                        candidate.summary(),
                        candidate.structuredFacts(),
                        null,
                        1,
                        weightedImportance,
                        candidate.confidence(),
                        score);
            }
            case "UPDATE", "MERGE" -> {
                if (target == null) {
                    // 目标未命中：首见该事实演进 → 降级 CREATE（版本 1，后续由 MERGE 承接）
                    logMissedTarget(candidate);
                    return new EpisodicAction(
                            EpisodicActionType.CREATE,
                            candidate.type(),
                            candidate.content(),
                            candidate.summary(),
                            candidate.structuredFacts(),
                            null,
                            1,
                            weightedImportance,
                            candidate.confidence(),
                            score);
                }
                return new EpisodicAction(
                        "UPDATE".equals(action) ? EpisodicActionType.UPDATE : EpisodicActionType.MERGE,
                        candidate.type(),
                        candidate.content(),
                        candidate.summary(),
                        candidate.structuredFacts(),
                        target.getId(),
                        target.getVersion() + 1,
                        weightedImportance,
                        candidate.confidence(),
                        score);
            }
            case "INVALIDATE" -> {
                if (target == null) {
                    // 目标未命中：无目标可否定 → IGNORE
                    return ignore(candidate, score);
                }
                return new EpisodicAction(
                        EpisodicActionType.INVALIDATE,
                        candidate.type(),
                        target.getContent(),
                        target.getSummary(),
                        null,
                        target.getId(),
                        target.getVersion(),
                        weightedImportance,
                        candidate.confidence(),
                        score);
            }
            default -> {
                // 未知动作视为无事实（LLM 输出容错，不计分修正）
                return ignore(candidate, score);
            }
        }
    }

    /** memory_score = 0.4×explicitness + 0.3×confidence + 0.3×(importance×typeWeight)（spec §8.3，权重配置化） */
    public double memoryScore(EpisodicMemoryExtraction candidate, double weightedImportance) {
        return props.getEpisodic().getWeightExplicitness() * clamp(candidate.explicitness())
                + props.getEpisodic().getWeightConfidence() * clamp(candidate.confidence())
                + props.getEpisodic().getWeightImportance() * weightedImportance;
    }

    /** 类型权重系统校正（spec §8.3；配置文件缺失时用 EpisodicTypes 默认兜底） */
    public double typeWeight(String type) {
        Double w = props.getEpisodic().getTypeWeights().get(type);
        return w != null ? w : EpisodicTypes.defaultWeight(type);
    }

    /** merge_target 按「同 type + content 逐字匹配（trim 后）」定位 active 目标行 */
    private UserEpisodicMemory matchTarget(List<UserEpisodicMemory> rows, String mergeTarget) {
        if (mergeTarget == null || mergeTarget.isBlank()) {
            return null;
        }
        String target = mergeTarget.trim();
        return rows.stream()
                .filter(r -> sameContent(r.getContent(), target))
                .findFirst()
                .orElse(null);
    }

    /** content 逐字匹配（null 安全；LLM 引用偏差容忍 trim） */
    private static boolean sameContent(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equals(b.trim());
    }

    private static EpisodicAction ignore(EpisodicMemoryExtraction candidate, double score) {
        return new EpisodicAction(
                EpisodicActionType.IGNORE,
                candidate.type(),
                null,
                null,
                null,
                null,
                1,
                candidate.importance(),
                candidate.confidence(),
                score);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static void logMissedTarget(EpisodicMemoryExtraction candidate) {
        // 目标未命中降级 CREATE：属预期演进路径，info 级留痕（不打断决策）
        log.info("经历记忆目标未命中，降级 CREATE: type={}, content={}", candidate.type(), candidate.content());
    }
}
