package com.commerce.rag.controller;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.service.ICourseCoverService;
import com.commerce.rag.service.ICourseService;
import com.commerce.rag.vo.PublicCourseDetailVO;
import com.commerce.rag.vo.PublicCourseVO;
import java.time.Duration;
import java.util.List;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端公开功能 Controller —— 无需登录即可访问（AuthConfig 已排除 /api/v1/public/**）
 *
 * <p>公开课程列表/详情：未登录用户浏览首页/课堂页/详情页的课程数据源；
 * 仅输出概括字段（PublicCourseVO / PublicCourseDetailVO，含公开价格字段），
 * 学习资料仍走 StudentController（登录 + 选课校验）。
 *
 * <p>封面公开访问（契约 D.2.3）：前缀白名单代理 MinIO 封面目录（0/ 前缀），
 * 免登录但白名单收窄（A.5.8 豁免声明见 ICourseCoverService/CourseCoverServiceImpl 注释），
 * 响应带公开缓存头（封面内容不可变，uuid 键变更即换 URL）。
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {

    /** 封面公开缓存时长（契约 D.2.3：public, max-age=86400——内容不可变，换封面即换新 uuid 键） */
    private static final Duration COVER_CACHE_MAX_AGE = Duration.ofDays(1);

    private final ICourseService courseService;
    /** 课程封面服务 —— 公开访问白名单校验与 MinIO 代理回读（契约 D.2.3） */
    private final ICourseCoverService courseCoverService;

    public PublicController(ICourseService courseService, ICourseCoverService courseCoverService) {
        this.courseService = courseService;
        this.courseCoverService = courseCoverService;
    }

    /**
     * 公开课程列表（仅 ACTIVE 状态，评分降序）
     *
     * @return 公开课程列表（无需登录，@TableLogic 自动过滤已删除课程，含价格字段）
     */
    @GetMapping("/courses")
    public ApiResponse<List<PublicCourseVO>> courses() {
        return ApiResponse.ok(courseService.findPublicCourses());
    }

    /**
     * 公开课程详情（契约 C.2.2，仅 ACTIVE 状态可见）
     *
     * <p>免登录（公开前缀已排除拦截）；课程不存在/已逻辑删除/已下架统一 404
     * （不泄露存在性），由 GlobalExceptionHandler 包装 ApiResponse。
     *
     * @param id 课程 ID（路径参数）
     * @return 公开课程详情 VO（含 price，单位元）
     * @throws com.commerce.rag.exception.BizException 404 课程不存在或已下架
     */
    @GetMapping("/courses/{id}")
    public ApiResponse<PublicCourseDetailVO> courseDetail(@PathVariable Long id) {
        return ApiResponse.ok(courseService.findPublicCourseById(id));
    }

    /**
     * 封面公开访问（契约 D.2.3，免登录 + 前缀白名单硬校验）
     *
     * <p>{@code {*objectKey}} 通配吸收多级路径（Spring 6 捕获值带前导斜杠，service 层剥离后
     * 以全锚定正则 {@code ^0/[0-9a-f]{32}\.(jpg|jpeg|png|webp)$} 校验——防目录穿越/跨前缀读取，
     * 不匹配即 404）；命中则 MinIO 流式回写，按扩展名设 Content-Type，
     * 响应头 Cache-Control: public, max-age=86400。
     *
     * @param objectKey 路径通配捕获的 objectKey（如 /0/3f2b8c...d9.png）
     * @return 封面图片字节流（流关闭由响应框架兜底）
     * @throws com.commerce.rag.exception.BizException 404 键不合法或对象不存在；503 MinIO 不可用
     */
    @GetMapping("/covers/{*objectKey}")
    public ResponseEntity<InputStreamResource> cover(@PathVariable String objectKey) {
        ICourseCoverService.CoverContent content = courseCoverService.downloadCover(objectKey);
        // 封面内容不可变（uuid 键变更即换 URL），公开缓存 1 天
        return ResponseEntity.ok()
                .contentType(content.contentType())
                .cacheControl(CacheControl.maxAge(COVER_CACHE_MAX_AGE).cachePublic())
                .body(new InputStreamResource(content.inputStream()));
    }
}
