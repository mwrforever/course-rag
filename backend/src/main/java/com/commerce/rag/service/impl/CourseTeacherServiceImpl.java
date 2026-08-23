package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.service.ICourseTeacherService;
import org.springframework.stereotype.Service;

/**
 * 课程-教师关联服务实现 —— course_teacher 表批量写入载体（P1-9）
 *
 * <p>核心职责：为 CourseServiceImpl.addTeachers 的批量插入（saveBatch）提供 MP ServiceImpl
 * 泛型载体；单表 CRUD 与批处理能力全部由 MyBatis-Plus 内置方法提供，无自定义业务方法。
 *
 * <p>依赖关系：仅依赖 CourseTeacherMapper（主表 mapper），不依赖任何其它 service，
 * 与 CourseServiceImpl 构成单向依赖（Course → CourseTeacher），无循环依赖。
 *
 * <p>线程安全：无共享可变状态，MyBatis-Plus SqlSession 按线程隔离，线程安全。
 *
 * @author commerce-rag
 */
@Service
public class CourseTeacherServiceImpl extends ServiceImpl<CourseTeacherMapper, CourseTeacher>
        implements ICourseTeacherService {}
