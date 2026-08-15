package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程信息实体 —— 对应 course_info 表
 *
 * <p>存储课程的基本信息（标题、价格、分类等），是课程查询的主表。
 * tags 字段为 JSONB 类型，在 Java 中以 String 存储，由调用方负责 JSON 解析。
 *
 * @author commerce-rag
 */
@Data
@TableName("course_info")
public class CourseInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 课程标题 */
    private String title;

    /** 课程描述 */
    private String description;

    /** 封面图片 URL */
    private String coverImage;

    /** 课程分类 */
    private String category;

    /** 讲师姓名 */
    private String instructorName;

    /** 课程价格 */
    private BigDecimal price;

    /** 课程时长（如 "12 weeks"） */
    private String duration;

    /** 课程标签（JSONB，JSON 数组字符串，如 ["Java","后端"]） */
    private String tags;

    /** 课程评分（0.0 ~ 5.0） */
    private BigDecimal rating;

    /** 学习人数 */
    private Integer learningCount;

    /** 报名链接 */
    private String enrollmentLink;

    /** 课程状态（ACTIVE / ARCHIVED） */
    private String status;

    /** 创建者 ID */
    private Long createdBy;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
