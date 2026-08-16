package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.service.impl.CourseQueryServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ICourseQueryService 测试 —— 缓存行为 + DB 查询路径（mapper 注入后可直接单测） */
@ExtendWith(MockitoExtension.class)
@DisplayName("ICourseQueryService 缓存与查询测试")
class CourseQueryServiceTest {

    private final Cache<String, Object> courseQueryCache = Caffeine.newBuilder()
            .maximumSize(512)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    @Mock
    private CourseInfoMapper courseInfoMapper;

    @Mock
    private CourseContentMapper courseContentMapper;

    @Mock
    private CourseScheduleMapper courseScheduleMapper;

    private ICourseQueryService service;

    @BeforeEach
    void setUp() {
        service = new CourseQueryServiceImpl(
                courseQueryCache, courseInfoMapper, courseContentMapper, courseScheduleMapper);
    }

    @Test
    @DisplayName("findCourseById 命中缓存返回同一实例")
    void findCourseById_hitsCache() {
        CourseInfo info = new CourseInfo();
        info.setId(1L);
        info.setTitle("缓存课程");
        courseQueryCache.put("course:1", info);

        CourseInfo result = service.findCourseById("1");
        assertThat(result).isSameAs(info);
        verify(courseInfoMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("findCourseById 未命中 → 经 mapper 查库并写入缓存（第二次命中）")
    void findCourseById_miss_queriesAndCaches() {
        CourseInfo info = new CourseInfo();
        info.setId(1L);
        when(courseInfoMapper.selectById(1L)).thenReturn(info);

        CourseInfo first = service.findCourseById("1");
        CourseInfo second = service.findCourseById("1");

        assertThat(first).isSameAs(info);
        assertThat(second).isSameAs(info);
        verify(courseInfoMapper, times(1)).selectById(1L);
    }

    @Test
    @DisplayName("findCourseById 未命中且课程不存在 → 不缓存 null，再次调用仍查库")
    void findCourseById_miss_notFound_notCached() {
        when(courseInfoMapper.selectById(99L)).thenReturn(null);

        assertThat(service.findCourseById("99")).isNull();
        assertThat(service.findCourseById("99")).isNull();
        verify(courseInfoMapper, times(2)).selectById(99L);
    }

    @Test
    @DisplayName("searchCourses 未命中 → 分页查询并缓存（第二次命中不查库）")
    void searchCourses_miss_queriesAndCaches() {
        Page<CourseInfo> paged = new Page<>(1, 10);
        paged.setRecords(List.of(new CourseInfo()));
        paged.setTotal(1);
        when(courseInfoMapper.selectPage(any(), any())).thenReturn(paged);

        var first = service.searchCourses("Java", 1);
        var second = service.searchCourses("Java", 1);

        assertThat(first.getTotal()).isEqualTo(1);
        assertThat(second).isSameAs(first);
        verify(courseInfoMapper, times(1)).selectPage(any(), any());
    }

    @Test
    @DisplayName("findContentsByCourseId 未命中 → 查询并缓存")
    void findContentsByCourseId_miss_queriesAndCaches() {
        CourseContent content = new CourseContent();
        content.setId(1L);
        when(courseContentMapper.selectList(any())).thenReturn(List.of(content));

        var first = service.findContentsByCourseId("1");
        var second = service.findContentsByCourseId("1");

        assertThat(first).hasSize(1);
        assertThat(second).isSameAs(first);
        verify(courseContentMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("findNextSchedule 未命中 → 查询最近排期并缓存（第二次命中）")
    void findNextSchedule_miss_queriesAndCaches() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        when(courseScheduleMapper.selectOne(any(), eq(false))).thenReturn(schedule);

        var first = service.findNextSchedule("1");
        var second = service.findNextSchedule("1");

        assertThat(first).isSameAs(schedule);
        assertThat(second).isSameAs(schedule);
        verify(courseScheduleMapper, times(1)).selectOne(any(), eq(false));
    }

    @Test
    @DisplayName("findNextSchedule 无可用排期 → 不缓存 null，再次调用仍查库")
    void findNextSchedule_noSchedule_notCached() {
        when(courseScheduleMapper.selectOne(any(), eq(false))).thenReturn(null);

        assertThat(service.findNextSchedule("1")).isNull();
        assertThat(service.findNextSchedule("1")).isNull();
        verify(courseScheduleMapper, times(2)).selectOne(any(), eq(false));
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
