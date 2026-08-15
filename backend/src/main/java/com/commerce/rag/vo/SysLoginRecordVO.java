package com.commerce.rag.vo;

import java.time.LocalDateTime;

/**
 * 登录记录视图对象 —— controller 出参（B 端管理接口 K1-K2）
 *
 * <p>与 SysLoginRecord 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。jti 字段保留：管理端踢出设备/审计定位需要。
 *
 * @param id         登录记录 ID
 * @param userId     登录用户 ID
 * @param jtiAt      Access Token 的 JWT ID
 * @param jtiRt      Refresh Token 的 JWT ID（每次刷新更新）
 * @param deviceType 设备类型（WEB_DESKTOP 等）
 * @param deviceInfo 设备信息（User-Agent 摘要 + IP）
 * @param ipAddress  登录 IP
 * @param expiresAt  RT 过期时间
 * @param status     状态：ACTIVE / REVOKED / EXPIRED
 * @param createdAt  创建时间
 * @param updatedAt  更新时间
 */
public record SysLoginRecordVO(
        Long id,
        Long userId,
        String jtiAt,
        String jtiRt,
        String deviceType,
        String deviceInfo,
        String ipAddress,
        LocalDateTime expiresAt,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
