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
 * 会话实体 —— 对应 chat_session 表
 *
 * <p>存储用户对话会话的元信息。status 可选值：ACTIVE / CLOSED。
 * 一个用户可拥有多个活跃会话，通过 user_id + last_message_at 索引加速列表查询。
 *
 * @author commerce-rag
 */
@Data
@TableName("chat_session")
public class ChatSession implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID */
    @TableField("user_id")
    private Long userId;

    /** 会话标题（最长 300 字符） */
    private String title;

    /** 会话状态：ACTIVE / CLOSED */
    private String status;

    /** 最后一条消息时间，用于会话列表排序 */
    @TableField("last_message_at")
    private LocalDateTime lastMessageAt;

    /** 使用的模型名称 */
    private String model;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
