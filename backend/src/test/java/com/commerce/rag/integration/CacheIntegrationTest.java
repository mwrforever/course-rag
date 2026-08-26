package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.cache.CourseQueryCacheService;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.service.IDashboardService;
import com.commerce.rag.test.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 缓存 Redis 化集成测试（真实 Testcontainers Redis，2026-08-25 缓存分层定稿后）
 *
 * <p>覆盖契约（宪法 A.5.4 分层）：
 * <ol>
 *   <li>领域缓存 CourseQueryCacheService：POJO 写入读取（JSON 类型还原）、get 未命中 loader 写回 + TTL、
 *       evictCourse 单键 + search:/byTitle: 前缀 SCAN 清理（course 前缀键保留）</li>
 *   <li>Spring Cache 注解化：dashboard 统计三端点 @Cacheable 命中写入 Redis（键 dashboard:stats::stats 等 +
 *       TTL 60 秒），DashboardCacheEvictor.evictAll 全量失效后键消失</li>
 * </ol>
 *
 * @author commerce-rag
 */
@DisplayName("缓存 Redis 化集成测试（领域缓存 + Spring Cache 注解）")
class CacheIntegrationTest extends IntegrationTestBase {

    /** 缓存对象序列化模板（CacheConfig.cacheObjectRedisTemplate，key String / value JSON 含类型） */
    @Autowired
    @Qualifier("cacheObjectRedisTemplate")
    private RedisTemplate<String, Object> cacheObjectRedisTemplate;

    @Autowired
    private CourseQueryCacheService courseQueryCacheService;

    @Autowired
    private DashboardCacheEvictor dashboardCacheEvictor;

    @Autowired
    private IDashboardService dashboardService;

    /** 课程实体样本（真实 MP 实体字段，验证 JSON 类型还原） */
    private CourseInfo sampleCourse() {
        CourseInfo course = new CourseInfo();
        course.setId(1001L);
        course.setTitle("高等数学（一）");
        course.setCategory("数学");
        course.setStatus("ACTIVE");
        return course;
    }

    @Test
    @DisplayName("领域缓存：POJO 写入读取（JSON 类型还原字段一致）")
    void courseCache_pojoRoundTrip() {
        courseQueryCacheService.invalidateAll();
        courseQueryCacheService.put("course:1001", sampleCourse());

        Object raw = cacheObjectRedisTemplate.opsForValue().get("course:query:course:1001");
        assertNotNull(raw, "Redis 端应存在序列化值");
        assertTrue(raw instanceof CourseInfo, "JSON 反序列化应还原为 CourseInfo（@class 类型信息）");

        Object cached = courseQueryCacheService.getIfPresent("course:1001");
        assertEquals("高等数学（一）", ((CourseInfo) cached).getTitle());
        assertEquals("ACTIVE", ((CourseInfo) cached).getStatus());
    }

    @Test
    @DisplayName("领域缓存：get 未命中 loader 写回 + Redis TTL 生效（5 分钟窗口）")
    void courseCache_missWriteBackWithTtl() {
        courseQueryCacheService.invalidateAll();

        Object result = courseQueryCacheService.get("search:java:1", k -> sampleCourse());

        assertNotNull(result);
        Long ttl = cacheObjectRedisTemplate.getExpire("course:query:search:java:1");
        assertNotNull(ttl, "键应存在");
        assertTrue(ttl > 240 && ttl <= 300, "TTL 应为 5 分钟窗口，实际: " + ttl);
    }

    @Test
    @DisplayName("领域缓存：evictCourse 单键 + search/byTitle 前缀 SCAN 清理（course 前缀保留）")
    void courseCache_evictCoursePrefixAndSingleKeys() {
        courseQueryCacheService.invalidateAll();
        courseQueryCacheService.put("course:1", "v1");
        courseQueryCacheService.put("course:2", "v2");
        courseQueryCacheService.put("search:java:1", "sj");
        courseQueryCacheService.put("search:java:2", "sj2");
        courseQueryCacheService.put("byTitle:高数", "bt");

        courseQueryCacheService.evictCourse(1L);

        // 单键三连 + 前缀全清（evictCourse 依赖语义）
        assertNull(courseQueryCacheService.getIfPresent("course:1"));
        assertNull(courseQueryCacheService.getIfPresent("search:java:1"));
        assertNull(courseQueryCacheService.getIfPresent("search:java:2"));
        assertNull(courseQueryCacheService.getIfPresent("byTitle:高数"));
        // 无关课程键保留（前缀清理不误删 course:2）
        assertNotNull(courseQueryCacheService.getIfPresent("course:2"));
    }

    @Test
    @DisplayName("Spring Cache：dashboard 统计 @Cacheable 写入 Redis（TTL 60s），evictAll 全量失效")
    void dashboardStats_annotationCacheAndEvict() {
        dashboardCacheEvictor.evictAll();

        // 注解缓存写入：三端点各调用一次，Redis 键落盘（含 TTL）
        dashboardService.dashboardStats();
        dashboardService.feedbackStats("today");
        dashboardService.feedbackTrend(7);
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("dashboard:stats::stats"));
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("dashboard:feedback-stats::today"));
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("dashboard:feedback-trend::7"));
        Long ttl = cacheObjectRedisTemplate.getExpire("dashboard:stats::stats");
        assertNotNull(ttl);
        assertTrue(ttl > 50 && ttl <= 60, "dashboard 统计 TTL 应为 60 秒窗口，实际: " + ttl);

        // 写方失效（一致性铁律：先写 DB 后失效，缓存清空）
        dashboardCacheEvictor.evictAll();
        assertNull(cacheObjectRedisTemplate.opsForValue().get("dashboard:stats::stats"));
        assertNull(cacheObjectRedisTemplate.opsForValue().get("dashboard:feedback-stats::today"));
    }
}
