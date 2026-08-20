package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户经历记忆实体 —— 对应 user_episodic_memory 表（spec §8）
 *
 * <p>一条 = 一个独立的原子事实（同 type 可多条）；validity 为状态机
 * active/superseded/merged/invalidated/archived（spec §8.6，archived 预留），
 * 与软删 deleted 0/1 双轨：validity 表达「事实生命周期演进」，deleted 表达「整条物理删除审计」。
 *
 * <p>structured_facts 为 JSONB 原始 JSON 文本（LLM 输出原文存储，v1 不消费、注入不用，
 * 完全回 PG 查询也不解析）；importance 存系统校正后有效值（LLM importance × typeWeight）。
 *
 * @author commerce-rag
 */
@Data
@TableName("user_episodic_memory")
public class UserEpisodicMemory implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属用户 ID（全链路 user_id 硬隔离过滤键，spec §10-6） */
    @TableField("user_id")
    private Long userId;

    /** 记忆分类（EpisodicTypes.ALL_TYPES 白名单，spec §8.2） */
    private String type;

    /** 完整记忆内容（事实源，注入用，提炼后的原子事实陈述） */
    private String content;

    /** 一句话摘要（与 content 合并做 embedding，spec §8.4） */
    private String summary;

    /** 结构化事实 JSONB（LLM 输出原文 JSON 文本，v1 不消费） */
    @TableField("structured_facts")
    private String structuredFacts;

    /** 系统校正后的有效重要性（LLM importance × typeWeight，spec §8.3） */
    private BigDecimal importance;

    /** LLM 初判置信度 0~1 */
    private BigDecimal confidence;

    /** 状态机（spec §8.6：active/superseded/merged/invalidated/archived） */
    private String validity;

    /** 版本号（UPDATE/MERGE 新行=旧+1，历史审计） */
    private Integer version;

    /** 来源会话 ID（提取触发所在 run 的会话快照） */
    @TableField("source_session_id")
    private Long sourceSessionId;

    /** 逻辑删除标记（0=未删除/1=已删除，MP @TableLogic 全局约定） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
