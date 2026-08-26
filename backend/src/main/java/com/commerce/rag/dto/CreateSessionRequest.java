package com.commerce.rag.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建会话请求 DTO —— C 端 POST /api/v1/student/sessions
 *
 * <p>title 可选：缺省/空白时由后端补「新对话」（J7 契约）。
 *
 * @param title 会话标题（可选，最长 300 字符）
 */
public record CreateSessionRequest(@Size(max = 300) String title) {}
