package com.commerce.rag.etl;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ETL 管道配置 —— 注册 EtlProperties + ETL 线程池 Bean
 *
 * <p>ETL 线程池与对话 Worker 线程池独立，避免文档解析阻塞对话执行。
 * 使用 CallerRunsPolicy（队列满时由调用线程执行），防止队列溢出。
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties(EtlProperties.class)
public class EtlConfig {

    /**
     * ETL 异步执行线程池
     *
     * @param props ETL 配置
     * @return 配置好的 ThreadPoolExecutor
     */
    @Bean("etlPool")
    public ThreadPoolExecutor etlPool(EtlProperties props) {
        EtlProperties.Executor exec = props.executor();
        return new ThreadPoolExecutor(
                exec.coreSize(),
                exec.maxSize(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(exec.queueCapacity()),
                new ThreadFactoryBuilder()
                        .setNameFormat(exec.threadNamePrefix() + "%d")
                        .setDaemon(true)
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
