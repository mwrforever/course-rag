package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 登录记录实体 —— 对应 sys_login_record 表（会话注册表）
 *
 * <p>每次登录/刷新时写入一条记录，作为该次认证会话的注册表。
 * 只存 jti（JWT ID），不存 Token 原文。
 * status 可选值：ACTIVE / REVOKED / EXPIRED。
 *
 * @author commerce-rag
 */
@Data
@TableName("sys_login_record")
public class SysLoginRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户 ID */
    @TableField("user_id")
    private Long userId;

    /** Access Token 的 JWT ID */
    @TableField("jti_at")
    private String jtiAt;

    /** Refresh Token 的 JWT ID（每次刷新更新） */
    @TableField("jti_rt")
    private String jtiRt;

    /** 设备类型：WEB_DESKTOP（预留扩展） */
    @TableField("device_type")
    private String deviceType;

    /** 设备信息（User-Agent 摘要 + IP） */
    @TableField("device_info")
    private String deviceInfo;

    /** 登录 IP */
    @TableField("ip_address")
    private String ipAddress;

    /** RT 过期时间 */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 状态：ACTIVE / REVOKED / EXPIRED */
    private String status;

    /** 逻辑删除标记（0 = 未删除，1 = 已删除） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
