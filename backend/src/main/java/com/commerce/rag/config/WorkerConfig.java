package com.commerce.rag.config;

import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.properties.WorkerProperties;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Worker 线程池配置 —— 创建独立于 ETL 的对话执行线程池。
 *
 * <p>设计文档 §3.3：runPool 使用 AbortPolicy（队列满时拒绝新任务并快速失败，
 * 由调用方回写 run=ERROR——M-8：原 CallerRunsPolicy 会让消费者线程内联执行整个 run，
 * 消费循环停摆，Redis Stream 所有新对话滞留；拒绝策略 + 状态回写保证消费循环永不阻塞）。
 *
 * <p>同时注册 {@link StreamProperties} 和 {@link WorkerProperties} 两个
 * {@code @ConfigurationProperties} record 为 Spring Bean。
 */
@Configuration
@EnableConfigurationProperties({WorkerProperties.class, StreamProperties.class})
public class WorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkerConfig.class);

    /**
     * 对话执行线程池（runPool）。
     *
     * <p>线程数直接取自 yml（worker.run-pool.core-size/max-size，默认 8/8）——L-12：
     * 原实现硬编码 CPU*2 覆盖 yml 值，yml 的 core-size/max-size 成为死配置
     * （注释声称 fallback 但无逻辑）；现按宪法「阈值全配置化」让 yml 生效。
     *
     * @param props Worker 线程池配置（coreSize / maxSize / queueCapacity / threadNamePrefix 均从 yml 注入）
     * @return 配置好的 ThreadPoolExecutor
     */
    @Bean("runPool")
    public ThreadPoolExecutor runPool(WorkerProperties props) {
        log.info("runPool 初始化: core={}, max={}, queue={}", props.coreSize(), props.maxSize(), props.queueCapacity());
        return new ThreadPoolExecutor(
                props.coreSize(),
                props.maxSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(props.queueCapacity()),
                new ThreadFactoryBuilder()
                        .setNameFormat(props.threadNamePrefix() + "%d")
                        .setDaemon(true)
                        .build(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
