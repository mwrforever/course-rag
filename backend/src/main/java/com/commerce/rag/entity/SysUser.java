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
 * 系统用户实体 —— 对应 sys_user 表
 *
 * <p>三层角色：SUPER_ADMIN / TEACHER / STUDENT。
 * status 可选值：ACTIVE / DISABLED。
 * 超级管理员全局唯一（DB 唯一索引约束），不可删/不可禁。
 *
 * @author commerce-rag
 */
@Data
@TableName("sys_user")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名 */
    private String username;

    /** 绑定邮箱（C 端学员自注册唯一标识；可空——存量账户/管理员可能未绑定，V15 起支持邮箱登录回退） */
    private String email;

    /** 密码哈希（BCrypt） */
    @TableField("password_hash")
    private String passwordHash;

    /** 显示名 */
    @TableField("display_name")
    private String displayName;

    /** 角色：SUPER_ADMIN / TEACHER / STUDENT */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /** 创建者用户 ID（超管/种子用户为 NULL） */
    @TableField("created_by")
    private Long createdBy;
}
