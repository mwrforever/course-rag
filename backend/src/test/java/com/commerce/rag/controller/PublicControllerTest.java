package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.vo.PublicCourseVO;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PublicController 单元测试 —— C 端公开端点（无需登录）
 *
 * <p>覆盖：公开课程列表正常返回（无鉴权先例在 AuthConfig/AuthInterceptor 集成层，
 * 本类仅验证 controller 转发与 VO 透传）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicController 公开端点测试")
class PublicControllerTest {

    @Mock
    private ICourseService courseService;

    private PublicController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicController(courseService);
    }

    /** 构造公开课程 VO（与 service 出参同构） */
    private PublicCourseVO courseVO(Long id, String title) {
        return new PublicCourseVO(id, title, "简介-" + id, "cover.png", "编程", "张老师", "10h", new BigDecimal("4.5"), 100);
    }

    @Test
    @DisplayName("公开课程列表 → 转发 service 并返回 VO 列表")
    void courses_returnsPublicList() {
        when(courseService.findPublicCourses()).thenReturn(List.of(courseVO(1L, "Java 入门"), courseVO(2L, "Spring")));

        ApiResponse<List<PublicCourseVO>> result = controller.courses();

        assertEquals(0, result.code());
        assertEquals(2, result.data().size());
        PublicCourseVO first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("Java 入门", first.title());
        assertEquals("简介-1", first.description());
        assertEquals("cover.png", first.coverImage());
        assertEquals("编程", first.category());
        assertEquals("张老师", first.instructorName());
        assertEquals("10h", first.duration());
        assertEquals(new BigDecimal("4.5"), first.rating());
        assertEquals(100, first.learningCount());
        verify(courseService).findPublicCourses();
    }

    @Test
    @DisplayName("公开课程列表 → 无课程时返回空列表")
    void courses_noCourses_returnsEmpty() {
        when(courseService.findPublicCourses()).thenReturn(List.of());

        ApiResponse<List<PublicCourseVO>> result = controller.courses();

        assertTrue(result.data().isEmpty());
    }
}
