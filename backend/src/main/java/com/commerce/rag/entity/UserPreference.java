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
 * 用户偏好实体 —— 对应 user_preference 表（spec §7）
 *
 * <p>一行 = (user_id, key, value)：key 为偏好维度（constants/PreferenceKeys 枚举约束），
 * value 为取值；单值 key 同 key 仅一行 active，多值 key 同 key 可多行（每 value 一行并存）。
 *
 * <p>status 仅承载业务状态 active/observing（spec §7.2）；软删走项目全局约定 deleted 0/1
 * + @TableLogic（MP 逻辑删除自动过滤查询，审计保留物理行）。
 *
 * @author commerce-rag
 */
@Data
@TableName("user_preference")
public class UserPreference implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属用户 ID（全链路 user_id 硬隔离过滤键，spec §10-6） */
    @TableField("user_id")
    private Long userId;

    /** 偏好维度（constants/PreferenceKeys 中已知 key，LLM 候选只能从中选择） */
    private String key;

    /** 偏好取值（一行一个 value；多值 key 同 key 可多行） */
    private String value;

    /** 适用场景（预留，可空） */
    private String scope;

    /** LLM 初判语义明确度 0~1 */
    private BigDecimal explicitness;

    /** 系统计算的稳定性 0~1（min(1, base+count*step)，不信任 LLM） */
    private BigDecimal stability;

    /** LLM 初判置信度 0~1 */
    private BigDecimal confidence;

    /** 综合写入分（0.4*explicitness+0.4*stability+0.2*confidence，决策统一标尺） */
    @TableField("write_score")
    private BigDecimal writeScore;

    /** 业务状态 active/observing（软删统一走 deleted，不设 deleted 状态） */
    private String status;

    /** 观察计数（隐式晋升用） */
    @TableField("observation_count")
    private Integer observationCount;

    /** 版本号（单值 key 冲突更新 +1，历史审计） */
    private Integer version;

    /** 来源 explicit=直接表达 / implicit=观察晋升 */
    private String source;

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
