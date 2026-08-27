package com.commerce.rag.record;

/**
 * 注册结果视图 —— 完成注册后供 Controller 签发会话的最小信息集（不出 service 边界语义同 AuthUserView）
 *
 * <p>不携带 Token/密码等敏感字段；Token 签发与互踢编排由 Controller 复用登录链路完成。</p>
 *
 * @param userId      新用户 ID（雪花）
 * @param username    生成的登录用户名（邮箱前缀 sanitized + 随机后缀，保证唯一）
 * @param displayName 显示昵称（请求 nickname 为空时回退邮箱前缀）
 * @param role        角色（自注册固定 STUDENT）
 *
 * @author commerce-rag
 */
public record RegisterResult(Long userId, String username, String displayName, String role) {}
