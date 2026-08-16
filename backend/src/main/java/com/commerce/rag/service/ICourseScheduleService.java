package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.dto.CreateScheduleRequest;
import com.commerce.rag.dto.UpdateScheduleRequest;
import com.commerce.rag.entity.CourseSchedule;
import java.util.List;

/**
 * 排期管理服务接口 —— 封装 course_schedule 表的 CRUD 操作（主表 CourseSchedule）
 *
 * <p>权限控制：教师只能操作自己创建的课程下的排期。
 *
 * @author commerce-rag
 */
public interface ICourseScheduleService extends IService<CourseSchedule> {

    /**
     * 创建排期
     *
     * @param courseId      课程 ID
     * @param request       创建请求
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期实体（含雪花 ID）
     */
    CourseSchedule create(Long courseId, CreateScheduleRequest request, Long currentUserId, boolean isAdmin);

    /**
     * 根据 ID 查询排期（无权限校验，供内部使用）
     */
    CourseSchedule findById(Long id);

    /**
     * 根据 ID 查询排期（带归属校验——教师仅能查看自己课程的排期）
     *
     * @param id            排期 ID
     * @param currentUserId 当前用户 ID
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期实体，不存在或无权访问返回 null
     */
    CourseSchedule findById(Long id, Long currentUserId, boolean isAdmin);

    /**
     * 查询课程的所有排期（按开课日期升序）
     */
    List<CourseSchedule> findByCourseId(Long courseId);

    /**
     * 查询课程的所有排期（带归属校验——教师仅能查看自己课程的排期）
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前用户 ID
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期列表；课程不存在或无权访问抛 404/403
     */
    List<CourseSchedule> findByCourseId(Long courseId, Long currentUserId, boolean isAdmin);

    /**
     * 更新排期
     *
     * @param id            排期 ID
     * @param request       更新请求
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    void update(Long id, UpdateScheduleRequest request, Long currentUserId, boolean isAdmin);

    /**
     * 删除排期（软删）
     *
     * @param id            排期 ID
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    void delete(Long id, Long currentUserId, boolean isAdmin);
}
