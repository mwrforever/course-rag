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
 * 用户反馈实体 —— 对应 user_feedback 表
 *
 * <p>用户对 AI 回答的点赞/点踩反馈。is_liked 三态：
 * <ul>
 *   <li>NULL — 未评价</li>
 *   <li>TRUE — 点赞</li>
 *   <li>FALSE — 点踩</li>
 * </ul>
 * UNIQUE(user_id, message_id) 约束：同一用户同一消息只允许一条反馈。
 * intent_type 用于按意图分组统计赞/踩数。
 *
 * @author commerce-rag
 */
@Data
@TableName("user_feedback")
public class UserFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话 ID */
    @TableField("session_id")
    private Long sessionId;

    /** 被反馈的消息 ID */
    @TableField("message_id")
    private Long messageId;

    /** 反馈用户 ID（P0-2h：反馈归属，防止跨用户伪造） */
    @TableField("user_id")
    private Long userId;

    /** 是否点赞（NULL=未评，TRUE=赞，FALSE=踩） */
    @TableField("is_liked")
    private Boolean isLiked;

    /** 意图类型（TECHNICAL_QA / COURSE_INFO） */
    @TableField("intent_type")
    private String intentType;

    /** 逻辑删除标记（0 = 未删除，1 = 已删除） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
