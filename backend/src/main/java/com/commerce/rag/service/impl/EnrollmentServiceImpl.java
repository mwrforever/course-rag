package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
import com.commerce.rag.vo.StudentCourseVO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 选课管理服务 —— 封装 course_enrollment 表的 CRUD 操作
 *
 * <p>核心功能：
 * <ul>
 *   <li>查询课程已选学生列表</li>
 *   <li>批量添加学生选课</li>
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
