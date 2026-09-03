package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 对话流式链路配置属性（绑定 rag.chat-stream.*；M3/M7 共用）。
 *
 * <p>注册方式：WorkerConfig 追加 {@code @EnableConfigurationProperties}（宪法 A.2 配置归位），
 * ChatRequestWorker 构造器注入。
 *
 * @param sourcesReadyWaitMs M3：SOURCES 就绪有界等待上限（毫秒，默认 2000——STAGE(generating)
 *                           转换点同步消费 sources 容器的等待阈值；R3 实证同线程程序序单次读取
 *                           即命中，本等待仅防御性冗余；允许 0 不允许负数）
 * @param stallTimeoutMs     M7/D1：主 agent 流 per-chunk idle 判死阈值（毫秒，默认 45000）——
 *                           语义 = 任意两次事件间隔超时即判死；仅约束普通段（检索/生成/工具执行），
 *                           QU 段（__START__ 之后）固定 65s 配额（QU 预算 60s + 余量，worker 内
 *                           常量，R4 修正 A），QU 阻塞与 unknown 降级不受本值影响
 * @param autoRetryMax       M7/D1：无产出失败自动整轮重试上限（默认 3——判死/连接级断流且本轮
 *                           无任何产出时同 run 重开图流；0 = 关闭自动重试）
 * @param retryBackoffMs     M7：重试退避初始值（毫秒，默认 2000，指数 ×2 封顶 30000）
 */
@Validated
@ConfigurationProperties(prefix = "rag.chat-stream")
public record ChatStreamProperties(
        @DefaultValue("2000") @Min(0) long sourcesReadyWaitMs,
        @DefaultValue("45000") @Min(1000) long stallTimeoutMs,
        @DefaultValue("3") @Min(0) int autoRetryMax,
        @DefaultValue("2000") @Min(100) long retryBackoffMs) {

    /** 紧凑构造器：非法配置阻断启动（宪法 A.2.2；与 @Min 注解双保险——注解在配置绑定期生效，
     * 构造器校验覆盖测试等直接 new 场景） */
    public ChatStreamProperties {
        if (sourcesReadyWaitMs < 0) {
            throw new IllegalArgumentException("rag.chat-stream.sources-ready-wait-ms 不得为负");
        }
        if (stallTimeoutMs < 1000) {
            throw new IllegalArgumentException("rag.chat-stream.stall-timeout-ms 不得低于 1000");
        }
        if (retryBackoffMs < 100) {
            throw new IllegalArgumentException("rag.chat-stream.retry-backoff-ms 不得低于 100");
        }
    }
}
