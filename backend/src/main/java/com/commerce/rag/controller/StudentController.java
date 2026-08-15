package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.ChatRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.vo.ChunkBriefVO;
import com.commerce.rag.controller.vo.ChunkContextVO;
import com.commerce.rag.controller.vo.ChunkVO;
import com.commerce.rag.controller.vo.SessionVO;
import com.commerce.rag.controller.vo.StudentCourseVO;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.service.ChatSessionService;
import com.commerce.rag.service.DocumentChunkService;
import com.commerce.rag.service.EnrollmentService;
import com.commerce.rag.service.StudentConverter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * C 端学生功能 Controller —— 端点 J1-J8
 *
 * <p>权限：STUDENT
 *
 * <p>J1-J4 查询课程资料（已选课才能查课程专属）
 * J5 提交反馈 —— 由 FeedbackController 独立处理（POST /api/v1/student/feedbacks）
 * J6-J7 会话管理
 * J8 SSE 流式对话（转发到 ChatController）
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/student")
@PreAuthorize("hasRole('STUDENT')")
public class StudentController {

    private static final Logger log = LoggerFactory.getLogger(StudentController.class);

    private final EnrollmentService enrollmentService;
    private final ChatSessionService sessionService;
    private final DocumentChunkService documentChunkService;
    private final ChatController chatController;
    private final StudentConverter converter;

    public StudentController(
            EnrollmentService enrollmentService,
            ChatSessionService sessionService,
            DocumentChunkService documentChunkService,
            ChatController chatController,
            StudentConverter converter) {
        this.enrollmentService = enrollmentService;
        this.sessionService = sessionService;
        this.documentChunkService = documentChunkService;
        this.chatController = chatController;
        this.converter = converter;
    }

    // ==================== J1: 我的课程 ====================

    /**
     * J1: 已选课列表
     */
    @GetMapping("/courses")
    public ApiResponse<List<StudentCourseVO>> myCourses(HttpServletRequest request) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        List<CourseInfo> courses = enrollmentService.findStudentCourses(userId);
        return ApiResponse.ok(courses.stream().map(converter::toCourseVO).toList());
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
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未选此课程，无权查看资料");
        }
        // 通过 DocumentChunkService 按 courseId 查询分片列表
        List<DocumentChunk> chunks = documentChunkService.findByCourseId(id);
        return ApiResponse.ok(chunks.stream().map(converter::toChunkVO).toList());
    }

    // ==================== J3: 通用资料库 ====================

    /**
     * J3: 通用资料库（course_id='DEFAULT' 的 chunk）
     */
    @GetMapping("/knowledge-bases")
    public ApiResponse<PageResponse<ChunkBriefVO>> knowledgeBase(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        // 通过 DocumentChunkService 按 courseId='DEFAULT' 分页查询
        IPage<DocumentChunk> paged = documentChunkService.findByCourseIdDefault(page, size);
        List<ChunkBriefVO> result = paged.getRecords().stream().map(converter::toChunkBriefVO).toList();
        return ApiResponse.ok(new PageResponse<>(result, paged.getTotal(), page, size));
    }

    // ==================== J4: 分片上下文 ====================

    /**
     * J4: 分片上下文（已选课才能查课程专属）
     */
    @GetMapping("/chunks/{id}/context")
    public ApiResponse<ChunkContextVO> chunkContext(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 通过 DocumentChunkService 查询分片及上下文
        DocumentChunk chunk = documentChunkService.findById(id);
        if (chunk == null) {
            // P1-3: 内联 404 双轨修复——统一走 ResponseStatusException（真实 HTTP 404）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分片不存在");
        }

        // 权限校验：如果是课程专属 chunk，学生必须已选该课程
        String courseId = chunk.getCourseId();
        if (courseId != null && !"DEFAULT".equals(courseId)) {
            Long courseIdLong = Long.parseLong(courseId);
            if (!enrollmentService.isEnrolled(courseIdLong, userId)) {
                // P1-3: 内联错误双轨修复——统一走 ResponseStatusException（真实 HTTP 403）
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未选此课程，无权查看资料");
            }
        }

        // 查询父/前/后 chunk（不存在则传 null，由转换器做空安全映射）
        DocumentChunk parent = chunk.getParentChunkId() == null ? null : documentChunkService.findById(chunk.getParentChunkId());
        DocumentChunk prev = chunk.getPrevChunkId() == null ? null : documentChunkService.findById(chunk.getPrevChunkId());
        DocumentChunk next = chunk.getNextChunkId() == null ? null : documentChunkService.findById(chunk.getNextChunkId());
        return ApiResponse.ok(converter.toChunkContextVO(chunk, parent, prev, next));
    }

    // ==================== J5: 提交反馈 ====================
    // J5 已移至 FeedbackController 独立处理（POST /api/v1/student/feedbacks）
    // 使用 UserFeedbackService 而非 JdbcTemplate，遵循分层架构

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
        IPage<ChatSession> result = sessionService.findSessionsByUser(userId, page, size);
        List<SessionVO> records = result.getRecords().stream().map(converter::toSessionVO).toList();
        return ApiResponse.ok(
                new PageResponse<>(records, result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
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
        ChatSession session = sessionService.createSession(userId, title);
        return ApiResponse.ok(converter.toSessionVO(session));
    }

    // ==================== J8: SSE 流式对话 ====================

    /**
     * J8: SSE 流式对话（转发到 ChatController）
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(HttpServletRequest request, @RequestBody ChatRequest chatRequest) {
        return chatController.chat(request, chatRequest);
    }
}
