package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.controller.dto.CourseDTO;
import com.commerce.rag.controller.dto.CreateCourseRequest;
import com.commerce.rag.controller.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * CourseService 权限单元测试 —— 课程详情归属校验（P0-2g）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CourseService 课程归属测试")
class CourseServiceTest {

    @Mock
    private CourseInfoMapper courseInfoMapper;

    @Mock
    private CourseContentMapper courseContentMapper;

    @Mock
    private CourseScheduleMapper courseScheduleMapper;

    @Mock
    private CourseTeacherMapper courseTeacherMapper;

    @Mock
    private CourseEnrollmentMapper courseEnrollmentMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private EtlPipeline etlPipeline;

    @Mock
    private CourseQueryService courseQueryService;

    private CourseService courseService;

    @BeforeAll
    static void initMybatisPlus() {
        // 纯 Mockito 单元测试（无 Spring 上下文）需先初始化 LambdaUpdateWrapper 的 TableInfo 缓存
        MybatisPlusTestHelper.initTableInfo();
    }

    @BeforeEach
    void setUp() {
        // 构造器注入（@RequiredArgsConstructor 按字段声明顺序生成全参构造器）
        courseService = new CourseService(
                courseInfoMapper,
                courseContentMapper,
                courseScheduleMapper,
                courseTeacherMapper,
                courseEnrollmentMapper,
                documentChunkMapper,
                etlPipeline,
                new CourseConverterImpl(),
                courseQueryService);
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
    @DisplayName("findById 过滤重载 → 创建者可查看 + 超管（filter=null）可查看任意课程")
    void findById_ownerAndAdmin_canView() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        assertNotNull(courseService.findById(1L, 100L));
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

    @Test
    @DisplayName("createCourse → 组装默认字段并插入，失效搜索缓存")
    void createCourse_buildsAndInserts() {
        CreateCourseRequest request = new CreateCourseRequest(
                "Java 入门", "描述", "cover.png", "编程", "张老师",
                new BigDecimal("99"), "10h", List.of("Java", "入门"), "link");

        CourseInfo result = courseService.createCourse(request, 7L);

        verify(courseInfoMapper).insert(result);
        verify(courseQueryService).evictCourse(result.getId());
        assertEquals("ACTIVE", result.getStatus());
        assertEquals(new BigDecimal("0"), result.getRating());
        assertEquals(0, result.getLearningCount());
        assertEquals("[\"Java\",\"入门\"]", result.getTags());
    }

    @Test
    @DisplayName("createCourse → tags 为空时序列化为 []")
    void createCourse_emptyTags_serializesEmptyArray() {
        CreateCourseRequest request = new CreateCourseRequest(
                "Java", null, null, null, null, null, null, null, null);

        CourseInfo result = courseService.createCourse(request, 7L);

        assertEquals("[]", result.getTags());
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

    @Test
    @DisplayName("findPage → 全条件筛选分页")
    void findPage_withFilters() {
        Page<CourseInfo> page = new Page<>(1, 20);
        when(courseInfoMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<CourseInfo> result = courseService.findPage(1, 20, "编程", "Java", 7L);

        assertSame(page, result);
    }

    @Test
    @DisplayName("updateCourse → 非空字段更新")
    void updateCourse_partialFields() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        UpdateCourseRequest request = new UpdateCourseRequest(
                "新标题", null, null, null, null, null, "20h", null, null, "ACTIVE");

        courseService.updateCourse(1L, request, 7L, false);

        verify(courseInfoMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("addTeachers → 跳过已存在教师，插入新教师")
    void addTeachers_skipsExisting() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));
        CourseTeacher existing = new CourseTeacher();
        existing.setTeacherId(2L);
        when(courseTeacherMapper.selectList(any())).thenReturn(List.of(existing));

        courseService.addTeachers(1L, List.of(2L, 3L), 7L, false);

        // 仅插入新教师 3（2 已存在）——captor 精确断言插入对象
        ArgumentCaptor<CourseTeacher> captor = ArgumentCaptor.forClass(CourseTeacher.class);
        verify(courseTeacherMapper, times(1)).insert(captor.capture());
        assertEquals(3L, captor.getValue().getTeacherId());
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

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> courseService.checkOwnership(99L, 7L, false));

        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("checkOwnership → 教师非创建者抛 403，创建者放行")
    void checkOwnership_teacherOwnership() {
        when(courseInfoMapper.selectById(1L)).thenReturn(course(1L, 7L));

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> courseService.checkOwnership(1L, 8L, false));
        assertEquals(403, ex.getStatusCode().value());

        assertDoesNotThrow(() -> courseService.checkOwnership(1L, 7L, false));
    }

    /** 构造测试用课程实体 */
    private CourseInfo course(Long id, Long createdBy) {
        CourseInfo course = new CourseInfo();
        course.setId(id);
        course.setCreatedBy(createdBy);
        return course;
    }
}
