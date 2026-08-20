package com.commerce.rag.service;

import com.commerce.rag.constants.PreferenceKeys;
import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.etl.TokenEstimator;
import com.commerce.rag.properties.MemoryProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 偏好块组装 —— active 偏好 → &lt;preference&gt; 文本（spec §7.7/§7.8）
 *
 * <p>预算分配：guaranteed 保底（response_language/verbosity/explain_depth，500 token 先注）
 * + 扩展段（其余按 write_score 降序，1500 token 用完截断）。多值 key 输出全部 active 值
 * （完整画像，spec §7.2 定稿）。token 估算用 {@link TokenEstimator}（中文 1 字≈1 token）。
 *
 * @author commerce-rag
 */
@Service
public class PreferenceBlockService {

    private final MemoryProperties properties;

    public PreferenceBlockService(MemoryProperties properties) {
        this.properties = properties;
    }

    /**
     * 组装偏好块文本
     *
     * @param active active 偏好行列表（可为空）
     * @return &lt;preference&gt; 块文本；无任何偏好返回空串（调用方不注入）
     */
    public String build(List<UserPreference> active) {
        if (active == null || active.isEmpty()) {
            return "";
        }
        // 1. 按 key 分组保留完整值列表（多值 key 全部 active 值并联）
        Map<String, List<UserPreference>> byKey = new LinkedHashMap<>();
        for (UserPreference row : active) {
            if (row.getKey() == null || row.getValue() == null || !PreferenceKeys.isKnown(row.getKey())) {
                continue; // 未知 key 行防御性跳过（正常不会出现，key 白名单已约束）
            }
            byKey.computeIfAbsent(row.getKey(), k -> new ArrayList<>()).add(row);
        }
        if (byKey.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder("<preference>\n");
        // 2. guaranteed 段（硬偏好保底 500 token，spec §7.8）
        int gTokens = 0;
        for (String key : PreferenceKeys.GUARANTEED_KEYS) {
            List<UserPreference> rows = byKey.get(key);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            String line = labelBlockLine(key, rows);
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (gTokens + TokenEstimator.estimate(line)
                    > properties.getPreference().getTokenGuaranteed()) {
                break;
            }
            sb.append(line);
            gTokens += TokenEstimator.estimate(line);
        }

        // 3. 扩展段（其余 key 按 write_score 降序，1500 token 用完截断）
        int eTokens = 0;
        List<String> extendedKeys = byKey.keySet().stream()
                .filter(k -> !PreferenceKeys.GUARANTEED_KEYS.contains(k))
                .sorted(Comparator.comparingDouble((String k) -> maxScore(byKey.get(k)))
                        .reversed())
                .toList();
        for (String key : extendedKeys) {
            String line = labelBlockLine(key, byKey.get(key));
            if (line == null || line.isEmpty()) {
                continue;
            }
            if (eTokens + TokenEstimator.estimate(line)
                    > properties.getPreference().getTokenExtended()) {
                break;
            }
            sb.append(line);
            eTokens += TokenEstimator.estimate(line);
        }

        // 4. 收尾（防止仅含空壳）
        if (sb.length() == "<preference>\n".length()) {
            return "";
        }
        sb.append("</preference>");
        return sb.toString();
    }

    /** 组装单行「标签:值1、值2\n」（多值 key 值并列；全空值返回 null） */
    private String labelBlockLine(String key, List<UserPreference> rows) {
        String label = PreferenceKeys.LABELS.getOrDefault(key, key);
        // 同 key 多值按 write_score 降序拼接（与 findActiveForInjection 返回序一致，高分值在前）
        String joined = rows.stream()
                .sorted(Comparator.comparingDouble((UserPreference r) -> r.getWriteScore() == null
                                ? 0.0
                                : r.getWriteScore().doubleValue())
                        .reversed())
                .map(UserPreference::getValue)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .reduce((a, b) -> a + "、" + b)
                .orElse("");
        if (joined.isEmpty()) {
            return null;
        }
        return label + ":" + joined + "\n";
    }

    /** 该 key 各行 writeScore 最大值（扩展排序依据） */
    private double maxScore(List<UserPreference> rows) {
        return rows.stream()
                .map(UserPreference::getWriteScore)
                .filter(s -> s != null)
                .map(BigDecimal::doubleValue)
                .max(Double::compareTo)
                .orElse(0.0);
    }
}
