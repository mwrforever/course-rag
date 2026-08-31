package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.ICourseCoverService;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import com.commerce.rag.vo.PublicScheduleVO;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * PublicController 单元测试 —— C 端公开端点（无需登录）
 *
 * <p>覆盖：公开课程列表透传（含价格）、公开课程详情透传与 404 透出、
 * 封面公开访问（白名单校验下沉 service，controller 仅组响应头：Content-Type + 公开缓存）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicController 公开端点测试")
class PublicControllerTest {

    @Mock
    private ICourseService courseService;

    @Mock
    private ICourseCoverService courseCoverService;

    private PublicController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicController(courseService, courseCoverService);
    }

    /** 构造公开课程 VO（与 service 出参同构，含公开价格字段） */
    private PublicCourseVO courseVO(Long id, String title) {
        return new PublicCourseVO(
                id,
                title,
                "简介-" + id,
                "cover.png",
                "编程",
                "张老师",
                "10h",
                new BigDecimal("4.5"),
                100,
                new BigDecimal("299.00"));
    }

    @Test
    @DisplayName("公开课程列表 → 转发 service 并返回 VO 列表（含价格）")
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
        assertEquals(new BigDecimal("299.00"), first.price());
        verify(courseService).findPublicCourses();
    }

    @Test
    @DisplayName("公开课程列表 → 无课程时返回空列表")
    void courses_noCourses_returnsEmpty() {
        when(courseService.findPublicCourses()).thenReturn(List.of());

        ApiResponse<List<PublicCourseVO>> result = controller.courses();

        assertTrue(result.data().isEmpty());
    }

    @Test
    @DisplayName("公开课程详情 → 转发 service 并返回详情 VO（含价格与排期列表）")
    void courseDetail_returnsDetailVO() {
        PublicCourseDetailVO detail = new PublicCourseDetailVO(
                1L,
                "Java 后端实战",
                "描述",
                "/api/v1/public/covers/0/a.png",
                "后端开发",
                "王老师",
                "12 weeks",
                new BigDecimal("4.9"),
                128,
                new BigDecimal("299.00"),
                List.of(new PublicScheduleVO(
                        11L,
                        LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 12, 20),
                        "ONLINE",
                        "线上直播",
                        "UPCOMING",
                        200,
                        35)));
        when(courseService.findPublicCourseById(1L)).thenReturn(detail);

        ApiResponse<PublicCourseDetailVO> result = controller.courseDetail(1L);

        assertEquals(0, result.code());
        assertEquals(1L, result.data().id());
        assertEquals("Java 后端实战", result.data().title());
        assertEquals("王老师", result.data().instructorName());
        assertEquals(new BigDecimal("299.00"), result.data().price());
        // 排期列表随详情 VO 透传（开课日期等对外信息）
        assertEquals(1, result.data().schedules().size());
        assertEquals(LocalDate.of(2026, 9, 1), result.data().schedules().get(0).startDate());
        assertEquals("线上直播", result.data().schedules().get(0).location());
        verify(courseService).findPublicCourseById(1L);
    }

    @Test
    @DisplayName("公开课程详情 → 课程不存在时 404 业务异常透出（GlobalExceptionHandler 统一包装）")
    void courseDetail_missing_throws404() {
        when(courseService.findPublicCourseById(99L)).thenThrow(new BizException(ErrorCode.NOT_FOUND, "课程不存在或已下架"));

        BizException ex = assertThrows(BizException.class, () -> controller.courseDetail(99L));

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("封面公开访问 → 返回图片流并携带 Content-Type 与公开缓存头（max-age=86400）")
    void cover_streamsImageWithPublicCacheHeaders() {
        // {*objectKey} 捕获值带前导斜杠（剥离与白名单校验下沉 service）
        when(courseCoverService.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png"))
                .thenReturn(new ICourseCoverService.CoverContent(
                        new ByteArrayInputStream(new byte[] {1, 2, 3}), MediaType.IMAGE_PNG));

        var response = controller.cover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        String cacheControl = response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL);
        assertNotNull(cacheControl, "封面响应应携带 Cache-Control 公开缓存头");
        assertTrue(cacheControl.contains("max-age=86400"), "缓存时长应为 1 天（86400 秒）");
        assertTrue(cacheControl.contains("public"), "封面内容不可变，允许公共缓存");
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    @DisplayName("封面公开访问 → 白名单不匹配的键 404 业务异常透出（service 层硬校验）")
    void cover_illegalKey_throws404() {
        when(courseCoverService.downloadCover("/0/../evil.png"))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "封面不存在"));

        BizException ex = assertThrows(BizException.class, () -> controller.cover("/0/../evil.png"));

        assertEquals(404, ex.getCode());
    }
}
