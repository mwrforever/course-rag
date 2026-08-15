package com.commerce.rag.service;

import com.commerce.rag.dto.ScheduleDTO;
import com.commerce.rag.entity.CourseSchedule;
import org.mapstruct.Mapper;

/**
 * 课程排期转换器 —— CourseSchedule 实体 ↔ ScheduleDTO（11 字段全同名）
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ScheduleConverter {

    /** 实体 → 排期 DTO */
    ScheduleDTO toDTO(CourseSchedule schedule);
}
