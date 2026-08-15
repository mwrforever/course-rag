package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.controller.dto.CreateScheduleRequest;
import com.commerce.rag.controller.dto.UpdateScheduleRequest;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.time.LocalDate;
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

    @Test
    @DisplayName("create → 校验归属后按请求创建 UPCOMING 排期并失效缓存")
    void create_buildsScheduleAndEvictsCache() {
        CreateScheduleRequest request = new CreateScheduleRequest(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "WEEKEND", "线上", "张老师", 50);

        CourseSchedule result = scheduleService.create(55L, request, 100L, false);

        verify(courseService).checkOwnership(55L, 100L, false);
        verify(scheduleMapper).insert(result);
        verify(courseQueryService).evictCourse(55L);
        assertEquals(55L, result.getCourseId());
        assertEquals(LocalDate.of(2026, 9, 1), result.getStartDate());
        assertEquals("WEEKEND", result.getScheduleType());
        assertEquals(50, result.getCapacity());
        assertEquals(0, result.getEnrolled());
        assertEquals("UPCOMING", result.getStatus());
        assertEquals(100L, result.getCreatedBy());
    }

    @Test
    @DisplayName("create → capacity 为空时默认 0")
    void create_nullCapacity_defaultsZero() {
        CreateScheduleRequest request = new CreateScheduleRequest(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "WEEKEND", "线下", "李老师", null);

        CourseSchedule result = scheduleService.create(55L, request, 100L, false);

        assertEquals(0, result.getCapacity());
    }

    @Test
    @DisplayName("findById(无参) → 直接按 ID 查询")
    void findById_noPermission_returnsSchedule() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);

        CourseSchedule result = scheduleService.findById(1L);

        assertEquals(1L, result.getId());
        verify(courseService, never()).checkOwnership(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("update → 排期不存在抛 404")
    void update_notFound_throws404() {
        when(scheduleMapper.selectById(99L)).thenReturn(null);

        UpdateScheduleRequest request =
                new UpdateScheduleRequest(LocalDate.of(2026, 9, 1), null, null, null, null, null, null, null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> scheduleService.update(99L, request, 100L, false));

        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("update → 校验归属后按非空字段更新并失效缓存")
    void update_partialFields_updatesAndEvictsCache() {
        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(1L);
        schedule.setCourseId(55L);
        when(scheduleMapper.selectById(1L)).thenReturn(schedule);
        // 仅更新 location 与 status，其余字段保持原值
        UpdateScheduleRequest request = new UpdateScheduleRequest(null, null, null, "新地点", null, null, null, "ACTIVE");

        scheduleService.update(1L, request, 100L, false);

        verify(courseService).checkOwnership(55L, 100L, false);
        verify(scheduleMapper).update(isNull(), any());
        verify(courseQueryService).evictCourse(55L);
    }
}
