package com.commerce.rag.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 课程排期视图对象 —— controller 出参（B 端管理接口 F1-F3）
 *
 * <p>与 CourseSchedule 实体同名业务字段一一对应，剔除 deleted（逻辑删除标记，
 * 内部数据管理字段，不对外暴露）。createdBy 保留：管理端展示创建者信息需要。
 *
 * @param id             排期 ID
 * @param courseId       课程 ID
 * @param startDate      开课日期
 * @param endDate        结束日期
 * @param scheduleType   排期类型（ONLINE / OFFLINE / HYBRID）
 * @param location       上课地点
 * @param instructorName 讲师姓名
 * @param capacity       容量上限
 * @param enrolled       已报名人数
 * @param status         排期状态（UPCOMING / IN_PROGRESS / COMPLETED）
 * @param createdBy      创建者 ID
 * @param createdAt      创建时间
 * @param updatedAt      更新时间
 */
public record CourseScheduleVO(
        Long id,
        Long courseId,
        LocalDate startDate,
        LocalDate endDate,
        String scheduleType,
        String location,
        String instructorName,
        Integer capacity,
        Integer enrolled,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
