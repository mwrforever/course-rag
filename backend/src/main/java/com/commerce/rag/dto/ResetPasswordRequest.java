package com.commerce.rag.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 重置密码请求 DTO
 *
 * @param newPassword 新密码（明文，Service 层 BCrypt 加密）
 */
public record ResetPasswordRequest(@NotBlank String newPassword) {}
