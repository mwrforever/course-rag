package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/**
 * Token 黑名单视图对象 —— controller 出参（B 端管理接口 K4）
 *
 * <p>与 SysTokenBlacklist 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。
 *
 * @param id           黑名单记录 ID
 * @param jti          被禁 Token 的 JWT ID（AT 或 RT）
 * @param tokenType    Token 类型：ACCESS / REFRESH
 * @param userId       所属用户 ID
 * @param blacklistedBy 操作人 ID（SUPER_ADMIN / TEACHER）
 * @param reason       禁用原因：DEVICE_KICKED / USER_DISABLED / MANUAL_REVOKE / TOKEN_REUSE
 * @param expiresAt    该 jti 对应 Token 的原始过期时间
 * @param createdAt    创建时间
 */
public record SysTokenBlacklistVO(
        Long id,
        String jti,
        String tokenType,
        Long userId,
        Long blacklistedBy,
        String reason,
        LocalDateTime expiresAt,
        LocalDateTime createdAt) {}
