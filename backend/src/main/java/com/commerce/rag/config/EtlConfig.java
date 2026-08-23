package com.commerce.rag.config;

import com.commerce.rag.properties.EtlProperties;
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
 * 使用 AbortPolicy（M-7：队列满时快速失败并抛 RejectedExecutionException，
 * 由调用方回写文档状态 PENDING/FAILED——原 CallerRunsPolicy 会让上传/reparse 的
 * HTTP 请求线程内联执行整个 ETL（分钟级任务直接阻塞上传接口））。
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
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * ETL 图片并行池（P2-2b：图片 upload+caption 子任务专用）
     *
     * <p><b>不得复用 etlPool</b>——ETL 主任务（chunkDocument 全流程）占用 etlPool 线程，
     * 图片子任务同池提交会排队等待主任务释放形成自锁死（池内主任务又在 join 子任务）；
     * 亦不复用 F7 的 attachmentPool（附件域聊天路径专用，ETL 图片突发会挤占对话关键路径）。
     * 与 etlPool 同哲学：AbortPolicy 快速失败 + 有界队列，拒绝/异常由 EtlPipeline 按
     * 「单图失败跳过」语义兜底（spec §4.2）。
     *
     * <p>生命周期：@Bean 推断 destroy（shutdown），与 etlPool 一致由 Spring 容器管理。
     *
     * @param props ETL 配置（image-executor 段）
     * @return 配置好的 ThreadPoolExecutor（守护线程 + 有界队列 + AbortPolicy）
     */
    @Bean("etlImagePool")
    public ThreadPoolExecutor etlImagePool(EtlProperties props) {
        EtlProperties.ImageExecutor exec = props.imageExecutor();
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
                new ThreadPoolExecutor.AbortPolicy());
    }
}
