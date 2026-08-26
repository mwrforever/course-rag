package com.commerce.rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.properties.CacheTtlProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/** 缓存配置测试 —— 对象序列化模板与 Spring Cache 管理器（TTL 按模块配置化注册） */
@DisplayName("CacheConfig 缓存配置测试")
class CacheConfigTest {

    private final CacheConfig cacheConfig = new CacheConfig();

    @Test
    @DisplayName("对象序列化 Redis 模板：key string / value JSON，可构建")
    void cacheObjectRedisTemplate_buildable() {
        RedisTemplate<String, Object> template =
                cacheConfig.cacheObjectRedisTemplate(mock(RedisConnectionFactory.class));
        assertThat(template.getKeySerializer()).isSameAs(RedisSerializer.string());
        assertThat(template.getValueSerializer()).isInstanceOf(GenericJackson2JsonRedisSerializer.class);
    }

    @Test
    @DisplayName("RedisCacheManager：三统计缓存区 TTL 配置化注册（60 秒窗口，perf P2-3 决策联动）")
    void redisCacheManager_ttlPerCacheName() {
        CacheTtlProperties ttl = new CacheTtlProperties(Duration.ofMinutes(5), Duration.ofSeconds(60));
        CacheManager manager = cacheConfig.redisCacheManager(mock(RedisConnectionFactory.class), ttl);

        for (String name : DashboardCacheEvictor.CACHE_NAMES) {
            RedisCache cache = (RedisCache) manager.getCache(name);
            assertThat(cache).as("缓存区 %s 应注册", name).isNotNull();
        }
        // 未注册缓存区：不经 withInitialCacheConfigurations 也由 cacheDefaults 兜底可用
        assertThat(manager.getCache("unknown")).isNotNull();
    }
}
