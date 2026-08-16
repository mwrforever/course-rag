package com.commerce.rag.dto;

import java.util.List;

/**
 * 选课请求 DTO —— 批量添加学生到课程。
 *
 * @param studentIds 学生 ID 列表
 */
public record EnrollmentRequest(List<Long> studentIds) {}
