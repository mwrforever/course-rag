package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * CourseScheduleService 单元测试 —— 排期 CRUD + 读端点归属校验（P0-4）
 *
 * <p>P0-4 修复：F1 列表/F3 详情读端点原先无任何归属校验（任意 TEACHER 可枚举他人课程排期），
 * 修复后 findById/findByCourseId 带权限的重载先经 CourseService.checkOwnership 校验。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CourseScheduleService 排期服务测试")
class CourseScheduleServiceTest {

    @Mock
    private CourseScheduleMapper scheduleMapper;

    @Mock
    private CourseService courseService;

    @Mock
    private CourseQueryService courseQueryService;

    private CourseScheduleService scheduleService;

    @BeforeAll
    static void initMybatisPlus() {
        // 纯 Mockito 单元测试（无 Spring 上下文）需先初始化 LambdaUpdateWrapper 的 TableInfo 缓存
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        // 构造器注入（@RequiredArgsConstructor 按字段声明顺序生成全参构造器）
        scheduleService = new CourseScheduleService(scheduleMapper, courseService, courseQueryService);
    }

    @Test
    @DisplayName("P0-4 findById(带权限) → 先经课程归属校验；无权访问抛 403")
    void findById_withPermission_checksCourseOwnership() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);
        // 教师 100 非课程 55 创建者 → checkOwnership 抛 403
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "无权操作此课程"))
                .when(courseService)
                .checkOwnership(55L, 100L, false);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> scheduleService.findById(1L, 100L, false));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("P0-4 findById(带权限) → 课程属主可查看")
    void findById_withPermission_ownerCanView() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);

        CourseSchedule result = scheduleService.findById(1L, 100L, false);

        assertNotNull(result);
        verify(courseService).checkOwnership(55L, 100L, false);
    }

    @Test
    @DisplayName("P0-4 findByCourseId(带权限) → 先经课程归属校验（读端点越权修复）")
    void findByCourseId_withPermission_checksCourseOwnership() {
        scheduleService.findByCourseId(55L, 100L, false);

        verify(courseService).checkOwnership(55L, 100L, false);
    }

    @Test
    @DisplayName("delete → 软删排期后按课程 ID 失效查询缓存")
    void delete_evictsQueryCache() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);

        scheduleService.delete(1L, 100L, false);

        verify(courseQueryService).evictCourse(55L);
    }
}
