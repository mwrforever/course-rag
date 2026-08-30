package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.vo.CoursePurchaseVO;
import com.commerce.rag.vo.StudentCourseVO;
import java.util.List;

/**
 * 选课管理服务接口 —— 封装 course_enrollment 的选课/退课/查询/学生自助购买（主表 CourseEnrollment）
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
     * 学生自助购买课程（C 端 POST /api/v1/student/courses/{courseId}/purchase）
     *
     * <p>契约 B.2 幂等语义：已 ACTIVE 直接返回成功（无任何写）；DROPPED 重激活
     * （置 ACTIVE + enrolledAt=now）；无记录插入 ACTIVE 记录；并发撞
     * uniq_course_enrollment(course_id, student_id) 部分唯一索引按已购成功返回
     * （catch 后直接构造成功 VO，禁止在 rollback-only 事务内重查）。
     * 不递增 learning_count（契约 D2：与 addStudents 行为一致，该列无业务写路径）。
     *
     * @param courseId  课程 ID（路径参数）
     * @param studentId 学生 ID（认证上下文取，禁止入参传递）
     * @return 购买结果 VO（courseId/status=ACTIVE/purchased=true）
     * @throws com.commerce.rag.exception.BizException 404 课程不存在、已删除或已下架
     */
    CoursePurchaseVO purchaseCourse(Long courseId, Long studentId);

    /**
     * 移除学生（软删选课记录，status → DROPPED）
     */
    void removeStudent(Long courseId, Long studentId, Long currentUserId, boolean isAdmin);

    /**
     * 查询学生已选课程列表（DTO 形式，不含关联数据）
     */
    List<CourseDTO> findStudentCoursesAsDTO(Long studentId);

    /**
     * 查询学生已选课程列表（实体形式，供 findStudentCoursesAsDTO 内部使用）
     */
    List<CourseInfo> findStudentCourses(Long studentId);

    /**
     * 查询学生已选课程列表（C 端视图对象，供 StudentController 使用）
     *
     * @param studentId 学生 ID
     * @return 学生课程视图对象列表（剔除价格/描述等内部字段）
     */
    List<StudentCourseVO> findStudentCoursesAsVO(Long studentId);

    /**
     * 检查学生是否已选某课程
     *
     * @return true=已选
     */
    boolean isEnrolled(Long courseId, Long studentId);
}
