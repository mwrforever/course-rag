package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件处理结果 Caffeine 缓存测试（key=字节 hash，同图/同文档只处理一次） */
class AttachmentCacheServiceTest {

    @Test
    @DisplayName("同 hash 二次请求 — 处理器只执行一次（缓存命中）")
    void sameHash_processorRunsOnce() {
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AtomicInteger calls = new AtomicInteger();
        byte[] bytes = "hello".getBytes();
        String hash = cache.computeHash(bytes);

        String r1 = cache.getOrProcess(hash, b -> "result" + calls.incrementAndGet(), bytes);
        String r2 = cache.getOrProcess(hash, b -> "result" + calls.incrementAndGet(), bytes);

        assertEquals("result1", r1);
        assertEquals("result1", r2);
        assertEquals(1, calls.get(), "同 hash 只处理一次");
    }

    @Test
    @DisplayName("computeHash — sha256 十六进制 64 字符")
    void computeHash_sha256Hex() {
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        assertEquals(64, cache.computeHash("abc".getBytes()).length());
        assertTrue(cache.computeHash("abc".getBytes()).matches("[0-9a-f]{64}"));
    }
}
