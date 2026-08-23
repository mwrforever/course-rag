package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.properties.EtlProperties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EtlConfig 单元测试 —— 验证 ETL 独立线程池 Bean 构建
 *
 * @author commerce-rag
 */
@DisplayName("EtlConfig 线程池配置测试")
class EtlConfigTest {

    @Test
    @DisplayName("etlPool 按 EtlProperties 创建独立线程池（core/max/队列/AbortPolicy——M-7 快速失败）")
    void etlPool_buildsPoolPerProperties() {
        EtlProperties props = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.ImageExecutor(3, 3, 20, "etl-image-", 60),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2),
                500);
        ThreadPoolExecutor pool = new EtlConfig().etlPool(props);

        try {
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(20, ((LinkedBlockingQueue<?>) pool.getQueue()).remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
            assertTrue(pool.getThreadFactory().newThread(() -> {}).isDaemon());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("etlImagePool 创建 ETL 图片并行池（P2-2b：与 etlPool 隔离防子任务自锁——core/max/有界队列/AbortPolicy）")
    void etlImagePool_buildsIsolatedImagePool() {
        EtlProperties props = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.ImageExecutor(2, 4, 20, "etl-image-", 60),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2),
                500);
        ThreadPoolExecutor pool = new EtlConfig().etlImagePool(props);

        try {
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(20, ((LinkedBlockingQueue<?>) pool.getQueue()).remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
            assertTrue(pool.getThreadFactory().newThread(() -> {}).isDaemon());
        } finally {
            pool.shutdownNow();
        }
    }
}
