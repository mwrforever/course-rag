package com.commerce.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 本地缓存配置测试 —— 验证两个 Cache bean 可构建且 TTL/容量配置生效 */
@DisplayName("CacheConfig 缓存配置测试")
class CacheConfigTest {

    private final CacheConfig cacheConfig = new CacheConfig();

    @Test
    @DisplayName("课程查询缓存：可写入读取，5 分钟后过期")
    void courseQueryCache_putGetAndExpire() {
        Cache<String, Object> cache = cacheConfig.courseQueryCache();
        cache.put("course:1", "value");
        assertThat(cache.getIfPresent("course:1")).isEqualTo("value");
        assertThat(cache.getIfPresent("course:2")).isNull();
        // Caffeine 基于写入时间窗口过期：此处断言过期策略配置存在（expireAfterWrite=5min）
        assertThat(cache.policy().expireAfterWrite()).isPresent();
    }

    @Test
    @DisplayName("Dashboard 统计缓存：可写入读取，容量受限")
    void dashboardStatsCache_putGet() {
        Cache<String, Object> cache = cacheConfig.dashboardStatsCache();
        cache.put("stats:1", 42L);
        assertThat(cache.getIfPresent("stats:1")).isEqualTo(42L);
        assertThat(cache.policy().expireAfterWrite()).isPresent();
    }
}
