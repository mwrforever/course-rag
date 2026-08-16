package com.commerce.rag.dto;

/**
 * 登录响应 DTO
 *
 * @param accessToken  Access Token（15min JWT）
 * @param refreshToken Refresh Token（7d JWT）
 * @param userId       用户 ID
 * @param role         用户角色
 * @param displayName  显示名
 */
public record LoginResponse(String accessToken, String refreshToken, Long userId, String role, String displayName) {}
