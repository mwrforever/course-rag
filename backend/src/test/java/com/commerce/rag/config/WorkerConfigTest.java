package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.properties.WorkerProperties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WorkerConfig 单元测试 —— 验证对话执行线程池 Bean 构建
 *
 * @author commerce-rag
 */
@DisplayName("WorkerConfig 线程池配置测试")
class WorkerConfigTest {

    @Test
    @DisplayName("runPool 按 yml 配置创建核心/最大线程数、队列容量与 AbortPolicy 的线程池（L-12/M-8）")
    void runPool_buildsPoolPerProperties() {
        WorkerProperties props = new WorkerProperties(2, 4, 100, "chat-worker-", 10);
        ThreadPoolExecutor pool = new WorkerConfig().runPool(props);

        try {
            // L-12: 线程数直接取自 yml 配置（原硬编码 CPU*2 使 yml 死配置）
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(100, ((LinkedBlockingQueue<?>) pool.getQueue()).remainingCapacity());
            assertEquals(60L, pool.getKeepAliveTime(TimeUnit.SECONDS));
            assertInstanceOf(ThreadPoolExecutor.AbortPolicy.class, pool.getRejectedExecutionHandler());
            // 线程工厂应使用配置的前缀（daemon 线程）
            assertTrue(pool.getThreadFactory().newThread(() -> {}).isDaemon());
        } finally {
            pool.shutdownNow();
        }
    }
}
