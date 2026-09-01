package com.commerce.rag.properties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 上下文窗口管理配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code context.*} 配置项，供
 * {@code bot/hook/CustomSummarizationHook}（对话历史摘要压缩）消费。
 *
 * <p>原四个键经 {@code @Value} 散落注入，现收敛为本属性类统一强类型绑定（宪法 A.2.2）。
 * 默认值与原 {@code @Value} 兜底值逐一相同（summary-model 兜底为历史值 qwen3.7-flash，
 * 当前 application.yml 显式配置 qwen3.7-max-2026-06-08，运行值以 yml 为准），行为零变化。
 *
 * <pre>
 * context:
 *   window-ratio: 0.7
 *   threshold: 0.7
 *   keep-recent: 6
 *   summary-model: qwen3.7-max-2026-06-08
 * </pre>
 *
 * @param windowRatio  压缩目标窗口比例（目标窗口 = model.max-context-tokens × window-ratio，预留安全余量；0~1）
 * @param threshold    压缩触发阈值（0~1，达到压缩目标窗口此比例时触发历史压缩）
 * @param keepRecent   压缩后保留的最近消息条数（至少 1 条，保证最近轮次完整）
 * @param summaryModel 摘要生成模型名（轻量模型承担摘要压缩，与主对话模型分离）
 */
@Validated
@ConfigurationProperties(prefix = "context")
public record ContextProperties(
        @DefaultValue("0.7") @DecimalMin("0.0") @DecimalMax("1.0") double windowRatio,
        @DefaultValue("0.7") @DecimalMin("0.0") @DecimalMax("1.0") double threshold,
        @DefaultValue("6") @Min(1) int keepRecent,
        @DefaultValue("qwen3.7-flash") @NotBlank String summaryModel) {}
