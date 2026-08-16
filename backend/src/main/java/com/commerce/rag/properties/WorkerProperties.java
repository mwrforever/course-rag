package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Worker 线程池配置属性。
 * 绑定 application.yml 中 {@code worker.run-pool.*} 配置块（第 219-224 行）。
 *
 * <pre>
 * worker:
 *   run-pool:
 *     core-size: 8
 *     max-size: 8
 *     queue-capacity: 100
 *     thread-name-prefix: chat-worker-
 *     stale-run-timeout-minutes: 10
 * </pre>
 *
 * <p>L-12：core-size/max-size 为实际生效值（WorkerConfig 直接读取，
 * 原"动态计算 CPU*2 + fallback"注释无实现逻辑）。
 *
 * <p>M-8：stale-run-timeout-minutes 为 ACTIVE run 巡检超时阈值——run 滞留 ACTIVE
 * 时 uniq_active_run_per_session 会锁死该会话（后续对话恒 409），巡检置 ERROR 解锁。
 */
@Validated
@ConfigurationProperties(prefix = "worker.run-pool")
public record WorkerProperties(
        @Min(1) int coreSize,
        @Min(1) int maxSize,
        @Min(1) int queueCapacity,
        @NotBlank String threadNamePrefix,
        @Min(1) int staleRunTimeoutMinutes) {}
