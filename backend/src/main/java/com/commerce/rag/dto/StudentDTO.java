package com.commerce.rag.dto;

import java.time.LocalDateTime;

/**
 * 学生 DTO —— 用于选课学生列表展示。
 *
 * @param id          学生 ID
 * @param username    登录名
 * @param displayName 显示名
 * @param enrolledAt  选课时间
 * @param status      选课状态（ACTIVE / DROPPED）
 */
public record StudentDTO(Long id, String username, String displayName, LocalDateTime enrolledAt, String status) {}
