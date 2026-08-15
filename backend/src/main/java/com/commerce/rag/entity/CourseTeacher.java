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
 * 课程-教师关联实体 —— 对应 course_teacher 表
 *
 * <p>多对多关联表，一个课程可有多位教师，一位教师可教多门课程。
 * 通过 (course_id, teacher_id) 唯一索引防止重复关联。
 *
 * @author commerce-rag
 */
@Data
@TableName("course_teacher")
public class CourseTeacher implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 课程 ID */
    @TableField("course_id")
    private Long courseId;

    /** 教师 ID（sys_user.role='TEACHER'） */
    @TableField("teacher_id")
    private Long teacherId;

    /** 逻辑删除标记（0 = 未删除，删除时写入毫秒时间戳） */
    @TableLogic(value = "0", delval = "1")
    private Long deleted;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
