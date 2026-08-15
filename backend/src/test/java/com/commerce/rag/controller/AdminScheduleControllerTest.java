package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.CreateScheduleRequest;
import com.commerce.rag.controller.dto.ScheduleDTO;
import com.commerce.rag.controller.dto.UpdateScheduleRequest;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.service.CourseScheduleService;
import com.commerce.rag.service.ScheduleConverter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminScheduleController 单元测试 —— B 端排期管理端点 F1-F5
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminScheduleController 排期管理端点测试")
class AdminScheduleControllerTest {

    @Mock
    private CourseScheduleService scheduleService;

    @Mock
    private ScheduleConverter scheduleConverter;

    private AdminScheduleController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminScheduleController(scheduleService, scheduleConverter);
    }

    private HttpServletRequest request(String role, Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    private CourseSchedule schedule(Long id) {
        CourseSchedule s = new CourseSchedule();
        s.setId(id);
        s.setCourseId(1L);
        s.setStartDate(LocalDate.of(2026, 9, 1));
        s.setEndDate(LocalDate.of(2026, 12, 31));
        s.setScheduleType("WEEKEND");
        s.setLocation("线上");
        s.setInstructorName("张老师");
        s.setCapacity(50);
        s.setEnrolled(10);
        s.setStatus("ACTIVE");
        s.setCreatedBy(1L);
        return s;
    }

    private ScheduleDTO scheduleDTO(Long id) {
        return new ScheduleDTO(
                id,
                1L,
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 12, 31),
                "WEEKEND",
                "线上",
                "张老师",
                50,
                10,
                "ACTIVE",
                1L);
    }

    @Test
    @DisplayName("F1 listByCourse → 超管不限归属，返回排期 DTO 列表")
    void listByCourse_superAdmin_returnsAll() {
        when(scheduleService.findByCourseId(1L, 1L, true)).thenReturn(List.of(schedule(1L)));
        when(scheduleConverter.toDTO(any(CourseSchedule.class))).thenReturn(scheduleDTO(1L));

        ApiResponse<List<ScheduleDTO>> result = controller.listByCourse(request("SUPER_ADMIN", 1L), 1L);

        assertEquals(1, result.data().size());
        assertEquals(1L, result.data().get(0).id());
        verify(scheduleService).findByCourseId(1L, 1L, true);
    }

    @Test
    @DisplayName("F1 listByCourse → 教师按归属过滤（isAdmin=false）")
    void listByCourse_teacher_filtersByOwnership() {
        when(scheduleService.findByCourseId(1L, 7L, false)).thenReturn(List.of());

        controller.listByCourse(request("TEACHER", 7L), 1L);

        verify(scheduleService).findByCourseId(1L, 7L, false);
    }

    @Test
    @DisplayName("F2 create → 调用创建并返回 DTO")
    void create_callsService() {
        CreateScheduleRequest createRequest = new CreateScheduleRequest(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "WEEKEND", "线上", "张老师", 50);
        when(scheduleService.create(1L, createRequest, 1L, true)).thenReturn(schedule(1L));
        when(scheduleConverter.toDTO(any(CourseSchedule.class))).thenReturn(scheduleDTO(1L));

        ApiResponse<ScheduleDTO> result = controller.create(request("SUPER_ADMIN", 1L), 1L, createRequest);

        assertEquals(1L, result.data().id());
        verify(scheduleService).create(1L, createRequest, 1L, true);
    }

    @Test
    @DisplayName("F3 detail → 排期不存在抛 404")
    void detail_notFound_throws404() {
        when(scheduleService.findById(99L, 7L, false)).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.detail(request("TEACHER", 7L), 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("排期不存在", ex.getReason());
    }

    @Test
    @DisplayName("F3 detail → 存在时返回 DTO")
    void detail_returnsDTO() {
        when(scheduleService.findById(1L, 1L, true)).thenReturn(schedule(1L));
        when(scheduleConverter.toDTO(any(CourseSchedule.class))).thenReturn(scheduleDTO(1L));

        ApiResponse<ScheduleDTO> result = controller.detail(request("SUPER_ADMIN", 1L), 1L);

        assertEquals("WEEKEND", result.data().scheduleType());
    }

    @Test
    @DisplayName("F4 update → 透传更新请求与归属标记")
    void update_passesThrough() {
        UpdateScheduleRequest updateRequest = new UpdateScheduleRequest(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), "WEEKEND", "线上", "张老师", 50, 10, "ACTIVE");

        controller.update(request("TEACHER", 7L), 1L, updateRequest);

        verify(scheduleService).update(1L, updateRequest, 7L, false);
    }

    @Test
    @DisplayName("F5 delete → 透传归属标记调用删除")
    void delete_passesThrough() {
        controller.delete(request("TEACHER", 7L), 1L);

        verify(scheduleService).delete(1L, 7L, false);
    }
}
