package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 模型能力参数配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code model.*} 配置项。
 *
 * <p>原 {@code bot/hook/CustomSummarizationHook} 经 {@code @Value} 散落注入
 * {@code model.max-context-tokens}，现收敛为本属性类统一强类型绑定（宪法 A.2.2）。
 * 默认值与原兜底值相同，行为零变化。
 *
 * <pre>
 * model:
 *   max-context-tokens: 128000
 * </pre>
 *
 * @param maxContextTokens 主对话模型最大上下文窗口（token 数；默认 128000，按部署模型调整）
 */
@Validated
@ConfigurationProperties(prefix = "model")
public record ModelProperties(@DefaultValue("128000") @Min(1) long maxContextTokens) {}
