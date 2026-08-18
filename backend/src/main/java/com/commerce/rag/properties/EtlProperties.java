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
 *   max-file-size-mb: 100
 *   embedding-batch-size: 16
 *   caption-model: qwen3.7-flash
 *   image-min-size-kb: 10
 *   executor:
 *     core-size: 2
 *     max-size: 4
 *     queue-capacity: 20
 *     thread-name-prefix: etl-
 *   chunk:
 *     size: 768
 *     min-chunk-size-chars: 64
 *   table:
 *     rows-per-chunk: 25
 *     max-rows-per-chunk: 30
 *     overlap-rows: 2
 * </pre>
 *
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(
        @Min(1) int maxFileSizeMb,
        Executor executor,
        Chunk chunk,
        @Min(1) int embeddingBatchSize,
        @NotBlank String captionModel,
        @Min(1) int imageMinSizeKb,
        Table table) {

    /**
     * ETL 线程池配置
     */
    public record Executor(
            @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}

    /**
     * 文本分块参数（Spring AI TokenTextSplitter 映射：size=chunkSizeTokens，
     * minChunkSizeChars=过小合并阈值；1.1.2 无 overlap 参数，见计划决策点 1）
     */
    public record Chunk(@Min(1) int size, @Min(1) int minChunkSizeChars) {}

    /**
     * 表格分块参数（spec §4.3：20~30 行一组按 token 动态调整，子 chunk 重复表头，组间 overlap 行）
     */
    public record Table(@Min(1) int rowsPerChunk, @Min(1) int maxRowsPerChunk, @Min(0) int overlapRows) {}
}
