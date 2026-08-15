package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程内容实体 —— 对应 course_content 表
 *
 * <p>存储课程的各种内容类型（大纲、描述、先修要求、目标受众等），
 * 一个课程对应多行内容，通过 content_type 区分类型，sort_order 排序。
 *
 * <p>content_type 枚举值：intro / syllabus / instructor / faq
 *
 * @author commerce-rag
 */
@Data
@TableName("course_content")
public class CourseContent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 课程 ID */
    private Long courseId;

    /** 内容类型（intro / syllabus / instructor / faq） */
    private String contentType;

    /** 内容文本 */
    private String content;

    /** 排序序号 */
    private Integer sortOrder;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
