package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.CourseConverter;
import com.commerce.rag.convert.PublicCourseConverter;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.CreateCourseRequest;
import com.commerce.rag.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.entity.CourseTeacher;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.mapper.CourseContentMapper;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.CourseInfoMapper;
import com.commerce.rag.mapper.CourseScheduleMapper;
import com.commerce.rag.mapper.CourseTeacherMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.properties.CourseProperties;
import com.commerce.rag.service.ICourseQueryService;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.service.ICourseTeacherService;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 课程管理服务 —— 封装 course_info 及其关联表的 CRUD 操作
 *
 * <p>核心功能：
 * <ul>
 *   <li>课程基本信息 CRUD（course_info；创建时同事务生成报名链接写回，更新忽略前端传入 enrollmentLink）</li>
 *   <li>公开课程列表/详情查询（C 端免登录，含 price 公开字段）</li>
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
public class CourseServiceImpl extends ServiceImpl<CourseInfoMapper, CourseInfo> implements ICourseService {

    private static final Logger log = LoggerFactory.getLogger(ICourseService.class);

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
    /** 课程-教师关联服务 —— addTeachers 批量插入（saveBatch）载体（P1-9） */
    private final ICourseTeacherService courseTeacherService;
    /** 公开课程转换器 —— C 端公开接口视图对象 */
    private final PublicCourseConverter publicCourseConverter;

    private final CourseEnrollmentMapper courseEnrollmentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final EtlPipeline etlPipeline;
    private final CourseConverter courseConverter;
    /** 课程查询服务（写后失效查询缓存，一致性铁律：先写 DB 后失效） */
    private final ICourseQueryService courseQueryService;

    /** Dashboard 统计缓存失效（Spring Cache 注解化的写方统一出口，先写 DB 后失效——一致性铁律） */
    private final DashboardCacheEvictor dashboardCacheEvictor;

    /** 课程域配置（报名链接生成基址 course.enroll-base-url） */
    private final CourseProperties courseProperties;

    // ==================== 课程基本信息 CRUD ====================

    /**
     * 创建课程（落库 + 同事务生成报名链接写回）
     *
     * <p>执行流程：insert 落库拿到雪花 ID → 同事务 {@code UPDATE course_info SET enrollment_link =
     * '{enrollBaseUrl}/courses/{id}'}（主表链式 this.lambdaUpdate()）→ 返回 DTO（enrollmentLink
     * 为服务端生成值）。
     *
     * <p>契约 A.2.2 行为变更：
     * <ul>
     *   <li>请求体中的 enrollmentLink 一律忽略（服务端管理字段，CreateCourseRequest 字段保留仅为
     *       兼容旧客户端，服务端不读）；</li>
     *   <li>方法加 {@code @Transactional}：insert 与链接写回同 commit，事务失败整体回滚不留空链接；</li>
     *   <li>缓存失效 evictCourse 挂 {@code TransactionSynchronization#afterCommit}（提交后失效）——
     *       避免加事务后同步调用发生在 commit 之前、出现「失效后-提交前并发读回填旧值」窗口
     *       （宪法 A.5.4 先写 DB 后失效的竞态边界）；无事务上下文时退化为直接失效（保持既有语义）。</li>
     * </ul>
     *
     * @param request   创建请求（enrollmentLink 字段被忽略）
     * @param createdBy 创建者 ID（来自认证上下文，不可空）
     * @return 课程 DTO（含雪花 ID 与服务端生成的 enrollmentLink，不含关联数据）
     */
    @Transactional
    public CourseDTO createCourse(CreateCourseRequest request, Long createdBy) {
        CourseInfo course = new CourseInfo();
        course.setTitle(request.title());
        course.setDescription(request.description());
        course.setCoverImage(request.coverImage());
        course.setCategory(request.category());
        course.setInstructorName(request.instructorName());
        course.setPrice(request.price());
        course.setDuration(request.duration());
        course.setTags(tagsToJson(request.tags()));
        // 契约 A.2.2：请求体 enrollmentLink 不再采信（落库留空，落库后由服务端生成写回）
        course.setStatus("ACTIVE");
        course.setCreatedBy(createdBy);
        course.setRating(BigDecimal.ZERO);
        course.setLearningCount(0);
        courseInfoMapper.insert(course);
        // 落库拿到雪花 ID 后，同事务生成报名链接写回（insert + update 同 commit，失败整体回滚）
        String enrollmentLink = buildEnrollmentLink(course.getId());
        this.lambdaUpdate()
                .eq(CourseInfo::getId, course.getId())
                .set(CourseInfo::getEnrollmentLink, enrollmentLink)
                .update();
        // 内存实体同步生成值，保证返回 DTO 与库内一致
        course.setEnrollmentLink(enrollmentLink);
        // 新建课程影响搜索列表可见性，失效该课程相关缓存键——afterCommit（提交后失效，见方法注释）
        evictCourseCacheAfterCommit(course.getId());
        log.info(
                "创建课程: courseId={}, title={}, createdBy={}, enrollmentLink={}",
                course.getId(),
                course.getTitle(),
                createdBy,
                enrollmentLink);
        return toDTO(course, false);
    }

    /**
     * 生成报名链接：{enrollBaseUrl}/courses/{courseId}
     *
     * <p>基址尾部斜杠归一化剥离（配置 {@code http://host/} 不产生 {@code //courses/} 双斜杠）。
     *
     * @param courseId 课程 ID（insert 后的雪花 ID，不可空）
     * @return 报名链接（如 {@code http://localhost:3000/courses/1948633200000000001}）
     */
    private String buildEnrollmentLink(Long courseId) {
        String base = courseProperties.enrollBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/courses/" + courseId;
    }

    /**
     * 注册缓存失效动作：事务提交后执行（afterCommit），无事务上下文时直接执行
     *
     * <p>一致性铁律（宪法 A.5.4：先写 DB → 后失效缓存）的时机收敛——失效必须发生在事务
     * commit 之后，否则存在「失效后-提交前并发读 miss 回填旧值」的脏读窗口（TTL 到期前
     * 旧数据持续命中）。createCourse（77751c4 先例）与 deleteCourse（BUG-05+PERF-02）共用本挂点。
     *
     * @param action 缓存失效动作（课程键失效 / dashboard 区失效等）
     */
    private void evictCacheAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务内：挂 afterCommit 回调，提交后失效（避免失效后-提交前并发读回填旧值窗口）
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            // 无事务上下文（如直接调用/单测）：保持既有同步失效语义
            action.run();
        }
    }

    /**
     * 注册课程查询缓存失效：事务提交后执行（afterCommit），无事务时直接执行
     *
     * @param courseId 课程 ID
     */
    private void evictCourseCacheAfterCommit(Long courseId) {
        evictCacheAfterCommit(() -> courseQueryService.evictCourse(courseId));
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
     * @return 课程 DTO（含内容/排期/教师关联），不存在或无权访问返回 null
     */
    public CourseDTO findById(Long courseId, Long createdByFilter) {
        CourseInfo course = courseInfoMapper.selectById(courseId);
        if (course == null) {
            return null;
        }
        // 归属校验：不匹配返回 null（controller 层 404，不泄露存在性）
        if (createdByFilter != null
                && (course.getCreatedBy() == null || !course.getCreatedBy().equals(createdByFilter))) {
            return null;
        }
        return toDTO(course, true);
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
        LambdaQueryWrapper<CourseInfo> wrapper = Wrappers.<CourseInfo>lambdaQuery()
                .in(CourseInfo::getId, courseIds)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .orderByDesc(CourseInfo::getCreatedAt);
        return courseInfoMapper.selectList(wrapper);
    }

    /**
     * 查询公开课程列表（C 端公开接口，未登录可访问）
     *
     * <p>仅返回 ACTIVE 状态课程（@TableLogic 自动过滤已删除），按评分降序；
     * 课程数据量小且低频，不做缓存——避免引入写路径失效链路
     * （课程增删改后前端重新拉取即得最新值）。
     *
     * <p>契约 C.2.1：投影补 price 列（价格转为 C 端公开展示字段，列表卡片展示）。
     *
     * @return 公开课程视图对象列表（仅对外信息字段，含价格）
     */
    public List<PublicCourseVO> findPublicCourses() {
        log.info("查询公开课程列表");
        // 本 service 主表操作：内置链式查询 + 精确投影（A.4.3 / A.4.4）
        List<CourseInfo> courses = this.lambdaQuery()
                .select(
                        CourseInfo::getId,
                        CourseInfo::getTitle,
                        CourseInfo::getDescription,
                        CourseInfo::getCoverImage,
                        CourseInfo::getCategory,
                        CourseInfo::getInstructorName,
                        CourseInfo::getDuration,
                        CourseInfo::getRating,
                        CourseInfo::getLearningCount,
                        CourseInfo::getPrice)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .orderByDesc(CourseInfo::getRating)
                .list();
        return courses.stream().map(publicCourseConverter::toVO).toList();
    }

    /**
     * 查询单个公开课程详情（C 端公开详情端点 GET /api/v1/public/courses/{id}）
     *
     * <p>执行流程：主表链式查询 + 精确投影（全详情字段），限定 id + status=ACTIVE
     * （@TableLogic 自动过滤已删除）→ 不存在即 404（课程不存在/已下架/已删除统一文案，不泄露存在性）
     * → 查询课程排期（course_schedule，开课日期升序，可为空列表）→ MapStruct 转公开详情 VO。
     *
     * @param courseId 课程 ID（路径参数，不可空）
     * @return 公开课程详情 VO（含 price 与 schedules 排期列表）
     * @throws BizException 404 课程不存在、已逻辑删除或 status 非 ACTIVE
     */
    public PublicCourseDetailVO findPublicCourseById(Long courseId) {
        log.info("查询公开课程详情: courseId={}", courseId);
        CourseInfo course = this.lambdaQuery()
                .select(
                        CourseInfo::getId,
                        CourseInfo::getTitle,
                        CourseInfo::getDescription,
                        CourseInfo::getCoverImage,
                        CourseInfo::getCategory,
                        CourseInfo::getInstructorName,
                        CourseInfo::getDuration,
                        CourseInfo::getRating,
                        CourseInfo::getLearningCount,
                        CourseInfo::getPrice)
                .eq(CourseInfo::getId, courseId)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .one();
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在或已下架");
        }
        // 排期列表：副表 course_schedule 按开课日期升序（可为空，C 端详情页按空态展示）
        List<CourseSchedule> schedules = findSchedules(courseId);
        return publicCourseConverter.toDetailVO(course, schedules);
    }

    /**
     * 分页查询课程列表
     *
     * @param page      页码（1-based）
     * @param size      每页条数
     * @param category  分类筛选（可选）
     * @param keyword   标题关键词（可选）
     * @param createdBy 创建者筛选（null=全部，非 null=仅该创建者）
     * @return 分页结果（records 为课程 DTO，不含关联数据）
     */
    public IPage<CourseDTO> findPage(int page, int size, String category, String keyword, Long createdBy) {
        Page<CourseInfo> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<CourseInfo> wrapper = Wrappers.<CourseInfo>lambdaQuery()
                .eq(StringUtils.hasText(category), CourseInfo::getCategory, category)
                .like(StringUtils.hasText(keyword), CourseInfo::getTitle, keyword)
                .eq(createdBy != null, CourseInfo::getCreatedBy, createdBy)
                .orderByDesc(CourseInfo::getCreatedAt);
        IPage<CourseInfo> entityPage = courseInfoMapper.selectPage(pageObj, wrapper);
        // 实体分页 → DTO 分页：records 逐条转换，total/current/size 分页语义保持
        Page<CourseDTO> dtoPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        dtoPage.setRecords(
                entityPage.getRecords().stream().map(c -> toDTO(c, false)).collect(Collectors.toList()));
        return dtoPage;
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

        LambdaUpdateWrapper<CourseInfo> wrapper =
                Wrappers.<CourseInfo>lambdaUpdate().eq(CourseInfo::getId, courseId);
        if (request.title() != null) wrapper.set(CourseInfo::getTitle, request.title());
        if (request.description() != null) wrapper.set(CourseInfo::getDescription, request.description());
        if (request.coverImage() != null) wrapper.set(CourseInfo::getCoverImage, request.coverImage());
        if (request.category() != null) wrapper.set(CourseInfo::getCategory, request.category());
        if (request.instructorName() != null) wrapper.set(CourseInfo::getInstructorName, request.instructorName());
        if (request.price() != null) wrapper.set(CourseInfo::getPrice, request.price());
        if (request.duration() != null) wrapper.set(CourseInfo::getDuration, request.duration());
        if (request.tags() != null) wrapper.set(CourseInfo::getTags, tagsToJson(request.tags()));
        // 契约 A.2.3：enrollmentLink 更新分支已删除——报名链接为服务端管理字段（创建时生成），
        // 更新接口传入 enrollmentLink 一律不生效
        if (request.status() != null) wrapper.set(CourseInfo::getStatus, request.status());
        wrapper.set(CourseInfo::getUpdatedAt, LocalDateTime.now());
        courseInfoMapper.update(null, wrapper);
        // 课程信息变更影响详情与搜索排序，失效该课程相关缓存键（先写 DB 后失效）
        courseQueryService.evictCourse(courseId);
        log.info("更新课程: courseId={}, operator={}", courseId, currentUserId);
    }

    /**
     * 删除课程（级联软删）
     *
     * <p>级联影响：course_content + course_schedule + course_teacher + course_enrollment + document_chunk(课程专属)
     *
     * <p>B2-5 事务说明：六条软删 UPDATE（content→schedule→teacher→enrollment→chunk→course）在同一事务内
     * 原子执行，中途失败整体回滚，避免留下"课程已删而排期/选课仍 active"的跨表中间态。
     * Milvus 清理位于事务最前段：其失败时事务内尚无任何 PG 写、回滚零代价；外部资源先行 + 幂等删除
     * 的既有重试收敛语义保持不变（事务注解不改变既有执行顺序）。
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前操作用户 ID（用于权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    @Transactional
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
                Wrappers.<CourseContent>lambdaUpdate()
                        .eq(CourseContent::getCourseId, courseId)
                        .eq(CourseContent::getDeleted, 0)
                        .set(CourseContent::getDeleted, ts));
        courseScheduleMapper.update(
                null,
                Wrappers.<CourseSchedule>lambdaUpdate()
                        .eq(CourseSchedule::getCourseId, courseId)
                        .eq(CourseSchedule::getDeleted, 0)
                        .set(CourseSchedule::getDeleted, ts));
        courseTeacherMapper.update(
                null,
                Wrappers.<CourseTeacher>lambdaUpdate()
                        .eq(CourseTeacher::getCourseId, courseId)
                        .eq(CourseTeacher::getDeleted, 0)
                        .set(CourseTeacher::getDeleted, ts));
        courseEnrollmentMapper.update(
                null,
                Wrappers.<CourseEnrollment>lambdaUpdate()
                        .eq(CourseEnrollment::getCourseId, courseId)
                        .eq(CourseEnrollment::getDeleted, 0)
                        .set(CourseEnrollment::getDeleted, ts));
        // document_chunk：课程专属 chunk（course_id 为字符串类型）
        documentChunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getCourseId, courseIdStr)
                        .eq(DocumentChunk::getDeleted, 0)
                        .set(DocumentChunk::getDeleted, ts));

        // 软删课程本身
        LambdaUpdateWrapper<CourseInfo> wrapper = Wrappers.<CourseInfo>lambdaUpdate()
                .eq(CourseInfo::getId, courseId)
                .set(CourseInfo::getDeleted, ts)
                .set(CourseInfo::getUpdatedAt, LocalDateTime.now());
        courseInfoMapper.update(null, wrapper);
        // 级联软删后课程详情/内容/排期均不可见，失效该课程相关缓存键 + dashboard 统计缓存
        // （BUG-05+PERF-02：失效挂 afterCommit——提交后失效，消除「失效后-提交前并发读 miss
        // 回填未删除旧值」的脏读窗口；失效内容不变仅时机后移，TTL 兜底不变）
        evictCacheAfterCommit(() -> {
            courseQueryService.evictCourse(courseId);
            // 统计失效：课程专属 PENDING 分片已软删，影响 pendingChunkCount（M-2 新增项）
            dashboardCacheEvictor.evictAll();
        });
        log.info("级联软删课程: courseId={}, operator={}", courseId, currentUserId);
    }

    // ==================== 教师管理 ====================

    /**
     * 添加授课教师（批量，跳过已存在的）
     *
     * <p>P1-9 事务与批处理说明：新增关联经 ICourseTeacherService.saveBatch 一次批插
     * （JDBC 批处理 + 自动填充雪花 ID），替代原逐条 insert（N 教师 N 次 SQL）；
     * saveBatch 须在事务内调用（宪法：批处理整体原子性），本方法以 @Transactional 保证。
     *
     * @param courseId      课程 ID
     * @param teacherIds    教师 ID 列表
     * @param currentUserId 当前操作用户 ID
     */
    @Transactional
    public void addTeachers(Long courseId, List<Long> teacherIds, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        // 查询已存在的教师关联（P1-9: 按需投影——仅取 teacher_id 列，@TableLogic 自动过滤已删关联）
        LambdaQueryWrapper<CourseTeacher> existWrapper = Wrappers.<CourseTeacher>lambdaQuery()
                .select(CourseTeacher::getTeacherId)
                .eq(CourseTeacher::getCourseId, courseId)
                .in(CourseTeacher::getTeacherId, teacherIds);
        List<Long> existingTeacherIds = courseTeacherMapper.selectList(existWrapper).stream()
                .map(CourseTeacher::getTeacherId)
                .collect(Collectors.toList());

        // 收集待新增关联（先查重的幂等语义保持），空集合不触发批插
        List<CourseTeacher> toCreate = teacherIds.stream()
                .filter(teacherId -> !existingTeacherIds.contains(teacherId))
                .map(teacherId -> {
                    CourseTeacher ct = new CourseTeacher();
                    ct.setCourseId(courseId);
                    ct.setTeacherId(teacherId);
                    return ct;
                })
                .collect(Collectors.toList());
        if (!toCreate.isEmpty()) {
            try {
                // 单次批插（事务内，N 条 → 1 次批处理 SQL 往返）
                courseTeacherService.saveBatch(toCreate);
            } catch (DataIntegrityViolationException e) {
                // B2-8: check-then-insert 竞态兜底——并发添加相同教师时查重双双落空、后插入者撞
                // uniq_course_teacher(course_id, teacher_id)，转 409 而非全局 503
                log.warn("并发添加授课教师冲突: courseId={}, teacherIds={}", courseId, teacherIds);
                throw new BizException(ErrorCode.CONFLICT, "授课教师已存在（并发操作冲突），请刷新后重试", e);
            }
        }
        int added = toCreate.size();
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
        LambdaUpdateWrapper<CourseTeacher> wrapper = Wrappers.<CourseTeacher>lambdaUpdate()
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
        LambdaQueryWrapper<CourseTeacher> wrapper = Wrappers.<CourseTeacher>lambdaQuery()
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
        LambdaQueryWrapper<CourseContent> wrapper = Wrappers.<CourseContent>lambdaQuery()
                .eq(CourseContent::getCourseId, courseId)
                .orderByAsc(CourseContent::getSortOrder);
        return courseContentMapper.selectList(wrapper);
    }

    /**
     * 更新单个 Tab 内容（不存在则创建）
     *
     * <p>单 Tab 入口：归属校验 + 落库 + 缓存失效齐全；批量路径走
     * {@link #batchUpdateContents}（校验/失效已收敛，循环内直调 {@link #doUpdateContent}）。
     *
     * @param courseId      课程 ID
     * @param contentType   内容类型（intro / syllabus / instructor / faq）
     * @param content       Markdown 内容
     * @param currentUserId 当前操作用户 ID
     */
    public void updateContent(Long courseId, String contentType, String content, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        doUpdateContent(courseId, contentType, content);
        // 内容变更影响学生端内容读取，失效该课程相关缓存键（先写 DB 后失效）
        courseQueryService.evictCourse(courseId);
        log.info("更新课程内容: courseId={}, contentType={}", courseId, contentType);
    }

    /**
     * 单个 Tab 内容落库（存在则更新、不存在则创建）——无归属校验、无缓存失效。
     *
     * <p>PERF-22（2026-08-31）：从 updateContent 提取的纯落库内核，供批量路径循环调用，
     * 将「每 Tab 一次校验 + 一次失效」收敛为「循环外校验 1 次 + 循环后失效 1 次」；
     * 校验/失效时机语义由调用方负责（updateContent 单 Tab 失效紧跟落库；
     * batchUpdateContents 全部落库后统一失效——本方法非事务 autocommit 多语句路径，
     * 与既有失效时机语义一致，见审核 §B.5）。
     *
     * @param courseId    课程 ID（归属校验已由调用方完成）
     * @param contentType 内容类型（intro / syllabus / instructor / faq）
     * @param content     Markdown 内容
     */
    private void doUpdateContent(Long courseId, String contentType, String content) {
        LambdaQueryWrapper<CourseContent> wrapper = Wrappers.<CourseContent>lambdaQuery()
                .eq(CourseContent::getCourseId, courseId)
                .eq(CourseContent::getContentType, contentType);
        CourseContent existing = courseContentMapper.selectOne(wrapper);
        if (existing != null) {
            LambdaUpdateWrapper<CourseContent> updateWrapper = Wrappers.<CourseContent>lambdaUpdate()
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
            try {
                courseContentMapper.insert(cc);
            } catch (DataIntegrityViolationException e) {
                // B2-8: check-then-insert 竞态兜底——并发首插双双 selectOne 落空时后者撞
                // uniq_course_content_type(course_id, content_type)，转 409 而非全局 503
                log.warn("并发创建课程内容冲突: courseId={}, contentType={}", courseId, contentType);
                throw new BizException(ErrorCode.CONFLICT, "课程内容已存在（并发操作冲突），请刷新后重试", e);
            }
        }
    }

    /**
     * 批量更新全部 4 个 Tab 内容
     *
     * <p>PERF-22（2026-08-31）：归属校验收敛到循环外 1 次、缓存失效收敛到全部落库后 1 次
     * （原每 Tab 各一次校验+失效，4 Tab 请求 4 次冗余主键查询 + 4 次冗余 evict——每次
     * evict 含 SCAN 前缀删除）；先写 DB 后失效顺序语义不变。
     *
     * @param courseId      课程 ID
     * @param contents      内容列表
     * @param currentUserId 当前操作用户 ID
     */
    public void batchUpdateContents(
            Long courseId, List<CourseDTO.CourseContentDTO> contents, Long currentUserId, boolean isAdmin) {
        checkOwnership(courseId, currentUserId, isAdmin);
        for (CourseDTO.CourseContentDTO dto : contents) {
            doUpdateContent(courseId, dto.contentType(), dto.content());
        }
        // 全部 Tab 落库后按 courseId 统一失效（先写 DB 后失效；批内中途被缓存的读最终一致）
        courseQueryService.evictCourse(courseId);
        log.info("批量更新课程内容: courseId={}, tabCount={}", courseId, contents.size());
    }

    // ==================== 聚合查询 ====================

    /**
     * 查询课程排期列表
     */
    public List<CourseSchedule> findSchedules(Long courseId) {
        LambdaQueryWrapper<CourseSchedule> wrapper = Wrappers.<CourseSchedule>lambdaQuery()
                .eq(CourseSchedule::getCourseId, courseId)
                .orderByAsc(CourseSchedule::getStartDate);
        return courseScheduleMapper.selectList(wrapper);
    }

    // ==================== DTO 转换 ====================

    /**
     * 将 CourseInfo + 关联数据转换为 CourseDTO
     *
     * @param course           课程实体
     * @param includeRelations 是否包含关联数据（内容、排期、教师）
     */
    public CourseDTO toDTO(CourseInfo course, boolean includeRelations) {
        // 关联数据查询留在 service，转换器只做纯映射（includeRelations=false 时传空列表）
        List<CourseContent> contents = List.of();
        List<CourseSchedule> schedules = List.of();
        List<Long> teacherIds = List.of();
        if (includeRelations) {
            contents = findContents(course.getId());
            schedules = findSchedules(course.getId());
            teacherIds = findTeacherIds(course.getId());
        }
        return courseConverter.toDTO(course, contents, schedules, teacherIds);
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
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在: " + courseId);
        }
        if (isAdmin) {
            return course;
        }
        if (!course.getCreatedBy().equals(currentUserId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作此课程");
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
}
