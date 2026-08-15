package com.commerce.rag.controller.dto;

import java.time.LocalDate;

/**
 * 排期 DTO —— 用于排期列表和详情展示。
 *
 * @param id             排期 ID
 * @param courseId       课程 ID
 * @param startDate      开课日期
 * @param endDate        结课日期
 * @param scheduleType   排期类型（ONLINE / OFFLINE / HYBRID）
 * @param location       上课地点
 * @param instructorName  讲师名
 * @param capacity       容量上限
 * @param enrolled       已报名人数
 * @param status         状态（UPCOMING / IN_PROGRESS / COMPLETED）
 * @param createdBy      创建者 ID
 */
public record ScheduleDTO(
        Long id,
        Long courseId,
        LocalDate startDate,
        LocalDate endDate,
        String scheduleType,
        String location,
        String instructorName,
        Integer capacity,
        Integer enrolled,
        String status,
        Long createdBy) {}
