package com.commerce.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 本地缓存配置测试 —— 验证两个 Cache bean 可构建且 TTL/容量配置生效 */
@DisplayName("CacheConfig 缓存配置测试")
class CacheConfigTest {

    private final CacheConfig cacheConfig = new CacheConfig();

    @Test
    @DisplayName("课程查询缓存：TTL 5 分钟、容量 512，可写入读取")
    void courseQueryCache_ttlAndCapacity() {
        Cache<String, Object> cache = cacheConfig.courseQueryCache();
        cache.put("course:1", "value");
        assertThat(cache.getIfPresent("course:1")).isEqualTo("value");
        assertThat(cache.getIfPresent("course:2")).isNull();
        // 契约保护：TTL 5min / 容量 512（写方失效钩子依赖该窗口，改动需同步 perf P2-2 决策）
        assertThat(cache.policy().expireAfterWrite()).isPresent();
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter())
                .isEqualTo(Duration.ofMinutes(5));
        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(512);
    }

    @Test
    @DisplayName("Dashboard 统计缓存：TTL 60 秒、容量 32，可写入读取")
    void dashboardStatsCache_ttlAndCapacity() {
        Cache<String, Object> cache = cacheConfig.dashboardStatsCache();
        cache.put("stats:1", 42L);
        assertThat(cache.getIfPresent("stats:1")).isEqualTo(42L);
        // 契约保护：TTL 60s / 容量 32（写方 invalidateAll 兜底，改动需同步 perf P2-3 决策）
        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter())
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(cache.policy().eviction().orElseThrow().getMaximum()).isEqualTo(32);
    }
}
