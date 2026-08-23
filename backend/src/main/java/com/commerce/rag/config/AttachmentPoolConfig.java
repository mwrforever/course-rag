package com.commerce.rag.config;

import com.commerce.rag.properties.AttachmentProperties;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 附件并行处理线程池配置（P2-2）—— 注册独立附件池 Bean + 优雅关闭钩子
 *
 * <p>附件下载/图片 caption 并行化基底。与 ETL/检索/记忆池完全隔离
 * （宪法「各业务独立线程池」）：<b>禁止复用 etlPool</b>——etlPool 仅 2~4 线程，
 * 主任务占用下附件子任务排队会自锁死。
 *
 * <p>使用 AbortPolicy（与 etlPool 同哲学）：队列满时快速失败，由 AttachmentOrchestrator
 * 按单附件失败语义跳过（不阻塞调用方、不内联执行慢任务）。
 *
 * <p>关闭顺序参照 ChatRequestWorker：shutdown → 30s 等待在途附件处理完成 → 强制停。
 * 非线程安全：@Bean 方法由 Spring 单线程初始化调用，字段赋值后只读。
 */
@Slf4j
@Configuration
public class AttachmentPoolConfig {

    /** 池实例引用（@Bean 方法赋值，供 @PreDestroy 优雅关闭） */
    private ThreadPoolExecutor attachmentPoolInstance;

    /**
     * 附件并行处理线程池（附件下载 + 图片 caption 并行基底）
     *
     * @param props 附件配置（executor 段：core/max/queue/线程名前缀）
     * @return 配置好的 ThreadPoolExecutor（守护线程 + 有界队列 + AbortPolicy）
     */
    @Bean("attachmentPool")
    public ThreadPoolExecutor attachmentPool(AttachmentProperties props) {
        AttachmentProperties.Executor exec = props.executor();
        attachmentPoolInstance = new ThreadPoolExecutor(
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
        log.info("附件并行线程池已创建: core={}, max={}, queue={}", exec.coreSize(), exec.maxSize(), exec.queueCapacity());
        return attachmentPoolInstance;
    }

    /**
     * 优雅关闭附件池：等待在途附件处理完成后终止，超时强制停
     *
     * <p>应用停机时保证已开始的附件下载/处理不被硬中断丢弃（最多等 30s）。
     */
    @PreDestroy
    public void shutdownAttachmentPool() {
        if (attachmentPoolInstance == null) {
            return;
        }
        log.info("附件并行线程池关闭中...");
        attachmentPoolInstance.shutdown();
        try {
            // 等待在途任务完成（超时 30s 与 ChatRequestWorker runPool 同参）
            if (!attachmentPoolInstance.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("附件并行线程池未在 30s 内终止，强制关闭");
                attachmentPoolInstance.shutdownNow();
            }
        } catch (InterruptedException e) {
            // 停机线程被中断：直接强制关闭，恢复中断标记交还上层
            Thread.currentThread().interrupt();
            attachmentPoolInstance.shutdownNow();
        }
        log.info("附件并行线程池已关闭");
    }
}
