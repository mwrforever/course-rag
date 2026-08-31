package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Context Builder 注入条数配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code rag.context-builder.*} 配置项。
 *
 * <p>原 {@code retrieval/ContextBuilderService} 经 {@code @Value} 散落注入，
 * 现收敛为本属性类统一强类型绑定（宪法 A.2.2）。默认值与原兜底值相同，行为零变化。
 *
 * <pre>
 * rag:
 *   context-builder:
 *     top-k: 5
 * </pre>
 *
 * @param topK 系统资料注入条数上限（spec §3.2：Top-K 仅限系统检索，rerank 分数降序取前 N；默认 5）
 */
@Validated
@ConfigurationProperties(prefix = "rag.context-builder")
public record ContextBuilderProperties(@DefaultValue("5") @Min(1) int topK) {}
