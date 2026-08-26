package com.commerce.rag.cache;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Dashboard 统计缓存失效器（Spring Cache 注解化的写方失效统一出口）
 *
 * <p>宪法 A.5.4：dashboard 三统计端点为简单场景（非热 key、TTL 配置化、全量失效可表达），
 * 读取侧走 {@code @Cacheable} 注解（RedisCacheManager 管理）；写方（文档上传/ETL 终态/反馈提交/
 * 学习用户变更等 16 处）统一经本组件失效——三缓存区 clear()，避免 16 处方法逐个注解 @CacheEvict 的
 * 散落与遗漏。
 *
 * <p>缓存区名与 DashboardServiceImpl 的 @Cacheable(cacheNames) 一一对应：
 * dashboard:stats / dashboard:feedback-stats / dashboard:feedback-trend。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class DashboardCacheEvictor {

    /** 三统计缓存区名（与 @Cacheable cacheNames 对应，TTL 见 CacheTtlProperties.dashboardStats；CacheConfig 注册同源） */
    public static final List<String> CACHE_NAMES =
            List.of("dashboard:stats", "dashboard:feedback-stats", "dashboard:feedback-trend");

    private final CacheManager cacheManager;

    /** 全量失效三统计缓存区（写 DB 后调用，一致性铁律：先写 DB 后失效） */
    public void evictAll() {
        for (String cacheName : CACHE_NAMES) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
