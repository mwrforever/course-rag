package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.repository.AbstractRepository;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.commerce.rag.cache.PublicCourseCacheEvictor;
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
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.service.impl.EnrollmentServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.CoursePurchaseVO;
import com.commerce.rag.vo.StudentCourseVO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;

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

    /** 课程主表 mapper（purchaseCourse 跨 service 链式查询载体，真实链式 wrapper 绑定此 mock） */
    @Mock
    private CourseInfoMapper courseInfoMapper;

    /** 公开课程缓存失效器（M9：Mock——购买/选课写路径失效钩子仅需不抛异常） */
    @Mock
    private PublicCourseCacheEvictor publicCourseCacheEvictor;

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
        // M9：批量选课变更公开可见数据，公开课程缓存区失效
        verify(publicCourseCacheEvictor).evictAllAfterCommit();
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
        // M9：重激活 + 新建混合分支同样触发公开课程缓存区失效（写路径统一出口）
        verify(publicCourseCacheEvictor).evictAllAfterCommit();
    }

    // ==================== removeStudent ====================

    @Test
    @DisplayName("removeStudent → 软删成功（status → DROPPED）")
    void removeStudent_success_softDeletes() {
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);

        enrollmentService.removeStudent(1L, 5L, 7L, false);

        verify(courseService).checkOwnership(1L, 7L, false);
        verify(enrollmentMapper).update(isNull(), any());
        // M9：退课变更选课数据，公开课程缓存区失效
        verify(publicCourseCacheEvictor).evictAllAfterCommit();
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

    // ==================== purchaseCourse（契约 B.2，C 端自助购买） ====================

    /** Spring 事务元数据解析器 —— 与生产事务切面同一解析路径，验证注解会被识别且异常触发回滚 */
    private static final AnnotationTransactionAttributeSource TX_SOURCE = new AnnotationTransactionAttributeSource();

    /**
     * 注入链式查询依赖的继承字段（baseMapper/entityClass）
     *
     * <p>纯 Mockito 下 {@code this.lambdaQuery()/this.lambdaUpdate()} 构建链时需预置
     * baseMapper 与 entityClass（与 CourseServiceTest 同款方案）。
     */
    private void injectChainFields() throws Exception {
        Field baseMapper = CrudRepository.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(enrollmentService, enrollmentMapper);
        Field entityClass = AbstractRepository.class.getDeclaredField("entityClass");
        entityClass.setAccessible(true);
        entityClass.set(enrollmentService, CourseEnrollment.class);
    }

    /** stub 课程查询链（跨 service 链式查询走真实 LambdaQueryChainWrapper 绑定 mock mapper） */
    private void stubCourseQuery(CourseInfo course) {
        when(courseService.lambdaQuery()).thenReturn(new LambdaQueryChainWrapper<>(courseInfoMapper));
        when(courseInfoMapper.selectOne(any())).thenReturn(course);
    }

    /** 构造 ACTIVE 课程实体（purchaseCourse 仅取 id/status 投影） */
    private CourseInfo activeCourse(Long id) {
        CourseInfo course = new CourseInfo();
        course.setId(id);
        course.setStatus("ACTIVE");
        return course;
    }

    @Test
    @DisplayName("T1.2: purchaseCourse → 课程不存在（含非 ACTIVE/已软删）统一 404，不泄露存在性")
    void purchaseCourse_courseMissing_throws404() throws Exception {
        injectChainFields();
        stubCourseQuery(null);

        BizException ex = assertThrows(BizException.class, () -> enrollmentService.purchaseCourse(99L, 5L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertTrue(ex.getMessage().contains("课程不存在或已下架"));
        // 404 短路：不触发任何选课查询与写入
        verify(enrollmentMapper, never()).selectOne(any());
        verify(enrollmentService, never()).save(any(CourseEnrollment.class));
    }

    @Test
    @DisplayName("T1.2: purchaseCourse → 无选课记录时插入 ACTIVE 记录（对齐 addStudents 构造语义）")
    void purchaseCourse_noRecord_insertsActive() throws Exception {
        injectChainFields();
        stubCourseQuery(activeCourse(1L));
        // 选课记录查询（主表链式 one()）→ 无记录
        when(enrollmentMapper.selectOne(any())).thenReturn(null);
        // save 走 spy 桩（内部 this.save 调用被拦截，不触真实 baseMapper）
        doReturn(true).when(enrollmentService).save(any(CourseEnrollment.class));

        CoursePurchaseVO vo = enrollmentService.purchaseCourse(1L, 5L);

        assertEquals(1L, vo.courseId());
        assertEquals("ACTIVE", vo.status());
        assertTrue(vo.purchased());
        // 插入实体语义：ACTIVE + enrolledAt=now + 归属 courseId/studentId
        ArgumentCaptor<CourseEnrollment> captor = ArgumentCaptor.forClass(CourseEnrollment.class);
        verify(enrollmentService).save(captor.capture());
        CourseEnrollment inserted = captor.getValue();
        assertEquals(1L, inserted.getCourseId());
        assertEquals(5L, inserted.getStudentId());
        assertEquals("ACTIVE", inserted.getStatus());
        assertNotNull(inserted.getEnrolledAt(), "插入记录应携带 enrolledAt=now");
        // M9：购买变更选课数据，公开课程缓存区失效（新购买立即可见于 C 端）
        verify(publicCourseCacheEvictor).evictAllAfterCommit();
    }

    @Test
    @DisplayName("T1.2: purchaseCourse → DROPPED 记录重激活（置 ACTIVE + enrolledAt=now）")
    void purchaseCourse_dropped_reactivates() throws Exception {
        injectChainFields();
        stubCourseQuery(activeCourse(1L));
        CourseEnrollment dropped = enrollment(1L, 5L, "DROPPED");
        dropped.setId(10L);
        when(enrollmentMapper.selectOne(any())).thenReturn(dropped);
        when(enrollmentMapper.update(isNull(), any())).thenReturn(1);

        CoursePurchaseVO vo = enrollmentService.purchaseCourse(1L, 5L);

        assertEquals("ACTIVE", vo.status());
        assertTrue(vo.purchased());
        // 重激活走主表链式 UPDATE，不产生新插入
        verify(enrollmentMapper).update(isNull(), any());
        verify(enrollmentService, never()).save(any(CourseEnrollment.class));
        // M9：重激活分支同样触发公开课程缓存区失效（与插入分支统一出口）
        verify(publicCourseCacheEvictor).evictAllAfterCommit();
    }

    @Test
    @DisplayName("T1.2: purchaseCourse → 已 ACTIVE 幂等直接返回成功，不产生任何写")
    void purchaseCourse_active_idempotentNoWrite() throws Exception {
        injectChainFields();
        stubCourseQuery(activeCourse(1L));
        CourseEnrollment active = enrollment(1L, 5L, "ACTIVE");
        active.setId(11L);
        when(enrollmentMapper.selectOne(any())).thenReturn(active);

        CoursePurchaseVO vo = enrollmentService.purchaseCourse(1L, 5L);

        assertEquals(1L, vo.courseId());
        assertEquals("ACTIVE", vo.status());
        assertTrue(vo.purchased());
        // 幂等：无插入、无更新（重复调用不重复插行）
        verify(enrollmentService, never()).save(any(CourseEnrollment.class));
        verify(enrollmentMapper, never()).update(isNull(), any());
        verify(enrollmentMapper, never()).insert(any(CourseEnrollment.class));
    }

    @Test
    @DisplayName("T1.2: purchaseCourse → 并发撞唯一索引（DIVE）按已购幂等返回成功，禁止重查（B.2.3）")
    void purchaseCourse_uniqueViolation_returnsSuccessWithoutRequery() throws Exception {
        injectChainFields();
        stubCourseQuery(activeCourse(1L));
        when(enrollmentMapper.selectOne(any())).thenReturn(null);
        // 并发竞态：check 落空后插入撞 uniq_course_enrollment 部分唯一索引
        doThrow(new DataIntegrityViolationException("uniq_course_enrollment 冲突"))
                .when(enrollmentService)
                .save(any(CourseEnrollment.class));

        CoursePurchaseVO vo = enrollmentService.purchaseCourse(1L, 5L);

        // 契约 B.2.3：事务已 rollback-only，catch 后直接构造成功 VO（不报 409、不重查）
        assertEquals(1L, vo.courseId());
        assertEquals("ACTIVE", vo.status());
        assertTrue(vo.purchased());
        // 禁止在原事务内重查（会触发 UnexpectedRollbackException）——selectOne 仅课程查询后的首次选课查询
        verify(enrollmentMapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("T1.2: purchaseCourse 标注 @Transactional（选课写入原子性）")
    void purchaseCourse_isTransactional() throws NoSuchMethodException {
        Method method = EnrollmentServiceImpl.class.getMethod("purchaseCourse", Long.class, Long.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, EnrollmentServiceImpl.class);

        // 契约 B.2：check-then-insert 事务内执行（DIVE 兜底依赖事务回滚语义）
        assertNotNull(attr, "purchaseCourse 应标注 @Transactional");
        assertTrue(attr.rollbackOn(new RuntimeException("选课写入失败")));
    }
}
