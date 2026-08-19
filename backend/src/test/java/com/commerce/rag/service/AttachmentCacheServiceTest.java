package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Test
    @DisplayName("8 线程并发同 hash — 处理器只执行一次（Caffeine 原子单次计算）")
    void concurrent_processorRunsOnce() throws Exception {
        AttachmentCacheService cache = new AttachmentCacheService(1000, 30);
        AtomicInteger calls = new AtomicInteger();
        byte[] bytes = "concurrent".getBytes();
        String hash = cache.computeHash(bytes);
        int threads = 8;
        // 就绪闸门：8 线程全部就绪后统一放行，制造同 hash 并发首次 miss 竞争
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<String>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cache.getOrProcess(hash, b -> "result" + calls.incrementAndGet(), bytes);
                }));
            }
            ready.await();
            start.countDown();
            for (Future<String> f : futures) {
                assertEquals("result1", f.get(), "并发下所有线程拿到同一处理结果");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, calls.get(), "并发同 hash 只处理一次");
    }
}
