package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.cache.PublicCourseCacheEvictor;
import com.commerce.rag.convert.EnrollmentConverter;
import com.commerce.rag.convert.StudentConverter;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import com.commerce.rag.mapper.SysUserMapper;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.service.IEnrollmentService;
import com.commerce.rag.vo.CoursePurchaseVO;
import com.commerce.rag.vo.StudentCourseVO;
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

/**
 * 选课管理服务 —— 封装 course_enrollment 表的 CRUD 与学生自助购买操作
 *
 * <p>核心功能：
 * <ul>
 *   <li>查询课程已选学生列表</li>
 *   <li>批量添加学生选课</li>
 *   <li>学生自助购买课程（幂等：已购直接成功 / 退课重激活 / 并发撞唯一索引按已购返回）</li>
 *   <li>移除学生（软删选课记录）</li>
 *   <li>查询学生已选课程列表</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class EnrollmentServiceImpl extends ServiceImpl<CourseEnrollmentMapper, CourseEnrollment>
        implements IEnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(IEnrollmentService.class);

    private final CourseEnrollmentMapper enrollmentMapper;
    private final ICourseService courseService;
    private final SysUserMapper sysUserMapper;
    private final EnrollmentConverter enrollmentConverter;
    /** 学生端转换器 —— 学生课程视图对象转换（toCourseVO），转换器跨层共用合法 */
    private final StudentConverter studentConverter;
    /** 公开课程缓存失效（M9/PERF-21：购买/选课可见性变更后 afterCommit 清空 publicCourses 缓存区） */
    private final PublicCourseCacheEvictor publicCourseCacheEvictor;

    /**
     * 查询课程的已选学生列表
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     * @return 学生 DTO 列表
     */
    public List<StudentDTO> findStudents(Long courseId, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);
        List<CourseEnrollment> enrollments = enrollmentMapper.selectList(Wrappers.<CourseEnrollment>lambdaQuery()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStatus, "ACTIVE")
                .orderByDesc(CourseEnrollment::getEnrolledAt));
        if (enrollments.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds =
                enrollments.stream().map(CourseEnrollment::getStudentId).toList();
        List<SysUser> users = sysUserMapper.selectByIdsIn(studentIds);
        Map<Long, CourseEnrollment> enrollmentByUser =
                enrollments.stream().collect(Collectors.toMap(CourseEnrollment::getStudentId, e -> e));
        List<StudentDTO> students = users.stream()
                .map(user -> enrollmentConverter.toDTO(user, enrollmentByUser.get(user.getId())))
                .toList();
        log.info("查询选课学生: courseId={}, count={}", courseId, students.size());
        return students;
    }

    /**
     * 批量添加学生选课（跳过已选的）
     *
     * <p>saveBatch 须在事务内调用（宪法：JDBC 批处理整体原子性）。
     *
     * @param courseId      课程 ID
     * @param studentIds    学生 ID 列表
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     * @return 实际新增选课数
     */
    @Transactional
    public int addStudents(Long courseId, List<Long> studentIds, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);

        // 查询已存在的选课记录（包含已退课的，复用记录）
        LambdaQueryWrapper<CourseEnrollment> existWrapper = Wrappers.<CourseEnrollment>lambdaQuery()
                .eq(CourseEnrollment::getCourseId, courseId)
                .in(CourseEnrollment::getStudentId, studentIds);
        List<CourseEnrollment> existing = enrollmentMapper.selectList(existWrapper);
        // 已存在且活跃的学生 ID
        List<Long> activeStudentIds = existing.stream()
                .filter(e -> "ACTIVE".equals(e.getStatus()))
                .map(CourseEnrollment::getStudentId)
                .collect(Collectors.toList());
        // 已退课的学生 ID（需要重新激活）
        List<Long> droppedStudentIds = existing.stream()
                .filter(e -> "DROPPED".equals(e.getStatus()))
                .map(CourseEnrollment::getStudentId)
                .collect(Collectors.toList());

        // L-3: 分离「待激活（已退课）」与「新建」集合，分别批量 UPDATE 与 saveBatch
        // （原逐条 UPDATE + 逐条 INSERT，N 条 SQL）
        List<Long> toReactivate =
                studentIds.stream().filter(droppedStudentIds::contains).toList();
        if (!toReactivate.isEmpty()) {
            enrollmentMapper.update(
                    null,
                    Wrappers.<CourseEnrollment>lambdaUpdate()
                            .eq(CourseEnrollment::getCourseId, courseId)
                            .in(CourseEnrollment::getStudentId, toReactivate)
                            .set(CourseEnrollment::getStatus, "ACTIVE")
                            .set(CourseEnrollment::getEnrolledAt, LocalDateTime.now()));
        }
        // 新建集合：既非活跃也非退课（完全新选课）
        List<CourseEnrollment> toCreate = studentIds.stream()
                .filter(s -> !activeStudentIds.contains(s) && !droppedStudentIds.contains(s))
                .map(s -> {
                    CourseEnrollment enrollment = new CourseEnrollment();
                    enrollment.setCourseId(courseId);
                    enrollment.setStudentId(s);
                    enrollment.setEnrolledAt(LocalDateTime.now());
                    enrollment.setStatus("ACTIVE");
                    return enrollment;
                })
                .toList();
        if (!toCreate.isEmpty()) {
            // 本 service 主表：saveBatch（JDBC 批处理，自动填充雪花 ID）
            this.saveBatch(toCreate);
        }
        int added = toReactivate.size() + toCreate.size();
        // M9 公开课程缓存失效：批量选课变更购买可见性相关数据（事务内写库完成后挂 afterCommit 失效）
        publicCourseCacheEvictor.evictAllAfterCommit();
        log.info("批量添加选课: courseId={}, requested={}, added={}", courseId, studentIds.size(), added);
        return added;
    }

    /**
     * 移除学生（软删选课记录，status → DROPPED）
     *
     * @param courseId      课程 ID
     * @param studentId     学生 ID
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    public void removeStudent(Long courseId, Long studentId, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);

        LambdaUpdateWrapper<CourseEnrollment> wrapper = Wrappers.<CourseEnrollment>lambdaUpdate()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStudentId, studentId)
                .set(CourseEnrollment::getStatus, "DROPPED");
        int rows = enrollmentMapper.update(null, wrapper);
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "选课记录不存在");
        }
        // M9 公开课程缓存失效：退课变更购买可见性相关数据（先写 DB 后失效）
        publicCourseCacheEvictor.evictAllAfterCommit();
        log.info("移除学生: courseId={}, studentId={}, operator={}", courseId, studentId, currentUserId);
    }

    /**
     * 查询学生已选课程列表（不含关联数据，与选课列表接口契约一致）
     *
     * @param studentId 学生 ID
     * @return 课程 DTO 列表
     */
    public List<CourseDTO> findStudentCoursesAsDTO(Long studentId) {
        return findStudentCourses(studentId).stream()
                .map(c -> courseService.toDTO(c, false))
                .toList();
    }

    /**
     * 查询学生已选课程列表（C 端视图对象，供 StudentController 使用）
     *
     * @param studentId 学生 ID
     * @return 学生课程视图对象列表（剔除价格/描述等内部字段）
     */
    public List<StudentCourseVO> findStudentCoursesAsVO(Long studentId) {
        // 复用实体查询（批量 in 查询课程），再逐条转 C 端视图对象
        return findStudentCourses(studentId).stream()
                .map(studentConverter::toCourseVO)
                .toList();
    }

    /**
     * 学生自助购买课程（C 端购买端点，契约 B.2）
     *
     * <p>执行流程（@Transactional 事务内）：
     * <ol>
     *   <li>查课程（仅取 id/status 投影，限定 ACTIVE）：不存在/已软删/非 ACTIVE → 404
     *       （统一文案「课程不存在或已下架」，不泄露存在性）；</li>
     *   <li>查选课记录（course_id + student_id，@TableLogic 自动过滤已删，部分唯一索引保证至多一条）：
     *       无记录 → 插入 ACTIVE 记录（对齐 addStudents 构造语义）；DROPPED → 重激活
     *       （置 ACTIVE + enrolledAt=now）；ACTIVE → 幂等直接返回成功，不产生任何写；</li>
     *   <li>并发兜底：check-then-insert 撞 uniq_course_enrollment(course_id, student_id)
     *       WHERE deleted=0 部分唯一索引时捕获 DataIntegrityViolationException——
     *       <b>契约 B.2.3 实现注记</b>：DIVE 抛出时当前事务已 rollback-only，
     *       <b>禁止在原事务内重查</b>（会触发 UnexpectedRollbackException）；撞索引必然意味着
     *       (course_id, student_id, deleted=0) 记录已存在且购买/addStudents 写入路径只会写
     *       ACTIVE，故直接构造成功 VO 返回（不再重查），事务自然回滚（无任何有效写丢失）。</li>
     * </ol>
     *
     * <p>契约 D2：不递增 learning_count（与 addStudents 行为一致，该列当前无任何业务写路径）。
     *
     * @param courseId  课程 ID（路径参数，来自用户输入）
     * @param studentId 学生 ID（认证上下文取，禁止入参传递）
     * @return 购买结果 VO（status 恒 ACTIVE、purchased 恒 true）
     * @throws BizException 404 课程不存在、已删除或已下架
     */
    @Transactional
    public CoursePurchaseVO purchaseCourse(Long courseId, Long studentId) {
        // TODO(purchase-business): 开发环境购买直接通过（不校验支付），补全支付/审批业务时移除（用户 2026-08-29 拍板）
        log.info("学生购买课程: courseId={}, userId={}", courseId, studentId);
        // 查课程（跨 service 链式查询 + 精确投影，仅取存在性与状态）：非 ACTIVE/已删/不存在统一 404
        CourseInfo course = courseService
                .lambdaQuery()
                .select(CourseInfo::getId, CourseInfo::getStatus)
                .eq(CourseInfo::getId, courseId)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .one();
        if (course == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在或已下架");
        }
        // 查选课记录（本 service 主表链式查询；部分唯一索引保证 (course_id, student_id, deleted=0) 至多一条）
        CourseEnrollment existing = this.lambdaQuery()
                .select(CourseEnrollment::getId, CourseEnrollment::getStatus)
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStudentId, studentId)
                .one();
        if (existing == null) {
            // 无记录 → 插入 ACTIVE（对齐 addStudents 构造语义：enrolledAt=now、status=ACTIVE）
            CourseEnrollment enrollment = new CourseEnrollment();
            enrollment.setCourseId(courseId);
            enrollment.setStudentId(studentId);
            enrollment.setEnrolledAt(LocalDateTime.now());
            enrollment.setStatus("ACTIVE");
            try {
                this.save(enrollment);
                log.info("学生购买课程成功（新插入选课记录）: courseId={}, userId={}", courseId, studentId);
            } catch (DataIntegrityViolationException e) {
                // 契约 B.2.3：并发撞部分唯一索引——购买幂等语义优先（区别于 addTeachers 的 409 策略，
                // 购买重试是用户合理行为）；事务已 rollback-only，禁止重查，直接构造成功 VO
                log.warn("学生购买课程并发冲突（按已购幂等返回成功）: courseId={}, userId={}", courseId, studentId);
                return new CoursePurchaseVO(courseId, "ACTIVE", true);
            }
        } else if ("DROPPED".equals(existing.getStatus())) {
            // 已退课 → 重激活（置 ACTIVE + enrolledAt=now，对齐 addStudents 重激活语义；UPDATE 不受唯一索引影响）
            this.lambdaUpdate()
                    .eq(CourseEnrollment::getId, existing.getId())
                    .set(CourseEnrollment::getStatus, "ACTIVE")
                    .set(CourseEnrollment::getEnrolledAt, LocalDateTime.now())
                    .update();
            log.info("学生购买课程成功（重激活退课记录）: courseId={}, userId={}", courseId, studentId);
        } else {
            // 已 ACTIVE → 幂等直接返回成功，不产生任何写（重复调用不重复插行）
            log.info("学生购买课程幂等命中（已购）: courseId={}, userId={}", courseId, studentId);
        }
        // M9 公开课程缓存失效：购买变更选课数据（事务内写库完成后挂 afterCommit 失效；幂等命中
        // 分支无写库，多一次 clear 无害——公开缓存区重建代价低，保持写路径统一出口）
        publicCourseCacheEvictor.evictAllAfterCommit();
        return new CoursePurchaseVO(courseId, "ACTIVE", true);
    }

    /**
     * 查询学生已选课程列表
     *
     * @param studentId 学生 ID
     * @return 课程信息列表
     */
    public List<CourseInfo> findStudentCourses(Long studentId) {
        // 查询学生活跃的选课记录
        LambdaQueryWrapper<CourseEnrollment> wrapper = Wrappers.<CourseEnrollment>lambdaQuery()
                .eq(CourseEnrollment::getStudentId, studentId)
                .eq(CourseEnrollment::getStatus, "ACTIVE");
        List<CourseEnrollment> enrollments = enrollmentMapper.selectList(wrapper);

        if (enrollments.isEmpty()) {
            return List.of();
        }

        // 批量查询课程信息
        List<Long> courseIds =
                enrollments.stream().map(CourseEnrollment::getCourseId).collect(Collectors.toList());
        return courseService.findByIds(courseIds);
    }

    /**
     * 检查学生是否已选某课程
     *
     * @param courseId  课程 ID
     * @param studentId 学生 ID
     * @return true=已选
     */
    public boolean isEnrolled(Long courseId, Long studentId) {
        LambdaQueryWrapper<CourseEnrollment> wrapper = Wrappers.<CourseEnrollment>lambdaQuery()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStudentId, studentId)
                .eq(CourseEnrollment::getStatus, "ACTIVE");
        return enrollmentMapper.selectCount(wrapper) > 0;
    }
}
