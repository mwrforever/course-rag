package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * F#3 Token 预算配置属性 —— 防止单次 run 消耗过多 token
 *
 * <p>基于 qwen3.7-max 128K 上下文窗口，200K 总预算给约 2.5× 余量。
 * 超过 {@code warnRatio} 比例时告警，超过 {@code hardStopRatio} 比例时触发软停。
 *
 * <p>绑定 YAML 路径：{@code rag.token-budget.*}
 *
 * @param maxTokensPerRun 单次 run 最大 token 预算
 * @param warnRatio       告警比例（0~1），达到 maxTokens × warnRatio 时告警
 * @param hardStopRatio   软停比例（0~1），达到 maxTokens × hardStopRatio 时软停
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "rag.token-budget")
public record TokenBudgetProperties(
        @Min(1) long maxTokensPerRun, @Min(0) double warnRatio, @Min(0) double hardStopRatio) {

    public TokenBudgetProperties {
        if (maxTokensPerRun <= 0) maxTokensPerRun = 200000L;
        if (warnRatio <= 0) warnRatio = 0.8;
        if (hardStopRatio <= 0) hardStopRatio = 1.0;
    }
}
