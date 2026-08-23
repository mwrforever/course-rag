package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.CourseTeacher;

/**
 * 课程-教师关联服务 —— course_teacher 表的批量写入载体
 *
 * <p>核心职责：为跨模块批量插入（P1-9：addTeachers 由逐条 insert 改为 saveBatch）
 * 提供 MyBatis-Plus IService 批处理能力（JDBC 批处理 + @TableId(ASSIGN_ID) 自动填充雪花 ID）。
 *
 * <p>使用约束：saveBatch 须在事务内调用（宪法：JDBC 批处理整体原子性），
 * 调用方（CourseServiceImpl.addTeachers）以 @Transactional 保证。
 *
 * @author commerce-rag
 */
public interface ICourseTeacherService extends IService<CourseTeacher> {}
