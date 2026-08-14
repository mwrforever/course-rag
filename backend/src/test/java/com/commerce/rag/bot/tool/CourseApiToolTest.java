package com.commerce.rag.bot.tool;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.bot.tool.dto.CourseDetailResult;
import com.commerce.rag.bot.tool.dto.CourseListResult;
import com.commerce.rag.bot.tool.dto.CourseListResult.CourseSummary;
import com.commerce.rag.bot.tool.dto.EnrollmentResult;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.service.CourseQueryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CourseApiTool 单元测试 —— Mock CourseQueryService，验证 DTO 映射逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class CourseApiToolTest {

    @Mock
    private CourseQueryService courseQueryService;

    @InjectMocks
    private CourseApiTool tool;

    @Test
    @DisplayName("listCourses 关键词搜索 — 返回分页结果")
    void listCourses_withKeyword_returnsPagedResult() {
        // Given
        CourseInfo info = mockCourseInfo();
        Page<CourseInfo> page = new Page<>(1, 10);
        page.setRecords(List.of(info));
        page.setTotal(1);
        when(courseQueryService.searchCourses("Java", 1)).thenReturn(page);

        // When
        CourseListResult result = tool.listCourses("Java", 1);

        // Then
        assertEquals(1, result.page());
        assertEquals(10, result.pageSize());
        assertEquals(1, result.total());
        assertEquals(1, result.courses().size());

        CourseSummary summary = result.courses().get(0);
        assertEquals("123", summary.courseId());
        assertEquals("Java入门到精通", summary.title());
        assertEquals("后端", summary.category());
        assertEquals("ACTIVE", summary.status());
        assertNotNull(summary.tags());
        assertEquals(2, summary.tags().size());
    }

    @Test
    @DisplayName("listCourses 无结果 — 返回空列表")
    void listCourses_noResults_returnsEmptyList() {
        Page<CourseInfo> emptyPage = new Page<>(1, 10);
        emptyPage.setRecords(Collections.emptyList());
        emptyPage.setTotal(0);
        when(courseQueryService.searchCourses("NotFound", 1)).thenReturn(emptyPage);

        CourseListResult result = tool.listCourses("NotFound", 1);

        assertEquals(0, result.total());
        assertTrue(result.courses().isEmpty());
    }

    @Test
    @DisplayName("queryCourseDetail — 4 Tab 内容与 DTO 字段一一对应（intro/syllabus/instructor/faq）")
    void queryCourseDetail_aggregatesAllData() {
        // Given: 4 个 Tab fixture（db-schema 权威枚举：intro / syllabus / instructor / faq）
        CourseInfo info = mockCourseInfo();
        CourseContent introContent = mockContent("intro", "本课程涵盖Java核心知识");
        CourseContent syllabusContent = mockContent("syllabus", "第一章 Java基础\n第二章 面向对象");
        CourseContent instructorTab = mockContent("instructor", "张老师：10年大型系统架构经验");
        CourseContent faqTab = mockContent("faq", "Q1：需要什么基础？\nA1：无基础要求");
        CourseSchedule schedule = mockSchedule();

        when(courseQueryService.findCourseById("123")).thenReturn(info);
        when(courseQueryService.findContentsByCourseId("123"))
                .thenReturn(List.of(introContent, syllabusContent, instructorTab, faqTab));
        when(courseQueryService.findNextSchedule("123")).thenReturn(schedule);

        // When
        CourseDetailResult result = tool.queryCourseDetail("123");

        // Then: 每个 DTO 字段内容 = 对应 Tab 内容（不再有字段错位）
        assertNotNull(result);
        assertEquals("123", result.summary().courseId());
        assertEquals("Java入门到精通", result.summary().title());
        assertEquals("本课程涵盖Java核心知识", result.introContent());
        assertEquals("第一章 Java基础\n第二章 面向对象", result.syllabusContent());
        assertEquals("张老师：10年大型系统架构经验", result.instructorContent());
        assertEquals("Q1：需要什么基础？\nA1：无基础要求", result.faqContent());
        assertEquals("12周", result.schedule().duration());
        assertNotNull(result.enrollmentUrl());
        assertEquals("https://enroll.example.com/course/123", result.enrollmentUrl());
        assertEquals("张老师", result.instructor().name());
        assertNotNull(result.schedule().nextStartDate());
    }

    @Test
    @DisplayName("queryCourseDetail 课程不存在 — 返回空详情")
    void queryCourseDetail_notFound_returnsEmptyDetail() {
        when(courseQueryService.findCourseById("999")).thenReturn(null);

        CourseDetailResult result = tool.queryCourseDetail("999");

        assertNotNull(result);
        assertEquals("999", result.summary().courseId());
        assertTrue(result.introContent().isEmpty());
        assertTrue(result.syllabusContent().isEmpty());
        assertTrue(result.instructorContent().isEmpty());
        assertTrue(result.faqContent().isEmpty());
    }

    @Test
    @DisplayName("queryEnrollment — 返回价格 + 报名链接 + 排期")
    void queryEnrollment_returnsEnrollmentInfo() {
        CourseInfo info = mockCourseInfo();
        CourseSchedule schedule = mockSchedule();

        when(courseQueryService.findCourseById("123")).thenReturn(info);
        when(courseQueryService.findNextSchedule("123")).thenReturn(schedule);

        EnrollmentResult result = tool.queryEnrollment("123");

        assertNotNull(result);
        assertNotNull(result.price());
        assertTrue(result.price().contains("1999"));
        assertEquals("https://enroll.example.com/course/123", result.enrollmentUrl());
        assertNotNull(result.nextSchedule());
        assertNotNull(result.nextSchedule().nextStartDate());
    }

    @Test
    @DisplayName("queryEnrollment 课程不存在 — 返回空报名信息")
    void queryEnrollment_notFound_returnsEmptyResult() {
        when(courseQueryService.findCourseById("999")).thenReturn(null);

        EnrollmentResult result = tool.queryEnrollment("999");

        assertNotNull(result);
        assertTrue(result.price().isEmpty());
        assertTrue(result.enrollmentUrl().isEmpty());
    }

    // ==================== 辅助方法 ====================

    private CourseInfo mockCourseInfo() {
        CourseInfo info = new CourseInfo();
        info.setId(123L);
        info.setTitle("Java入门到精通");
        info.setDescription("Java核心知识");
        info.setCategory("后端");
        info.setInstructorName("张老师");
        info.setPrice(new BigDecimal("1999.00"));
        info.setDuration("12周");
        info.setTags("[\"Java\",\"后端\"]");
        info.setStatus("ACTIVE");
        info.setEnrollmentLink("https://enroll.example.com/course/123");
        return info;
    }

    private CourseContent mockContent(String contentType, String content) {
        CourseContent c = new CourseContent();
        c.setId(1L);
        c.setCourseId(123L);
        c.setContentType(contentType);
        c.setContent(content);
        c.setSortOrder(1);
        return c;
    }

    private CourseSchedule mockSchedule() {
        CourseSchedule s = new CourseSchedule();
        s.setId(1L);
        s.setCourseId(123L);
        s.setStartDate(LocalDate.of(2026, 8, 1));
        s.setEndDate(LocalDate.of(2026, 10, 31));
        s.setScheduleType("ONLINE");
        s.setLocation("线上直播");
        s.setInstructorName("张老师");
        s.setCapacity(50);
        s.setEnrolled(30);
        s.setStatus("UPCOMING");
        return s;
    }
}
