package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
                new CourseConverterImpl());
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
}
