package com.commerce.rag.properties;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 缓存 TTL 配置（宪法 A.5.4：简单场景 Spring Cache 的缓存区 TTL 经 cache.ttl.* 配置化；复杂场景领域缓存类同源复用）
 *
 * <p>绑定 application.yml 中 cache.ttl 配置块，由 CacheConfig 注册。
 * 默认值与 Caffeine 时代一致：课程查询 5 分钟、dashboard 统计 60 秒（perf P2-2/P2-3 决策窗口，改动需同步写方失效钩子）。
 *
 * @param courseQuery   课程查询领域缓存 TTL（CourseQueryCacheService 用，默认 5 分钟）
 * @param dashboardStats dashboard 统计三缓存区 TTL（@Cacheable 注解化，默认 60 秒兜底）
 */
@Validated
@ConfigurationProperties(prefix = "cache.ttl")
public record CacheTtlProperties(
        @DefaultValue("5m") Duration courseQuery, @DefaultValue("60s") Duration dashboardStats) {}
