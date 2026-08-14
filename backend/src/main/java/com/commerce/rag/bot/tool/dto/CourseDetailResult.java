package com.commerce.rag.bot.tool.dto;

import java.util.List;

/**
 * 课程详情 DTO —— queryCourseDetail(courseId) 的聚合返回
 *
 * <p>聚合课程摘要、排期、讲师与课程正文四 Tab 内容。
 * 四 Tab 字段与 db-schema course_content.content_type 枚举一一对应：
 * intro / syllabus / instructor / faq（本 spec S3 语义对齐，废弃 prerequisites/targetAudience 错位字段）。
 *
 * @param summary            课程摘要（与列表页同类型）
 * @param schedule           排期信息（含课时时长）
 * @param instructor         讲师档案（姓名/头衔/简介，来自 course_info.instructor_name 纯展示文本）
 * @param introContent       课程介绍（intro Tab，Markdown）
 * @param syllabusContent    课程大纲（syllabus Tab，Markdown）
 * @param instructorContent  讲师详细介绍（instructor Tab，Markdown）
 * @param faqContent         常见问题（faq Tab，Markdown）
 * @param enrollmentUrl      报名链接
 * @param tags               课程标签
 */
public record CourseDetailResult(
        CourseListResult.CourseSummary summary,
        ScheduleInfo schedule,
        InstructorInfo instructor,
        String introContent,
        String syllabusContent,
        String instructorContent,
        String faqContent,
        String enrollmentUrl,
        List<String> tags) {

    /**
     * 课程排期信息。
     *
     * @param nextStartDate 下期开班日期（ISO-8601 字符串，可为 null）
     * @param duration      课时时长标签（如 "12 weeks"）
     * @param totalLessons  总课时数
     * @param schedule      上课节奏描述（如 "Tue/Thu 19:00-21:00"）
     */
    public record ScheduleInfo(String nextStartDate, String duration, int totalLessons, String schedule) {}

    /**
     * 讲师档案。
     *
     * @param name  讲师姓名
     * @param title 讲师头衔（如 "Senior Architect"）
     * @param bio   讲师简介（简短）
     */
    public record InstructorInfo(String name, String title, String bio) {}
}
