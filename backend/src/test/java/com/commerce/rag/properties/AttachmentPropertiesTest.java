package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AttachmentProperties 默认值测试（与 application.yml attachment 段一致） */
class AttachmentPropertiesTest {

    @Test
    @DisplayName("默认限额 — 图片10MB/文档50MB/10个/合计100MB，缓存100条30分钟")
    void defaults() {
        AttachmentProperties p = new AttachmentProperties(10, 50, 10, 100, 100, 30);
        assertEquals(10, p.imageMaxSizeMb());
        assertEquals(50, p.documentMaxSizeMb());
        assertEquals(10, p.maxCount());
        assertEquals(100, p.totalMaxSizeMb());
        assertEquals(100, p.cacheMaxSize());
        assertEquals(30, p.cacheExpireMinutes());
    }
}
