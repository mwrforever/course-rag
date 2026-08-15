package com.commerce.rag.controller.dto;

import java.time.LocalDate;

/**
 * 创建排期请求 DTO。
 *
 * @param startDate     开课日期（必填）
 * @param endDate       结课日期（必填）
 * @param scheduleType  排期类型（ONLINE / OFFLINE / HYBRID，必填）
 * @param location      上课地点
 * @param instructorName 讲师名
 * @param capacity      容量上限
 */
public record CreateScheduleRequest(
        LocalDate startDate,
        LocalDate endDate,
        String scheduleType,
        String location,
        String instructorName,
        Integer capacity) {}
