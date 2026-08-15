package com.commerce.rag.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * F#3 置信度配置属性 —— 检索结果质量判定
 *
 * <p>判定规则：
 * <ul>
 *   <li>API 工具调用 → HIGH（数据源可信）</li>
 *   <li>检索分 ≥ {@code searchHighThreshold} → HIGH</li>
 *   <li>检索分 ≥ {@code searchMediumThreshold} → MEDIUM</li>
 *   <li>多源命中数 ≥ {@code multiSourceMin} → MEDIUM（兜底）</li>
 *   <li>否则 → LOW</li>
 * </ul>
 *
 * <p>绑定 YAML 路径：{@code rag.confidence.*}
 *
 * @param searchHighThreshold   检索分 HIGH 阈值（≥ 此值 → HIGH）
 * @param searchMediumThreshold 检索分 MEDIUM 阈值（≥ 此值 → MEDIUM）
 * @param multiSourceMin        多源命中最小数量（≥ 此值 → MEDIUM 兜底）
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "rag.confidence")
public record ConfidenceProperties(
        @Min(0) double searchHighThreshold, @Min(0) double searchMediumThreshold, @Min(1) int multiSourceMin) {

    public ConfidenceProperties {
        if (searchHighThreshold <= 0) searchHighThreshold = 0.8;
        if (searchMediumThreshold <= 0) searchMediumThreshold = 0.5;
        if (multiSourceMin <= 0) multiSourceMin = 2;
    }
}
