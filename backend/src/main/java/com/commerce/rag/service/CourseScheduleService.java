package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.controller.dto.CreateScheduleRequest;
import com.commerce.rag.controller.dto.UpdateScheduleRequest;
import com.commerce.rag.entity.CourseSchedule;
import com.commerce.rag.mapper.CourseScheduleMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 排期管理服务 —— 封装 course_schedule 表的 CRUD 操作
 *
 * <p>权限控制：教师只能操作自己创建的课程下的排期（通过 CourseService.checkOwnership 校验）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class CourseScheduleService {

    private static final Logger log = LoggerFactory.getLogger(CourseScheduleService.class);

    private final CourseScheduleMapper scheduleMapper;
    private final CourseService courseService;
    /** 课程查询服务（排期写后失效该课程的查询缓存，先写 DB 后失效） */
    private final CourseQueryService courseQueryService;

    /**
     * 创建排期
     *
     * @param courseId      课程 ID
     * @param request       创建请求
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期实体（含雪花 ID）
     */
    public CourseSchedule create(Long courseId, CreateScheduleRequest request, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);
        CourseSchedule schedule = new CourseSchedule();
        schedule.setCourseId(courseId);
        schedule.setStartDate(request.startDate());
        schedule.setEndDate(request.endDate());
        schedule.setScheduleType(request.scheduleType());
        schedule.setLocation(request.location());
        schedule.setInstructorName(request.instructorName());
        schedule.setCapacity(request.capacity() != null ? request.capacity() : 0);
        schedule.setEnrolled(0);
        schedule.setStatus("UPCOMING");
        schedule.setCreatedBy(currentUserId);
        scheduleMapper.insert(schedule);
        // 新排期影响"下一期排期"查询结果，失效该课程缓存键（先写 DB 后失效）
        courseQueryService.evictCourse(courseId);
        log.info("创建排期: scheduleId={}, courseId={}, operator={}", schedule.getId(), courseId, currentUserId);
        return schedule;
    }

    /**
     * 根据 ID 查询排期（无权限校验，供内部使用）
     */
    public CourseSchedule findById(Long id) {
        return scheduleMapper.selectById(id);
    }

    /**
     * 根据 ID 查询排期（P0-4：带归属校验——教师仅能查看自己课程的排期）
     *
     * @param id            排期 ID
     * @param currentUserId 当前用户 ID
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期实体，不存在或无权访问返回 null
     */
    public CourseSchedule findById(Long id, Long currentUserId, boolean isAdmin) {
        CourseSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            return null;
        }
        // 归属校验：非超管必须为课程创建者（无权访问返回 null，不泄露存在性）
        courseService.checkOwnership(schedule.getCourseId(), currentUserId, isAdmin);
        return schedule;
    }

    /**
     * 查询课程的所有排期（按开课日期升序）
     */
    public List<CourseSchedule> findByCourseId(Long courseId) {
        LambdaQueryWrapper<CourseSchedule> wrapper = Wrappers.<CourseSchedule>lambdaQuery()
                .eq(CourseSchedule::getCourseId, courseId)
                .orderByAsc(CourseSchedule::getStartDate);
        return scheduleMapper.selectList(wrapper);
    }

    /**
     * 查询课程的所有排期（P0-4：带归属校验——教师仅能查看自己课程的排期）
     *
     * @param courseId      课程 ID
     * @param currentUserId 当前用户 ID
     * @param isAdmin       是否为超管（超管旁路）
     * @return 排期列表；课程不存在或无权访问抛 404/403
     */
    public List<CourseSchedule> findByCourseId(Long courseId, Long currentUserId, boolean isAdmin) {
        // 归属校验：课程不存在或非创建者直接拒绝（读端点越权修复）
        courseService.checkOwnership(courseId, currentUserId, isAdmin);
        return findByCourseId(courseId);
    }

    /**
     * 更新排期
     *
     * @param id            排期 ID
     * @param request       更新请求
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    public void update(Long id, UpdateScheduleRequest request, Long currentUserId, boolean isAdmin) {
        CourseSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排期不存在: " + id);
        }
        courseService.checkOwnership(schedule.getCourseId(), currentUserId, isAdmin);

        LambdaUpdateWrapper<CourseSchedule> wrapper =
                Wrappers.<CourseSchedule>lambdaUpdate().eq(CourseSchedule::getId, id);
        if (request.startDate() != null) wrapper.set(CourseSchedule::getStartDate, request.startDate());
        if (request.endDate() != null) wrapper.set(CourseSchedule::getEndDate, request.endDate());
        if (request.scheduleType() != null) wrapper.set(CourseSchedule::getScheduleType, request.scheduleType());
        if (request.location() != null) wrapper.set(CourseSchedule::getLocation, request.location());
        if (request.instructorName() != null) wrapper.set(CourseSchedule::getInstructorName, request.instructorName());
        if (request.capacity() != null) wrapper.set(CourseSchedule::getCapacity, request.capacity());
        if (request.enrolled() != null) wrapper.set(CourseSchedule::getEnrolled, request.enrolled());
        if (request.status() != null) wrapper.set(CourseSchedule::getStatus, request.status());
        wrapper.set(CourseSchedule::getUpdatedAt, LocalDateTime.now());
        scheduleMapper.update(null, wrapper);
        // 排期变更影响"下一期排期"查询结果，失效该课程缓存键（先写 DB 后失效）
        courseQueryService.evictCourse(schedule.getCourseId());
        log.info("更新排期: scheduleId={}, operator={}", id, currentUserId);
    }

    /**
     * 删除排期（软删）
     *
     * @param id            排期 ID
     * @param currentUserId 当前用户 ID（权限校验）
     * @param isAdmin       是否为超管（超管旁路）
     */
    public void delete(Long id, Long currentUserId, boolean isAdmin) {
        CourseSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "排期不存在: " + id);
        }
        courseService.checkOwnership(schedule.getCourseId(), currentUserId, isAdmin);

        LambdaUpdateWrapper<CourseSchedule> wrapper = Wrappers.<CourseSchedule>lambdaUpdate()
                .eq(CourseSchedule::getId, id)
                .set(CourseSchedule::getDeleted, System.currentTimeMillis())
                .set(CourseSchedule::getUpdatedAt, LocalDateTime.now());
        scheduleMapper.update(null, wrapper);
        // 排期删除影响"下一期排期"查询结果，失效该课程缓存键（先写 DB 后失效）
        courseQueryService.evictCourse(schedule.getCourseId());
        log.info("删除排期: scheduleId={}, operator={}", id, currentUserId);
    }
}
