package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 消息实体 —— 对应 chat_message 表
 *
 * <p>与 SAA checkpoint 分离的消息渲染表，用于前端历史回放和降级重组。
 * role 可选值：USER / ASSISTANT。
 * messageType 可选值：TOOL_CALL / TOOL_RESULT / thinking / null（普通消息）。
 *
 * <p>sourcesJson 为 JSONB 类型（JSON 数组字符串），存储引用来源信息。
 * confidence 为 NUMERIC(3,2)，表示回答置信度。
 *
 * @author commerce-rag
 */
@Data
@TableName("chat_message")
public class ChatMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话 ID */
    @TableField("session_id")
    private Long sessionId;

    /** 消息角色：USER / ASSISTANT */
    private String role;

    /** 消息内容 */
    private String content;

    /** 意图类型 */
    @TableField("intent_type")
    private String intentType;

    /** 引用来源 JSON（JSONB，JSON 数组字符串） */
    @TableField("sources_json")
    private String sourcesJson;

    /** 本次消息附件列表 JSON（[{type,url,name,size}]，渲染/审计用，spec §5.1 双存决策） */
    @TableField("attachments_json")
    private String attachmentsJson;

    /** Token 消耗数 */
    @TableField("token_count")
    private Integer tokenCount;

    /** 所属 Run ID */
    @TableField("run_id")
    private Long runId;

    /** 消息序号（同 run 内递增，用于排序） */
    private Integer seq;

    /** 置信度（0.00 ~ 1.00） */
    private BigDecimal confidence;

    /** 链路追踪 ID */
    @TableField("trace_id")
    private String traceId;

    /** 消息类型：TOOL_CALL / TOOL_RESULT / thinking / null */
    @TableField("message_type")
    private String messageType;

    /** 思考来源阶段：understanding / attachments / generating（仅 messageType=thinking 行有值，其余行为 null） */
    @TableField("thinking_stage")
    private String thinkingStage;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
