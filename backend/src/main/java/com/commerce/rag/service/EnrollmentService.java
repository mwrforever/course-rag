package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.commerce.rag.controller.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.mapper.CourseEnrollmentMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    private final CourseEnrollmentMapper enrollmentMapper;
    private final CourseService courseService;
    private final JdbcTemplate jdbcTemplate;

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

        // 查询选课记录
        LambdaQueryWrapper<CourseEnrollment> wrapper = new LambdaQueryWrapper<CourseEnrollment>()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStatus, "ACTIVE")
                .orderByDesc(CourseEnrollment::getEnrolledAt);
        List<CourseEnrollment> enrollments = enrollmentMapper.selectList(wrapper);

        if (enrollments.isEmpty()) {
            return List.of();
        }

        // 批量查询学生信息（sys_user 表，由 auth 工程师负责，此处用 JdbcTemplate）
        List<Long> studentIds =
                enrollments.stream().map(CourseEnrollment::getStudentId).collect(Collectors.toList());
        String placeholders = studentIds.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql =
                "SELECT id, username, display_name FROM sys_user WHERE id IN (" + placeholders + ") AND deleted = 0";

        List<StudentDTO> students = jdbcTemplate.query(sql, studentIds.toArray(), (rs, rowNum) -> {
            Long studentId = rs.getLong("id");
            // 匹配对应的 enrollment
            CourseEnrollment enrollment = enrollments.stream()
                    .filter(e -> e.getStudentId().equals(studentId))
                    .findFirst()
                    .orElse(null);
            return new StudentDTO(
                    studentId,
                    rs.getString("username"),
                    rs.getString("display_name"),
                    enrollment != null ? enrollment.getEnrolledAt() : null,
                    enrollment != null ? enrollment.getStatus() : null);
        });

        log.info("查询选课学生: courseId={}, count={}", courseId, students.size());
        return students;
    }

    /**
     * 批量添加学生选课（跳过已选的）
     *
     * @param courseId      课程 ID
     * @param studentIds    学生 ID 列表
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     * @return 实际新增选课数
     */
    public int addStudents(Long courseId, List<Long> studentIds, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);

        // 查询已存在的选课记录（包含已退课的，复用记录）
        LambdaQueryWrapper<CourseEnrollment> existWrapper = new LambdaQueryWrapper<CourseEnrollment>()
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

        int added = 0;
        for (Long studentId : studentIds) {
            if (activeStudentIds.contains(studentId)) {
                continue; // 已选课，跳过
            }
            if (droppedStudentIds.contains(studentId)) {
                // 重新激活退课记录
                LambdaUpdateWrapper<CourseEnrollment> updateWrapper = new LambdaUpdateWrapper<CourseEnrollment>()
                        .eq(CourseEnrollment::getCourseId, courseId)
                        .eq(CourseEnrollment::getStudentId, studentId)
                        .set(CourseEnrollment::getStatus, "ACTIVE")
                        .set(CourseEnrollment::getEnrolledAt, LocalDateTime.now());
                enrollmentMapper.update(null, updateWrapper);
            } else {
                // 新建选课记录
                CourseEnrollment enrollment = new CourseEnrollment();
                enrollment.setCourseId(courseId);
                enrollment.setStudentId(studentId);
                enrollment.setEnrolledAt(LocalDateTime.now());
                enrollment.setStatus("ACTIVE");
                enrollmentMapper.insert(enrollment);
            }
            added++;
        }
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

        LambdaUpdateWrapper<CourseEnrollment> wrapper = new LambdaUpdateWrapper<CourseEnrollment>()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStudentId, studentId)
                .set(CourseEnrollment::getStatus, "DROPPED");
        int rows = enrollmentMapper.update(null, wrapper);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "选课记录不存在");
        }
        log.info("移除学生: courseId={}, studentId={}, operator={}", courseId, studentId, currentUserId);
    }

    /**
     * 查询学生已选课程列表
     *
     * @param studentId 学生 ID
     * @return 课程信息列表
     */
    public List<CourseInfo> findStudentCourses(Long studentId) {
        // 查询学生活跃的选课记录
        LambdaQueryWrapper<CourseEnrollment> wrapper = new LambdaQueryWrapper<CourseEnrollment>()
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
        LambdaQueryWrapper<CourseEnrollment> wrapper = new LambdaQueryWrapper<CourseEnrollment>()
                .eq(CourseEnrollment::getCourseId, courseId)
                .eq(CourseEnrollment::getStudentId, studentId)
                .eq(CourseEnrollment::getStatus, "ACTIVE");
        return enrollmentMapper.selectCount(wrapper) > 0;
    }
}
