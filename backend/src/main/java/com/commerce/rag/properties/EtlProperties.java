package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * ETL 管道配置属性
 *
 * <p>绑定 application.yml 中 {@code etl.*} 配置块：
 * <pre>
 * etl:
 *   executor:
 *     core-size: 2
 *     max-size: 4
 *     queue-capacity: 20
 *     thread-name-prefix: etl-
 *   chunk:
 *     size: 768
 *     overlap: 128
 * </pre>
 *
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(@Min(1) int maxFileSizeMb, Executor executor, Chunk chunk) {

    /**
     * ETL 线程池配置
     */
    public record Executor(
            @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}

    /**
     * 分块参数
     */
    public record Chunk(@Min(1) int size, @Min(0) int overlap) {}
}
