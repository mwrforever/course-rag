package com.commerce.rag.worker;

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
 *     core-size: 4
 *     max-size: 8
 *     queue-capacity: 100
 *     thread-name-prefix: chat-worker-
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "worker.run-pool")
public record WorkerProperties(
        @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}
