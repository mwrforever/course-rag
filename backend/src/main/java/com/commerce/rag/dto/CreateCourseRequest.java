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
 * @param enrollmentLink 报名链接
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
