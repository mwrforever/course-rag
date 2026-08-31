package com.commerce.rag.vo;

import java.time.LocalDate;

/**
 * 公开排期视图对象 —— 公开课程详情接口的排期出参（GET /api/v1/public/courses/{id} 内嵌）
 *
 * <p>仅输出课程排期的对外信息（开课/结课日期、类型、地点、状态、容量），
 * createdBy 等内部管理字段不下发。排期来自 course_schedule 表，按开课日期升序。
 *
 * @param id           排期 ID
 * @param startDate    开课日期
 * @param endDate      结课日期
 * @param scheduleType 排期类型（ONLINE / OFFLINE / HYBRID）
 * @param location     上课地点
 * @param status       排期状态（UPCOMING / IN_PROGRESS / COMPLETED）
 * @param capacity     容量上限
 * @param enrolled     已报名人数
 */
public record PublicScheduleVO(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        String scheduleType,
        String location,
        String status,
        Integer capacity,
        Integer enrolled) {}
