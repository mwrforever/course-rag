package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.convert.EnrollmentConverter;
import com.commerce.rag.convert.EnrollmentConverterImpl;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.convert.StudentConverterImpl;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.service.impl.EnrollmentServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.StudentCourseVO;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * IEnrollmentService 单元测试 —— 选课管理（列表/批量添加/移除/学生课程/选课校验）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IEnrollmentService 选课管理测试")
class EnrollmentServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private CourseEnrollmentMapper enrollmentMapper;

    @Mock
    private ICourseService courseService;

    @Mock
    private SysUserMapper sysUserMapper;

    @Spy
    private EnrollmentConverter enrollmentConverter = new EnrollmentConverterImpl();

    /** 学生端转换器用真实实现（MapStruct 生成类），转换行为由 StudentConverterTest 单独覆盖 */
    @Spy
    private StudentConverter studentConverter = new StudentConverterImpl();

    @Spy
    @InjectMocks
    private EnrollmentServiceImpl enrollmentService;

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
    @DisplayName("addStudents → 全部新学生时批量插入（L-3：saveBatch 替代逐条 insert）")
    void addStudents_allNew_insertsAll() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());
        // L-3: 新建集合走 service 批量 API（JDBC 批处理），spy 拦截真实 DB 调用
        doReturn(true).when(enrollmentService).saveBatch(anyList());

        int added = enrollmentService.addStudents(1L, List.of(5L, 6L), 1L, true);

        assertEquals(2, added);
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(enrollmentService).saveBatch(captor.capture());
        assertEquals(2, captor.getValue().size(), "批量插入应携带 2 条新选课记录");
        verify(enrollmentMapper, never()).insert(any(CourseEnrollment.class));
    }

    @Test
    @DisplayName("addStudents → 已活跃的跳过、已退课的重新激活、新学生插入")
    void addStudents_skipActive_reactivateDropped() {
        // 已存在记录：5 号活跃、6 号已退课；本次请求 [5,6,7]
        when(enrollmentMapper.selectList(any()))
                .thenReturn(List.of(enrollment(1L, 5L, "ACTIVE"), enrollment(1L, 6L, "DROPPED")));

        doReturn(true).when(enrollmentService).saveBatch(anyList());

        int added = enrollmentService.addStudents(1L, List.of(5L, 6L, 7L), 1L, true);

        assertEquals(2, added);
        // L-3: 待激活集合（6 号）单条批量 UPDATE 激活、新建集合（7 号）saveBatch；5 号无任何操作
        verify(enrollmentMapper).update(isNull(), any());
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(enrollmentService).saveBatch(captor.capture());
        assertEquals(1, captor.getValue().size(), "仅 7 号进入新建集合");
        verify(enrollmentMapper, never()).insert(any(CourseEnrollment.class));
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

        BizException ex = assertThrows(BizException.class, () -> enrollmentService.removeStudent(1L, 5L, 1L, true));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
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

    @Test
    @DisplayName("findStudentCoursesAsDTO → 经 courseService.toDTO 转换为 DTO 列表（转换下沉 service）")
    void findStudentCoursesAsDTO_convertsViaCourseService() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of(enrollment(1L, 5L, "ACTIVE")));
        CourseInfo c1 = new CourseInfo();
        c1.setId(1L);
        c1.setTitle("Java");
        when(courseService.findByIds(List.of(1L))).thenReturn(List.of(c1));
        when(courseService.toDTO(c1, false))
                .thenReturn(new CourseDTO(
                        1L, "Java", null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null));

        var result = enrollmentService.findStudentCoursesAsDTO(5L);

        assertEquals(1, result.size());
        assertEquals("Java", result.get(0).title());
        verify(courseService).toDTO(c1, false);
    }

    @Test
    @DisplayName("findStudentCoursesAsDTO → 无选课时返回空列表（不触发转换）")
    void findStudentCoursesAsDTO_noEnrollment_returnsEmpty() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());

        var result = enrollmentService.findStudentCoursesAsDTO(5L);

        assertTrue(result.isEmpty());
        verify(courseService, never()).toDTO(any(), anyBoolean());
    }

    // ==================== findStudentCoursesAsVO ====================

    @Test
    @DisplayName("findStudentCoursesAsVO → 批量查询后转为 C 端课程 VO（剔除价格/描述等内部字段）")
    void findStudentCoursesAsVO_returnsConvertedVOs() {
        when(enrollmentMapper.selectList(any()))
                .thenReturn(List.of(enrollment(1L, 5L, "ACTIVE"), enrollment(2L, 5L, "ACTIVE")));
        CourseInfo c1 = new CourseInfo();
        c1.setId(1L);
        c1.setTitle("Java 入门");
        c1.setCoverImage("cover.png");
        c1.setCategory("编程");
        c1.setInstructorName("张老师");
        c1.setDuration("10h");
        c1.setRating(new BigDecimal("4.5"));
        c1.setLearningCount(100);
        c1.setPrice(new BigDecimal("99"));
        CourseInfo c2 = new CourseInfo();
        c2.setId(2L);
        c2.setTitle("Spring");
        when(courseService.findByIds(List.of(1L, 2L))).thenReturn(List.of(c1, c2));

        List<StudentCourseVO> result = enrollmentService.findStudentCoursesAsVO(5L);

        assertEquals(2, result.size());
        StudentCourseVO vo = result.get(0);
        assertEquals(1L, vo.id());
        assertEquals("Java 入门", vo.title());
        assertEquals("cover.png", vo.coverImage());
        assertEquals("编程", vo.category());
        assertEquals("张老师", vo.instructorName());
        assertEquals("10h", vo.duration());
        assertEquals(new BigDecimal("4.5"), vo.rating());
        assertEquals(100, vo.learningCount());
        verify(courseService).findByIds(List.of(1L, 2L));
    }

    @Test
    @DisplayName("findStudentCoursesAsVO → 无选课时返回空列表（不触发课程查询）")
    void findStudentCoursesAsVO_noEnrollment_returnsEmpty() {
        when(enrollmentMapper.selectList(any())).thenReturn(List.of());

        List<StudentCourseVO> result = enrollmentService.findStudentCoursesAsVO(5L);

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
