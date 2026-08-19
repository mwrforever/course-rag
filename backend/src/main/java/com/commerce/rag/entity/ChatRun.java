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
 * Run 生命周期实体 —— 对应 chat_run 表
 *
 * <p>记录一次对话推理执行的完整生命周期。status 可选值：
 * QUEUED → ACTIVE → COMPLETED / CANCELLED / ERROR。
 *
 * <p>并发守卫：DB partial unique index（uniq_active_run_per_session）
 * 保证同一 session 同时只有一个 QUEUED 或 ACTIVE 的 run。
 *
 * <p>metaJson 为 JSONB 类型，Java 中以 String 存储，由 Service 层用 Jackson 序列化。
 *
 * @author commerce-rag
 */
@Data
@TableName("chat_run")
public class ChatRun implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话 ID */
    @TableField("session_id")
    private Long sessionId;

    /** 发起用户 ID */
    @TableField("user_id")
    private Long userId;

    /** Run 状态：QUEUED / ACTIVE / COMPLETED / CANCELLED / ERROR */
    private String status;

    /** 模型调用次数 */
    @TableField("model_calls")
    private Integer modelCalls;

    /** 链路追踪 ID */
    @TableField("trace_id")
    private String traceId;

    /** 错误信息（status=ERROR 时填充） */
    @TableField("error_message")
    private String errorMessage;

    /** 元数据 JSON（JSONB，JSON 字符串，由 Jackson 序列化） */
    @TableField("meta_json")
    private String metaJson;

    /** 本次输入的附件列表 JSON（[{type,url,name,size}]，业务入口表，spec §5.1 双存决策） */
    @TableField("attachments_json")
    private String attachmentsJson;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** Run 开始执行时间 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** Run 结束时间 */
    @TableField("ended_at")
    private LocalDateTime endedAt;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
