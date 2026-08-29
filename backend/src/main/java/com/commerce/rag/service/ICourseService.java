package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.CreateCourseRequest;
import com.commerce.rag.dto.UpdateCourseRequest;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import java.util.List;

/**
 * 课程管理服务接口 —— 封装课程 CRUD、教师管理、内容维护（主表 CourseInfo）
 *
 * @author commerce-rag
 */
public interface ICourseService extends IService<CourseInfo> {

    /**
     * 创建课程（事务内落库 + 服务端生成报名链接写回）
     *
     * <p>契约 A.2.2：请求体 enrollmentLink 忽略；落库后同事务生成
     * {@code {course.enroll-base-url}/courses/{id}} 写回 enrollment_link；
     * 缓存失效挂 afterCommit（提交后失效）。
     *
     * @param request  创建请求（enrollmentLink 字段被忽略）
     * @param createdBy 创建者 ID
     * @return 课程 DTO（含雪花 ID 与服务端生成的 enrollmentLink，不含关联数据）
     */
    CourseDTO createCourse(CreateCourseRequest request, Long createdBy);

    /**
     * 按 ID 查询课程（无权限过滤）
     */
    CourseInfo findById(Long courseId);

    /**
     * 按 ID 查询课程（按创建者过滤，用于教师数据权限）
     *
     * @param courseId        课程 ID
     * @param createdByFilter 创建者 ID 过滤（null 不过滤）
     * @return 课程 DTO（含内容/排期/教师关联），不存在或无权返回 null
     */
    CourseDTO findById(Long courseId, Long createdByFilter);

    /**
     * 批量查询课程（按 ID 列表）
     */
    List<CourseInfo> findByIds(List<Long> courseIds);

    /**
     * 查询公开课程列表（C 端公开接口：仅 ACTIVE 状态，评分降序）
     *
     * @return 公开课程视图对象列表（含价格，剔除标签/状态等内部管理字段）
     */
    List<PublicCourseVO> findPublicCourses();

    /**
     * 查询单个公开课程详情（C 端公开详情端点，仅 ACTIVE 状态可见）
     *
     * @param courseId 课程 ID
     * @return 公开课程详情 VO（含 price，单位元）
     * @throws com.commerce.rag.exception.BizException 404 课程不存在、已删除或已下架
     */
    PublicCourseDetailVO findPublicCourseById(Long courseId);

    /**
     * 分页查询课程（支持分类/关键词/创建者过滤）
     *
     * @return 分页结果（records 为课程 DTO，不含关联数据）
     */
    IPage<CourseDTO> findPage(int page, int size, String category, String keyword, Long createdBy);

    /**
     * 更新课程
     */
    void updateCourse(Long courseId, UpdateCourseRequest request, Long currentUserId, boolean isAdmin);

    /**
     * 删除课程（级联清理）
     */
    void deleteCourse(Long courseId, Long currentUserId, boolean isAdmin);

    /**
     * 批量添加授课教师
     */
    void addTeachers(Long courseId, List<Long> teacherIds, Long currentUserId, boolean isAdmin);

    /**
     * 批量移除授课教师
     */
    void removeTeachers(Long courseId, List<Long> teacherIds, Long currentUserId, boolean isAdmin);

    /**
     * 查询课程教师 ID 列表
     */
    List<Long> findTeacherIds(Long courseId);

    /**
     * 查询课程内容列表
     */
    List<CourseContent> findContents(Long courseId);

    /**
     * 更新课程内容
     */
    void updateContent(Long courseId, String contentType, String content, Long currentUserId, boolean isAdmin);

    /**
     * 批量更新课程内容
     */
    void batchUpdateContents(
            Long courseId, List<CourseDTO.CourseContentDTO> contents, Long currentUserId, boolean isAdmin);

    /**
     * 查询课程排期列表
     */
    List<CourseSchedule> findSchedules(Long courseId);

    /**
     * 课程实体转 DTO（可含关联数据）
     */
    CourseDTO toDTO(CourseInfo course, boolean includeRelations);

    /**
     * 归属校验：课程存在且（超管或创建者本人），否则抛 404/403
     *
     * @return 课程实体
     */
    CourseInfo checkOwnership(Long courseId, Long currentUserId, boolean isAdmin);
}
