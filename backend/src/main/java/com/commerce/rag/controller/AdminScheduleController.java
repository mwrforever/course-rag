package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.CreateScheduleRequest;
import com.commerce.rag.dto.UpdateScheduleRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.ICourseScheduleService;
import com.commerce.rag.vo.CourseScheduleVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

    public AdminScheduleController(ICourseScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    /**
     * F1: 排期列表（P0-4：读端点补归属校验——教师仅能查看自己课程的排期）
     */
    @GetMapping("/courses/{courseId}/schedules")
    public ApiResponse<List<CourseScheduleVO>> listByCourse(HttpServletRequest request, @PathVariable Long courseId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        return ApiResponse.ok(scheduleService.findByCourseId(courseId, userId, isAdmin));
    }

    /**
     * F2: 创建排期
     */
    @PostMapping("/courses/{courseId}/schedules")
    public ApiResponse<CourseScheduleVO> create(
            HttpServletRequest request, @PathVariable Long courseId, @RequestBody CreateScheduleRequest createRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        return ApiResponse.ok(scheduleService.create(courseId, createRequest, userId, isAdmin));
    }

    /**
     * F3: 查看排期（P0-4：读端点补归属校验——教师仅能查看自己课程的排期）
     */
    @GetMapping("/schedules/{id}")
    public ApiResponse<CourseScheduleVO> detail(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        CourseScheduleVO schedule = scheduleService.findById(id, userId, isAdmin);
        if (schedule == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "排期不存在");
        }
        return ApiResponse.ok(schedule);
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
