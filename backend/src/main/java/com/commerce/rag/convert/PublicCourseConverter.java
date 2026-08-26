package com.commerce.rag.convert;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.vo.PublicCourseVO;
import org.mapstruct.Mapper;

/**
 * 公开课程转换器 —— CourseInfo → 公开课程视图对象（C 端公开接口）
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）；
 * 内部管理字段（price/tags/status/deleted 等）因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface PublicCourseConverter {

    /** 课程实体 → 公开课程视图对象 */
    PublicCourseVO toVO(CourseInfo course);
}
