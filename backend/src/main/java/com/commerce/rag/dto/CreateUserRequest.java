package com.commerce.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 创建用户请求 DTO
 *
 * @param username    登录名
 * @param password    密码（明文，Service 层 BCrypt 加密）
 * @param displayName 显示名
 * @param role        角色（TEACHER / STUDENT，枚举白名单校验防扩权）
 */
public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String displayName,
        @NotBlank @Pattern(regexp = "SUPER_ADMIN|TEACHER|STUDENT", message = "角色取值非法") String role) {}
