package com.commerce.rag.config;

import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.properties.CacheTtlProperties;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * 缓存配置 —— Spring Cache（RedisCacheManager）为简单场景缓存主体；Caffeine 仅限本地缓存
 *
 * <p>宪法 A.5.4 缓存实现分层（2026-08-25 用户架构定稿）：
 * <ul>
 *   <li>简单场景（dashboard 统计三端点等）：{@code @Cacheable} 注解 + 本类 {@link RedisCacheManager}，
 *       缓存区 TTL 经 {@code cache.ttl.dashboard-stats} 配置化注册（cacheConfigurations）</li>
 *   <li>复杂场景（课程查询：单键 + search:/byTitle: 前缀批量失效，Spring Cache 无法表达）：
 *       领域缓存类 {@code cache/CourseQueryCacheService}（RedisTemplate 实现，不经 Spring Cache，
 *       TTL 同源 {@code cache.ttl.course-query}）</li>
 *   <li>本地/会话级缓存（附件、偏好冻结）：Caffeine 在各 Service 内自建，不经本类（Caffeine 仅限本地）</li>
 * </ul>
 *
 * <p>一致性铁律对两类缓存同等生效：先写 DB（事务内）→ 后失效缓存（A.5.4）。
 *
 * @author commerce-rag
 */
@Configuration
@EnableCaching
@EnableConfigurationProperties(CacheTtlProperties.class)
public class CacheConfig {

    /**
     * 对象序列化 Redis 模板（领域缓存 CourseQueryCacheService 专用：key String / value JSON 含类型）
     *
     * <p>GenericJackson2JsonRedisSerializer 自带 default typing（@class 属性），
     * 支持 IPage/CourseInfo 等任意 POJO 存取；与全局 StringRedisTemplate（字符串语义）
     * 分离，避免相互干扰。
     */
    @Bean
    public RedisTemplate<String, Object> cacheObjectRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Spring Cache 管理器（dashboard 统计三缓存区，TTL 按模块配置化）
     *
     * <p>键序列化 string、值 JSON 含类型（POJO 缓存往返一致）；缓存区 TTL 统一取自
     * {@code cache.ttl.dashboard-stats}（perf P2-3 决策窗口 60 秒，写方失效钩子与窗口联动）。
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory factory, CacheTtlProperties cacheTtlProperties) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer()));
        Map<String, RedisCacheConfiguration> byName = new HashMap<>();
        for (String name : DashboardCacheEvictor.CACHE_NAMES) {
            byName.put(name, defaults.entryTtl(cacheTtlProperties.dashboardStats()));
        }
        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(byName)
                .build();
    }
}
