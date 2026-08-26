package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.cache.CourseQueryCacheService;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.service.impl.CourseQueryServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * ICourseQueryService 测试 —— 缓存调用路径 + DB 查询（缓存内部语义由 CourseQueryCacheServiceTest 覆盖）
 *
 * <p>缓存 mock 以内存 store 模拟 get(key, loader) 语义（computeIfAbsent：命中不执行 loader、
 * loader 返回 null 不入缓存），聚焦 service 与领域缓存类的交互契约；
 * evictCourse 行为验证委托给领域缓存类自身测试（P1-2 前缀清理语义在其单测覆盖）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ICourseQueryService 缓存与查询测试")
class CourseQueryServiceTest {

    /** 缓存内存替身 store（mock 支撑：命中不执行 loader、null 不缓存） */
    private final Map<String, Object> store = new HashMap<>();

    @Mock
    private CourseQueryCacheService courseQueryCache;

    @Mock
    private CourseInfoMapper courseInfoMapper;

    @Mock
    private CourseContentMapper courseContentMapper;

    @Mock
    private CourseScheduleMapper courseScheduleMapper;

    private ICourseQueryService service;

    @BeforeAll
    static void initMybatisPlus() {
        // 纯 Mockito 单元测试（无 Spring 上下文）需先初始化 Lambda 表达式的 TableInfo 缓存
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        // get(key, loader)：命中返回 store 值；未命中执行 loader 写入（null 不缓存，与领域缓存语义一致）；
        // lenient：evictCourse 专用用例不触达 get stub（通用契约，各用例选择性使用）
        lenient().when(courseQueryCache.get(any(), any())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Function<String, Object> loader = inv.getArgument(1);
            if (store.containsKey(key)) {
                return store.get(key);
            }
            Object value = loader.apply(key);
            if (value != null) {
                store.put(key, value);
            }
            return value;
        });
        service = new CourseQueryServiceImpl(
                courseQueryCache, courseInfoMapper, courseContentMapper, courseScheduleMapper);
    }

    /** evictCourse 语义 stub：清空 store（真实实现为 Redis 单键 + 前缀 SCAN，行为由领域缓存单测覆盖） */
    private void stubEvictClearsStore() {
        doAnswer(inv -> {
                    store.clear();
                    return null;
                })
                .when(courseQueryCache)
                .evictCourse(anyLong());
    }

    @Test
    @DisplayName("findCourseById 命中缓存返回同一实例")
    void findCourseById_hitsCache() {
        CourseInfo info = new CourseInfo();
        info.setId(1L);
        info.setTitle("缓存课程");
        store.put("course:1", info);

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
    @DisplayName("findByTitle — 精确匹配返回课程列表（同名多课全返回）")
    void findByTitle_matchesExactTitle() {
        CourseInfo raw = new CourseInfo();
        raw.setId(101L);
        raw.setTitle("高等数学");
        when(courseInfoMapper.selectList(any())).thenReturn(List.of(raw));

        List<CourseInfo> result = service.findByTitle("高等数学");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(101L);
        // 断言查询按 title 精确过滤（捕获 wrapper 的 SQL 段应含 title 条件）
        ArgumentCaptor<Wrapper<CourseInfo>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(courseInfoMapper).selectList(captor.capture());
        assertThat(captor.getValue().getExpression().getSqlSegment()).contains("title");
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
    @DisplayName("evictCourse 委托领域缓存类（单键 + search/byTitle 前缀清理在 CourseQueryCacheService 自身测试）")
    void evictCourse_delegatesToDomainCache() {
        stubEvictClearsStore();
        service.evictCourse(1L);
        verify(courseQueryCache).evictCourse(1L);
    }

    @Test
    @DisplayName("P1-2 evictCourse 失效后再次查询回源 DB（缓存不再命中，拿到最新映射）")
    void evictCourse_reloadedFromDbAfterEviction() {
        stubEvictClearsStore();
        CourseInfo stale = new CourseInfo();
        stale.setId(1L);
        when(courseInfoMapper.selectList(any())).thenReturn(List.of(stale));

        // 第一次查询写入缓存
        service.findByTitle("高等数学");
        // 课程删除/改名 → evictCourse 失效（store 清空，等价 Redis 前缀清理）
        service.evictCourse(1L);
        // 再次查询必须回源（若失效未生效则第二次命中缓存、mapper 只调用 1 次）
        CourseInfo fresh = new CourseInfo();
        fresh.setId(9L);
        when(courseInfoMapper.selectList(any())).thenReturn(List.of(fresh));
        List<CourseInfo> after = service.findByTitle("高等数学");

        assertThat(after.get(0).getId()).isEqualTo(9L);
        verify(courseInfoMapper, times(2)).selectList(any());
    }
}
