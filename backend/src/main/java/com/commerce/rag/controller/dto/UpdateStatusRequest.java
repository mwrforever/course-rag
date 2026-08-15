package com.commerce.rag.controller.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新用户状态请求 DTO
 *
 * @param status 状态：ACTIVE / DISABLED
 */
public record UpdateStatusRequest(@NotBlank String status) {}
