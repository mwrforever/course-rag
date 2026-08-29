package com.commerce.rag.vo;

import java.math.BigDecimal;

/**
 * 公开课程详情视图对象 —— controller 出参（C 端公开接口 GET /api/v1/public/courses/{id}）
 *
 * <p>契约 C.2.2：详情页数据源，无需登录（/api/v1/public/** 免拦截）；字段与 PublicCourseVO
 * 同集（每接口独立 VO，禁止跨接口复用，D.4 契约独立演化）；tags/status/createdBy 等
 * 内部管理字段不下发。
 *
 * @param id             课程 ID
 * @param title          课程标题
 * @param description    课程简介
 * @param coverImage     封面图 URL（如 /api/v1/public/covers/0/{uuid}.png 相对路径）
 * @param category       分类
 * @param instructorName 授课讲师名
 * @param duration       课程时长描述（如 "12 weeks"）
 * @param rating         评分（0-5）
 * @param learningCount  学习人数
 * @param price          课程价格（单位元，BigDecimal ≤2 位小数；C 端直接展示 ¥{price}，不做分转元）
 */
public record PublicCourseDetailVO(
        Long id,
        String title,
        String description,
        String coverImage,
        String category,
        String instructorName,
        String duration,
        BigDecimal rating,
        Integer learningCount,
        BigDecimal price) {}
