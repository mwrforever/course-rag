package com.commerce.rag.controller.dto;

/**
 * 更新用户请求 DTO（超管不可改角色）
 *
 * @param displayName 显示名
 */
public record UpdateUserRequest(String displayName) {}
