package com.commerce.rag.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 完成注册请求 DTO —— POST /api/v1/auth/register
 *
 * <p>流程契约：先调 /register/code 获取 6 位邮件验证码，再携码完成注册；
 * 注册成功直接返回双 Token（自动登录语义，与 /login 响应同构）。</p>
 *
 * @param email    注册邮箱（用户输入；服务端小写归一化）
 * @param code     邮箱验证码（6 位数字，用户从邮件转抄，可能带首尾空格 → 服务端 trim）
 * @param password 登录密码（8–64 位；仅约束长度，复杂度提示由前端强度计承担）
 * @param nickname 昵称（可选，1-50 字符；为空时回退邮箱前缀作为显示名）
 *
 * @author commerce-rag
 */
public record RegisterRequest(
        @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") @Size(max = 255, message = "邮箱最长 255 字符")
                String email,
        @NotBlank(message = "验证码不能为空") @Pattern(regexp = "^\\d{6}$", message = "验证码为 6 位数字") String code,
        @NotBlank(message = "密码不能为空") @Size(min = 8, max = 64, message = "密码长度须在 8-64 位之间") String password,
        @Size(max = 50, message = "昵称最长 50 字符") String nickname) {}
