package com.commerce.rag.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 更新课程请求 DTO。所有字段可选，null 表示不更新。
 */
public record UpdateCourseRequest(
        String title,
        String description,
        String coverImage,
        String category,
        String instructorName,
        BigDecimal price,
        String duration,
        List<String> tags,
        String enrollmentLink,
        String status) {}
