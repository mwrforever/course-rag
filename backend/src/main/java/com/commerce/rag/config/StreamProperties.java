package com.commerce.rag.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SSE 流式 + Worker Queue 配置属性。
 * 绑定 application.yml 中 stream.* 配置块。
 */
@Validated
@ConfigurationProperties(prefix = "stream")
public record StreamProperties(
        @NotBlank String requestStream,
        @NotBlank String consumerGroup,
        @Min(1) int batchSize,
        @Min(1) int pollTimeout,
        @Min(1) int responseTtl,
        @Min(1) int heartbeatInterval,
        @Min(1) int ringBufferSize) {}
