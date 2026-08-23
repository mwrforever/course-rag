package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AttachmentProperties 默认值测试（与 application.yml attachment 段一致） */
class AttachmentPropertiesTest {

    @Test
    @DisplayName("默认限额 — 图片10MB/文档50MB/10个/合计100MB，缓存100条30分钟，批量向量化批大小16")
    void defaults() {
        AttachmentProperties p = new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-"));
        assertEquals(10, p.imageMaxSizeMb());
        assertEquals(50, p.documentMaxSizeMb());
        assertEquals(10, p.maxCount());
        assertEquals(100, p.totalMaxSizeMb());
        assertEquals(100, p.cacheMaxSize());
        assertEquals(30, p.cacheExpireMinutes());
        assertEquals(16, p.embeddingBatchSize());
    }

    @Test
    @DisplayName("并行处理配置 — 总超时与线程池参数（core/max/队列/线程名前缀）绑定（P2-2）")
    void parallelExecutorBinding() {
        AttachmentProperties p = new AttachmentProperties(
                10, 50, 10, 100, 100, 30, 16, 60000, new AttachmentProperties.Executor(2, 4, 20, "attachment-"));
        assertEquals(60000, p.processTimeoutMs(), "附件并行处理总超时应绑定 process-timeout-ms");
        assertEquals(2, p.executor().coreSize());
        assertEquals(4, p.executor().maxSize());
        assertEquals(20, p.executor().queueCapacity());
        assertEquals("attachment-", p.executor().threadNamePrefix());
    }
}
