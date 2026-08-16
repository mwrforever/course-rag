package com.commerce.rag.convert;

import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 课程转换器 —— CourseInfo + 关联数据 → CourseDTO
 *
 * <p>关联数据（内容/排期/教师）由 ICourseService 查询后传入，转换器只做纯映射；
 * 嵌套 record 列表（CourseContentDTO/ScheduleDTO）按同名字段自动映射。
 * tags 字段为 String(JSON) → List&lt;String&gt;，无内建转换，经默认方法 parseTags 解析（与原
 * ICourseService 手写逻辑一致，防止课程标签从 API 响应中丢失）。
 *
 * <p>ObjectMapper 以接口常量形式持有（线程安全无状态，MapStruct 1.6.3 生成的 Impl
 * 只调用无参构造器，抽象类构造注入不可行；注入 Spring bean 方案已实证排除）。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface CourseConverter {

    Logger log = LoggerFactory.getLogger(CourseConverter.class);

    ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 课程实体 + 关联数据 → 课程 DTO（includeRelations=false 时传空 List） */
    @Mapping(target = "contents", source = "contents")
    @Mapping(target = "schedules", source = "schedules")
    @Mapping(target = "teacherIds", source = "teacherIds")
    CourseDTO toDTO(
            CourseInfo course, List<CourseContent> contents, List<CourseSchedule> schedules, List<Long> teacherIds);

    /** 内容实体 → 内容 Tab DTO（AdminCourseController 内联转换替代） */
    CourseDTO.CourseContentDTO toContentDTO(CourseContent content);

    /**
     * tags JSON 字符串 → 标签列表（MapStruct 自动用于 CourseDTO.tags 映射）
     *
     * @param tagsJson 数据库 tags 列（JSON 数组字符串，如 ["Java","后端"]），可空
     * @return 解析后的标签列表，空/非法 JSON 返回空列表
     */
    default List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            // 数据不一致兜底：解析失败不阻断课程查询，按空标签处理
            log.warn("解析课程标签失败: tags={}", tagsJson);
            return Collections.emptyList();
        }
    }
}
