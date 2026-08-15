package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.controller.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * EnrollmentService 单元测试 —— 选课管理（列表/批量添加/移除/学生课程/选课校验）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnrollmentService 选课管理测试")
class EnrollmentServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private CourseEnrollmentMapper enrollmentMapper;

    @Mock
    private CourseService courseService;

    @Mock
    private SysUserMapper sysUserMapper;

    @Spy
    private EnrollmentConverter enrollmentConverter = new EnrollmentConverterImpl();

    @InjectMocks
    private EnrollmentService enrollmentService;

    private CourseEnrollment enrollment(Long courseId, Long studentId, String status) {
        CourseEnrollment e = new CourseEnrollment();
        e.setCourseId(courseId);
        e.setStudentId(studentId);
        e.setEnrolledAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        e.setStatus(status);
        return e;
    }

    private SysUser user(Long id) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setUsername("student" + id);
        u.setDisplayName("学生" + id);
        u.setStatus("ACTIVE");
        return u;
    }

    // ==================== findStudents ====================

    @Test
    @DisplayName("findStudents → 校验归属后按选课时间倒序返回学生 DTO 列表")
    void findStudents_returnsConvertedDTOs() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of(enrollment(1L, 5L, "ACTIVE")));
        when(sysUserMapper.selectByIdsIn(List.of(5L))).thenReturn(List.of(user(5L)));

        List<StudentDTO> result = enrollmentService.findStudents(1L, 7L, false);

        verify(courseService).checkOwnership(1L, 7L, false);
        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).id());
        assertEquals("student5", result.get(0).username());
        assertEquals("ACTIVE", result.get(0).status());
    }

    @Test
    @DisplayName("findStudents → 无选课记录时返回空列表（不触发用户查询）")
    void findStudents_noEnrollments_returnsEmpty() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());

        List<StudentDTO> result = enrollmentService.findStudents(1L, 1L, true);

        assertTrue(result.isEmpty());
        verify(sysUserMapper, never()).selectByIdsIn(anyList());
    }

    // ==================== addStudents ====================

    @Test
    @DisplayName("addStudents → 全部新学生时逐条插入")
    void addStudents_allNew_insertsAll() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());

        int added = enrollmentService.addStudents(1L, List.of(5L, 6L), 1L, true);

        assertEquals(2, added);
        verify(enrollmentMapper, times(2)).insert(any(CourseEnrollment.class));
    }

    @Test
    @DisplayName("addStudents → 已活跃的跳过、已退课的重新激活、新学生插入")
    void addStudents_skipActive_reactivateDropped() {
        // 已存在记录：5 号活跃、6 号已退课；本次请求 [5,6,7]
        when(enrollmentMapper.selectList(any()))
                .thenReturn(List.of(enrollment(1L, 5L, "ACTIVE"), enrollment(1L, 6L, "DROPPED")));

        int added = enrollmentService.addStudents(1L, List.of(5L, 6L, 7L), 1L, true);

        assertEquals(2, added);
        // 6 号走更新激活、7 号走插入；5 号无任何操作
        verify(enrollmentMapper).update(isNull(), any());
        verify(enrollmentMapper).insert(any(CourseEnrollment.class));
    }

    // ==================== removeStudent ====================

    @Test
    @DisplayName("removeStudent → 软删成功（status → DROPPED）")
    void removeStudent_success_softDeletes() {
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);

        enrollmentService.removeStudent(1L, 5L, 7L, false);

        verify(courseService).checkOwnership(1L, 7L, false);
        verify(enrollmentMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("removeStudent → 无选课记录时抛 404")
    void removeStudent_noRow_throws404() {
        when(enrollmentMapper.update(isNull(), any())).thenReturn(0);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> enrollmentService.removeStudent(1L, 5L, 1L, true));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ==================== findStudentCourses ====================

    @Test
    @DisplayName("findStudentCourses → 批量查询学生已选课程")
    void findStudentCourses_returnsCourses() {
        when(enrollmentMapper.selectList(any()))
                .thenReturn(List.of(enrollment(1L, 5L, "ACTIVE"), enrollment(2L, 5L, "ACTIVE")));
        CourseInfo c1 = new CourseInfo();
        c1.setId(1L);
        CourseInfo c2 = new CourseInfo();
        c2.setId(2L);
        when(courseService.findByIds(List.of(1L, 2L))).thenReturn(List.of(c1, c2));

        List<CourseInfo> result = enrollmentService.findStudentCourses(5L);

        assertEquals(2, result.size());
        verify(courseService).findByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("findStudentCourses → 无选课时返回空列表")
    void findStudentCourses_noEnrollment_returnsEmpty() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());

        List<CourseInfo> result = enrollmentService.findStudentCourses(5L);

        assertTrue(result.isEmpty());
        verify(courseService, never()).findByIds(anyList());
    }

    // ==================== isEnrolled ====================

    @Test
    @DisplayName("isEnrolled → 存在活跃选课记录时返回 true")
    void isEnrolled_activeRecord_returnsTrue() {
        when(enrollmentMapper.selectCount(any())).thenReturn(1L);

        assertTrue(enrollmentService.isEnrolled(1L, 5L));
    }

    @Test
    @DisplayName("isEnrolled → 无记录时返回 false")
    void isEnrolled_noRecord_returnsFalse() {
        when(enrollmentMapper.selectCount(any())).thenReturn(0L);

        assertFalse(enrollmentService.isEnrolled(1L, 5L));
    }
}
