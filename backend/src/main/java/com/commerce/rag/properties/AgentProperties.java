package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Agent 主对话配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code rag.agent.*} 配置项。
 *
 * <p>原 {@code bot/graph/LeadAgentGraph}（run-limit）与 {@code worker/ChatRequestWorker}
 * （model）各自经 {@code @Value} 散落注入，现收敛为本属性类统一强类型绑定（宪法 A.2.2）。
 * 默认值与原 {@code @Value} 兜底值相同，行为零变化。
 *
 * <pre>
 * rag:
 *   agent:
 *     run-limit: 15
 *     model: qwen3.8-max
 * </pre>
 *
 * @param runLimit 单 run 模型调用上限（ModelCallLimitHook 防无限迭代；默认 15）
 * @param model    主对话模型名（SSE METADATA 事件 model 字段来源；默认 qwen3.8-max）
 */
@Validated
@ConfigurationProperties(prefix = "rag.agent")
public record AgentProperties(
        @DefaultValue("15") @Min(1) int runLimit, @DefaultValue("qwen3.8-max") @NotBlank String model) {}
