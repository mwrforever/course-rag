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
 * 学生选课实体 —— 对应 course_enrollment 表
 *
 * <p>多对多关联表，记录学生的选课信息。
 * 通过 (course_id, student_id) 唯一索引防止重复选课。
 * status: ACTIVE（在选）/ DROPPED（退课）。
 *
 * @author commerce-rag
 */
@Data
@TableName("course_enrollment")
public class CourseEnrollment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 课程 ID */
    @TableField("course_id")
    private Long courseId;

    /** 学生 ID（sys_user.role='STUDENT'） */
    @TableField("student_id")
    private Long studentId;

    /** 选课时间 */
    @TableField("enrolled_at")
    private LocalDateTime enrolledAt;

    /** 选课状态（ACTIVE / DROPPED） */
    private String status;

    /** 逻辑删除标记（0 = 未删除，1 = 已删除） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;
}
