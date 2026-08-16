package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.convert.ScheduleConverter;
import com.commerce.rag.dto.CreateScheduleRequest;
import com.commerce.rag.dto.ScheduleDTO;
import com.commerce.rag.dto.UpdateScheduleRequest;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.ICourseScheduleService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端排期管理 Controller —— CRUD 端点 F1-F5
 *
 * <p>权限：SUPER_ADMIN + TEACHER（教师只能操作自己创建的课程下的排期）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminScheduleController {

    private final ICourseScheduleService scheduleService;
    private final ScheduleConverter scheduleConverter;

    public AdminScheduleController(ICourseScheduleService scheduleService, ScheduleConverter scheduleConverter) {
        this.scheduleService = scheduleService;
        this.scheduleConverter = scheduleConverter;
    }

    /**
     * F1: 排期列表（P0-4：读端点补归属校验——教师仅能查看自己课程的排期）
     */
    @GetMapping("/courses/{courseId}/schedules")
    public ApiResponse<List<ScheduleDTO>> listByCourse(HttpServletRequest request, @PathVariable Long courseId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        List<CourseSchedule> schedules = scheduleService.findByCourseId(courseId, userId, isAdmin);
        List<ScheduleDTO> dtos =
                schedules.stream().map(scheduleConverter::toDTO).collect(Collectors.toList());
        return ApiResponse.ok(dtos);
    }

    /**
     * F2: 创建排期
     */
    @PostMapping("/courses/{courseId}/schedules")
    public ApiResponse<ScheduleDTO> create(
            HttpServletRequest request, @PathVariable Long courseId, @RequestBody CreateScheduleRequest createRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        CourseSchedule schedule = scheduleService.create(courseId, createRequest, userId, isAdmin);
        return ApiResponse.ok(scheduleConverter.toDTO(schedule));
    }

    /**
     * F3: 查看排期（P0-4：读端点补归属校验——教师仅能查看自己课程的排期）
     */
    @GetMapping("/schedules/{id}")
    public ApiResponse<ScheduleDTO> detail(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        CourseSchedule schedule = scheduleService.findById(id, userId, isAdmin);
        if (schedule == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "排期不存在");
        }
        return ApiResponse.ok(scheduleConverter.toDTO(schedule));
    }

    /**
     * F4: 更新排期
     */
    @PutMapping("/schedules/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody UpdateScheduleRequest updateRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        scheduleService.update(id, updateRequest, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * F5: 删除排期
     */
    @DeleteMapping("/schedules/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        scheduleService.delete(id, userId, isAdmin);
        return ApiResponse.ok();
    }
}
