package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import java.util.List;

/**
 * 选课管理服务接口 —— 封装 course_enrollment 的选课/退课/查询（主表 CourseEnrollment）
 *
 * @author commerce-rag
 */
public interface IEnrollmentService extends IService<CourseEnrollment> {

    /**
     * 查询课程的已选学生列表（带归属校验）
     */
    List<StudentDTO> findStudents(Long courseId, Long currentUserId, boolean isAdmin);

    /**
     * 批量添加学生选课（跳过已选的）
     *
     * @return 实际新增选课数
     */
    int addStudents(Long courseId, List<Long> studentIds, Long currentUserId, boolean isAdmin);

    /**
     * 移除学生（软删选课记录，status → DROPPED）
     */
    void removeStudent(Long courseId, Long studentId, Long currentUserId, boolean isAdmin);

    /**
     * 查询学生已选课程列表（DTO 形式，不含关联数据）
     */
    List<CourseDTO> findStudentCoursesAsDTO(Long studentId);

    /**
     * 查询学生已选课程列表（实体形式）
     */
    List<CourseInfo> findStudentCourses(Long studentId);

    /**
     * 检查学生是否已选某课程
     *
     * @return true=已选
     */
    boolean isEnrolled(Long courseId, Long studentId);
}
