package com.commerce.rag.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求 DTO
 *
 * @param username   用户名
 * @param password   密码
 * @param deviceType 设备类型（默认 WEB_DESKTOP）
 */
public record LoginRequest(@NotBlank String username, @NotBlank String password, String deviceType) {}
