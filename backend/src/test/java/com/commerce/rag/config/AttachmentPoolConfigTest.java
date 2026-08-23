package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.properties.AttachmentProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AttachmentPoolConfig 单元测试 —— 附件独立线程池构建与优雅关闭（P2-2）
 *
 * <p>验证：池参数（core/max/有界队列/AbortPolicy/守护线程）按配置绑定；
 * @PreDestroy 关闭钩子等待在途任务完成后终止池（不丢在途附件处理）。
 */
@DisplayName("AttachmentPoolConfig 附件线程池配置测试")
class AttachmentPoolConfigTest {

    /** 构建附件配置（池参数可变，其余用默认值） */
    private static AttachmentProperties props(int core, int max, int queue, String prefix) {
        return new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(core, max, queue, prefix));
    }

    @Test
    @DisplayName("attachmentPool 按配置创建独立线程池（core/max/有界队列/AbortPolicy/守护线程）")
    void attachmentPool_buildsPoolPerProperties() {
        ThreadPoolExecutor pool = new AttachmentPoolConfig().attachmentPool(props(2, 4, 20, "attachment-"));
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
    @DisplayName("关闭钩子 — 等待在途任务完成后池终止（优雅关闭不丢任务）")
    void shutdownAttachmentPool_waitsForInFlightTaskThenTerminates() throws Exception {
        AttachmentPoolConfig config = new AttachmentPoolConfig();
        ThreadPoolExecutor pool = config.attachmentPool(props(1, 2, 5, "attachment-test-"));
        // 在途任务：开跑后挂起等待放行（证明关闭钩子会等它完成而非立即丢弃）
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        pool.submit(() -> {
            running.countDown();
            try {
                finish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS), "任务应已在池线程开跑");

        finish.countDown();
        config.shutdownAttachmentPool();

        assertTrue(pool.isShutdown(), "关闭钩子执行后池应进入 shutdown 状态");
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "在途任务完成后池应终止");
    }
}
