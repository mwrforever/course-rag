package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 用户附件限额与缓存配置（spec §5.2 用户拍板限额定稿）
 *
 * @param imageMaxSizeMb   单张图片大小上限（MB）
 * @param documentMaxSizeMb 单个文档大小上限（MB）
 * @param maxCount         单次消息附件个数上限
 * @param totalMaxSizeMb   单次消息附件合计大小上限（MB）
 * @param cacheMaxSize     Caffeine 附件处理结果缓存条数（LRU）
 * @param cacheExpireMinutes 附件处理结果缓存失效时间（分钟）
 * @param embeddingBatchSize 文档附件批量向量化批大小（P2-1：一次请求携带多条文本，
 *                           与 etl.embedding-batch-size 同范式，避免大文档逐块串行远程调用）
 * @param processTimeoutMs  附件并行处理总超时（毫秒，P2-2：超时后取消未完成附件、
 *                          保留已完成结果，避免慢附件阻塞 SSE 首 token）
 * @param executor          附件并行处理线程池配置（与 ETL/检索/记忆池隔离，宪法「各业务独立线程池」）
 * @param captionModel      图片描述 VLM 模型（2026-08-29 M-2 迁移：自 etl.caption-model 迁入——
 *                          caption 业务归属附件域；ETL 离线管道与用户附件两通道共用同一值，
 *                          @NotBlank 启动期校验，配置缺失启动失败）
 */
@Validated
@ConfigurationProperties(prefix = "attachment")
public record AttachmentProperties(
        @Min(1) int imageMaxSizeMb,
        @Min(1) int documentMaxSizeMb,
        @Min(1) int maxCount,
        @Min(1) int totalMaxSizeMb,
        @Min(1) int cacheMaxSize,
        @Min(1) int cacheExpireMinutes,
        @Min(1) int embeddingBatchSize,
        @Min(1) long processTimeoutMs,
        Executor executor,
        @NotBlank String captionModel) {

    /** 附件并行处理线程池配置（P2-2：附件下载/图片 caption 并行基底） */
    public record Executor(
            @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}
}
