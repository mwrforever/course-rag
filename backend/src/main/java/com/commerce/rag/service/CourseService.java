package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.controller.dto.CourseDTO;
import com.commerce.rag.controller.dto.CreateCourseRequest;
import com.commerce.rag.controller.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 课程管理服务 —— 封装 course_info 及其关联表的 CRUD 操作
 *
 * <p>核心功能：
 * <ul>
 *   <li>课程基本信息 CRUD（course_info）</li>
 *   <li>课程内容 4 Tab 管理（course_content）</li>
 *   <li>授课教师多对多管理（course_teacher）</li>
 *   <li>级联软删：course_content + course_schedule + course_teacher + course_enrollment + document_chunk</li>
 * </ul>
 *
 * <p>权限控制：教师只能操作自己创建的课程（created_by 校验）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** 课程内容 4 个 Tab 类型及默认排序 */
    private static final Map<String, Integer> CONTENT_SORT_ORDER = Map.of(
            "intro", 0,
            "syllabus", 1,
            "instructor", 2,
            "faq", 3);

    private final CourseInfoMapper courseInfoMapper;
    private final CourseContentMapper courseContentMapper;
    private final CourseScheduleMapper courseScheduleMapper;
    private final CourseTeacherMapper courseTeacherMapper;
    private final CourseEnrollmentMapper courseEnrollmentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final EtlPipeline etlPipeline;

    // ==================== 课程基本信息 CRUD ====================

    /**
     * 创建课程
     *
     * @param request   创建请求
     * @param createdBy 创建者 ID
     * @return 课程实体（含雪花 ID）
     */
    public CourseInfo createCourse(CreateCourseRequest request, Long createdBy) {
        CourseInfo course = new CourseInfo();
        course.setTitle(request.title());
        course.setDescription(request.description());
        course.setCoverImage(request.coverImage());
        course.setCategory(request.category());
        course.setInstructorName(request.instructorName());
        course.setPrice(request.price());
        course.setDuration(request.duration());
        course.setTags(tagsToJson(request.tags()));
        course.setEnrollmentLink(request.enrollmentLink());
        course.setStatus("ACTIVE");
        course.setCreatedBy(createdBy);
        course.setRating(BigDecimal.ZERO);
        course.setLearningCount(0);
        courseInfoMapper.insert(course);
        log.info("创建课程: courseId={}, title={}, createdBy={}", course.getId(), course.getTitle(), createdBy);
        return course;
    }

    /**
     * 根据 ID 查询课程（不含关联数据）
     *
     * @param courseId 课程 ID
     * @return 课程实体，不存在返回 null
     */
    public CourseInfo findById(Long courseId) {
        return courseInfoMapper.selectById(courseId);
    }

    /**
     * 根据 ID 查询课程（含创建者过滤）
     *
     * <p>P0-2g：教师只能查看自己创建的课程（created_by 归属），超管不过滤。
     *
     * @param courseId        课程 ID
     * @param createdByFilter 创建者过滤（null=不过滤；非 null 且不匹配返回 null）
     * @return 课程实体，不存在或无权访问返回 null
     */
    public CourseInfo findById(Long courseId, Long createdByFilter) {
        CourseInfo course = courseInfoMapper.selectById(courseId);
        if (course == null) {
            return null;
        }
        // 归属校验：不匹配返回 null（controller 层 404，不泄露存在性）
        if (createdByFilter != null
                && (course.getCreatedBy() == null || !course.getCreatedBy().equals(createdByFilter))) {
            return null;
        }
        return course;
    }

    /**
     * 批量查询课程（仅 ACTIVE 状态），按创建时间降序
     *
     * @param courseIds 课程 ID 列表
     * @return 课程列表
     */
    public List<CourseInfo> findByIds(List<Long> courseIds) {
        if (courseIds == null || courseIds.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<CourseInfo> wrapper = new LambdaQueryWrapper<CourseInfo>()
                .in(CourseInfo::getId, courseIds)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .orderByDesc(CourseInfo::getCreatedAt);
        return courseInfoMapper.selectList(wrapper);
    }

    /**
     * 分页查询课程列表
     *
     * @param page      页码（1-based）
     * @param size      每页条数
     * @param category  分类筛选（可选）
     * @param keyword   标题关键词（可选）
     * @param createdBy 创建者筛选（null=全部，非 null=仅该创建者）
     * @return 分页结果
     */
    public IPage<CourseInfo> findPage(int page, int size, String category, String keyword, Long createdBy) {
        Page<CourseInfo> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<CourseInfo> wrapper = new LambdaQueryWrapper<CourseInfo>()
                .eq(StringUtils.hasText(category), CourseInfo::getCategory, category)
                .like(StringUtils.hasText(keyword), CourseInfo::getTitle, keyword)
                .eq(createdBy != null, CourseInfo::getCreatedBy, createdBy)
                .orderByDesc(CourseInfo::getCreatedAt);
        return courseInfoMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 更新课程信息
     *
     * @param courseId      课程 ID
     * @param request       更新请求
     * @param currentUserId 当前操作用户 ID（用于权限校验）
     */
    public void updateCourse(Long courseId, UpdateCourseRequest request, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);

        LambdaUpdateWrapper<CourseInfo> wrapper = new LambdaUpdateWrapper<CourseInfo>().eq(CourseInfo::getId, courseId);
        if (request.title() != null) wrapper.set(CourseInfo::getTitle, request.title());
        if (request.description() != null) wrapper.set(CourseInfo::getDescription, request.description());
        if (request.coverImage() != null) wrapper.set(CourseInfo::getCoverImage, request.coverImage());
        if (request.category() != null) wrapper.set(CourseInfo::getCategory, request.category());
        if (request.instructorName() != null) wrapper.set(CourseInfo::getInstructorName, request.instructorName());
        if (request.price() != null) wrapper.set(CourseInfo::getPrice, request.price());
        if (request.duration() != null) wrapper.set(CourseInfo::getDuration, request.duration());
        if (request.tags() != null) wrapper.set(CourseInfo::getTags, tagsToJson(request.tags()));
        if (request.enrollmentLink() != null) wrapper.set(CourseInfo::getEnrollmentLink, request.enrollmentLink());
        if (request.status() != null) wrapper.set(CourseInfo::getStatus, request.status());
        wrapper.set(CourseInfo::getUpdatedAt, LocalDateTime.now());
        courseInfoMapper.update(null, wrapper);
        log.info("更新课程: courseId={}, operator={}", courseId, currentUserId);
    }

    /**
     * 删除课程（级联软删）
     *
     * <p>级联影响：course_content + course_schedule + course_teacher + course_enrollment + document_chunk(课程专属)
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前操作用户 ID（用于权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    public void deleteCourse(Long courseId, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        long ts = System.currentTimeMillis();
        String courseIdStr = String.valueOf(courseId);

        // P1-4 Bug 2: 同步清理 Milvus 中该课程标注的向量（失败上抛阻断级联，
        // 避免 PG 已删而 Milvus 残留 → 学生端按 course_id 过滤仍命中已删课程内容）
        // P0-1 修复: Milvus 侧 course_id 标注与 PG 不同步（ETL 写死 DEFAULT、D5/D7 只改 PG），
        // 仅按 course_id 过滤删不到任何向量——先按 PG 未删 chunk（course_id=该课程）查 chunk_id，
        // 按 chunk_id IN 精确清理，再保留 ByCourseId 兜底（对已同步标注的历史数据幂等）
        List<DocumentChunk> courseChunks = documentChunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                .eq(DocumentChunk::getCourseId, courseIdStr)
                .select(DocumentChunk::getId));
        if (!courseChunks.isEmpty()) {
            etlPipeline.deleteFromMilvusByChunkIds(
                    courseChunks.stream().map(c -> String.valueOf(c.getId())).collect(Collectors.toList()));
        }
        etlPipeline.deleteFromMilvusByCourseId(courseIdStr);

        // 级联软删关联表（使用 MyBatis-Plus LambdaUpdateWrapper，遵循三层架构）
        courseContentMapper.update(
                null,
                new LambdaUpdateWrapper<CourseContent>()
                        .eq(CourseContent::getCourseId, courseId)
                        .eq(CourseContent::getDeleted, 0)
                        .set(CourseContent::getDeleted, ts));
        courseScheduleMapper.update(
                null,
                new LambdaUpdateWrapper<CourseSchedule>()
                        .eq(CourseSchedule::getCourseId, courseId)
                        .eq(CourseSchedule::getDeleted, 0)
                        .set(CourseSchedule::getDeleted, ts));
        courseTeacherMapper.update(
                null,
                new LambdaUpdateWrapper<CourseTeacher>()
                        .eq(CourseTeacher::getCourseId, courseId)
                        .eq(CourseTeacher::getDeleted, 0)
                        .set(CourseTeacher::getDeleted, ts));
        courseEnrollmentMapper.update(
                null,
                new LambdaUpdateWrapper<CourseEnrollment>()
                        .eq(CourseEnrollment::getCourseId, courseId)
                        .eq(CourseEnrollment::getDeleted, 0)
                        .set(CourseEnrollment::getDeleted, ts));
        // document_chunk：课程专属 chunk（course_id 为字符串类型）
        documentChunkMapper.update(
                null,
                new LambdaUpdateWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getCourseId, courseIdStr)
                        .eq(DocumentChunk::getDeleted, 0)
                        .set(DocumentChunk::getDeleted, ts));

        // 软删课程本身
        LambdaUpdateWrapper<CourseInfo> wrapper = new LambdaUpdateWrapper<CourseInfo>()
                .eq(CourseInfo::getId, courseId)
                .set(CourseInfo::getDeleted, ts)
                .set(CourseInfo::getUpdatedAt, LocalDateTime.now());
        courseInfoMapper.update(null, wrapper);
        log.info("级联软删课程: courseId={}, operator={}", courseId, currentUserId);
    }

    // ==================== 教师管理 ====================

    /**
     * 添加授课教师（批量，跳过已存在的）
     *
     * @param courseId      课程 ID
     * @param teacherIds    教师 ID 列表
     * @param currentUserId 当前操作用户 ID
     */
    public void addTeachers(Long courseId, List<Long> teacherIds, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        // 查询已存在的教师关联
        LambdaQueryWrapper<CourseTeacher> existWrapper = new LambdaQueryWrapper<CourseTeacher>()
                .eq(CourseTeacher::getCourseId, courseId)
                .in(CourseTeacher::getTeacherId, teacherIds);
        List<Long> existingTeacherIds = courseTeacherMapper.selectList(existWrapper).stream()
                .map(CourseTeacher::getTeacherId)
                .collect(Collectors.toList());

        // 插入新关联
        int added = 0;
        for (Long teacherId : teacherIds) {
            if (!existingTeacherIds.contains(teacherId)) {
                CourseTeacher ct = new CourseTeacher();
                ct.setCourseId(courseId);
                ct.setTeacherId(teacherId);
                courseTeacherMapper.insert(ct);
                added++;
            }
        }
        log.info("添加教师: courseId={}, teacherIds={}, added={}", courseId, teacherIds, added);
    }

    /**
     * 移除授课教师（批量软删）
     *
     * @param courseId      课程 ID
     * @param teacherIds    教师 ID 列表
     * @param currentUserId 当前操作用户 ID
     */
    public void removeTeachers(Long courseId, List<Long> teacherIds, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        LambdaUpdateWrapper<CourseTeacher> wrapper = new LambdaUpdateWrapper<CourseTeacher>()
                .eq(CourseTeacher::getCourseId, courseId)
                .in(CourseTeacher::getTeacherId, teacherIds)
                .set(CourseTeacher::getDeleted, System.currentTimeMillis());
        courseTeacherMapper.update(null, wrapper);
        log.info("移除教师: courseId={}, teacherIds={}", courseId, teacherIds);
    }

    /**
     * 查询课程的授课教师 ID 列表
     */
    public List<Long> findTeacherIds(Long courseId) {
        LambdaQueryWrapper<CourseTeacher> wrapper = new LambdaQueryWrapper<CourseTeacher>()
                .select(CourseTeacher::getTeacherId)
                .eq(CourseTeacher::getCourseId, courseId);
        return courseTeacherMapper.selectList(wrapper).stream()
                .map(CourseTeacher::getTeacherId)
                .collect(Collectors.toList());
    }

    // ==================== 课程内容管理 ====================

    /**
     * 查询课程的所有内容 Tab（按 sort_order 排序）
     */
    public List<CourseContent> findContents(Long courseId) {
        LambdaQueryWrapper<CourseContent> wrapper = new LambdaQueryWrapper<CourseContent>()
                .eq(CourseContent::getCourseId, courseId)
                .orderByAsc(CourseContent::getSortOrder);
        return courseContentMapper.selectList(wrapper);
    }

    /**
     * 更新单个 Tab 内容（不存在则创建）
     *
     * @param courseId      课程 ID
     * @param contentType   内容类型（intro / syllabus / instructor / faq）
     * @param content       Markdown 内容
     * @param currentUserId 当前操作用户 ID
     */
    public void updateContent(Long courseId, String contentType, String content, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        LambdaQueryWrapper<CourseContent> wrapper = new LambdaQueryWrapper<CourseContent>()
                .eq(CourseContent::getCourseId, courseId)
                .eq(CourseContent::getContentType, contentType);
        CourseContent existing = courseContentMapper.selectOne(wrapper);
        if (existing != null) {
            LambdaUpdateWrapper<CourseContent> updateWrapper = new LambdaUpdateWrapper<CourseContent>()
                    .eq(CourseContent::getId, existing.getId())
                    .set(CourseContent::getContent, content)
                    .set(CourseContent::getUpdatedAt, LocalDateTime.now());
            courseContentMapper.update(null, updateWrapper);
        } else {
            CourseContent cc = new CourseContent();
            cc.setCourseId(courseId);
            cc.setContentType(contentType);
            cc.setContent(content);
            cc.setSortOrder(CONTENT_SORT_ORDER.getOrDefault(contentType, 0));
            courseContentMapper.insert(cc);
        }
        log.info("更新课程内容: courseId={}, contentType={}", courseId, contentType);
    }

    /**
     * 批量更新全部 4 个 Tab 内容
     *
     * @param courseId      课程 ID
     * @param contents      内容列表
     * @param currentUserId 当前操作用户 ID
     */
    public void batchUpdateContents(
            Long courseId, List<CourseDTO.CourseContentDTO> contents, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        for (CourseDTO.CourseContentDTO dto : contents) {
            updateContent(courseId, dto.contentType(), dto.content(), currentUserId, isAdmin);
        }
        log.info("批量更新课程内容: courseId={}, tabCount={}", courseId, contents.size());
    }

    // ==================== 聚合查询 ====================

    /**
     * 查询课程排期列表
     */
    public List<CourseSchedule> findSchedules(Long courseId) {
        LambdaQueryWrapper<CourseSchedule> wrapper = new LambdaQueryWrapper<CourseSchedule>()
                .eq(CourseSchedule::getCourseId, courseId)
                .orderByAsc(CourseSchedule::getStartDate);
        return courseScheduleMapper.selectList(wrapper);
    }

    // ==================== DTO 转换 ====================

    /**
     * 将 CourseInfo + 关联数据转换为 CourseDTO
     *
     * @param course          课程实体
     * @param includeRelations 是否包含关联数据（内容、排期、教师）
     */
    public CourseDTO toDTO(CourseInfo course, boolean includeRelations) {
        List<CourseDTO.CourseContentDTO> contentDTOs = null;
        List<Long> teacherIds = null;
        List<com.commerce.rag.controller.dto.ScheduleDTO> scheduleDTOs = null;

        if (includeRelations) {
            List<CourseContent> contents = findContents(course.getId());
            contentDTOs = contents.stream()
                    .map(c -> new CourseDTO.CourseContentDTO(c.getContentType(), c.getContent(), c.getSortOrder()))
                    .collect(Collectors.toList());

            teacherIds = findTeacherIds(course.getId());

            List<CourseSchedule> schedules = findSchedules(course.getId());
            scheduleDTOs = schedules.stream()
                    .map(s -> new com.commerce.rag.controller.dto.ScheduleDTO(
                            s.getId(),
                            s.getCourseId(),
                            s.getStartDate(),
                            s.getEndDate(),
                            s.getScheduleType(),
                            s.getLocation(),
                            s.getInstructorName(),
                            s.getCapacity(),
                            s.getEnrolled(),
                            s.getStatus(),
                            s.getCreatedBy()))
                    .collect(Collectors.toList());
        }

        return new CourseDTO(
                course.getId(),
                course.getTitle(),
                course.getDescription(),
                course.getCoverImage(),
                course.getCategory(),
                course.getInstructorName(),
                course.getPrice(),
                course.getDuration(),
                parseTags(course.getTags()),
                course.getRating(),
                course.getLearningCount(),
                course.getEnrollmentLink(),
                course.getStatus(),
                course.getCreatedBy(),
                course.getCreatedAt(),
                contentDTOs,
                scheduleDTOs,
                teacherIds);
    }

    // ==================== 权限校验 ====================

    /**
     * 校验当前用户是否有权操作该课程。
     * 教师只能操作自己创建的课程；超管可操作全部（isAdmin 旁路）。
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前用户 ID
     * @param isAdmin       是否为超管（超管旁路，不校验 ownership）
     * @return 课程实体
     * @throws ResponseStatusException 课程不存在或无权操作
     */
    public CourseInfo checkOwnership(Long courseId, Long currentUserId, boolean isAdmin) {
        CourseInfo course = courseInfoMapper.selectById(courseId);
        if (course == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "课程不存在: " + courseId);
        }
        if (isAdmin) {
            return course;
        }
        if (!course.getCreatedBy().equals(currentUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此课程");
        }
        return course;
    }

    // ==================== 辅助方法 ====================

    /**
     * tags List → JSON 字符串
     */
    private String tagsToJson(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "[]";
        }
        try {
            return JSON_MAPPER.writeValueAsString(tags);
        } catch (Exception e) {
            log.warn("序列化标签失败: {}", tags);
            return "[]";
        }
    }

    /**
     * tags JSON 字符串 → List
     */
    private List<String> parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON_MAPPER.readValue(tagsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析标签失败: tags={}", tagsJson);
            return Collections.emptyList();
        }
    }
}
