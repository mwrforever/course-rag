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
 * 知识库实体 —— 对应 knowledge_base 表
 *
 * <p>知识库是文档的顶层容器，教师创建后可上传文档、管理分片。
 * status 可选值：ACTIVE / ARCHIVED（db-schema.md:79）。
 * 教师只能操作自己创建的知识库（Service 层 created_by 校验）。
 *
 * @author commerce-rag
 */
@Data
@TableName("knowledge_base")
public class KnowledgeBase implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 知识库名称（唯一） */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 状态：ACTIVE / ARCHIVED */
    private String status;

    /** 创建者 ID（教师 user_id） */
    @TableField("created_by")
    private Long createdBy;

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
