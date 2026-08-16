package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.service.IDocumentChunkService;
import com.commerce.rag.service.IEnrollmentService;
import com.commerce.rag.stream.ChatStreamEntry;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.vo.StudentCourseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * C 端学生功能 Controller —— 端点 J1-J8
 *
 * <p>权限：STUDENT
 *
 * <p>J1-J4 查询课程资料（已选课才能查课程专属）
 * J5 提交反馈 —— 由 FeedbackController 独立处理（POST /api/v1/student/feedbacks）
 * J6-J7 会话管理
 * J8 SSE 流式对话（经 ChatStreamEntry，不再依赖 ChatController）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/student")
@PreAuthorize("hasRole('STUDENT')")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    private final IEnrollmentService enrollmentService;
    private final IChatSessionService sessionService;
    private final IDocumentChunkService documentChunkService;
    private final ChatStreamEntry chatStreamEntry;

    public StudentController(
            IEnrollmentService enrollmentService,
            IChatSessionService sessionService,
            IDocumentChunkService documentChunkService,
            ChatStreamEntry chatStreamEntry) {
        this.enrollmentService = enrollmentService;
        this.sessionService = sessionService;
        this.documentChunkService = documentChunkService;
        this.chatStreamEntry = chatStreamEntry;
    }

    // ==================== J1: 我的课程 ====================

    /**
     * J1: 已选课列表
     */
    @GetMapping("/courses")
    public ApiResponse<List<StudentCourseVO>> myCourses(HttpServletRequest request) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        return ApiResponse.ok(enrollmentService.findStudentCoursesAsVO(userId));
    }

    // ==================== J2: 课程专属资料 ====================

    /**
     * J2: 课程专属 chunk 列表（已选课才能查看）
     */
    @GetMapping("/courses/{id}/materials")
    public ApiResponse<List<ChunkVO>> courseMaterials(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 权限校验：学生必须已选此课程
        if (!enrollmentService.isEnrolled(id, userId)) {
            // P1-3: 内联错误双轨修复——统一走 ResponseStatusException（真实 HTTP 403）
            throw new BizException(ErrorCode.FORBIDDEN, "未选此课程，无权查看资料");
        }
        // 通过 IDocumentChunkService 按 courseId 查询分片列表（VO 直接出参）
        return ApiResponse.ok(documentChunkService.findByCourseIdAsVO(id));
    }

    // ==================== J3: 通用资料库 ====================

    /**
     * J3: 通用资料库（course_id='DEFAULT' 的 chunk）
     */
    @GetMapping("/knowledge-bases")
    public ApiResponse<PageResponse<ChunkBriefVO>> knowledgeBase(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        // 通过 IDocumentChunkService 按 courseId='DEFAULT' 分页查询（VO 直接出参）
        IPage<ChunkBriefVO> paged = documentChunkService.findByCourseIdDefaultAsVO(page, size);
        return ApiResponse.ok(new PageResponse<>(
                paged.getRecords(), paged.getTotal(), (int) paged.getCurrent(), (int) paged.getSize()));
    }

    // ==================== J4: 分片上下文 ====================

    /**
     * J4: 分片上下文（已选课才能查课程专属）
     */
    @GetMapping("/chunks/{id}/context")
    public ApiResponse<ChunkContextVO> chunkContext(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 通过 IDocumentChunkService 查询分片及上下文（父/前/后由 service 组装）
        ChunkContextVO chunk = documentChunkService.findContext(id);
        if (chunk == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new BizException(ErrorCode.NOT_FOUND, "分片不存在");
        }

        // 权限校验：如果是课程专属 chunk，学生必须已选该课程（courseId 取自 VO）
        String courseId = chunk.courseId();
        if (courseId != null && !"DEFAULT".equals(courseId)) {
            Long courseIdLong = Long.parseLong(courseId);
            if (!enrollmentService.isEnrolled(courseIdLong, userId)) {
                // P1-3: 内联错误双轨修复——统一走 ResponseStatusException（真实 HTTP 403）
                throw new BizException(ErrorCode.FORBIDDEN, "未选此课程，无权查看资料");
            }
        }
        return ApiResponse.ok(chunk);
    }

    // ==================== J5: 提交反馈 ====================
    // J5 已移至 FeedbackController 独立处理（POST /api/v1/student/feedbacks）
    // 使用 IUserFeedbackService 而非 JdbcTemplate，遵循分层架构

    // ==================== J6: 我的会话 ====================

    /**
     * J6: 我的会话列表
     */
    @GetMapping("/sessions")
    public ApiResponse<PageResponse<SessionVO>> mySessions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // service 返回的即 SessionVO 分页（records 不含 userId 等内部字段）
        IPage<SessionVO> result = sessionService.findSessionsByUser(userId, page, size);
        return ApiResponse.ok(new PageResponse<>(
                result.getRecords(), result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
    }

    // ==================== J7: 创建会话 ====================

    /**
     * J7: 创建会话
     */
    @PostMapping("/sessions")
    public ApiResponse<SessionVO> createSession(
            HttpServletRequest request, @RequestBody Map<String, String> sessionRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String title = sessionRequest.getOrDefault("title", "新对话");
        return ApiResponse.ok(sessionService.createSession(userId, title));
    }

    // ==================== J8: SSE 流式对话 ====================

    /**
     * J8: SSE 流式对话（经 ChatStreamEntry，不再依赖 ChatController）
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody ChatRequest chatRequest) {
        return chatStreamEntry.chat(request, chatRequest);
    }
}
