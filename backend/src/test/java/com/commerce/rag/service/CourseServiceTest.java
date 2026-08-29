package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.repository.AbstractRepository;
import com.baomidou.mybatisplus.extension.repository.CrudRepository;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.CourseConverterImpl;
import com.commerce.rag.convert.PublicCourseConverterImpl;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.CreateCourseRequest;
import com.commerce.rag.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.properties.CourseProperties;
import com.commerce.rag.service.impl.CourseServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ICourseService 权限单元测试 —— 课程详情归属校验（P0-2g）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ICourseService 课程归属测试")
class CourseServiceTest {

    @Mock
    private CourseInfoMapper courseInfoMapper;

    @Mock
    private CourseContentMapper courseContentMapper;

    @Mock
    private CourseScheduleMapper courseScheduleMapper;

    @Mock
    private CourseTeacherMapper courseTeacherMapper;

    /** 课程-教师关联服务（P1-9：addTeachers 批量插入载体） */
    @Mock
    private ICourseTeacherService courseTeacherService;

    @Mock
    private CourseEnrollmentMapper courseEnrollmentMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private ICourseQueryService courseQueryService;

    /** Dashboard 统计缓存（Mock——级联软删路径的失效钩子仅需不抛异常） */
    @Mock
    private DashboardCacheEvictor dashboardCacheEvictor;

    /** saveBatch 批插参数捕获器（P1-9：批插内容等价断言） */
    @Captor
    private ArgumentCaptor<List<CourseTeacher>> batchCaptor;

    /** UPDATE wrapper 捕获器（T1.1：断言更新 SET 子句不含 enrollment_link） */
    @Captor
    private ArgumentCaptor<LambdaUpdateWrapper<CourseInfo>> updateWrapperCaptor;

    private ICourseService courseService;

    /** 课程域配置（默认基址与 application.yml course 段一致） */
    private static final CourseProperties COURSE_PROPERTIES = new CourseProperties(
            "http://localhost:3000", new CourseProperties.Cover(List.of("jpg", "jpeg", "png", "webp"), 5));

    @BeforeAll
    static void initMybatisPlus() {
        // 纯 Mockito 单元测试（无 Spring 上下文）需先初始化 LambdaUpdateWrapper 的 TableInfo 缓存
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        // 构造器注入（@RequiredArgsConstructor 按字段声明顺序生成全参构造器）
        courseService = new CourseServiceImpl(
                courseInfoMapper,
                courseContentMapper,
                courseScheduleMapper,
                courseTeacherMapper,
                courseTeacherService,
                new PublicCourseConverterImpl(),
                courseEnrollmentMapper,
                documentChunkMapper,
                etlPipeline,
                new CourseConverterImpl(),
                courseQueryService,
                dashboardCacheEvictor,
                COURSE_PROPERTIES);
    }

    @Test
    @DisplayName("findById 过滤重载 → 教师查看非自己创建的课程返回 null")
    void findById_teacherNotOwner_returnsNull() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        // 教师 200 查 createdBy=100 的课程 → null（controller 层 404）
        assertNull(courseService.findById(1L, 200L));
    }

    @Test
    @DisplayName("findById 过滤重载 → 创建者可查看 + 超管（filter=null）可查看任意课程（返回 DTO）")
    void findById_ownerAndAdmin_canView() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        course.setTitle("Java 入门");
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        // 返回课程 DTO（含关系查询走 mock 默认空列表）
        CourseDTO ownerDto = courseService.findById(1L, 100L);
        assertNotNull(ownerDto);
        assertEquals(1L, ownerDto.id());
        assertEquals("Java 入门", ownerDto.title());
        assertNotNull(courseService.findById(1L, null));
    }

    @Test
    @DisplayName("deleteCourse — 先清 Milvus（ByCourseId）再级联软删")
    void deleteCourse_cleansMilvusBeforeSoftDelete() {
        // Given: 课程 1 属于创建者 100
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        // When
        courseService.deleteCourse(1L, 100L, false);

        // Then: Milvus 清理先于 course_info 软删
        InOrder inOrder = inOrder(etlPipeline, courseInfoMapper);
        inOrder.verify(etlPipeline).deleteFromMilvusByCourseId("1");
        inOrder.verify(courseInfoMapper).update(any(), any());
    }

    @Test
    @DisplayName("deleteCourse — Milvus 删除失败上抛，级联软删不执行")
    void deleteCourse_milvusFailure_blocksSoftDelete() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);
        doThrow(new RuntimeException("Milvus 不可用")).when(etlPipeline).deleteFromMilvusByCourseId("1");

        assertThrows(RuntimeException.class, () -> courseService.deleteCourse(1L, 100L, false));
        verify(courseInfoMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("deleteCourse → 级联软删完成后按 courseId 失效查询缓存")
    void deleteCourse_evictsQueryCache() {
        // Given: 课程 1 属于创建者 100
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        // When
        courseService.deleteCourse(1L, 100L, false);

        // Then: 软删完成后触发缓存失效（先写 DB 后失效）
        verify(courseQueryService).evictCourse(1L);
    }

    // ==================== createCourse / 查询 / 教师 / 内容 补充 ====================

    /** stub insert 回填雪花 ID（模拟 ASSIGN_ID 主键回填，供链接生成断言） */
    private void stubInsertAssignId(long assignedId) {
        doAnswer(invocation -> {
                    CourseInfo entity = invocation.getArgument(0);
                    entity.setId(assignedId);
                    return 1;
                })
                .when(courseInfoMapper)
                .insert(any(CourseInfo.class));
    }

    @Test
    @DisplayName("createCourse → 组装默认字段并插入，失效搜索缓存（返回 DTO）")
    void createCourse_buildsAndInserts() throws Exception {
        injectChainFields(courseService);
        stubInsertAssignId(123L);
        CreateCourseRequest request = new CreateCourseRequest(
                "Java 入门", "描述", "cover.png", "编程", "张老师", new BigDecimal("99"), "10h", List.of("Java", "入门"), "link");

        CourseDTO dto = courseService.createCourse(request, 7L);

        // 插入实体为默认字段组装（默认状态/评分/学习人数/标签 JSON）
        ArgumentCaptor<CourseInfo> captor = ArgumentCaptor.forClass(CourseInfo.class);
        verify(courseInfoMapper).insert(captor.capture());
        CourseInfo inserted = captor.getValue();
        verify(courseQueryService).evictCourse(inserted.getId());
        assertEquals("ACTIVE", inserted.getStatus());
        assertEquals(new BigDecimal("0"), inserted.getRating());
        assertEquals(0, inserted.getLearningCount());
        assertEquals("[\"Java\",\"入门\"]", inserted.getTags());
        // 返回契约为课程 DTO（不含关联数据）
        assertEquals("Java 入门", dto.title());
        assertEquals("ACTIVE", dto.status());
    }

    @Test
    @DisplayName("createCourse → tags 为空时序列化为 []（返回 DTO）")
    void createCourse_emptyTags_serializesEmptyArray() throws Exception {
        injectChainFields(courseService);
        CreateCourseRequest request = new CreateCourseRequest("Java", null, null, null, null, null, null, null, null);

        CourseDTO dto = courseService.createCourse(request, 7L);

        assertTrue(dto.tags().isEmpty());
    }

    @Test
    @DisplayName("T1.1: createCourse → 忽略请求体 enrollmentLink，落库后同事务生成服务端链接写回")
    void createCourse_ignoresRequestLink_generatesServerLink() throws Exception {
        injectChainFields(courseService);
        stubInsertAssignId(1948633200000000001L);
        // 请求体携带恶意/旧值 enrollmentLink，服务端不得采信（契约 A.2.2）
        CreateCourseRequest request = new CreateCourseRequest(
                "Java 后端实战", null, null, null, null, null, null, null, "http://evil.example/enroll");

        CourseDTO dto = courseService.createCourse(request, 7L);

        // 插入实体最终 enrollmentLink 为服务端生成值（写回后同一实体引用的内存值同步），
        // 与请求体恶意值不同——证明请求体 enrollmentLink 未被采信（契约 A.2.2）
        ArgumentCaptor<CourseInfo> captor = ArgumentCaptor.forClass(CourseInfo.class);
        verify(courseInfoMapper).insert(captor.capture());
        assertEquals(
                "http://localhost:3000/courses/1948633200000000001",
                captor.getValue().getEnrollmentLink(),
                "enrollmentLink 应为服务端生成值而非请求体值");
        assertNotEquals("http://evil.example/enroll", captor.getValue().getEnrollmentLink());
        // 链接写回走主表链式 UPDATE（insert + update 同事务，契约 A.2.2）
        verify(courseInfoMapper).update(isNull(), any());
        // 返回 DTO 携带服务端生成链接：{enrollBaseUrl}/courses/{courseId}
        assertEquals("http://localhost:3000/courses/1948633200000000001", dto.enrollmentLink());
    }

    @Test
    @DisplayName("T1.1: createCourse → 基址含尾部斜杠时归一化（不产生 //courses/ 双斜杠）")
    void createCourse_baseUrlTrailingSlash_normalized() throws Exception {
        stubInsertAssignId(9L);
        CourseServiceImpl service = new CourseServiceImpl(
                courseInfoMapper,
                courseContentMapper,
                courseScheduleMapper,
                courseTeacherMapper,
                courseTeacherService,
                new PublicCourseConverterImpl(),
                courseEnrollmentMapper,
                documentChunkMapper,
                etlPipeline,
                new CourseConverterImpl(),
                courseQueryService,
                dashboardCacheEvictor,
                new CourseProperties("http://localhost:3000/", new CourseProperties.Cover(List.of("png"), 5)));
        // 链式字段须注入本测试另行构造的实例（非 setUp 的 courseService）
        injectChainFields(service);

        CourseDTO dto = service.createCourse(
                new CreateCourseRequest("Java", null, null, null, null, null, null, null, null), 7L);

        assertEquals("http://localhost:3000/courses/9", dto.enrollmentLink());
    }

    @Test
    @DisplayName("T1.1: createCourse → 事务上下文内缓存失效挂 afterCommit（提交后才失效）")
    void createCourse_evictsCacheAfterCommit() throws Exception {
        injectChainFields(courseService);
        stubInsertAssignId(123L);
        // 模拟活动事务同步上下文（生产由 @Transactional 切面注册）
        TransactionSynchronizationManager.initSynchronization();
        try {
            courseService.createCourse(
                    new CreateCourseRequest("Java", null, null, null, null, null, null, null, null), 7L);

            // 事务提交前：失效未执行（避免「失效后-提交前并发读回填旧值」窗口，契约 A.2.2 实现注记）
            verify(courseQueryService, never()).evictCourse(anyLong());
            // 触发 afterCommit 回调（等价事务提交后）
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            verify(courseQueryService).evictCourse(123L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("T1.1: createCourse 标注 @Transactional（insert 与链接写回同 commit 原子性）")
    void createCourse_isTransactional() throws NoSuchMethodException {
        Method method = CourseServiceImpl.class.getMethod("createCourse", CreateCourseRequest.class, Long.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, CourseServiceImpl.class);

        // 契约 A.2.2：落库 + 同事务二次写回，事务失败整体回滚不留空链接
        assertNotNull(attr, "createCourse 应标注 @Transactional（insert + 链接写回同事务）");
        assertTrue(attr.rollbackOn(new RuntimeException("链接写回失败")));
    }

    @Test
    @DisplayName("findByIds → 空列表直接返回空")
    void findByIds_empty_returnsEmpty() {
        assertTrue(courseService.findByIds(List.of()).isEmpty());
        verify(courseInfoMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("findByIds → 批量查询 ACTIVE 课程")
    void findByIds_returnsCourses() {
        when(courseInfoMapper.selectList(any())).thenReturn(List.of(new CourseInfo()));

        List<CourseInfo> result = courseService.findByIds(List.of(1L, 2L));

        assertEquals(1, result.size());
    }

    // ==================== 公开课程列表（C 端公开接口） ====================

    /**
     * 注入链式查询依赖的继承字段（baseMapper/entityClass）
     *
     * <p>纯 Mockito 下 {@code this.lambdaQuery()} 构建链时会经 getEntityClass →
     * getMapperClass → MybatisUtils.getMapperProxy 内窥真实 Mapper 代理（mock 非代理对象直接失败）；
     * 预置 entityClass 与 baseMapper 两个字段即可绕开内窥（与 ChatRunServiceTest 同款方案）。
     *
     * @param target 待注入的 service 实例（courseService 或测试内另行构造的实例）
     */
    private void injectChainFields(Object target) throws Exception {
        Field baseMapper = CrudRepository.class.getDeclaredField("baseMapper");
        baseMapper.setAccessible(true);
        baseMapper.set(target, courseInfoMapper);
        Field entityClass = AbstractRepository.class.getDeclaredField("entityClass");
        entityClass.setAccessible(true);
        entityClass.set(target, CourseInfo.class);
    }

    @Test
    @DisplayName("findPublicCourses → 查询 ACTIVE 课程并转换为公开 VO（字段映射完整，含价格）")
    void findPublicCourses_returnsPublicVO() throws Exception {
        injectChainFields(courseService);
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setTitle("Java 入门");
        course.setDescription("面向初学者的 Java 课程");
        course.setCoverImage("cover.png");
        course.setCategory("编程");
        course.setInstructorName("张老师");
        course.setDuration("10h");
        course.setRating(new BigDecimal("4.5"));
        course.setLearningCount(120);
        course.setPrice(new BigDecimal("299.00"));
        when(courseInfoMapper.selectList(any())).thenReturn(List.of(course));

        List<PublicCourseVO> result = courseService.findPublicCourses();

        assertEquals(1, result.size());
        PublicCourseVO vo = result.get(0);
        assertEquals(1L, vo.id());
        assertEquals("Java 入门", vo.title());
        assertEquals("面向初学者的 Java 课程", vo.description());
        assertEquals("cover.png", vo.coverImage());
        assertEquals("编程", vo.category());
        assertEquals("张老师", vo.instructorName());
        assertEquals("10h", vo.duration());
        assertEquals(new BigDecimal("4.5"), vo.rating());
        assertEquals(120, vo.learningCount());
        // 契约 C.2.1：价格转为 C 端公开展示字段随列表下发（单位元）
        assertEquals(new BigDecimal("299.00"), vo.price());
    }

    @Test
    @DisplayName("T1.3: findPublicCourseById → ACTIVE 课程返回公开详情 VO（含价格）")
    void findPublicCourseById_active_returnsDetailVO() throws Exception {
        injectChainFields(courseService);
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setTitle("Java 后端实战");
        course.setDescription("从零到一掌握 Spring Boot 企业级开发");
        course.setCoverImage("/api/v1/public/covers/0/abc.png");
        course.setCategory("后端开发");
        course.setInstructorName("王老师");
        course.setDuration("12 weeks");
        course.setRating(new BigDecimal("4.9"));
        course.setLearningCount(128);
        course.setPrice(new BigDecimal("299.00"));
        when(courseInfoMapper.selectOne(any())).thenReturn(course);

        PublicCourseDetailVO vo = courseService.findPublicCourseById(1L);

        assertEquals(1L, vo.id());
        assertEquals("Java 后端实战", vo.title());
        assertEquals("从零到一掌握 Spring Boot 企业级开发", vo.description());
        assertEquals("/api/v1/public/covers/0/abc.png", vo.coverImage());
        assertEquals("后端开发", vo.category());
        assertEquals("王老师", vo.instructorName());
        assertEquals("12 weeks", vo.duration());
        assertEquals(new BigDecimal("4.9"), vo.rating());
        assertEquals(128, vo.learningCount());
        assertEquals(new BigDecimal("299.00"), vo.price());
    }

    @Test
    @DisplayName("T1.3: findPublicCourseById → 课程不存在/非 ACTIVE 统一 404（不泄露存在性）")
    void findPublicCourseById_missingOrInactive_throws404() throws Exception {
        injectChainFields(courseService);
        when(courseInfoMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> courseService.findPublicCourseById(99L));

        assertEquals(404, ex.getCode());
        assertTrue(ex.getMessage().contains("课程不存在或已下架"));
    }

    @Test
    @DisplayName("findPage → 全条件筛选分页（返回 DTO 分页）")
    void findPage_withFilters() {
        Page<CourseInfo> page = new Page<>(1, 20);
        CourseInfo c = new CourseInfo();
        c.setId(1L);
        c.setTitle("Java 入门");
        page.setRecords(List.of(c));
        page.setTotal(1);
        when(courseInfoMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<CourseDTO> result = courseService.findPage(1, 20, "编程", "Java", 7L);

        assertEquals(1, result.getRecords().size());
        assertEquals(1L, result.getRecords().get(0).id());
        assertEquals("Java 入门", result.getRecords().get(0).title());
        assertEquals(1, result.getTotal());
    }

    @Test
    @DisplayName("updateCourse → 非空字段更新")
    void updateCourse_partialFields() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        UpdateCourseRequest request =
                new UpdateCourseRequest("新标题", null, null, null, null, null, "20h", null, null, "ACTIVE");

        courseService.updateCourse(1L, request, 7L, false);

        verify(courseInfoMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("updateCourse → 全字段更新：各字段分支均写入 SET 子句（enrollmentLink 除外）")
    void updateCourse_allFields_setAllBranches() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        UpdateCourseRequest request = new UpdateCourseRequest(
                "新标题",
                "新描述",
                "new-cover.png",
                "新分类",
                "李老师",
                new BigDecimal("199"),
                "30h",
                List.of("Java"),
                "http://ignored.example/link",
                "ACTIVE");

        courseService.updateCourse(1L, request, 7L, false);

        // 全字段（除 enrollmentLink）均进入 SET 子句
        verify(courseInfoMapper).update(isNull(), updateWrapperCaptor.capture());
        String sqlSet = updateWrapperCaptor.getValue().getSqlSet();
        assertNotNull(sqlSet);
        assertTrue(sqlSet.contains("title"), "title 应更新");
        assertTrue(sqlSet.contains("description"), "description 应更新");
        assertTrue(sqlSet.contains("cover_image"), "cover_image 应更新");
        assertTrue(sqlSet.contains("category"), "category 应更新");
        assertTrue(sqlSet.contains("instructor_name"), "instructor_name 应更新");
        assertTrue(sqlSet.contains("price"), "price 应更新");
        assertTrue(sqlSet.contains("duration"), "duration 应更新");
        assertTrue(sqlSet.contains("tags"), "tags 应更新");
        assertFalse(sqlSet.contains("enrollment_link"), "enrollment_link 不应出现在 SET 子句");
    }

    @Test
    @DisplayName("T1.1: updateCourse → 传入 enrollmentLink 一律不生效（服务端管理字段）")
    void updateCourse_ignoresEnrollmentLink() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        UpdateCourseRequest request = new UpdateCourseRequest(
                null, null, null, null, null, null, null, null, "http://evil.example/overwrite", null);

        courseService.updateCourse(1L, request, 7L, false);

        // 捕获 UPDATE wrapper：SET 子句不得包含 enrollment_link 列（契约 A.2.3 更新分支已删除）
        verify(courseInfoMapper).update(isNull(), updateWrapperCaptor.capture());
        String sqlSet = updateWrapperCaptor.getValue().getSqlSet();
        assertNotNull(sqlSet, "仅 updatedAt 刷新时 SET 子句应存在");
        assertFalse(sqlSet.contains("enrollment_link"), "SET 子句不应包含 enrollment_link（更新忽略报名链接）");
    }

    @Test
    @DisplayName("P1-9: addTeachers → 跳过已存在教师，新增集合单次批量插入（内容等价）")
    void addTeachers_skipsExisting_batchesInsert() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        CourseTeacher existing = new CourseTeacher();
        existing.setTeacherId(2L);
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of(existing));

        courseService.addTeachers(1L, List.of(2L, 3L, 4L), 7L, false);

        // P1-9: N 次单条 insert → 1 次 saveBatch 批插（仅含新教师 3/4，courseId 逐条带齐）
        verify(courseTeacherService).saveBatch(batchCaptor.capture());
        List<CourseTeacher> batched = batchCaptor.getValue();
        assertEquals(2, batched.size());
        assertEquals(
                List.of(3L, 4L),
                batched.stream().map(CourseTeacher::getTeacherId).collect(Collectors.toList()));
        batched.forEach(ct -> assertEquals(1L, ct.getCourseId()));
        // 逐条 insert 已被批插取代
        verify(courseTeacherMapper, never()).insert(any(CourseTeacher.class));
    }

    @Test
    @DisplayName("P1-9: addTeachers → 无新教师时不触发批插（幂等语义保持）")
    void addTeachers_allExisting_noBatchInsert() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        CourseTeacher existing = new CourseTeacher();
        existing.setTeacherId(2L);
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of(existing));

        courseService.addTeachers(1L, List.of(2L), 7L, false);

        // 查重后无新增集合 → 不调用 saveBatch
        verify(courseTeacherService, never()).saveBatch(anyList());
    }

    @Test
    @DisplayName("B2-8: addTeachers 并发批插撞 course_teacher 唯一索引 → 转 BizException 409 而非 503")
    void addTeachers_uniqueViolationOnBatchInsert_throwsConflict() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        // 竞态窗口：两请求查重均未见教师 3，后插入者撞 uniq_course_teacher(course_id, teacher_id)
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of());
        when(courseTeacherService.saveBatch(anyList()))
                .thenThrow(new DataIntegrityViolationException("uniq_course_teacher 冲突"));

        BizException ex = assertThrows(BizException.class, () -> courseService.addTeachers(1L, List.of(3L), 7L, false));

        // 语义应为 409（教师已在授课列表/重复操作请刷新），而非 DataAccessException 全局映射的 503
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("removeTeachers → 批量软删教师关联")
    void removeTeachers_softDeletes() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));

        courseService.removeTeachers(1L, List.of(2L, 3L), 7L, false);

        verify(courseTeacherMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("findTeacherIds → 返回教师 ID 列表")
    void findTeacherIds_returnsIds() {
        CourseTeacher t1 = new CourseTeacher();
        t1.setTeacherId(2L);
        CourseTeacher t2 = new CourseTeacher();
        t2.setTeacherId(3L);
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of(t1, t2));

        List<Long> result = courseService.findTeacherIds(1L);

        assertEquals(List.of(2L, 3L), result);
    }

    @Test
    @DisplayName("findContents → 按 sort_order 排序返回")
    void findContents_returnsSorted() {
        when(courseContentMapper.selectList(any())).thenReturn(List.of(new CourseContent()));

        assertEquals(1, courseService.findContents(1L).size());
    }

    @Test
    @DisplayName("updateContent → 已存在内容时更新")
    void updateContent_existing_updates() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        CourseContent existing = new CourseContent();
        existing.setId(10L);
        when(courseContentMapper.selectOne(any())).thenReturn(existing);

        courseService.updateContent(1L, "overview", "新内容", 7L, false);

        verify(courseContentMapper).update(isNull(), any());
        verify(courseQueryService).evictCourse(1L);
    }

    @Test
    @DisplayName("updateContent → 不存在时创建（默认 sort_order）")
    void updateContent_missing_creates() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        when(courseContentMapper.selectOne(any())).thenReturn(null);

        courseService.updateContent(1L, "faq", "常见问题", 7L, false);

        verify(courseContentMapper).insert(any(CourseContent.class));
        verify(courseQueryService).evictCourse(1L);
    }

    @Test
    @DisplayName("B2-8: updateContent 并发首插撞 course_content 唯一索引 → 转 BizException 409 而非 503")
    void updateContent_uniqueViolationOnInsert_throwsConflict() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        // 并发双击竞态窗口：两请求 selectOne 均得 null，后插入者撞 uniq_course_content_type
        when(courseContentMapper.selectOne(any())).thenReturn(null);
        when(courseContentMapper.insert(any(CourseContent.class)))
                .thenThrow(new DataIntegrityViolationException("uniq_course_content_type 冲突"));

        BizException ex =
                assertThrows(BizException.class, () -> courseService.updateContent(1L, "faq", "内容", 7L, false));

        // 语义应为 409（已存在/重复操作），而非 DataAccessException 全局映射的 503
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("batchUpdateContents → 逐 Tab 更新并兜底失效缓存")
    void batchUpdateContents_updatesAll() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        when(courseContentMapper.selectOne(any())).thenReturn(null);
        List<CourseDTO.CourseContentDTO> contents = List.of(
                new CourseDTO.CourseContentDTO("overview", "简介", 1),
                new CourseDTO.CourseContentDTO("syllabus", "大纲", 2));

        courseService.batchUpdateContents(1L, contents, 7L, false);

        verify(courseContentMapper, times(2)).insert(any(CourseContent.class));
        verify(courseQueryService, times(3)).evictCourse(1L);
    }

    @Test
    @DisplayName("findSchedules → 返回排期列表")
    void findSchedules_returnsList() {
        when(courseScheduleMapper.selectList(any())).thenReturn(List.of(new CourseSchedule()));

        assertEquals(1, courseService.findSchedules(1L).size());
    }

    @Test
    @DisplayName("toDTO → 不含关联数据时传空列表")
    void toDTO_withoutRelations() {
        CourseInfo course = course(1L, 7L);
        CourseDTO dto = courseService.toDTO(course, false);

        assertNotNull(dto);
        assertEquals(1L, dto.id());
    }

    @Test
    @DisplayName("toDTO → 含关联数据时聚合内容/排期/教师")
    void toDTO_withRelations() {
        CourseInfo course = course(1L, 7L);
        when(courseContentMapper.selectList(any())).thenReturn(List.of(new CourseContent()));
        when(courseScheduleMapper.selectList(any())).thenReturn(List.of(new CourseSchedule()));
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of());

        CourseDTO dto = courseService.toDTO(course, true);

        assertNotNull(dto);
        verify(courseContentMapper).selectList(any());
        verify(courseScheduleMapper).selectList(any());
        verify(courseTeacherMapper).selectList(any());
    }

    @Test
    @DisplayName("checkOwnership → 课程不存在抛 404")
    void checkOwnership_notFound_throws404() {
        when(courseInfoMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> courseService.checkOwnership(99L, 7L, false));

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("checkOwnership → 教师非创建者抛 403，创建者放行")
    void checkOwnership_teacherOwnership() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));

        BizException ex = assertThrows(BizException.class, () -> courseService.checkOwnership(1L, 8L, false));
        assertEquals(403, ex.getCode());

        assertDoesNotThrow(() -> courseService.checkOwnership(1L, 7L, false));
    }

    /** 构造测试用课程实体 */
    private CourseInfo course(Long id, Long createdBy) {
        CourseInfo course = new CourseInfo();
        course.setId(id);
        course.setCreatedBy(createdBy);
        return course;
    }

    // ==================== B2-5 级联软删事务原子性 ====================

    /** Spring 事务元数据解析器 —— 与生产事务切面同一解析路径，验证注解会被识别且异常触发回滚 */
    private static final AnnotationTransactionAttributeSource TX_SOURCE = new AnnotationTransactionAttributeSource();

    @Test
    @DisplayName("B2-5: deleteCourse 标注 @Transactional 且运行时异常触发回滚")
    void deleteCourse_isTransactional_rollsBackOnRuntimeFailure() throws NoSuchMethodException {
        Method method = CourseServiceImpl.class.getMethod("deleteCourse", Long.class, Long.class, boolean.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, CourseServiceImpl.class);

        // 注解存在（事务切面可识别）且 RuntimeException 触发回滚（默认回滚规则）
        assertNotNull(attr, "deleteCourse 应标注 @Transactional（B2-5：六连 UPDATE 原子性）");
        assertTrue(attr.rollbackOn(new RuntimeException("级联软删中途失败")));
    }

    @Test
    @DisplayName("P1-9: addTeachers 标注 @Transactional（saveBatch 批处理须在事务内整体原子）")
    void addTeachers_isTransactional_forSaveBatch() throws NoSuchMethodException {
        Method method =
                CourseServiceImpl.class.getMethod("addTeachers", Long.class, List.class, Long.class, boolean.class);
        TransactionAttribute attr = TX_SOURCE.getTransactionAttribute(method, CourseServiceImpl.class);

        // 宪法约束：saveBatch（JDBC 批处理）须在事务内调用，保证批量插入整体原子性
        assertNotNull(attr, "addTeachers 应标注 @Transactional（saveBatch 事务性批处理）");
        assertTrue(attr.rollbackOn(new RuntimeException("批插中途失败")));
    }
}
