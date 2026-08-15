package com.commerce.rag.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 课程排期实体 —— 对应 course_schedule 表
 *
 * <p>存储课程的排期信息（开课日期、结束日期、地点、容量等），
 * 一个课程可有多期排期，通过 start_date 排序。
 *
 * @author commerce-rag
 */
@Data
@TableName("course_schedule")
public class CourseSchedule implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 ID（雪花算法生成） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 课程 ID */
    private Long courseId;

    /** 开课日期 */
    private LocalDate startDate;

    /** 结束日期 */
    private LocalDate endDate;

    /** 排期类型（ONLINE / OFFLINE / HYBRID） */
    private String scheduleType;

    /** 上课地点 */
    private String location;

    /** 讲师姓名 */
    private String instructorName;

    /** 容量上限 */
    private Integer capacity;

    /** 已报名人数 */
    private Integer enrolled;

    /** 排期状态（UPCOMING / IN_PROGRESS / COMPLETED） */
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
