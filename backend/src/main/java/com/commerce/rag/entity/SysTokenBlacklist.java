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
 * Token 黑名单实体 —— 对应 sys_token_blacklist 表
 *
 * <p>存储已被吊销的单个 Token jti。管理员禁用某 Token 或用户后立即生效。
 * Redis 为执法层（纳秒级生效），PG 为审计层（持久化记录）。
 *
 * @author commerce-rag
 */
@Data
@TableName("sys_token_blacklist")
public class SysTokenBlacklist implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 被禁 Token 的 JWT ID（AT 或 RT） */
    private String jti;

    /** Token 类型：ACCESS / REFRESH */
    @TableField("token_type")
    private String tokenType;

    /** 所属用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 操作人 ID（SUPER_ADMIN / TEACHER） */
    @TableField("blacklisted_by")
    private Long blacklistedBy;

    /** 禁用原因：DEVICE_KICKED / USER_DISABLED / MANUAL_REVOKE / TOKEN_REUSE */
    private String reason;

    /** 该 jti 对应 Token 的原始过期时间（过期后可清理此条记录） */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /** 逻辑删除标记（0 = 未删除，1 = 已删除） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
