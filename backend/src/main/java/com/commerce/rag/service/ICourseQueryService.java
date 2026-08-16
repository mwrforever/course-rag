package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import java.util.List;

/**
 * 课程查询服务接口 —— 聚合查询型（无单一主表实体，不继承 IService）
 *
 * <p>提供 C 端课程检索、详情、内容与排期查询，带 Caffeine 缓存。
 *
 * @author commerce-rag
 */
public interface ICourseQueryService {

    /**
     * 分页搜索课程（按名称/描述/分类关键词，带缓存）
     *
     * @param keyword 关键词（可空）
     * @param page    页码（1-based）
     * @return 分页结果
     */
    IPage<CourseInfo> searchCourses(String keyword, int page);

    /**
     * 按 ID 查询课程（带缓存）
     *
     * @param courseId 课程 ID
     * @return 课程实体，不存在返回 null
     */
    CourseInfo findCourseById(String courseId);

    /**
     * 按课程 ID 查询课程内容（带缓存）
     *
     * @param courseId 课程 ID
     * @return 内容列表
     */
    List<CourseContent> findContentsByCourseId(String courseId);

    /**
     * 查询课程下一期排期（带缓存）
     *
     * @param courseId 课程 ID
     * @return 下一期排期，无则返回 null
     */
    CourseSchedule findNextSchedule(String courseId);

    /**
     * 失效课程相关缓存键（先写 DB 后失效，一致性铁律）
     *
     * @param courseId 课程 ID
     */
    void evictCourse(Long courseId);
}
