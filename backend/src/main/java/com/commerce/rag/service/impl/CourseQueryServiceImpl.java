package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.service.ICourseQueryService;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 课程查询服务 —— 封装 MyBatis-Plus 数据层查询，供 CourseApiTool 调用
 *
 * <p>查询经 mapper 注入（Wrappers lambda 链式），不绕数据层直调 Db 静态工具
 * （工程宪法：mapper 调用传值不传 wrapper，条件查询 wrapper 在 service 内构建）。
 *
 * <p>查询结果使用 Caffeine 本地缓存（courseQueryCache，TTL 5 分钟，容量 512），
 * 键格式 search:{keyword}:{page} / course:{id} / contents:{id} / schedule:{id}；
 * 课程/排期写方法通过 {@link #evictCourse(Long)} 失效对应键（一致性铁律：先写 DB 后失效）。
 *
 * <p>所有查询自动过滤逻辑删除记录（@TableLogic）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class CourseQueryServiceImpl implements ICourseQueryService {

    private static final Logger log = LoggerFactory.getLogger(ICourseQueryService.class);

    /** 默认每页条数 */
    private static final int PAGE_SIZE = 10;

    /** 课程查询缓存（TTL 5 分钟，见 CacheConfig.courseQueryCache bean） */
    @Qualifier("courseQueryCache")
    private final Cache<String, Object> courseQueryCache;

    private final CourseInfoMapper courseInfoMapper;
    private final CourseContentMapper courseContentMapper;
    private final CourseScheduleMapper courseScheduleMapper;

    /**
     * 分页搜索课程 —— 按标题模糊匹配，仅返回 ACTIVE 状态课程（结果缓存 5 分钟）
     *
     * <p>L-9：缓存重建原子化（cache.get(key, fn)——并发 miss 仅一个线程查库重建，
     * 替代原 getIfPresent + 查库 + put 两段式）。
     *
     * @param keyword 搜索关键词（可为空，空时返回全部）
     * @param page    页码（1-based）
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    public IPage<CourseInfo> searchCourses(String keyword, int page) {
        String key = "search:" + (keyword == null ? "" : keyword) + ":" + page;
        IPage<CourseInfo> cached = (IPage<CourseInfo>) courseQueryCache.get(key, k -> {
            log.info("搜索课程: keyword={}, page={}", keyword, page);
            return courseInfoMapper.selectPage(
                    new Page<>(page, PAGE_SIZE),
                    Wrappers.<CourseInfo>lambdaQuery()
                            .select(
                                    CourseInfo::getId,
                                    CourseInfo::getTitle,
                                    CourseInfo::getCategory,
                                    CourseInfo::getPrice,
                                    CourseInfo::getStatus,
                                    CourseInfo::getTags,
                                    CourseInfo::getDuration,
                                    CourseInfo::getRating)
                            .like(StringUtils.hasText(keyword), CourseInfo::getTitle, keyword)
                            .eq(CourseInfo::getStatus, "ACTIVE")
                            .orderByDesc(CourseInfo::getRating));
        });
        return cached;
    }

    /**
     * 根据 ID 查询课程信息（结果缓存 5 分钟；课程不存在不缓存，避免缓存 null 值）
     *
     * <p>L-9：cache.get(key, fn) 原子重建——加载函数返回 null 时不记录映射（Caffeine 语义），
     * 保持「不存在返回 null」契约。
     *
     * @param courseId 课程 ID（字符串形式）
     * @return 课程信息实体，不存在则返回 null
     */
    public CourseInfo findCourseById(String courseId) {
        String key = "course:" + courseId;
        return (CourseInfo) courseQueryCache.get(key, k -> {
            log.info("查询课程: courseId={}", courseId);
            return courseInfoMapper.selectById(Long.parseLong(courseId));
        });
    }

    /**
     * 查询课程的所有内容（大纲、描述等），按 sort_order 升序排列（结果缓存 5 分钟）
     *
     * @param courseId 课程 ID（字符串形式）
     * @return 内容列表
     */
    @SuppressWarnings("unchecked")
    public List<CourseContent> findContentsByCourseId(String courseId) {
        String key = "contents:" + courseId;
        return (List<CourseContent>) courseQueryCache.get(key, k -> {
            log.info("查询课程内容: courseId={}", courseId);
            return courseContentMapper.selectList(Wrappers.<CourseContent>lambdaQuery()
                    .select(
                            CourseContent::getId,
                            CourseContent::getCourseId,
                            CourseContent::getContentType,
                            CourseContent::getContent,
                            CourseContent::getSortOrder)
                    .eq(CourseContent::getCourseId, Long.parseLong(courseId))
                    .orderByAsc(CourseContent::getSortOrder));
        });
    }

    /**
     * 查询课程的下一期排期（start_date >= 今天，取最近的一期；结果缓存 5 分钟，无排期不缓存）
     *
     * @param courseId 课程 ID（字符串形式）
     * @return 排期实体，无可用排期则返回 null
     */
    public CourseSchedule findNextSchedule(String courseId) {
        String key = "schedule:" + courseId;
        return (CourseSchedule) courseQueryCache.get(key, k -> {
            log.info("查询下一期排期: courseId={}", courseId);
            // selectOne(throwEx=false)：按 startDate 升序取结果集第一条 = 最近一期（不拼 SQL 片段）
            return courseScheduleMapper.selectOne(
                    Wrappers.<CourseSchedule>lambdaQuery()
                            .select(
                                    CourseSchedule::getId, CourseSchedule::getCourseId,
                                    CourseSchedule::getStartDate, CourseSchedule::getEndDate,
                                    CourseSchedule::getScheduleType, CourseSchedule::getLocation,
                                    CourseSchedule::getInstructorName, CourseSchedule::getCapacity,
                                    CourseSchedule::getEnrolled, CourseSchedule::getStatus)
                            .eq(CourseSchedule::getCourseId, Long.parseLong(courseId))
                            .ge(CourseSchedule::getStartDate, LocalDate.now())
                            .orderByAsc(CourseSchedule::getStartDate),
                    false);
        });
    }

    /**
     * 失效课程相关缓存（一致性铁律：写方先写 DB 后调用）
     *
     * <p>精确失效详情/内容/排期键（course/contents/schedule:{courseId}），
     * 并清理 search:* 前缀的列表键（课程数据变更影响列表可见性与排序）。
     *
     * @param courseId 发生变更的课程 ID
     */
    public void evictCourse(Long courseId) {
        String id = String.valueOf(courseId);
        courseQueryCache.invalidate("course:" + id);
        courseQueryCache.invalidate("contents:" + id);
        courseQueryCache.invalidate("schedule:" + id);
        courseQueryCache.asMap().keySet().removeIf(k -> k.startsWith("search:"));
    }
}
