package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.EnrollmentRequest;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.service.EnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AdminEnrollmentController 单元测试 —— 选课管理端点 G1-G4
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminEnrollmentController 选课管理端点测试")
class AdminEnrollmentControllerTest {

    @Mock
    private EnrollmentService enrollmentService;

    private AdminEnrollmentController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminEnrollmentController(enrollmentService);
    }

    private HttpServletRequest request(String role, Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    @Test
    @DisplayName("G1 students → 超管返回全部学生列表")
    void students_superAdmin_returnsAll() {
        StudentDTO student = new StudentDTO(5L, "student5", "学生五", LocalDateTime.now(), "ACTIVE");
        when(enrollmentService.findStudents(1L, 1L, true)).thenReturn(List.of(student));

        ApiResponse<List<StudentDTO>> result = controller.students(request("SUPER_ADMIN", 1L), 1L);

        assertEquals(1, result.data().size());
        assertEquals("student5", result.data().get(0).username());
        verify(enrollmentService).findStudents(1L, 1L, true);
    }

    @Test
    @DisplayName("G1 students → 教师按归属过滤（isAdmin=false）")
    void students_teacher_filtersByOwnership() {
        when(enrollmentService.findStudents(1L, 7L, false)).thenReturn(List.of());

        controller.students(request("TEACHER", 7L), 1L);

        verify(enrollmentService).findStudents(1L, 7L, false);
    }

    @Test
    @DisplayName("G2 addStudents → 返回实际新增数量")
    void addStudents_returnsAddedCount() {
        EnrollmentRequest enrollmentRequest = new EnrollmentRequest(List.of(5L, 6L));
        when(enrollmentService.addStudents(1L, List.of(5L, 6L), 1L, true)).thenReturn(2);

        ApiResponse<Integer> result = controller.addStudents(request("SUPER_ADMIN", 1L), 1L, enrollmentRequest);

        assertEquals(2, result.data());
    }

    @Test
    @DisplayName("G3 removeStudent → 透传归属标记调用移除")
    void removeStudent_passesThrough() {
        controller.removeStudent(request("TEACHER", 7L), 1L, 5L);

        verify(enrollmentService).removeStudent(1L, 5L, 7L, false);
    }

    @Test
    @DisplayName("G4 studentCourses → 返回学生选课 DTO 列表（service 转换）")
    void studentCourses_returnsDTOs() {
        when(enrollmentService.findStudentCoursesAsDTO(5L))
                .thenReturn(List.of(new CourseDTO(
                        1L, "Java", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null)));

        ApiResponse<List<CourseDTO>> result = controller.studentCourses(5L);

        assertEquals(1, result.data().size());
        assertEquals("Java", result.data().get(0).title());
    }
}
