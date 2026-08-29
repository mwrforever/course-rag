package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.AttachmentProperties;
import java.lang.reflect.Field;
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
 * @PreDestroy 关闭钩子等待在途任务完成后终止池（不丢在途附件处理）；
 * 关闭钩子的边界与异常分支（未建池直接关闭 / 30s 超时强停 / 停机线程被中断）。
 */
@DisplayName("AttachmentPoolConfig 附件线程池配置测试")
class AttachmentPoolConfigTest {

    /** 构建附件配置（池参数可变，其余用默认值） */
    private static AttachmentProperties props(int core, int max, int queue, String prefix) {
        return new AttachmentProperties(
                10,
                50,
                10,
                100,
                100,
                30,
                16,
                60000,
                new AttachmentProperties.Executor(core, max, queue, prefix),
                "qwen3.7-max-2026-06-08");
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

    /**
     * 向配置对象注入池实例引用（@Bean 方法赋值的私有字段，纯单测无 Spring 上下文）
     *
     * @param config 被测配置对象
     * @param pool   注入的池实例（真实池或 Mockito mock）
     */
    private static void injectPoolInstance(AttachmentPoolConfig config, ThreadPoolExecutor pool) throws Exception {
        Field field = AttachmentPoolConfig.class.getDeclaredField("attachmentPoolInstance");
        field.setAccessible(true);
        field.set(config, pool);
    }

    @Test
    @DisplayName("关闭钩子 — 未创建池（Bean 未初始化）时直接返回，不抛 NPE")
    void shutdownAttachmentPool_noPoolInstance_returnsSilently() {
        // 边界：未经过 attachmentPool @Bean 方法（attachmentPoolInstance=null）时触发 @PreDestroy
        // 语义：跳过关闭流程而非空指针崩溃（例如上下文启动失败即销毁的场景）
        new AttachmentPoolConfig().shutdownAttachmentPool();

        // 无异常返回即为通过（早退分支无可断言副作用，此处以不抛异常为业务断言）
    }

    @Test
    @DisplayName("关闭钩子 — 30s 内未终止时强制关闭（awaitTermination=false → shutdownNow）")
    void shutdownAttachmentPool_timeoutForcesShutdownNow() throws Exception {
        // Given: mock 池实例，在途任务 30s 仍不结束（awaitTermination 返回 false）
        AttachmentPoolConfig config = new AttachmentPoolConfig();
        ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);
        when(pool.awaitTermination(30, TimeUnit.SECONDS)).thenReturn(false);
        injectPoolInstance(config, pool);

        // When: 停机触发 @PreDestroy
        config.shutdownAttachmentPool();

        // Then: 先优雅 shutdown，超时后强制 shutdownNow（不无限等待挂死停机流程）
        verify(pool).shutdown();
        verify(pool).shutdownNow();
    }

    @Test
    @DisplayName("关闭钩子 — 停机线程被中断时强停并恢复中断标记（交还上层停机框架）")
    void shutdownAttachmentPool_interruptedForcesShutdownNowAndRestoresFlag() throws Exception {
        // Given: mock 池实例，等待在途任务期间停机线程被中断
        AttachmentPoolConfig config = new AttachmentPoolConfig();
        ThreadPoolExecutor pool = mock(ThreadPoolExecutor.class);
        when(pool.awaitTermination(30, TimeUnit.SECONDS)).thenThrow(new InterruptedException("停机线程被中断"));
        injectPoolInstance(config, pool);

        // When
        config.shutdownAttachmentPool();

        // Then: 中断不外抛（钩子吞掉），直接强制 shutdownNow
        verify(pool).shutdown();
        verify(pool).shutdownNow();
        // Then: 中断标记被恢复（Thread.interrupted 读取并清除，兼作测试现场清理）
        assertTrue(Thread.interrupted(), "中断标记应恢复交还上层停机框架");
    }
}
