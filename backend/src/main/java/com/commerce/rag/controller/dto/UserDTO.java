package com.commerce.rag.controller.dto;

import java.time.LocalDateTime;

/**
 * 用户信息 DTO（对外展示，不含密码）
 *
 * @param id          用户 ID
 * @param username    登录名
 * @param displayName 显示名
 * @param role        角色
 * @param status      状态
 * @param createdAt   创建时间
 */
public record UserDTO(
        Long id, String username, String displayName, String role, String status, LocalDateTime createdAt) {}
