package com.commerce.rag.bot.tool;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.bot.tool.dto.CourseDetailResult;
import com.commerce.rag.bot.tool.dto.CourseDetailResult.InstructorInfo;
import com.commerce.rag.bot.tool.dto.CourseDetailResult.ScheduleInfo;
import com.commerce.rag.bot.tool.dto.CourseListResult;
import com.commerce.rag.bot.tool.dto.CourseListResult.CourseSummary;
import com.commerce.rag.bot.tool.dto.EnrollmentResult;
import com.commerce.rag.bot.tool.dto.EnrollmentResult.NextSchedule;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.service.ICourseQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 课程 API 工具组 —— LLM Agent 直接调用，获取课程实时数据
 *
 * <p>三个方法对应三个 DTO 记录类型。课程结构化信息（列表/详情/报名）
 * 经 CourseApiTool 获取；知识库资料由系统检索节点注入 <document> 上下文。
 *
 * <p>课程"报名"为只读交接：仅返回 enrollmentUrl，绝不调用业务报名 API。
 *
 * <p>数据层通过 {@link ICourseQueryService} 封装 MyBatis-Plus 查询，
 * DTO 映射逻辑（Entity → DTO）在本类中完成。
 *
 * @author commerce-rag
 */
@Component
public class CourseApiTool {

    private static final Logger log = LoggerFactory.getLogger(CourseApiTool.class);

    /** JSON 解析器（用于解析 tags JSONB 字段） */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 授课模式枚举 → 人类可读描述（P2-5：schedule 字段语义为「上课节奏描述」，不得传原始枚举） */
    private static final Map<String, String> SCHEDULE_TYPE_LABELS =
            Map.of("ONLINE", "线上授课", "OFFLINE", "线下授课", "HYBRID", "线上线下结合");

    private final ICourseQueryService courseQueryService;

    public CourseApiTool(ICourseQueryService courseQueryService) {
        this.courseQueryService = courseQueryService;
    }

    /**
     * 分页查询课程列表
     *
     * @param keyword 搜索关键词（可选，空时返回全部）
     * @param page    页码（1-based）
     * @return 分页课程列表
     */
    @Tool(description = "分页查询课程列表，支持关键词过滤")
    public CourseListResult listCourses(String keyword, int page) {
        log.info("查询课程列表: keyword={}, page={}", keyword, page);

        IPage<CourseInfo> pageInfo = courseQueryService.searchCourses(keyword, page);

        List<CourseSummary> summaries =
                pageInfo.getRecords().stream().map(this::toCourseSummary).collect(Collectors.toList());

        return new CourseListResult(
                (int) pageInfo.getCurrent(), (int) pageInfo.getSize(), pageInfo.getTotal(), summaries);
    }

    /**
     * 查询课程详情（聚合 header + content + schedule）
     *
     * @param courseId 课程 ID
     * @return 课程详情（含 schedule / instructor）
     */
    @Tool(description = "查询课程详情（含排期、讲师、简介、大纲、FAQ 等聚合信息）")
    public CourseDetailResult queryCourseDetail(String courseId) {
        log.info("查询课程详情: courseId={}", courseId);

        CourseInfo info = courseQueryService.findCourseById(courseId);
        if (info == null) {
            return emptyDetail(courseId);
        }

        List<CourseContent> contents = courseQueryService.findContentsByCourseId(courseId);
        CourseSchedule schedule = courseQueryService.findNextSchedule(courseId);

        // 按内容类型提取：四 Tab 与 DTO 字段一一对应（db-schema 权威枚举：intro / syllabus / instructor / faq）
        String introContent = extractContent(contents, "intro");
        String syllabusContent = extractContent(contents, "syllabus");
        String instructorContent = extractContent(contents, "instructor");
        String faqContent = extractContent(contents, "faq");

        CourseSummary summary = toCourseSummary(info);
        ScheduleInfo scheduleInfo = toScheduleInfo(schedule, info);
        InstructorInfo instructorInfo =
                new InstructorInfo(info.getInstructorName() != null ? info.getInstructorName() : "", "", "");

        return new CourseDetailResult(
                summary,
                scheduleInfo,
                instructorInfo,
                introContent,
                syllabusContent,
                instructorContent,
                faqContent,
                info.getEnrollmentLink() != null ? info.getEnrollmentLink() : "",
                parseTags(info.getTags()));
    }

    /**
     * 查询课程报名信息（精简版）
     *
     * @param courseId 课程 ID
     * @return 报名相关字段（价格、折扣、链接、排期）
     */
    @Tool(description = "查询课程报名信息（价格、折扣、报名链接、下期排期）")
    public EnrollmentResult queryEnrollment(String courseId) {
        log.info("查询报名信息: courseId={}", courseId);

        CourseInfo info = courseQueryService.findCourseById(courseId);
        if (info == null) {
            return new EnrollmentResult("", null, "", new NextSchedule(null, ""));
        }

        CourseSchedule schedule = courseQueryService.findNextSchedule(courseId);

        NextSchedule nextSchedule = schedule != null
                ? new NextSchedule(
                        schedule.getStartDate() != null
                                ? schedule.getStartDate().toString()
                                : null,
                        info.getDuration() != null ? info.getDuration() : "")
                : new NextSchedule(null, info.getDuration() != null ? info.getDuration() : "");

        return new EnrollmentResult(
                formatPrice(info.getPrice()),
                null,
                info.getEnrollmentLink() != null ? info.getEnrollmentLink() : "",
                nextSchedule);
    }

    // ==================== Entity → DTO 映射方法 ====================

    /**
     * CourseInfo → CourseSummary 映射
     */
    private CourseSummary toCourseSummary(CourseInfo info) {
        return new CourseSummary(
                String.valueOf(info.getId()),
                info.getTitle() != null ? info.getTitle() : "",
                info.getCategory() != null ? info.getCategory() : "",
                formatPrice(info.getPrice()),
                null,
                null,
                info.getStatus() != null ? info.getStatus() : "",
                null,
                parseTags(info.getTags()));
    }

    /**
     * CourseSchedule + CourseInfo → ScheduleInfo 映射
     *
     * <p>P2-5 修复：schedule 字段按 DTO 契约（上课节奏描述）传授课模式的人类可读描述
     * （如 "线上授课"），不再传原始枚举（ONLINE/OFFLINE/HYBRID——LLM 会向学生传达
     * "上课节奏：OFFLINE" 的错误语义）。totalLessons 无数据源（course_schedule 无课时数字段），
     * 保持 0。
     */
    private ScheduleInfo toScheduleInfo(CourseSchedule schedule, CourseInfo info) {
        if (schedule == null) {
            return new ScheduleInfo(null, info.getDuration() != null ? info.getDuration() : "", 0, "");
        }
        String scheduleType = schedule.getScheduleType();
        String scheduleDesc = scheduleType != null ? SCHEDULE_TYPE_LABELS.getOrDefault(scheduleType, scheduleType) : "";
        return new ScheduleInfo(
                schedule.getStartDate() != null ? schedule.getStartDate().toString() : null,
                info.getDuration() != null ? info.getDuration() : "",
                0,
                scheduleDesc);
    }

    // ==================== 辅助方法 ====================

    /**
     * 格式化价格显示
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "";
        }
        return "\u00a5" + price.toPlainString();
    }

    /**
     * 解析 tags JSON 字符串为 List<String>
     */
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析课程标签失败: tags={}, error={}", tagsJson, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按内容类型从 CourseContent 列表中提取内容
     */
    private String extractContent(List<CourseContent> contents, String contentType) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        return contents.stream()
                .filter(c -> contentType.equals(c.getContentType()))
                .map(CourseContent::getContent)
                .findFirst()
                .orElse("");
    }

    /**
     * 构造空详情（课程不存在时使用）
     */
    private CourseDetailResult emptyDetail(String courseId) {
        return new CourseDetailResult(
                new CourseSummary(courseId, "", "", "", null, null, "", null, Collections.emptyList()),
                new ScheduleInfo(null, "", 0, ""),
                new InstructorInfo("", "", ""),
                "",
                "",
                "",
                "",
                "",
                Collections.emptyList());
    }
}
