package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 课程查询服务 —— 封装 MyBatis-Plus 数据层查询，供 CourseApiTool 调用
 *
 * <p>使用 {@link Db} 静态工具类的 {@code lambdaQuery()} 链式 API 进行单表查询，
 * 遵循 MyBatis-Plus 最佳实践（按需 select 字段、条件链式过滤）。
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
public class CourseQueryService {

    private static final Logger log = LoggerFactory.getLogger(CourseQueryService.class);

    /** 默认每页条数 */
    private static final int PAGE_SIZE = 10;

    /** 课程查询缓存（TTL 5 分钟，见 CacheConfig.courseQueryCache bean） */
    private final Cache<String, Object> courseQueryCache;

    /**
     * 分页搜索课程 —— 按标题模糊匹配，仅返回 ACTIVE 状态课程（结果缓存 5 分钟）
     *
     * @param keyword 搜索关键词（可为空，空时返回全部）
     * @param page    页码（1-based）
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    public IPage<CourseInfo> searchCourses(String keyword, int page) {
        String key = "search:" + (keyword == null ? "" : keyword) + ":" + page;
        IPage<CourseInfo> cached = (IPage<CourseInfo>) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("搜索课程: keyword={}, page={}", keyword, page);
        IPage<CourseInfo> result = Db.lambdaQuery(CourseInfo.class)
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
                .orderByDesc(CourseInfo::getRating)
                .page(new Page<>(page, PAGE_SIZE));
        courseQueryCache.put(key, result);
        return result;
    }

    /**
     * 根据 ID 查询课程信息（结果缓存 5 分钟；课程不存在不缓存，避免缓存 null 值）
     *
     * @param courseId 课程 ID（字符串形式）
     * @return 课程信息实体，不存在则返回 null
     */
    public CourseInfo findCourseById(String courseId) {
        String key = "course:" + courseId;
        CourseInfo cached = (CourseInfo) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("查询课程: courseId={}", courseId);
        CourseInfo result = Db.getById(Long.parseLong(courseId), CourseInfo.class);
        if (result != null) {
            // Caffeine 禁止缓存 null 值：课程不存在时不写入，保持"不存在返回 null"语义
            courseQueryCache.put(key, result);
        }
        return result;
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
        List<CourseContent> cached = (List<CourseContent>) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("查询课程内容: courseId={}", courseId);
        List<CourseContent> result = Db.lambdaQuery(CourseContent.class)
                .select(
                        CourseContent::getId,
                        CourseContent::getCourseId,
                        CourseContent::getContentType,
                        CourseContent::getContent,
                        CourseContent::getSortOrder)
                .eq(CourseContent::getCourseId, Long.parseLong(courseId))
                .orderByAsc(CourseContent::getSortOrder)
                .list();
        courseQueryCache.put(key, result);
        return result;
    }

    /**
     * 查询课程的下一期排期（start_date >= 今天，取最近的一期；结果缓存 5 分钟，无排期不缓存）
     *
     * @param courseId 课程 ID（字符串形式）
     * @return 排期实体，无可用排期则返回 null
     */
    public CourseSchedule findNextSchedule(String courseId) {
        String key = "schedule:" + courseId;
        CourseSchedule cached = (CourseSchedule) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("查询下一期排期: courseId={}", courseId);
        CourseSchedule result = Db.lambdaQuery(CourseSchedule.class)
                .select(
                        CourseSchedule::getId, CourseSchedule::getCourseId,
                        CourseSchedule::getStartDate, CourseSchedule::getEndDate,
                        CourseSchedule::getScheduleType, CourseSchedule::getLocation,
                        CourseSchedule::getInstructorName, CourseSchedule::getCapacity,
                        CourseSchedule::getEnrolled, CourseSchedule::getStatus)
                .eq(CourseSchedule::getCourseId, Long.parseLong(courseId))
                .ge(CourseSchedule::getStartDate, LocalDate.now())
                .orderByAsc(CourseSchedule::getStartDate)
                .last("LIMIT 1")
                .one();
        if (result != null) {
            // Caffeine 禁止缓存 null 值：无可用排期时不写入，保持"无排期返回 null"语义
            courseQueryCache.put(key, result);
        }
        return result;
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
