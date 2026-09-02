package com.commerce.rag.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.dto.CreateSessionRequest;
import com.commerce.rag.dto.PageResponse;
import com.commerce.rag.dto.SessionRenameRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.service.IDocumentChunkService;
import com.commerce.rag.service.IEnrollmentService;
import com.commerce.rag.stream.ChatStreamEntry;
import com.commerce.rag.vo.ActiveRunVO;
import com.commerce.rag.vo.ChatSessionVO;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
import com.commerce.rag.vo.CoursePurchaseVO;
import com.commerce.rag.vo.SessionVO;
import com.commerce.rag.vo.StudentCourseVO;
import com.commerce.rag.vo.StudentMessageVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * C 端学生功能 Controller —— 端点 J1-J8 + 会话历史消息（R1 补口 A）
 *
 * <p>权限：STUDENT
 *
 * <p>J1-J4 查询课程资料（已选课才能查课程专属）
 * J5 提交反馈 —— 由 FeedbackController 独立处理（POST /api/v1/student/feedbacks）
 * J6-J7 会话管理
 * J8 SSE 流式对话（经 ChatStreamEntry，不再依赖 ChatController）
 * 历史消息 —— 会话内消息分页回显（GET /sessions/{sessionId}/messages）
 * 删除会话 —— 级联软删（DELETE /sessions/{sessionId}，活跃 run 409 守卫）
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
    private final IChatMessageService messageService;
    private final IChatRunService runService;
    private final IDocumentChunkService documentChunkService;
    private final ChatStreamEntry chatStreamEntry;

    public StudentController(
            IEnrollmentService enrollmentService,
            IChatSessionService sessionService,
            IChatMessageService messageService,
            IChatRunService runService,
            IDocumentChunkService documentChunkService,
            ChatStreamEntry chatStreamEntry) {
        this.enrollmentService = enrollmentService;
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.runService = runService;
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

    // ==================== J1b: 学生自助购买课程 ====================

    /**
     * 学生自助购买课程（契约 B.2，幂等）
     *
     * <p>执行流程：认证上下文取 userId（禁止入参传递）→ 委托 enrollmentService.purchaseCourse
     * （课程 404 校验 / 已购幂等返回 / 退课重激活 / 并发撞唯一索引按已购返回）。
     * 重复调用幂等不重复插行、不报 409；不递增 learning_count（契约 D2）。
     *
     * @param request   请求（AuthInterceptor 注入的用户属性）
     * @param courseId  课程 ID（路径参数）
     * @return 购买结果 VO（courseId/status=ACTIVE/purchased=true）
     * @throws BizException 404 课程不存在、已删除或已下架
     */
    @PostMapping("/courses/{courseId}/purchase")
    public ApiResponse<CoursePurchaseVO> purchaseCourse(HttpServletRequest request, @PathVariable Long courseId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        return ApiResponse.ok(enrollmentService.purchaseCourse(courseId, userId));
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
            // BUG-09：course_id 列为 VARCHAR 且上传链路无格式校验，非数字脏数据直接
            // parseLong 会抛 NumberFormatException 变 500；先判格式，非法归属无法完成
            // 选课校验，按未授权拒绝（fail-closed），同时告警暴露脏数据
            if (!courseId.matches("\\d+")) {
                log.warn("分片课程归属为非数字脏数据，按未授权拒绝: chunkId={}, courseId={}", id, courseId);
                throw new BizException(ErrorCode.FORBIDDEN, "资料课程归属异常，无权查看");
            }
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
     * J6: 我的会话列表（标题可选模糊搜索）
     *
     * @param keyword 标题搜索关键词（可选，缺省全量列表）
     */
    @GetMapping("/sessions")
    public ApiResponse<PageResponse<SessionVO>> mySessions(
            HttpServletRequest request,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // service 返回的即 SessionVO 分页（records 不含 userId 等内部字段）
        IPage<SessionVO> result = sessionService.findSessionsByUser(userId, page, size, keyword);
        return ApiResponse.ok(new PageResponse<>(
                result.getRecords(), result.getTotal(), (int) result.getCurrent(), (int) result.getSize()));
    }

    // ==================== J7: 创建会话 ====================

    /**
     * J7: 创建会话
     */
    @PostMapping("/sessions")
    public ApiResponse<SessionVO> createSession(
            HttpServletRequest request, @Valid @RequestBody CreateSessionRequest createRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 标题缺省/空白补「新对话」（J7 契约），trim 防全空白标题
        String title = createRequest.title() == null || createRequest.title().isBlank()
                ? "新对话"
                : createRequest.title().trim();
        return ApiResponse.ok(sessionService.createSession(userId, title));
    }

    // ==================== J8: SSE 流式对话 ====================

    /**
     * J8: SSE 流式对话（经 ChatStreamEntry，不再依赖 ChatController）
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(
            HttpServletRequest request, HttpServletResponse response, @RequestBody ChatRequest chatRequest) {
        return chatStreamEntry.chat(request, response, chatRequest);
    }

    // ==================== 历史消息（R1 补口 A） ====================

    /**
     * 会话历史消息分页查询（R1 补口 A，聊天 UI 进入会话时全量回显）
     *
     * <p>执行流程：取当前用户 → 会话归属校验（404 不存在 / 403 非本人，
     * 会话语义 403 与 ChatStreamEntry 先例一致）→ service 分页查询。
     * 默认 page=1/size=200（升序最旧一页）；M3 半截过滤与 sources/attachments
     * JSON 解析均在 service 层完成，controller 仅做参数绑定与归属校验。
     *
     * @param request   请求（AuthInterceptor 注入的用户属性）
     * @param sessionId 会话 ID（路径参数）
     * @param page      页码（1-based，缺省 1）
     * @param size      每页条数（缺省 200，service 钳制上限 500）
     * @return 学生消息 VO 分页（Long 字段经全局序列化输出 string）
     * @throws BizException 404 会话不存在；403 非本人会话
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<PageResponse<StudentMessageVO>> sessionMessages(
            HttpServletRequest request,
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "200") int size) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 归属校验：selectById 自动过 @TableLogic 软删，删除后的会话按不存在处理
        ChatSessionVO session = sessionService.findById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        // 会话语义 403：非本人会话拒绝查看（与 ChatStreamEntry「无权操作此会话」先例一致）
        if (!session.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看此会话");
        }
        IPage<StudentMessageVO> paged = messageService.findStudentMessagesBySession(sessionId, page, size);
        return ApiResponse.ok(new PageResponse<>(
                paged.getRecords(), paged.getTotal(), (int) paged.getCurrent(), (int) paged.getSize()));
    }

    // ==================== 会话活跃 run（多会话并继续流，2026-09-01 用户拍板） ====================

    /**
     * 查会话当前活跃 run 的 ID（多会话并继续流锚点）
     *
     * <p>C 端允许同一用户同时开启多会话问答——一个会话流式生成期间用户可切到其他会话
     * 继续提问，切回原会话时前端以此端点拿到活跃 runId 后发起 GET reconnect 全量回放，
     * 恢复进行中回答的实时视图（run 继续在服务端执行，断连不取消，只有显式 cancel 才停）。
     *
     * <p>归属校验与 R1 历史消息先例一致（404 不存在 / 403 非本人）；活跃定义与
     * uniq_active_run_per_session 一致（status ∈ {QUEUED, ACTIVE}），单会话串行
     * 由该唯一索引保证至多一条。
     *
     * @param request   请求（AuthInterceptor 注入的用户属性）
     * @param sessionId 会话 ID（路径参数）
     * @return 活跃 run 视图（data.runId=run ID 字符串；无活跃 run 时 data=null）
     * @throws BizException 404 会话不存在；403 非本人会话
     */
    @GetMapping("/chat/session/{sessionId}/active-run")
    public ApiResponse<ActiveRunVO> activeRun(HttpServletRequest request, @PathVariable Long sessionId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 归属校验：selectById 自动过 @TableLogic 软删，已删除会话按不存在处理（幂等 404）
        ChatSessionVO session = sessionService.findById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        // 会话语义 403：非本人会话拒绝查询（与 R1 历史消息「无权查看此会话」先例一致）
        if (!session.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权查看此会话");
        }
        Long runId = runService.findActiveRunId(sessionId);
        return ApiResponse.ok(runId == null ? null : new ActiveRunVO(runId.toString()));
    }

    // ==================== 删除会话（R3 补口 C） ====================

    /**
     * 删除学生会话（R3 补口 C，级联软删 chat_message→chat_run→chat_session）
     *
     * <p>执行流程：取当前用户 → 会话归属校验（404 不存在 / 403 非本人，与 R1
     * 历史消息先例一致）→ 活跃 run 守卫（存在 QUEUED/ACTIVE run 时 409，
     * 避免删除正在进行的对话）→ 复用 {@code sessionService.deleteSession}
     * 既有 @Transactional 级联软删路径（与 H4 管理端删除同路径）。
     *
     * <p>竞态边界说明：
     * <ul>
     *   <li>活跃 run 校验与级联删除非原子——校验通过后、删除落库前的并发窗口内
     *       新入队的 run 可能被一并级联软删。该窗口频率极低且不产生数据不一致
     *       （软删可恢复、消息双端经 @TableLogic 过滤互不可见），故不加锁；
     *   <li>幂等语义：重复 DELETE 同一会话 → findById 已过 @TableLogic 过滤
     *       返回 null → 404（REST 幂等删除语义）；
     *   <li>前端约定：删除成功后须丢弃本地 runId 并关闭对应 SSE 连接。
     * </ul>
     *
     * @param request   请求（AuthInterceptor 注入的用户属性）
     * @param sessionId 会话 ID（路径参数）
     * @return 空响应体（data=null）
     * @throws BizException 404 会话不存在（含重复删除）；403 非本人会话；
     *                      409 会话存在活跃 run（正在对话中）
     */
    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(HttpServletRequest request, @PathVariable Long sessionId) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 归属校验：selectById 自动过 @TableLogic 软删，已删除会话按不存在处理（幂等 404）
        ChatSessionVO session = sessionService.findById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        // 会话语义 403：非本人会话拒绝删除（与 R1 历史消息「无权查看此会话」先例一致）
        if (!session.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权删除此会话");
        }
        // 活跃 run 守卫：QUEUED/ACTIVE run 存在时阻断删除（校验与删除非原子见方法注释竞态说明）
        if (runService.existsActiveRun(sessionId)) {
            throw new BizException(ErrorCode.CONFLICT, "会话正在对话中，请稍后删除");
        }
        // 复用既有 @Transactional 级联软删（操作者=当前用户，供审计日志）
        sessionService.deleteSession(sessionId, userId);
        log.info("学生会话已删除: sessionId={}, operatorId={}", sessionId, userId);
        return ApiResponse.ok();
    }

    // ==================== 重命名会话（会话管理：改） ====================

    /**
     * 重命名学生会话标题
     *
     * <p>执行流程：取当前用户 → 会话归属校验（404 不存在 / 403 非本人，与 R3 删除先例一致）
     * → service 更新标题并回读最新视图。
     * 重命名不改排序字段（last_message_at），允许任意时刻执行，不影响进行中的对话。
     *
     * @param request       请求（AuthInterceptor 注入的用户属性）
     * @param sessionId     会话 ID（路径参数）
     * @param renameRequest 重命名请求（title 必填 1~300 字符，@Valid 校验失败由全局异常处理返回 400）
     * @return 重命名后的会话视图对象
     * @throws BizException 404 会话不存在（含已删除）；403 非本人会话
     */
    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<SessionVO> renameSession(
            HttpServletRequest request,
            @PathVariable Long sessionId,
            @Valid @RequestBody SessionRenameRequest renameRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        // 归属校验：selectById 自动过 @TableLogic 软删，已删除会话按不存在处理（幂等 404）
        ChatSessionVO session = sessionService.findById(sessionId);
        if (session == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        // 会话语义 403：非本人会话拒绝重命名（与删除「无权删除此会话」先例一致）
        if (!session.userId().equals(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权重命名此会话");
        }
        SessionVO updated =
                sessionService.renameSession(sessionId, renameRequest.title().trim());
        log.info("学生会话已重命名: sessionId={}, userId={}", sessionId, userId);
        return ApiResponse.ok(updated);
    }
}
