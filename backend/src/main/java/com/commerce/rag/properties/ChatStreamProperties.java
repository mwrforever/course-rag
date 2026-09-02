package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 对话流式链路配置属性（绑定 rag.chat-stream.*；M3/M7 共用，逐字段随任务落地——
 * M3 仅 sourcesReadyWaitMs，M7 的 stall/retry 项随 T10 追加，本类不提前扩展）。
 *
 * <p>注册方式：WorkerConfig 追加 {@code @EnableConfigurationProperties}（宪法 A.2 配置归位），
 * ChatRequestWorker 构造器注入。
 */
@Validated
@ConfigurationProperties(prefix = "rag.chat-stream")
public record ChatStreamProperties(
        /**
         * SOURCES 就绪有界等待上限（毫秒，M3：STAGE(generating) 转换点同步消费 sources 容器的
         * 等待阈值）。默认 2000——spec §6 契约值；R3 实证转换点（retrieveNode 完成）sink 与读取
         * 为同线程程序序连续执行、单次读取即命中，本等待仅防御性冗余，实际不消耗；无来源场景
         * （空检索命中）经此阈值超时放行，迟到兜底交 maybePushSources + 前端渲染排序。
         * 允许 0（不等待，转换点单次读取后立即放行）。不允许负数。
         */
        @DefaultValue("2000") @Min(0) long sourcesReadyWaitMs) {

    /** 紧凑构造器：等待阈值不得为负（非法配置阻断启动，宪法 A.2.2） */
    public ChatStreamProperties {
        if (sourcesReadyWaitMs < 0) {
            throw new IllegalArgumentException("rag.chat-stream.sources-ready-wait-ms 不得为负");
        }
    }
}
