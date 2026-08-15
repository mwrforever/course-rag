package com.commerce.rag.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 课程详情 DTO —— 用于课程列表卡片和详情页展示。
 *
 * @param id             课程 ID
 * @param title           课程标题
 * @param description     课程简述
 * @param coverImage      封面图 URL
 * @param category        分类
 * @param instructorName  讲师名
 * @param price           价格
 * @param duration        课时描述
 * @param tags            标签列表
 * @param rating          评分
 * @param learningCount   学习人数
 * @param enrollmentLink  报名链接
 * @param status          状态（ACTIVE / ARCHIVED）
 * @param createdBy       创建者 ID
 * @param createdAt       创建时间
 * @param contents       课程内容列表（详情页填充）
 * @param schedules      排期列表（详情页填充）
 * @param teachers       授课老师列表（详情页填充）
 */
public record CourseDTO(
        Long id,
        String title,
        String description,
        String coverImage,
        String category,
        String instructorName,
        BigDecimal price,
        String duration,
        List<String> tags,
        BigDecimal rating,
        Integer learningCount,
        String enrollmentLink,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        List<CourseContentDTO> contents,
        List<ScheduleDTO> schedules,
        List<Long> teacherIds) {
    /**
     * 课程内容 Tab DTO。
     */
    public record CourseContentDTO(String contentType, String content, Integer sortOrder) {}
}
