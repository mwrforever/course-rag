package com.commerce.rag.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 公开课程缓存失效器（M9/PERF-21，Spring Cache 写方失效统一出口）
 *
 * <p>公开课程列表/详情缓存区 "publicCourses" 的失效出口：课程写路径（创建/更新/软删/
 * 内容与排期变更）与购买可见性变更经 {@link #evictAllAfterCommit()} 在事务提交后统一清空，
 * 遵循宪法 A.5.4 一致性铁律「先写 DB（事务内）→ 后失效缓存」。
 * 失效时机晚于事务提交的窗口由 TTL 兜底（公开数据可容忍短时不一致，60 秒决策窗口）。
 *
 * <p>与 DashboardCacheEvictor 同构（PERF-02 落地形态），失效范围收敛为单一缓存区 clear()，
 * 避免各写路径逐个注解 @CacheEvict 的散落与遗漏。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class PublicCourseCacheEvictor {

    /** 公开课程缓存区名（与 @Cacheable cacheNames 一一对应；TTL 见 CacheTtlProperties.publicCourses） */
    public static final String CACHE_NAME = "publicCourses";

    private final CacheManager cacheManager;

    /** 全量失效公开课程缓存区（写 DB 后调用；afterCommit 语义走 {@link #evictAllAfterCommit()}） */
    public void evictAll() {
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache != null) {
            cache.clear();
        }
    }

    /**
     * 事务提交后失效（无事务时直接执行）——供写路径调用，避免事务回滚后误失效。
     *
     * <p>一致性铁律的时机收敛：失效必须发生在事务 commit 之后，否则存在「失效后-提交前并发读
     * miss 回填旧值」的脏读窗口（与 CourseServiceImpl#evictCacheAfterCommit 同语义）。
     */
    public void evictAllAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务内：挂 afterCommit 回调，提交后失效（避免失效后-提交前并发读回填旧值窗口）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictAll();
                }
            });
        } else {
            // 无事务上下文（如非事务写路径/单测）：保持同步失效语义
            evictAll();
        }
    }
}
