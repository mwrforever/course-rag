package com.commerce.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话重命名请求 DTO —— C 端 PATCH /api/v1/student/sessions/{sessionId}
 *
 * @param title 新会话标题（必填 1~300 字符）
 */
public record SessionRenameRequest(@NotBlank @Size(max = 300) String title) {}
