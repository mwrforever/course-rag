package com.commerce.rag.config;

import com.commerce.rag.worker.WorkerProperties;
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
 * <p>设计文档 §3.3：runPool 使用 CallerRunsPolicy（队列满时由调用线程执行），
 * 防止 Redis Stream 消费过快导致 OOM。
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
     * <p>设计文档 §3.3：core=CPU*2, max=CPU*2, queue=100。
     * 线程数根据 CPU 核心数动态计算（{@code Runtime.getRuntime().availableProcessors() * 2}），
     * 而非 yml 硬编码值。yml 中 {@code worker.run-pool.core-size/max-size} 仅作为 fallback。
     *
     * @param props Worker 线程池配置（queueCapacity / threadNamePrefix 仍从 yml 注入）
     * @return 配置好的 ThreadPoolExecutor
     */
    @Bean("runPool")
    public ThreadPoolExecutor runPool(WorkerProperties props) {
        // 设计文档 §3.3: core=CPU*2, max=CPU*2 —— 动态计算，不硬编码
        int cpuCount = Runtime.getRuntime().availableProcessors();
        int poolSize = cpuCount * 2;
        log.info("runPool 初始化: core=max={} (CPU={})", poolSize, cpuCount);
        return new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(props.queueCapacity()),
                new ThreadFactoryBuilder()
                        .setNameFormat(props.threadNamePrefix() + "%d")
                        .setDaemon(true)
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
