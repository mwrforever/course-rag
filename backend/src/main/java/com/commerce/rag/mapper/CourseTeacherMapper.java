package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.CourseTeacher;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程-教师关联 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供，无需手写 SQL。
 *
 * @author commerce-rag
 */
@Mapper
public interface CourseTeacherMapper extends BaseMapper<CourseTeacher> {}
