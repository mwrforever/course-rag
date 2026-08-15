package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.*;

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
    @DisplayName("etlPool 按 EtlProperties 创建独立线程池（core/max/队列/CallerRunsPolicy）")
    void etlPool_buildsPoolPerProperties() {
        EtlProperties props =
                new EtlProperties(100, new EtlProperties.Executor(2, 4, 20, "etl-"), new EtlProperties.Chunk(768, 128));
        ThreadPoolExecutor pool = new EtlConfig().etlPool(props);

        try {
            assertEquals(2, pool.getCorePoolSize());
            assertEquals(4, pool.getMaximumPoolSize());
            assertEquals(20, ((LinkedBlockingQueue<?>) pool.getQueue()).remainingCapacity());
            assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, pool.getRejectedExecutionHandler());
            assertTrue(pool.getThreadFactory().newThread(() -> {}).isDaemon());
        } finally {
            pool.shutdownNow();
        }
    }
}
