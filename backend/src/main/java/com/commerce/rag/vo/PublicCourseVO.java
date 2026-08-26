package com.commerce.rag.vo;

import java.math.BigDecimal;

/**
 * 公开课程视图对象 —— controller 出参（C 端公开接口 GET /api/v1/public/courses）
 *
 * <p>未登录用户浏览首页/课堂页的课程数据源，仅输出课程对外信息
 * （价格/tags/status/createdBy 等内部管理字段不下发）。
 * 与 StudentCourseVO 契约独立演化：额外暴露 description 供详情页展示。
 *
 * @param id             课程 ID
 * @param title          课程标题
 * @param description    课程简介
 * @param coverImage     封面图 URL
 * @param category       分类
 * @param instructorName 授课讲师名
 * @param duration       课程时长描述
 * @param rating         评分（0-5）
 * @param learningCount  学习人数
 */
public record PublicCourseVO(
        Long id,
        String title,
        String description,
        String coverImage,
        String category,
        String instructorName,
        String duration,
        BigDecimal rating,
        Integer learningCount) {}
