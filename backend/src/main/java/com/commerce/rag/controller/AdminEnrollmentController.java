package com.commerce.rag.controller;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.EnrollmentRequest;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.service.EnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端选课管理 Controller —— CRUD 端点 G1-G4
 *
 * <p>权限：SUPER_ADMIN + TEACHER（教师只能操作自己创建的课程）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminEnrollmentController {

    private final EnrollmentService enrollmentService;

    public AdminEnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /**
     * G1: 选课学生列表
     */
    @GetMapping("/courses/{courseId}/students")
    public ApiResponse<List<StudentDTO>> students(HttpServletRequest request, @PathVariable Long courseId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        List<StudentDTO> students = enrollmentService.findStudents(courseId, userId, isAdmin);
        return ApiResponse.ok(students);
    }

    /**
     * G2: 批量添加学生
     */
    @PostMapping("/courses/{courseId}/students")
    public ApiResponse<Integer> addStudents(
            HttpServletRequest request, @PathVariable Long courseId, @RequestBody EnrollmentRequest enrollmentRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        int added = enrollmentService.addStudents(courseId, enrollmentRequest.studentIds(), userId, isAdmin);
        return ApiResponse.ok(added);
    }

    /**
     * G3: 移除学生
     */
    @DeleteMapping("/courses/{courseId}/students/{studentId}")
    public ApiResponse<Void> removeStudent(
            HttpServletRequest request, @PathVariable Long courseId, @PathVariable Long studentId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        enrollmentService.removeStudent(courseId, studentId, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * G4: 学生选课列表
     */
    @GetMapping("/students/{id}/courses")
    public ApiResponse<List<CourseDTO>> studentCourses(@PathVariable Long id) {
        return ApiResponse.ok(enrollmentService.findStudentCoursesAsDTO(id));
    }
}
