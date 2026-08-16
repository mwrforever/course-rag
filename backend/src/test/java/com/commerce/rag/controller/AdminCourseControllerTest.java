package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.*;
import com.commerce.rag.convert.CourseConverter;
import com.commerce.rag.dto.*;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.service.ICourseService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * AdminCourseController 单元测试 —— B 端课程管理端点 E1-E9
 *
 * <p>覆盖：分页列表（超管/教师归属过滤）、创建、详情 404、更新/删除/教师维护、
 * 内容 Tab 查询与更新。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCourseController 课程管理端点测试")
class AdminCourseControllerTest {

    @Mock
    private ICourseService courseService;

    @Mock
    private CourseConverter courseConverter;

    private AdminCourseController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminCourseController(courseService, courseConverter);
    }

    private HttpServletRequest request(String role, Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    private CourseInfo course(Long id) {
        CourseInfo c = new CourseInfo();
        c.setId(id);
        c.setTitle("课程" + id);
        c.setCategory("编程");
        c.setCreatedBy(1L);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    private CourseDTO courseDTO(Long id) {
        return new CourseDTO(
                id, "课程" + id, null, null, "编程", null, null, null, null, null, null, null, "ACTIVE", 1L, null, null,
                null, null);
    }

    @Test
    @DisplayName("E1 list → 超管不传 createdBy 过滤，返回全量分页")
    void list_superAdmin_passesNullCreatedBy() {
        Page<CourseInfo> paged = new Page<>(1, 20);
        paged.setRecords(List.of(course(1L)));
        paged.setTotal(1);
        when(courseService.findPage(1, 20, "编程", null, null)).thenReturn(paged);
        when(courseService.toDTO(any(CourseInfo.class), eq(false))).thenReturn(courseDTO(1L));

        ApiResponse<PageResponse<CourseDTO>> result = controller.list(request("SUPER_ADMIN", 1L), 1, 20, "编程", null);

        assertEquals(1, result.data().records().size());
        assertEquals(1L, result.data().records().get(0).id());
        verify(courseService).findPage(1, 20, "编程", null, null);
    }

    @Test
    @DisplayName("E1 list → 教师按 createdBy=自己过滤")
    void list_teacher_filtersByOwnId() {
        Page<CourseInfo> paged = new Page<>(1, 20);
        paged.setRecords(List.of());
        when(courseService.findPage(1, 20, null, "Java", 7L)).thenReturn(paged);

        controller.list(request("TEACHER", 7L), 1, 20, null, "Java");

        verify(courseService).findPage(1, 20, null, "Java", 7L);
    }

    @Test
    @DisplayName("E2 create → 调用 createCourse 并返回 DTO")
    void create_callsCreateCourse() {
        CreateCourseRequest createRequest = new CreateCourseRequest(
                "新课程", "描述", null, "编程", "张老师", new BigDecimal("99"), "10h", List.of("Java"), null);
        // create 端点仅读取 userId（不读角色），故只 stub userId
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(courseService.createCourse(createRequest, 1L)).thenReturn(course(3L));
        when(courseService.toDTO(any(CourseInfo.class), eq(false))).thenReturn(courseDTO(3L));

        ApiResponse<CourseDTO> result = controller.create(req, createRequest);

        assertEquals(3L, result.data().id());
        verify(courseService).createCourse(createRequest, 1L);
    }

    @Test
    @DisplayName("E3 detail → 超管不过滤，返回含关系的 DTO")
    void detail_superAdmin_noFilter() {
        when(courseService.findById(1L, null)).thenReturn(course(1L));
        when(courseService.toDTO(any(CourseInfo.class), eq(true))).thenReturn(courseDTO(1L));

        ApiResponse<CourseDTO> result = controller.detail(request("SUPER_ADMIN", 1L), 1L);

        assertEquals(1L, result.data().id());
        verify(courseService).findById(1L, null);
    }

    @Test
    @DisplayName("E3 detail → 教师按归属过滤，查不到抛 404")
    void detail_teacherNotFound_throws404() {
        when(courseService.findById(99L, 7L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.detail(request("TEACHER", 7L), 99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertEquals("课程不存在", ex.getMessage());
    }

    @Test
    @DisplayName("E4 update → 超管透传 isAdmin=true")
    void update_superAdmin_passesAdminFlag() {
        UpdateCourseRequest updateRequest =
                new UpdateCourseRequest("新标题", null, null, null, null, null, null, null, null, "ACTIVE");

        controller.update(request("SUPER_ADMIN", 1L), 1L, updateRequest);

        verify(courseService).updateCourse(eq(1L), eq(updateRequest), eq(1L), eq(true));
    }

    @Test
    @DisplayName("E4 update → 教师透传 isAdmin=false（归属校验由 service 执行）")
    void update_teacher_passesAdminFlagFalse() {
        UpdateCourseRequest updateRequest =
                new UpdateCourseRequest("新标题", null, null, null, null, null, null, null, null, "ACTIVE");

        controller.update(request("TEACHER", 7L), 1L, updateRequest);

        verify(courseService).updateCourse(eq(1L), eq(updateRequest), eq(7L), eq(false));
    }

    @Test
    @DisplayName("E5 delete → 调用 deleteCourse")
    void delete_callsDeleteCourse() {
        controller.delete(request("TEACHER", 7L), 1L);

        verify(courseService).deleteCourse(1L, 7L, false);
    }

    @Test
    @DisplayName("E6 addTeachers/removeTeachers → 透传教师 ID 列表")
    void teacherOperations_passThroughTeacherIds() {
        List<Long> teacherIds = List.of(2L, 3L);

        controller.addTeachers(request("SUPER_ADMIN", 1L), 1L, teacherIds);
        controller.removeTeachers(request("SUPER_ADMIN", 1L), 1L, teacherIds);

        verify(courseService).addTeachers(1L, teacherIds, 1L, true);
        verify(courseService).removeTeachers(1L, teacherIds, 1L, true);
    }

    @Test
    @DisplayName("E7 contents → 归属校验后返回内容 Tab DTO 列表")
    void contents_returnsContentDTOs() {
        CourseContent content = new CourseContent();
        content.setContentType("overview");
        content.setContent("简介");
        content.setSortOrder(1);
        when(courseService.checkOwnership(1L, 7L, false)).thenReturn(course(1L));
        when(courseService.findContents(1L)).thenReturn(List.of(content));
        when(courseConverter.toContentDTO(content)).thenReturn(new CourseDTO.CourseContentDTO("overview", "简介", 1));

        ApiResponse<List<CourseDTO.CourseContentDTO>> result = controller.contents(request("TEACHER", 7L), 1L);

        assertEquals(1, result.data().size());
        assertEquals("overview", result.data().get(0).contentType());
    }

    @Test
    @DisplayName("E8 updateContent → 透传 contentType 与内容")
    void updateContent_passesThrough() {
        controller.updateContent(request("SUPER_ADMIN", 1L), 1L, "overview", "新简介");

        verify(courseService).updateContent(1L, "overview", "新简介", 1L, true);
    }

    @Test
    @DisplayName("E9 batchUpdateContents → 透传全部 Tab 内容列表")
    void batchUpdateContents_passesThrough() {
        List<CourseDTO.CourseContentDTO> contents = List.of(
                new CourseDTO.CourseContentDTO("overview", "简介", 1),
                new CourseDTO.CourseContentDTO("syllabus", "大纲", 2));

        controller.batchUpdateContents(request("TEACHER", 7L), 1L, contents);

        verify(courseService).batchUpdateContents(1L, contents, 7L, false);
    }
}
