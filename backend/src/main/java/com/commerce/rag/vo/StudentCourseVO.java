package com.commerce.rag.vo;

import java.math.BigDecimal;

/**
 * 学生课程视图对象 —— controller 出参（C 端接口 J1 我的课程）
 *
 * <p>与 CourseInfo 实体同名业务字段一一对应，剔除内部管理字段
 * （description/price/tags/enrollmentLink/status/createdBy/deleted/时间戳）。
 *
 * @param id            课程 ID
 * @param title         课程标题
 * @param coverImage    封面图 URL
 * @param category      分类
 * @param instructorName 授课讲师名
 * @param duration      课程时长描述
 * @param rating        评分（0-5）
 * @param learningCount 学习人数
 */
public record StudentCourseVO(
        Long id,
        String title,
        String coverImage,
        String category,
        String instructorName,
        String duration,
        BigDecimal rating,
        Integer learningCount) {}
