package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ETL 管道配置属性
 *
 * <p>绑定 application.yml 中 {@code etl.*} 配置块：
 * <pre>
 * etl:
 *   max-file-size-mb: 100
 *   embedding-batch-size: 16
 *   image-min-size-kb: 10
 *   executor:
 *     core-size: 2
 *     max-size: 4
 *     queue-capacity: 20
 *     thread-name-prefix: etl-
 *   image-executor:
 *     core-size: 2
 *     max-size: 4
 *     queue-capacity: 20
 *     thread-name-prefix: etl-image-
 *     process-timeout-seconds: 60
 *   chunk:
 *     size: 768
 *     min-chunk-size-chars: 64
 *   table:
 *     rows-per-chunk: 25
 *     max-rows-per-chunk: 30
 *     overlap-rows: 2
 *   chunk-insert-batch-size: 500
 *   annotation-sync-timeout-seconds: 120
 * </pre>
 *
 * <p>2026-08-29 M-2 迁移：caption-model 键迁出至 {@code attachment.caption-model}
 * （caption 业务归属附件域，ETL 离线与用户附件两通道共用一值，见 AttachmentProperties）。
 *
 * @param maxFileSizeMb              单文件解析大小上限（MB）
 * @param executor                   ETL 主线程池配置
 * @param imageExecutor              ETL 图片并行池配置
 * @param chunk                      文本分块参数
 * @param embeddingBatchSize         embedding 批量调用批次大小
 * @param imageMinSizeKb             图片过滤最小尺寸（KB）
 * @param table                      表格分块参数
 * @param chunkInsertBatchSize       分片批量插入批次大小
 * @param annotationSyncTimeoutSeconds 批量标注文档级 Milvus 同步总超时（秒，PERF-20，默认 120）：
 *                                     B 端批量标注接口并行同步涉及的全部文档必须在此时限内完成，
 *                                     超时阻断上抛（可重试收敛），防 HTTP 请求线程无限陪等挂死任务
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(
        @Min(1) int maxFileSizeMb,
        Executor executor,
        ImageExecutor imageExecutor,
        Chunk chunk,
        @Min(1) int embeddingBatchSize,
        @Min(1) int imageMinSizeKb,
        Table table,
        @Min(1) int chunkInsertBatchSize,
        @DefaultValue("120") @Min(1) int annotationSyncTimeoutSeconds) {

    /**
     * ETL 线程池配置
     */
    public record Executor(
            @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}

    /**
     * ETL 图片并行池配置（P2-2b：图片 upload+caption 子任务专用池——不得复用 etlPool，
     * ETL 主任务占用 etlPool 时子任务同池排队会自锁死）
     *
     * <p>processTimeoutSeconds：单图处理（MinIO 上传 + VLM caption）超时——超时图片跳过
     * 标注（记 warn），不阻断文档 ETL（spec §4.2 单图失败跳过边界）。
     */
    public record ImageExecutor(
            @Min(1) int coreSize,
            @Min(1) int maxSize,
            @Min(1) int queueCapacity,
            @NotBlank String threadNamePrefix,
            @Min(1) int processTimeoutSeconds) {}

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
