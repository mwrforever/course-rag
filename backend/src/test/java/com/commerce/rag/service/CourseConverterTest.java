package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.ScheduleDTO;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CourseConverter 转换器测试 —— 课程实体 + 关联数据 → CourseDTO 字段映射正确性 */
@DisplayName("CourseConverter 转换器测试")
class CourseConverterTest {

    private final CourseConverter converter = new CourseConverterImpl();

    @Test
    @DisplayName("课程实体 + 关联数据完整映射（16 字段 + 嵌套 contents/schedules 列表）")
    void toDTO_mapsAllFields() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setTitle("Java 入门");
        course.setDescription("Java 基础课程");
        course.setCoverImage("https://example.com/cover.jpg");
        course.setCategory("编程");
        course.setInstructorName("张老师");
        course.setPrice(new BigDecimal("99.00"));
        course.setDuration("12 weeks");
        course.setTags("[\"Java\",\"后端\"]");
        course.setRating(new BigDecimal("4.8"));
        course.setLearningCount(120);
        course.setEnrollmentLink("https://example.com/enroll");
        course.setStatus("ACTIVE");
        course.setCreatedBy(100L);
        course.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));

        CourseContent intro = new CourseContent();
        intro.setContentType("intro");
        intro.setContent("课程简介");
        intro.setSortOrder(0);
        CourseContent syllabus = new CourseContent();
        syllabus.setContentType("syllabus");
        syllabus.setContent("课程大纲");
        syllabus.setSortOrder(1);

        CourseSchedule schedule = new CourseSchedule();
        schedule.setId(10L);
        schedule.setCourseId(1L);
        schedule.setStartDate(LocalDate.of(2026, 9, 1));
        schedule.setEndDate(LocalDate.of(2026, 11, 30));
        schedule.setScheduleType("ONLINE");
        schedule.setLocation("线上");
        schedule.setInstructorName("张老师");
        schedule.setCapacity(50);
        schedule.setEnrolled(20);
        schedule.setStatus("UPCOMING");
        schedule.setCreatedBy(100L);

        CourseDTO dto = converter.toDTO(course, List.of(intro, syllabus), List.of(schedule), List.of(200L, 300L));

        // 16 个标量/直传字段（contents/schedules 嵌套列表单独断言）
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.title()).isEqualTo("Java 入门");
        assertThat(dto.description()).isEqualTo("Java 基础课程");
        assertThat(dto.coverImage()).isEqualTo("https://example.com/cover.jpg");
        assertThat(dto.category()).isEqualTo("编程");
        assertThat(dto.instructorName()).isEqualTo("张老师");
        assertThat(dto.price()).isEqualByComparingTo(new BigDecimal("99.00"));
        assertThat(dto.duration()).isEqualTo("12 weeks");
        // tags 为 JSON 字符串 → 列表（经转换器默认方法 parseTags 解析）
        assertThat(dto.tags()).containsExactly("Java", "后端");
        assertThat(dto.rating()).isEqualByComparingTo(new BigDecimal("4.8"));
        assertThat(dto.learningCount()).isEqualTo(120);
        assertThat(dto.enrollmentLink()).isEqualTo("https://example.com/enroll");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.createdBy()).isEqualTo(100L);
        assertThat(dto.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
        assertThat(dto.teacherIds()).containsExactly(200L, 300L);

        // 嵌套 contents 列表：长度 + 字段
        assertThat(dto.contents()).hasSize(2);
        CourseDTO.CourseContentDTO first = dto.contents().get(0);
        assertThat(first.contentType()).isEqualTo("intro");
        assertThat(first.content()).isEqualTo("课程简介");
        assertThat(first.sortOrder()).isEqualTo(0);
        CourseDTO.CourseContentDTO second = dto.contents().get(1);
        assertThat(second.contentType()).isEqualTo("syllabus");
        assertThat(second.content()).isEqualTo("课程大纲");
        assertThat(second.sortOrder()).isEqualTo(1);

        // 嵌套 schedules 列表：长度 + 字段
        assertThat(dto.schedules()).hasSize(1);
        ScheduleDTO s = dto.schedules().get(0);
        assertThat(s.id()).isEqualTo(10L);
        assertThat(s.courseId()).isEqualTo(1L);
        assertThat(s.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(s.endDate()).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(s.scheduleType()).isEqualTo("ONLINE");
        assertThat(s.location()).isEqualTo("线上");
        assertThat(s.instructorName()).isEqualTo("张老师");
        assertThat(s.capacity()).isEqualTo(50);
        assertThat(s.enrolled()).isEqualTo(20);
        assertThat(s.status()).isEqualTo("UPCOMING");
        assertThat(s.createdBy()).isEqualTo(100L);
    }

    @Test
    @DisplayName("includeRelations=false 传空列表 → 关联字段为空列表，标量字段不受影响")
    void toDTO_emptyRelations() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setTitle("Spring 实战");
        course.setTags("[]");

        CourseDTO dto = converter.toDTO(course, List.of(), List.of(), List.of());

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.title()).isEqualTo("Spring 实战");
        assertThat(dto.tags()).isEmpty();
        assertThat(dto.contents()).isEmpty();
        assertThat(dto.schedules()).isEmpty();
        assertThat(dto.teacherIds()).isEmpty();
    }

    @Test
    @DisplayName("tags 为空/非法 JSON → 空列表兜底（不阻断转换）")
    void toDTO_invalidTags_returnsEmptyList() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setTags("not-a-json");

        CourseDTO dto = converter.toDTO(course, List.of(), List.of(), List.of());

        assertThat(dto.tags()).isEmpty();
    }

    @Test
    @DisplayName("parseTags null/空白字符串 → 空列表兜底")
    void parseTags_nullOrBlank_returnsEmpty() {
        assertThat(converter.parseTags(null)).isEmpty();
        assertThat(converter.parseTags("")).isEmpty();
        assertThat(converter.parseTags("   ")).isEmpty();
    }

    @Test
    @DisplayName("内容实体完整映射到内容 Tab DTO")
    void toContentDTO_mapsAllFields() {
        CourseContent content = new CourseContent();
        content.setId(5L);
        content.setCourseId(1L);
        content.setContentType("faq");
        content.setContent("常见问题");
        content.setSortOrder(3);

        CourseDTO.CourseContentDTO dto = converter.toContentDTO(content);

        assertThat(dto.contentType()).isEqualTo("faq");
        assertThat(dto.content()).isEqualTo("常见问题");
        assertThat(dto.sortOrder()).isEqualTo(3);
    }
}
