package com.commerce.rag.service;

/**
 * 认证用户视图 —— 登录校验用的内部视图对象（不出 service 边界，controller 内部使用）
 *
 * <p>含 passwordHash 仅为密码校验，禁止序列化返回前端；
 * 由 {@link SysUserService#findAuthViewByUsername} 返回，
 * 避免登录链路在 controller 层直接持有 SysUser 实体（工程宪法：Entity 不出 service 边界）。
 *
 * @param id          用户 ID
 * @param username    用户名
 * @param passwordHash 密码哈希（BCrypt，仅用于登录校验）
 * @param role        角色（STUDENT / TEACHER / SUPER_ADMIN）
 * @param displayName 显示名
 * @param status      状态（ACTIVE / DISABLED）
 */
public record AuthUserView(
        Long id, String username, String passwordHash, String role, String displayName, String status) {}
