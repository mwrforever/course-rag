package com.commerce.rag.convert;

import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.vo.CourseScheduleVO;
import org.mapstruct.Mapper;

/**
 * 课程排期转换器 —— CourseSchedule 实体 → 排期视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ScheduleConverter {

    /** 排期实体 → 视图对象（全部业务字段同名映射） */
    CourseScheduleVO toVO(CourseSchedule schedule);
}
