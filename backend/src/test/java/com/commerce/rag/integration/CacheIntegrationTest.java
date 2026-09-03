package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.cache.CourseQueryCacheService;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.cache.PublicCourseCacheEvictor;
import com.commerce.rag.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.service.IDashboardService;
import com.commerce.rag.test.IntegrationTestBase;
import com.commerce.rag.vo.PublicCourseDetailVO;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
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
 *   <li>M9/PERF-21：公开课程列表/详情 @Cacheable publicCourses（TTL 60 秒配置化注册），
 *       首查落缓存 → 二次命中不查库 → 写路径 afterCommit 失效后重建</li>
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

    @Autowired
    private ICourseService courseService;

    @Autowired
    private PublicCourseCacheEvictor publicCourseCacheEvictor;

    /**
     * 本类用例前置：清理课程/排期表与公开课程缓存键（基类只清 chat/auth 相关表，
     * course_info 残留会干扰公开列表断言，course_schedule 残留会干扰详情排期断言；
     * JUnit 5 保证父类 @BeforeEach 先于本方法执行）。
     */
    @BeforeEach
    void cleanCourseFixture() {
        jdbcTemplate.update("DELETE FROM course_schedule");
        jdbcTemplate.update("DELETE FROM course_info");
        publicCourseCacheEvictor.evictAll();
    }

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

    @Test
    @DisplayName("M9 publicCourses 缓存：首查落缓存（TTL 60s）→ 二次命中不查库 → 写路径失效后重建")
    void publicCoursesCache_hitAndEvict() {
        // 数据准备：直接插 course_info 构造可控初始态（title/created_by 为仅有的 NOT NULL 业务列）
        Long courseId = 990001L;
        Long ownerId = 990002L;
        jdbcTemplate.update(
                "INSERT INTO course_info (id, title, rating, status, created_by, deleted)"
                        + " VALUES (?, ?, 4.5, 'ACTIVE', ?, 0)",
                courseId,
                "M9-缓存-初始标题",
                ownerId);

        // 第一段：首次查询 → DB 命中 1 次 → 落缓存（键 publicCourses::all 存在，TTL 60 秒配置化窗口）
        assertEquals("M9-缓存-初始标题", publicCourseTitleOf(courseId));
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("publicCourses::all"), "首查后缓存键应落盘");
        Long ttl = cacheObjectRedisTemplate.getExpire("publicCourses::all");
        assertNotNull(ttl);
        assertTrue(ttl > 50 && ttl <= 60, "publicCourses TTL 应为 60 秒窗口，实际: " + ttl);

        // 第二段：DB 直改标题绕过写路径 → 再查仍返回旧标题（缓存命中，不再查库——「计数 0」的行为等价断言）
        jdbcTemplate.update("UPDATE course_info SET title = ? WHERE id = ?", "M9-缓存-DB直改", courseId);
        assertEquals("M9-缓存-初始标题", publicCourseTitleOf(courseId), "缓存命中期内应返回旧值（未查库）");

        // 第三段：写路径 updateCourse → afterCommit 失效缓存（键消失）→ 再查重查库重建
        courseService.updateCourse(
                courseId,
                new UpdateCourseRequest("M9-缓存-写路径更新", null, null, null, null, null, null, null, null, null),
                ownerId,
                true);
        assertNull(cacheObjectRedisTemplate.opsForValue().get("publicCourses::all"), "写路径失效后缓存键应清空");
        assertEquals("M9-缓存-写路径更新", publicCourseTitleOf(courseId), "失效后重查应取到 DB 最新值");
        // 失效后重建：键重新落盘（查询计数等价 1 → 0 → 1 的末段）
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("publicCourses::all"), "失效后再查应重建缓存键");
    }

    /**
     * 从公开课程列表中取指定课程的标题（经 @Cacheable 代理入口查询，缓存行为随调用生效）。
     *
     * @param courseId 课程 ID
     * @return 该课程当前（可能是缓存的）标题；不存在时断言失败
     */
    private String publicCourseTitleOf(Long courseId) {
        return courseService.findPublicCourses().stream()
                .filter(vo -> courseId.equals(vo.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("公开列表应包含课程: " + courseId))
                .title();
    }

    @Test
    @DisplayName("M9 publicCourses 详情缓存：首查按 courseId 落键（LocalDate 排期 JSR310 往返）→ 二查命中")
    void publicCourseDetailCache_keyedHitWithLocalDateRoundTrip() {
        // 数据准备：课程 + 一期排期（排期含 LocalDate 字段，验证 JSR310 序列化往返无损）
        Long courseId = 990003L;
        Long ownerId = 990004L;
        jdbcTemplate.update(
                "INSERT INTO course_info (id, title, rating, status, created_by, deleted)"
                        + " VALUES (?, ?, 4.5, 'ACTIVE', ?, 0)",
                courseId,
                "M9-详情-初始标题",
                ownerId);
        jdbcTemplate.update(
                "INSERT INTO course_schedule (id, course_id, start_date, end_date, schedule_type, location,"
                        + " capacity, enrolled, status, created_by, deleted)"
                        + " VALUES (?, ?, ?, ?, 'ONLINE', '线上直播', 200, 35, 'UPCOMING', ?, 0)",
                990005L,
                courseId,
                java.sql.Date.valueOf(LocalDate.of(2026, 9, 1)),
                java.sql.Date.valueOf(LocalDate.of(2026, 12, 20)),
                ownerId);

        // 第一段：首查 → DB 命中落缓存，键 publicCourses::{courseId}（详情按 courseId 分键，区别于列表 'all'）
        PublicCourseDetailVO first = courseService.findPublicCourseById(courseId);
        assertEquals("M9-详情-初始标题", first.title());
        assertNotNull(cacheObjectRedisTemplate.opsForValue().get("publicCourses::" + courseId), "首查后详情缓存键应落盘");
        // LocalDate 排期字段经 JSON 序列化往返无损（缓存模板 JSR310 模块注册有效性）
        assertEquals(1, first.schedules().size());
        assertEquals(LocalDate.of(2026, 9, 1), first.schedules().get(0).startDate());
        assertEquals(LocalDate.of(2026, 12, 20), first.schedules().get(0).endDate());

        // 第二段：DB 直改标题绕过写路径 → 二查仍返回旧标题（详情键命中，未查库）
        jdbcTemplate.update("UPDATE course_info SET title = ? WHERE id = ?", "M9-详情-DB直改", courseId);
        PublicCourseDetailVO second = courseService.findPublicCourseById(courseId);
        assertEquals("M9-详情-初始标题", second.title(), "缓存命中期内应返回旧值（未查库）");
        assertEquals(LocalDate.of(2026, 9, 1), second.schedules().get(0).startDate(), "命中值仍为反序列化后的缓存排期");
    }
}
