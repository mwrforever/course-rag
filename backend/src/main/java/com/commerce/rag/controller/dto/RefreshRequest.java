package com.commerce.rag.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新 Token 请求 DTO
 *
 * @param refreshToken Refresh Token
 */
public record RefreshRequest(@NotBlank String refreshToken) {}
