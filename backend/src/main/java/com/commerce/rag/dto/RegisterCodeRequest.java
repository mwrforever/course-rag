package com.commerce.rag.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 发送注册验证码请求 DTO —— POST /api/v1/auth/register/code
 *
 * @param email 注册邮箱（用户输入；服务端统一小写归一化后入库/查重）
 *
 * @author commerce-rag
 */
public record RegisterCodeRequest(@NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email) {}
