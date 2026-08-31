package com.commerce.rag.convert;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import com.commerce.rag.vo.PublicScheduleVO;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * 公开课程转换器 —— 课程实体/排期实体 → 公开课程视图对象（C 端公开接口）
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）；
 * 内部管理字段（tags/status/deleted 等）因 VO 无对应组件而自然忽略；
 * price 为 C 端公开展示字段（契约 C.2.1），同名字段自动映射。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface PublicCourseConverter {

    /** 课程实体 → 公开课程视图对象（列表） */
    PublicCourseVO toVO(CourseInfo course);

    /**
     * 课程实体 + 排期列表 → 公开课程详情视图对象（详情端点，契约 C.2.2）
     *
     * <p>排期列表按开课日期升序传入（service 层排序），逐条经 {@link #toScheduleVO}
     * 映射进详情 VO；空列表表示管理端未录入排期，前端按空态处理。
     *
     * @param course    课程实体（ACTIVE 校验由 service 层负责）
     * @param schedules 排期实体列表（开课日期升序，可为空）
     * @return 公开课程详情 VO（含价格与排期列表）
     */
    PublicCourseDetailVO toDetailVO(CourseInfo course, List<CourseSchedule> schedules);

    /** 课程排期实体 → 公开排期视图对象（仅对外字段，createdBy 不下发） */
    PublicScheduleVO toScheduleVO(CourseSchedule schedule);
}
