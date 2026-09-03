package com.commerce.rag.cache;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * 公开课程缓存失效器单元测试（M9/PERF-21）
 *
 * <p>覆盖契约：
 * <ol>
 *   <li>evictAll → 对 CacheManager 取 publicCourses 缓存区并 clear（一致性铁律：
 *       先写 DB 后失效的失效出口）；</li>
 *   <li>evictAllAfterCommit → 无事务上下文时立即执行失效（单测环境无事务，直退同步执行）。</li>
 * </ol>
 *
 * @author commerce-rag
 */
@DisplayName("PublicCourseCacheEvictor 公开课程缓存失效器测试")
class PublicCourseCacheEvictorTest {

    @Test
    @DisplayName("evictAll → 清空 publicCourses 缓存区（一致性铁律：先写 DB 后失效的失效出口）")
    void evictAll_clearsPublicCoursesCache() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("publicCourses")).thenReturn(cache);

        new PublicCourseCacheEvictor(cacheManager).evictAll();

        verify(cache).clear();
    }

    @Test
    @DisplayName("evictAllAfterCommit → 无事务上下文时立即执行失效（单测环境无事务，直退同步执行）")
    void evictAllAfterCommit_withoutTransaction_evictsImmediately() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache("publicCourses")).thenReturn(cache);

        new PublicCourseCacheEvictor(cacheManager).evictAllAfterCommit();

        verify(cache).clear();
    }
}
