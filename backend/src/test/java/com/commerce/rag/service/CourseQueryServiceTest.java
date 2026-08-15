package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CourseQueryService 缓存行为测试 —— 命中/键隔离/evictCourse 精确失效 */
@DisplayName("CourseQueryService 缓存测试")
class CourseQueryServiceTest {

    private final Cache<String, Object> courseQueryCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    private final CourseQueryService service = new CourseQueryService(courseQueryCache);

    @Test
    @DisplayName("findCourseById 命中缓存返回同一实例")
    void findCourseById_hitsCache() {
        CourseInfo info = new CourseInfo();
        info.setId(1L);
        info.setTitle("缓存课程");
        courseQueryCache.put("course:1", info);

        CourseInfo result = service.findCourseById("1");
        assertThat(result).isSameAs(info);
    }

    @Test
    @DisplayName("evictCourse 精确失效详情键并清理 search 列表键")
    void evictCourse_removesDetailAndSearchKeys() {
        courseQueryCache.put("course:1", new CourseInfo());
        courseQueryCache.put("contents:1", List.of());
        courseQueryCache.put("schedule:1", new CourseSchedule());
        courseQueryCache.put("search:java:1", new Object());
        courseQueryCache.put("course:2", new CourseInfo());

        service.evictCourse(1L);

        assertThat(courseQueryCache.getIfPresent("course:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("contents:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("schedule:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("search:java:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("course:2")).isNotNull();
    }
}
