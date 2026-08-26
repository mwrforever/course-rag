package com.commerce.rag.controller;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.vo.PublicCourseVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端公开功能 Controller —— 无需登录即可访问（AuthConfig 已排空 /api/v1/public/**）
 *
 * <p>公开课程列表：未登录用户浏览首页/课堂页的课程数据源；
 * 仅输出概括字段（PublicCourseVO），学习资料仍走 StudentController（登录 + 选课校验）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    private final ICourseService courseService;

    public PublicController(ICourseService courseService) {
        this.courseService = courseService;
    }

    /**
     * 公开课程列表（仅 ACTIVE 状态，评分降序）
     *
     * @return 公开课程列表（无需登录，@TableLogic 自动过滤已删除课程）
     */
    @GetMapping("/courses")
    public ApiResponse<List<PublicCourseVO>> courses() {
        return ApiResponse.ok(courseService.findPublicCourses());
    }
}
