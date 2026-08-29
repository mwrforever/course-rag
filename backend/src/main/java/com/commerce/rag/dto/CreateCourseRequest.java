package com.commerce.rag.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建课程请求 DTO。
 *
 * @param title          课程标题（必填）
 * @param description    课程简述
 * @param coverImage     封面图 URL
 * @param category       分类
 * @param instructorName 讲师名
 * @param price          价格
 * @param duration       课时描述
 * @param tags           标签列表
 * @param enrollmentLink 报名链接（契约 A.2.2：字段保留仅为兼容旧客户端，服务端不读——
 *                       落库后由服务端生成 {course.enroll-base-url}/courses/{id} 写回）
 */
public record CreateCourseRequest(
        String title,
        String description,
        String coverImage,
        String category,
        String instructorName,
        BigDecimal price,
        String duration,
        List<String> tags,
        String enrollmentLink) {}
