package com.commerce.rag.controller.dto;

import java.time.LocalDate;

/**
 * 更新排期请求 DTO。所有字段可选，null 表示不更新。
 */
public record UpdateScheduleRequest(
        LocalDate startDate,
        LocalDate endDate,
        String scheduleType,
        String location,
        String instructorName,
        Integer capacity,
        Integer enrolled,
        String status) {}
