package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.convert.CourseConverter;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.CourseDTO;
import com.commerce.rag.dto.CreateCourseRequest;
import com.commerce.rag.dto.PageResponse;
import com.commerce.rag.dto.UpdateCourseRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.ICourseCoverService;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.vo.CourseCoverVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * B 端课程管理 Controller —— CRUD 端点 E1-E9 + 封面上传（契约 D.2.2）
 *
 * <p>权限：SUPER_ADMIN + TEACHER（教师只能操作自己创建的课程）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin/courses")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
public class AdminCourseController {

    private static final Logger log = LoggerFactory.getLogger(AdminCourseController.class);

    private final ICourseService courseService;
    private final CourseConverter courseConverter;
    /** 课程封面服务 —— 上传校验与 MinIO 落盘（契约 D.2.2） */
    private final ICourseCoverService courseCoverService;

    public AdminCourseController(
            ICourseService courseService, CourseConverter courseConverter, ICourseCoverService courseCoverService) {
        this.courseService = courseService;
        this.courseConverter = courseConverter;
        this.courseCoverService = courseCoverService;
    }

    /**
     * E1: 课程列表（分页 + 分类筛选）
     */
    @GetMapping
    public ApiResponse<PageResponse<CourseDTO>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        Long createdBy = isAdmin ? null : userId;
        // service 返回的即 DTO 分页（records 不含关联数据），不再于 controller 内逐条转换
        IPage<CourseDTO> result = courseService.findPage(page, size, category, keyword, createdBy);
        return ApiResponse.ok(new PageResponse<>(
                result.getRecords(), result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
    }

    /**
     * E2: 创建课程
     */
    @PostMapping
    public ApiResponse<CourseDTO> create(HttpServletRequest request, @RequestBody CreateCourseRequest createRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        return ApiResponse.ok(courseService.createCourse(createRequest, userId));
    }

    /**
     * E3: 查看课程（含内容 + 排期 + 老师）
     *
     * <p>归属校验：教师只能查看自己创建的课程（P0-2g）。
     */
    @GetMapping("/{id}")
    public ApiResponse<CourseDTO> detail(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        // 教师按 created_by 过滤，超管不过滤
        Long createdByFilter = "SUPER_ADMIN".equals(role) ? null : userId;
        CourseDTO course = courseService.findById(id, createdByFilter);
        if (course == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "课程不存在");
        }
        return ApiResponse.ok(course);
    }

    /**
     * E4: 更新课程
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            HttpServletRequest request, @PathVariable Long id, @RequestBody UpdateCourseRequest updateRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.updateCourse(id, updateRequest, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * E5: 删除课程（级联软删）
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.deleteCourse(id, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * E6: 添加授课教师
     */
    @PostMapping("/{id}/teachers")
    public ApiResponse<Void> addTeachers(
            HttpServletRequest request, @PathVariable Long id, @RequestBody List<Long> teacherIds) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.addTeachers(id, teacherIds, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * E6: 移除授课教师
     */
    @DeleteMapping("/{id}/teachers")
    public ApiResponse<Void> removeTeachers(
            HttpServletRequest request, @PathVariable Long id, @RequestBody List<Long> teacherIds) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.removeTeachers(id, teacherIds, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * E7: 课程内容列表（按 sort_order 排序返回 4 个 Tab）
     */
    @GetMapping("/{id}/contents")
    public ApiResponse<List<CourseDTO.CourseContentDTO>> contents(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.checkOwnership(id, userId, isAdmin);
        var contents = courseService.findContents(id);
        var dtos = contents.stream().map(courseConverter::toContentDTO).collect(Collectors.toList());
        return ApiResponse.ok(dtos);
    }

    /**
     * E8: 更新单个 Tab 内容
     */
    @PutMapping("/{id}/contents/{contentType}")
    public ApiResponse<Void> updateContent(
            HttpServletRequest request,
            @PathVariable Long id,
            @PathVariable String contentType,
            @RequestBody String content) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.updateContent(id, contentType, content, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * E9: 批量更新全部 4 个 Tab 内容
     */
    @PutMapping("/{id}/contents")
    public ApiResponse<Void> batchUpdateContents(
            HttpServletRequest request, @PathVariable Long id, @RequestBody List<CourseDTO.CourseContentDTO> contents) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        boolean isAdmin = "SUPER_ADMIN".equals(AuthInterceptor.getCurrentRole(request));
        courseService.batchUpdateContents(id, contents, userId, isAdmin);
        return ApiResponse.ok();
    }

    /**
     * 封面上传（契约 D.2.2）
     *
     * <p>multipart 字段名 file；MIME/扩展名白名单与大小上限校验（course.cover.* 配置化）、
     * uuid 预生成落盘均在 ICourseCoverService 内完成。鉴权沿用本类级
     * SUPER_ADMIN/TEACHER 口径（/api/v1/admin/** 登录保护 + 方法级角色校验）。
     *
     * @param file 封面图片文件（用户上传，jpg/jpeg/png/webp 白名单）
     * @return 上传结果 VO（objectKey + 相对访问 URL，B 端表单将 url 写入 coverImage 字段提交）
     * @throws BizException 400 文件为空/类型或 MIME 非法/超限；503 MinIO 不可用
     */
    @PostMapping(value = "/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CourseCoverVO> uploadCover(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(courseCoverService.uploadCover(file));
    }
}
