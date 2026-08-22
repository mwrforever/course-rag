package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.constants.PreferenceKeys;
import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.enums.PreferenceActionType;
import com.commerce.rag.mapper.UserPreferenceMapper;
import com.commerce.rag.record.PreferenceAction;
import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceDeletion;
import com.commerce.rag.record.PreferenceExtractionResult;
import com.commerce.rag.service.IPreferenceService;
import com.commerce.rag.service.PreferenceDecisionEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户偏好服务实现 —— 决策执行与落库（spec §7.5/§7.6，PG 事务唯一写入口）
 *
 * <p>本 service 主表操作走内置链式（this.lambdaQuery/lambdaUpdate/removeById/save），
 * 按需取列；软删走 @TableLogic（removeById 置 deleted=1，审计保留物理行）。
 *
 * <p>测试注意（计划 3 实证）：this.lambdaQuery() 不可 Mockito 直测，SQL 段由集成测试覆盖；
 * 纯规则段（toExistingValuesText/collectWrites/toWriteRow）下沉 public 纯函数直测。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PreferenceServiceImpl extends ServiceImpl<UserPreferenceMapper, UserPreference>
        implements IPreferenceService {

    private final PreferenceDecisionEngine decisionEngine;

    @Override
    @Transactional
    public int applyExtraction(Long userId, PreferenceExtractionResult result) {
        if (userId == null) {
            return 0;
        }
        if (result == null) {
            return 0;
        }

        // 1. DELETE 意图先执行（用户明确否定，系统软删，spec §7.5；无需观察期）
        if (result.deletions() != null) {
            for (PreferenceDeletion del : result.deletions()) {
                softDelete(userId, del.key(), del.value());
            }
        }

        // 2. 批量取行：本批候选涉及的全部 key 一次 in 查询（防 N+1），按 key 内存分组——批前快照
        //    （必须位于 deletions 之后，软删行不进入决策视图）
        if (result.candidates() != null && !result.candidates().isEmpty()) {
            Set<String> keys =
                    result.candidates().stream().map(PreferenceCandidate::key).collect(Collectors.toSet());
            List<UserPreference> allRows = this.lambdaQuery()
                    .select(
                            UserPreference::getId,
                            UserPreference::getKey,
                            UserPreference::getValue,
                            UserPreference::getStatus,
                            UserPreference::getObservationCount,
                            UserPreference::getVersion,
                            UserPreference::getWriteScore)
                    .eq(UserPreference::getUserId, userId)
                    .in(UserPreference::getKey, keys)
                    .list();
            Map<String, List<UserPreference>> rowsByKey = allRows.stream()
                    .collect(Collectors.groupingBy(UserPreference::getKey, Collectors.toCollection(ArrayList::new)));

            // 3. 逐候选「决策 → 执行」：决策基于批前快照 + 批内已执行写入的内存视图（执行后同步视图，
            //    同批同 key 后序候选可见前序写入——单值 key 冲突转 UPDATE/REINFORCE 而非撞唯一索引）
            int written = 0;
            for (PreferenceCandidate candidate : result.candidates()) {
                List<UserPreference> rows = rowsByKey.computeIfAbsent(candidate.key(), k -> new ArrayList<>());
                PreferenceAction action = decisionEngine.decide(candidate, rows);
                written += execute(userId, action, rows);
            }
            if (written > 0) {
                log.info(
                        "偏好提取落库: userId={}, 生效动作={}, 候选={}条",
                        userId,
                        written,
                        result.candidates().size());
            }
            return written;
        }
        return 0;
    }

    /**
     * 执行单个决策动作（返回 1=生效写操作/0=IGNORE 无操作）
     *
     * @param view 该 key 的批内内存视图（执行后同步，使同批后序候选可见前序写入；
     *             仅在 applyExtraction 批处理链路上传入）
     */
    private int execute(Long userId, PreferenceAction action, List<UserPreference> view) {
        switch (action.type()) {
            case CREATE_ACTIVE -> {
                UserPreference row = toWriteRow(userId, action, "active", "explicit");
                save(row);
                view.add(row);
            }
            case CREATE_OBSERVING -> {
                UserPreference row = toWriteRow(userId, action, "observing", "explicit");
                save(row);
                view.add(row);
            }
            case REINFORCE -> {
                updateStats(action.targetRowId(), action);
                syncView(view, action, null);
            }
            case OBSERVE_REINFORCE -> {
                updateStats(action.targetRowId(), action);
                syncView(view, action, null);
            }
            case OBSERVE_RESET -> {
                // 观察池覆盖 value、count 重置 1、分数重算（spec §7.5 方向变了重新观察）
                this.lambdaUpdate()
                        .eq(UserPreference::getId, action.targetRowId())
                        .set(UserPreference::getValue, action.value())
                        .set(UserPreference::getObservationCount, action.count())
                        .set(UserPreference::getStability, bd(action.stability()))
                        .set(UserPreference::getWriteScore, bd(action.writeScore()))
                        .set(UserPreference::getUpdatedAt, LocalDateTime.now())
                        .update();
                syncView(view, action, null);
            }
            case PROMOTE -> {
                // 晋升撞车：旧 active 软删审计（spec §7.5「旧值行保留审计」）
                if (action.supersededRowId() != null) {
                    this.removeById(action.supersededRowId());
                }
                this.lambdaUpdate()
                        .eq(UserPreference::getId, action.targetRowId())
                        .set(UserPreference::getStatus, "active")
                        .set(UserPreference::getObservationCount, action.count())
                        .set(UserPreference::getStability, bd(action.stability()))
                        .set(UserPreference::getWriteScore, bd(action.writeScore()))
                        .set(UserPreference::getSource, "implicit")
                        .set(UserPreference::getUpdatedAt, LocalDateTime.now())
                        .update();
                syncView(view, action, action.supersededRowId());
            }
            case UPDATE -> {
                // 明确冲突：旧 active 软删审计 + 新 active version+1（spec §7.5）
                this.removeById(action.supersededRowId());
                UserPreference row = toWriteRow(userId, action, "active", "explicit");
                save(row);
                // 视图同步：旧行退出（软删审计）后新行加入
                view.removeIf(r -> r.getId().equals(action.supersededRowId()));
                view.add(row);
            }
            case IGNORE -> {
                log.debug("偏好候选忽略: userId={}, key={}, value={}", userId, action.key(), action.value());
                return 0;
            }
        }
        return 1;
    }

    /**
     * 行级状态写后同步批内内存视图（REINFORCE/OBSERVE_REINFORCE/OBSERVE_RESET/PROMOTE 用）——
     * 决策引擎后续读取 status/value/observationCount/version 等字段均以视图为准
     *
     * @param view          该 key 的批内内存视图
     * @param action        已执行动作（携带目标行 ID 与重算后的计数/分数）
     * @param removedRowId  软删审计的行 ID（PROMOTE 撞车旧行），null 表示无
     */
    private void syncView(List<UserPreference> view, PreferenceAction action, Long removedRowId) {
        if (removedRowId != null) {
            view.removeIf(r -> r.getId().equals(removedRowId));
        }
        for (UserPreference r : view) {
            if (r.getId().equals(action.targetRowId())) {
                // 与 lambdaUpdate 同步更新：计数/分数/状态（PROMOTE 转 active）与 value（OBSERVE_RESET 覆盖）
                r.setObservationCount(action.count());
                r.setStability(bd(action.stability()));
                r.setWriteScore(bd(action.writeScore()));
                if (action.type() == PreferenceActionType.PROMOTE) {
                    r.setStatus("active");
                    r.setValue(action.value());
                    r.setSource("implicit");
                }
                if (action.type() == PreferenceActionType.OBSERVE_RESET) {
                    r.setValue(action.value());
                }
                return;
            }
        }
    }

    /** 分数重算（REINFORCE/OBSERVE_REINFORCE：count+1 + stability/writeScore 重算） */
    private void updateStats(Long rowId, PreferenceAction action) {
        this.lambdaUpdate()
                .eq(UserPreference::getId, rowId)
                .set(UserPreference::getObservationCount, action.count())
                .set(UserPreference::getStability, bd(action.stability()))
                .set(UserPreference::getWriteScore, bd(action.writeScore()))
                .set(UserPreference::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    /**
     * DELETE 软删：按 (user_id, key, value) 精确匹配 active/observing 行逻辑删除（spec §7.5）
     *
     * @return 命中删除的行数（未命中记日志，视为无操作）
     */
    private int softDelete(Long userId, String key, String value) {
        List<UserPreference> matched = this.lambdaQuery()
                .select(UserPreference::getId)
                .eq(UserPreference::getUserId, userId)
                .eq(UserPreference::getKey, key)
                .eq(UserPreference::getValue, value)
                .list();
        if (matched.isEmpty()) {
            log.info("偏好删除未命中（无需操作）: userId={}, key={}, value={}", userId, key, value);
            return 0;
        }
        int n = 0;
        for (UserPreference row : matched) {
            this.removeById(row.getId());
            n++;
        }
        log.info("偏好软删: userId={}, key={}, value={}, 命中={}行", userId, key, value, n);
        return n;
    }

    @Override
    public String findExistingValuesText(Long userId) {
        List<UserPreference> active = findActiveForInjection(userId);
        return toExistingValuesText(active);
    }

    @Override
    public List<UserPreference> findActiveForInjection(Long userId) {
        return this.lambdaQuery()
                .select(
                        UserPreference::getKey, UserPreference::getValue,
                        UserPreference::getWriteScore, UserPreference::getStatus)
                .eq(UserPreference::getUserId, userId)
                .eq(UserPreference::getStatus, "active")
                .orderByDesc(UserPreference::getWriteScore)
                .list();
    }

    // ========================================================================
    // 纯函数（public 供单测直测；SQL 段不可 Mockito，见类注释）
    // ========================================================================

    /**
     * active 偏好行 → 「标签:值」逐行文本（提取 prompt 同义收敛输入，spec §7.4-③）
     *
     * @param active active 行列表（可为空）
     * @return 文本（空列表返回「无」，与 findExistingValuesText 对外契约一致）
     */
    public String toExistingValuesText(List<UserPreference> active) {
        if (active == null || active.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (UserPreference row : active) {
            String label = PreferenceKeys.LABELS.getOrDefault(row.getKey(), row.getKey());
            sb.append(label).append(":").append(row.getValue()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 计算动作列表中的生效写数（IGNORE 不计，其余各计 1）
     *
     * @param actions 决策动作列表
     * @return 生效写操作数
     */
    public int collectWrites(List<PreferenceAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return 0;
        }
        return (int) actions.stream()
                .filter(a -> a.type() != PreferenceActionType.IGNORE)
                .count();
    }

    /**
     * 由动作构造待写入的偏好行（CREATE_ACTIVE/CREATE_OBSERVING/UPDATE 用）
     *
     * @param userId 所属用户
     * @param action 决策动作（含重算分数/计数/版本）
     * @param status 目标状态（active/observing）
     * @param source 来源（explicit/implicit）
     * @return 待持久化实体（id 由 ASSIGN_ID 填充）
     */
    public UserPreference toWriteRow(Long userId, PreferenceAction action, String status, String source) {
        UserPreference row = new UserPreference();
        row.setUserId(userId);
        row.setKey(action.key());
        row.setValue(action.value());
        row.setExplicitness(bd(action.explicitness()));
        row.setStability(bd(action.stability()));
        row.setConfidence(bd(action.confidence()));
        row.setWriteScore(bd(action.writeScore()));
        row.setStatus(status);
        row.setObservationCount(action.count());
        row.setVersion(action.version());
        row.setSource(source);
        return row;
    }

    /** double → BigDecimal（保留 3 位小数，与 NUMERIC(4,3) 一致） */
    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
