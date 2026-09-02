package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.dto.CreateSessionRequest;
import com.commerce.rag.dto.PageResponse;
import com.commerce.rag.dto.SessionRenameRequest;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.RetrievalSource;
import com.commerce.rag.service.IChatMessageService;
import com.commerce.rag.service.IChatRunService;
import com.commerce.rag.service.IChatSessionService;
import com.commerce.rag.service.IDocumentChunkService;
import com.commerce.rag.service.IEnrollmentService;
import com.commerce.rag.stream.ChatStreamEntry;
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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * StudentController 单元测试 —— C 端学生端点 J1-J8
 *
 * <p>覆盖：选课/资料/通用库/分片上下文/会话列表/创建会话/SSE 转发；
 * 异常分支：未选课 403、分片不存在 404。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StudentController C 端端点测试")
class StudentControllerTest {

    @Mock
    private IEnrollmentService enrollmentService;

    @Mock
    private IChatSessionService sessionService;

    @Mock
    private IChatMessageService messageService;

    @Mock
    private IChatRunService runService;

    @Mock
    private IDocumentChunkService documentChunkService;

    @Mock
    private ChatStreamEntry chatStreamEntry;

    private StudentController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentController(
                enrollmentService, sessionService, messageService, runService, documentChunkService, chatStreamEntry);
    }

    private HttpServletRequest studentRequest(Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        return req;
    }

    private StudentCourseVO courseVO(Long id, String title) {
        return new StudentCourseVO(id, title, "cover.png", "编程", "张老师", "10h", new BigDecimal("4.5"), 100);
    }

    private ChunkVO chunkVO(Long id) {
        return new ChunkVO(id, "内容-" + id, "第一章", 1, "小节", 1, 2);
    }

    private ChunkBriefVO chunkBriefVO(Long id) {
        return new ChunkBriefVO(id, "内容-" + id, "第一章", 1, "小节");
    }

    private ChunkContextVO chunkContextVO(
            Long id, String courseId, ChunkBriefVO parent, ChunkBriefVO prev, ChunkBriefVO next) {
        return new ChunkContextVO(id, 1L, 1L, "内容-" + id, "第一章", 1, courseId, null, null, null, parent, prev, next);
    }

    private SessionVO sessionVO(Long id, String title) {
        return new SessionVO(
                id, title, "ACTIVE", LocalDateTime.of(2026, 8, 15, 10, 0), LocalDateTime.of(2026, 8, 15, 9, 0));
    }

    // ==================== J1b: 学生自助购买课程 ====================

    @Test
    @DisplayName("购买 → 认证上下文取 userId 委托 purchaseCourse，返回契约 VO")
    void purchaseCourse_delegatesWithCurrentUserId() {
        when(enrollmentService.purchaseCourse(1L, 5L)).thenReturn(new CoursePurchaseVO(1L, "ACTIVE", true));

        ApiResponse<CoursePurchaseVO> result = controller.purchaseCourse(studentRequest(5L), 1L);

        // userId 一律取认证上下文（禁止入参传递），课程 ID 走路径参数
        verify(enrollmentService).purchaseCourse(1L, 5L);
        assertEquals(0, result.code());
        assertEquals(1L, result.data().courseId());
        assertEquals("ACTIVE", result.data().status());
        assertTrue(result.data().purchased());
    }

    @Test
    @DisplayName("购买 → 课程不存在时 404 业务异常透出（重复购买幂等由 service 保证）")
    void purchaseCourse_courseMissing_throws404() {
        when(enrollmentService.purchaseCourse(99L, 5L)).thenThrow(new BizException(ErrorCode.NOT_FOUND, "课程不存在或已下架"));

        BizException ex = assertThrows(BizException.class, () -> controller.purchaseCourse(studentRequest(5L), 99L));

        assertEquals(404, ex.getCode());
    }

    // ==================== J1: 我的课程 ====================

    @Test
    @DisplayName("J1 myCourses → 返回已选课列表（VO 字段映射完整）")
    void myCourses_returnsEnrolledCourses() {
        when(enrollmentService.findStudentCoursesAsVO(5L))
                .thenReturn(List.of(courseVO(1L, "Java 入门"), courseVO(2L, "Spring")));

        ApiResponse<List<StudentCourseVO>> result = controller.myCourses(studentRequest(5L));

        assertEquals(0, result.code());
        assertEquals(2, result.data().size());
        StudentCourseVO first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("Java 入门", first.title());
        assertEquals("cover.png", first.coverImage());
        assertEquals("编程", first.category());
        assertEquals("张老师", first.instructorName());
        assertEquals("10h", first.duration());
        assertEquals(new BigDecimal("4.5"), first.rating());
        assertEquals(100, first.learningCount());
    }

    @Test
    @DisplayName("J1 myCourses → 无选课时返回空列表")
    void myCourses_noEnrollment_returnsEmpty() {
        when(enrollmentService.findStudentCoursesAsVO(5L)).thenReturn(List.of());

        ApiResponse<List<StudentCourseVO>> result = controller.myCourses(studentRequest(5L));

        assertTrue(result.data().isEmpty());
    }

    // ==================== J2: 课程专属资料 ====================

    @Test
    @DisplayName("J2 courseMaterials → 未选课抛 403")
    void courseMaterials_notEnrolled_throws403() {
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> controller.courseMaterials(studentRequest(5L), 10L));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
        verify(documentChunkService, never()).findByCourseIdAsVO(anyLong());
    }

    @Test
    @DisplayName("J2 courseMaterials → 已选课返回课程专属 chunk 列表（VO）")
    void courseMaterials_enrolled_returnsChunks() {
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(true);
        when(documentChunkService.findByCourseIdAsVO(10L)).thenReturn(List.of(chunkVO(1L)));

        ApiResponse<List<ChunkVO>> result = controller.courseMaterials(studentRequest(5L), 10L);

        ChunkVO first = result.data().get(0);
        assertEquals(1L, first.id());
        assertEquals("内容-1", first.content());
        assertEquals("第一章", first.headingPath());
        assertEquals(1, first.chunkIndex());
        assertEquals("小节", first.parentTitle());
        assertEquals(1, first.startPage());
        assertEquals(2, first.endPage());
    }

    // ==================== J3: 通用资料库 ====================

    @Test
    @DisplayName("J3 knowledgeBase → 分页返回 DEFAULT 通用库 chunk（VO）")
    void knowledgeBase_returnsPagedChunks() {
        Page<ChunkBriefVO> paged = new Page<>(2, 20);
        paged.setRecords(List.of(chunkBriefVO(1L)));
        paged.setTotal(1);
        when(documentChunkService.findByCourseIdDefaultAsVO(2, 20)).thenReturn(paged);

        ApiResponse<PageResponse<ChunkBriefVO>> result = controller.knowledgeBase(2, 20);

        PageResponse<ChunkBriefVO> data = result.data();
        assertEquals(1, data.records().size());
        assertEquals(1L, data.total());
        assertEquals(2, data.page());
        assertEquals(20, data.size());
        assertEquals("内容-1", data.records().get(0).content());
    }

    // ==================== J4: 分片上下文 ====================

    @Test
    @DisplayName("J4 chunkContext → 分片不存在抛 404")
    void chunkContext_notFound_throws404() {
        when(documentChunkService.findContext(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.chunkContext(studentRequest(5L), 99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
    }

    @Test
    @DisplayName("J4 chunkContext → 课程专属 chunk 且未选课抛 403")
    void chunkContext_courseChunkNotEnrolled_throws403() {
        when(documentChunkService.findContext(1L)).thenReturn(chunkContextVO(1L, "10", null, null, null));
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(false);

        BizException ex = assertThrows(BizException.class, () -> controller.chunkContext(studentRequest(5L), 1L));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
    }

    @Test
    @DisplayName("J4 chunkContext → 课程归属非数字脏数据按 403 拒绝（BUG-09：不再 NumberFormatException 500）")
    void chunkContext_nonNumericCourseId_throws403Not500() {
        when(documentChunkService.findContext(1L)).thenReturn(chunkContextVO(1L, "abc脏数据", null, null, null));

        BizException ex = assertThrows(BizException.class, () -> controller.chunkContext(studentRequest(5L), 1L));

        // 无法解析的课程归属不可能通过选课校验，按未授权拒绝（fail-closed），禁止 500
        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
        verify(enrollmentService, never()).isEnrolled(anyLong(), anyLong());
    }

    @Test
    @DisplayName("J4 chunkContext → DEFAULT 分片免选课校验，返回上下文（无关联分片）")
    void chunkContext_defaultChunk_returnsContext() {
        when(documentChunkService.findContext(1L)).thenReturn(chunkContextVO(1L, "DEFAULT", null, null, null));

        ApiResponse<ChunkContextVO> result = controller.chunkContext(studentRequest(5L), 1L);

        assertEquals("DEFAULT", result.data().courseId());
        assertEquals(1L, result.data().docId());
        assertEquals(1L, result.data().kbId());
        // 无关联 chunk 时关联字段为 null
        assertNull(result.data().parent());
        assertNull(result.data().prev());
        assertNull(result.data().next());
        verify(enrollmentService, never()).isEnrolled(anyLong(), anyLong());
    }

    @Test
    @DisplayName("J4 chunkContext → 课程专属分片已选课，且带 parent/prev/next 关联")
    void chunkContext_withNeighbors_returnsContext() {
        ChunkContextVO context = chunkContextVO(1L, "10", chunkBriefVO(100L), chunkBriefVO(101L), chunkBriefVO(102L));
        when(documentChunkService.findContext(1L)).thenReturn(context);
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(true);

        ApiResponse<ChunkContextVO> result = controller.chunkContext(studentRequest(5L), 1L);

        ChunkContextVO data = result.data();
        assertEquals(100L, data.parent().id());
        assertEquals(101L, data.prev().id());
        assertEquals(102L, data.next().id());
        // 关联 chunk 只输出摘要字段（VO 类型保证不含 courseId 等内部字段）
        assertEquals("内容-100", data.parent().content());
    }

    @Test
    @DisplayName("J4 chunkContext → 关联 chunk 缺失时关联字段为 null（不中断）")
    void chunkContext_missingNeighbor_omitsKey() {
        when(documentChunkService.findContext(1L)).thenReturn(chunkContextVO(1L, "DEFAULT", null, null, null));

        ApiResponse<ChunkContextVO> result = controller.chunkContext(studentRequest(5L), 1L);

        assertNull(result.data().parent());
    }

    // ==================== J6: 我的会话 ====================

    @Test
    @DisplayName("J6 mySessions → 分页返回会话列表（VO，keyword 缺省全量）")
    void mySessions_returnsPagedSessions() {
        Page<SessionVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(sessionVO(1L, "会话一")));
        paged.setTotal(1);
        when(sessionService.findSessionsByUser(5L, 1, 20, null)).thenReturn(paged);

        ApiResponse<PageResponse<SessionVO>> result = controller.mySessions(studentRequest(5L), 1, 20, null);

        SessionVO record = result.data().records().get(0);
        assertEquals("会话一", record.title());
        assertEquals("ACTIVE", record.status());
        assertNotNull(record.lastMessageAt());
        assertNotNull(record.createdAt());
    }

    @Test
    @DisplayName("J6 mySessions → 带 keyword 时透传模糊搜索条件")
    void mySessions_withKeyword_passesThrough() {
        Page<SessionVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(sessionVO(1L, "RAG 讲义问答")));
        paged.setTotal(1);
        when(sessionService.findSessionsByUser(5L, 1, 20, "RAG")).thenReturn(paged);

        controller.mySessions(studentRequest(5L), 1, 20, "RAG");

        verify(sessionService).findSessionsByUser(5L, 1, 20, "RAG");
    }

    // ==================== J7: 创建会话 ====================

    @Test
    @DisplayName("J7 createSession → 未传标题时使用默认标题「新对话」")
    void createSession_noTitle_usesDefault() {
        when(sessionService.createSession(5L, "新对话")).thenReturn(sessionVO(1L, "新对话"));

        ApiResponse<SessionVO> result = controller.createSession(studentRequest(5L), new CreateSessionRequest(null));

        assertEquals("新对话", result.data().title());
        verify(sessionService).createSession(5L, "新对话");
    }

    @Test
    @DisplayName("J7 createSession → 全空白标题按缺省处理（trim 兜底）")
    void createSession_blankTitle_usesDefault() {
        when(sessionService.createSession(5L, "新对话")).thenReturn(sessionVO(1L, "新对话"));

        ApiResponse<SessionVO> result = controller.createSession(studentRequest(5L), new CreateSessionRequest("   "));

        assertEquals("新对话", result.data().title());
        verify(sessionService).createSession(5L, "新对话");
    }

    @Test
    @DisplayName("J7 createSession → 传入标题时透传创建（trim 去首尾空白）")
    void createSession_withTitle_usesProvidedTitle() {
        when(sessionService.createSession(5L, "自定义标题")).thenReturn(sessionVO(2L, "自定义标题"));

        ApiResponse<SessionVO> result =
                controller.createSession(studentRequest(5L), new CreateSessionRequest("  自定义标题  "));

        assertEquals("自定义标题", result.data().title());
        verify(sessionService).createSession(5L, "自定义标题");
    }

    // ==================== 重命名会话（会话管理：改） ====================

    @Test
    @DisplayName("重命名会话 → 归属本人时更新标题并返回最新 VO")
    void renameSession_owner_renames() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 5L));
        when(sessionService.renameSession(1L, "新标题")).thenReturn(sessionVO(1L, "新标题"));

        ApiResponse<SessionVO> result =
                controller.renameSession(studentRequest(5L), 1L, new SessionRenameRequest("新标题"));

        assertEquals(0, result.code());
        assertEquals("新标题", result.data().title());
        verify(sessionService).renameSession(1L, "新标题");
    }

    @Test
    @DisplayName("重命名会话 → 会话不存在抛 404，不触发更新")
    void renameSession_sessionNotFound_throws404() {
        when(sessionService.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(
                BizException.class,
                () -> controller.renameSession(studentRequest(5L), 99L, new SessionRenameRequest("新标题")));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertEquals("会话不存在", ex.getMessage());
        verify(sessionService, never()).renameSession(anyLong(), any());
    }

    @Test
    @DisplayName("重命名会话 → 非本人会话抛 403")
    void renameSession_notOwner_throws403() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 9L));

        BizException ex = assertThrows(
                BizException.class,
                () -> controller.renameSession(studentRequest(5L), 1L, new SessionRenameRequest("新标题")));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
        assertEquals("无权重命名此会话", ex.getMessage());
        verify(sessionService, never()).renameSession(anyLong(), any());
    }

    @Test
    @DisplayName("重命名会话 → 请求体校验契约：title @NotBlank + @Size(max=300)（MVC 层拦截非法输入）")
    void renameSession_dtoValidationAnnotationsPresent() throws Exception {
        Method method = StudentController.class.getMethod(
                "renameSession", HttpServletRequest.class, Long.class, SessionRenameRequest.class);
        Parameter[] params = method.getParameters();
        assertNotNull(params[2].getAnnotation(Valid.class), "请求体应 @Valid 校验");

        Field titleField = SessionRenameRequest.class.getDeclaredField("title");
        assertNotNull(titleField.getAnnotation(NotBlank.class));
        Size size = titleField.getAnnotation(Size.class);
        assertNotNull(size);
        assertEquals(300, size.max());
    }

    // ==================== 历史消息（R1 补口 A） ====================

    /** 构造归属用户的会话摘要 VO（归属校验经 sessionService.findById 出参完成） */
    private ChatSessionVO chatSessionVO(Long id, Long userId) {
        return new ChatSessionVO(
                id,
                userId,
                "会话" + id,
                "ACTIVE",
                LocalDateTime.of(2026, 8, 15, 10, 0),
                "qwen3.8-max",
                LocalDateTime.of(2026, 8, 15, 9, 0));
    }

    /** 构造带解析后 sources/attachments 的学生消息 VO（模拟 service 出参；thinkingStage 见时间线改版用例；
     *  M4 runStatus/errorMessage 仅终态行由 service 下发，controller 层测试构造用例不涉及——恒 null） */
    private StudentMessageVO studentMessageVO(Long id, String role) {
        return new StudentMessageVO(
                id,
                role,
                "内容-" + id,
                null,
                null,
                "knowledge_question",
                10L,
                1,
                LocalDateTime.of(2026, 8, 15, 9, 1),
                List.of(new RetrievalSource("101", "RAG 讲义", "Ch3 > 3.2", 0.87)),
                List.of(new AttachmentRecord("image", "0/a.png", "a.png", 1024L)),
                null,
                null);
    }

    @Test
    @DisplayName("历史消息 → 归属校验通过后返回分页消息（含 sources/attachments 解析数组与 thinking 阶段键）")
    void sessionMessages_returnsParsedArrays() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 5L));
        Page<StudentMessageVO> paged = new Page<>(1, 200);
        // 2026-08-28 时间线改版：thinking 行携带 thinkingStage 下发（前端分段归组渲染；null 语义=降级 generating）
        StudentMessageVO thinkingRow = new StudentMessageVO(
                3L,
                "ASSISTANT",
                "意图分析思考",
                "thinking",
                "understanding",
                null,
                10L,
                2,
                LocalDateTime.of(2026, 8, 15, 9, 1),
                List.of(),
                List.of(),
                null,
                null);
        paged.setRecords(List.of(studentMessageVO(1L, "USER"), studentMessageVO(2L, "ASSISTANT"), thinkingRow));
        paged.setTotal(3);
        when(messageService.findStudentMessagesBySession(1L, 1, 200)).thenReturn(paged);

        ApiResponse<PageResponse<StudentMessageVO>> result = controller.sessionMessages(studentRequest(5L), 1L, 1, 200);

        PageResponse<StudentMessageVO> data = result.data();
        assertEquals(0, result.code());
        assertEquals(3, data.records().size());
        assertEquals(3L, data.total());
        assertEquals(1, data.page());
        assertEquals(200, data.size());
        StudentMessageVO first = data.records().get(0);
        assertEquals(1L, first.id());
        assertEquals("USER", first.role());
        assertEquals("knowledge_question", first.intentType());
        assertEquals(10L, first.runId());
        // sources 为服务端解析后的对象数组（chunkId/docTitle/headingPath/score）
        assertEquals(1, first.sources().size());
        assertEquals("RAG 讲义", first.sources().get(0).docTitle());
        assertEquals(0.87, first.sources().get(0).score());
        // attachments 为服务端解析后的对象数组（type/url/name/size）
        assertEquals("0/a.png", first.attachments().get(0).url());
        assertEquals(1024L, first.attachments().get(0).size());
        // thinking 行阶段键经接口透传（同构实时 THINKING 事件的 stage 字段）
        StudentMessageVO thinking = data.records().get(2);
        assertEquals("thinking", thinking.messageType());
        assertEquals("understanding", thinking.thinkingStage());
    }

    @Test
    @DisplayName("历史消息 → 会话不存在抛 404，不触发消息查询")
    void sessionMessages_sessionNotFound_throws404() {
        when(sessionService.findById(99L)).thenReturn(null);

        BizException ex =
                assertThrows(BizException.class, () -> controller.sessionMessages(studentRequest(5L), 99L, 1, 200));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertEquals("会话不存在", ex.getMessage());
        verify(messageService, never()).findStudentMessagesBySession(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("历史消息 → 非本人会话抛 403（会话语义，与 ChatStreamEntry 先例一致）")
    void sessionMessages_notOwner_throws403() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 9L));

        BizException ex =
                assertThrows(BizException.class, () -> controller.sessionMessages(studentRequest(5L), 1L, 1, 200));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
        assertEquals("无权查看此会话", ex.getMessage());
        verify(messageService, never()).findStudentMessagesBySession(anyLong(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("历史消息 → 分页参数缺省为 page=1 与 size=200（注解 defaultValue + 透传）")
    void sessionMessages_defaultPageParams_are1And200() throws Exception {
        // 注解层：@RequestParam defaultValue 断言（缺省契约由 MVC 层按注解填充）
        Method method = StudentController.class.getMethod(
                "sessionMessages", HttpServletRequest.class, Long.class, int.class, int.class);
        Parameter[] params = method.getParameters();
        assertEquals("1", params[2].getAnnotation(RequestParam.class).defaultValue());
        assertEquals("200", params[3].getAnnotation(RequestParam.class).defaultValue());

        // 行为层：缺省值（1/200）透传给 service
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 5L));
        Page<StudentMessageVO> paged = new Page<>(1, 200);
        paged.setRecords(List.of());
        paged.setTotal(0);
        when(messageService.findStudentMessagesBySession(1L, 1, 200)).thenReturn(paged);

        controller.sessionMessages(studentRequest(5L), 1L, 1, 200);

        verify(messageService).findStudentMessagesBySession(1L, 1, 200);
    }

    // ==================== 删除会话（R3 补口 C） ====================

    @Test
    @DisplayName("删除会话 → 会话不存在抛 404，不触发活跃 run 校验与删除")
    void deleteSession_sessionNotFound_throws404() {
        when(sessionService.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.deleteSession(studentRequest(5L), 99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
        assertEquals("会话不存在", ex.getMessage());
        verify(runService, never()).existsActiveRun(anyLong());
        verify(sessionService, never()).deleteSession(anyLong(), anyLong());
    }

    @Test
    @DisplayName("删除会话 → 非本人会话抛 403，不触发活跃 run 校验与删除")
    void deleteSession_notOwner_throws403() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 9L));

        BizException ex = assertThrows(BizException.class, () -> controller.deleteSession(studentRequest(5L), 1L));

        assertEquals(HttpStatus.FORBIDDEN.value(), ex.getCode());
        assertEquals("无权删除此会话", ex.getMessage());
        verify(runService, never()).existsActiveRun(anyLong());
        verify(sessionService, never()).deleteSession(anyLong(), anyLong());
    }

    @Test
    @DisplayName("删除会话 → 存在 QUEUED/ACTIVE run 时抛 409，级联软删不执行")
    void deleteSession_activeRun_throws409() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 5L));
        when(runService.existsActiveRun(1L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> controller.deleteSession(studentRequest(5L), 1L));

        assertEquals(HttpStatus.CONFLICT.value(), ex.getCode());
        assertEquals("会话正在对话中，请稍后删除", ex.getMessage());
        verify(sessionService, never()).deleteSession(anyLong(), anyLong());
    }

    @Test
    @DisplayName("删除会话 → 归属本人且无活跃 run 时级联软删（操作者=当前用户）")
    void deleteSession_ownerIdle_deletes() {
        when(sessionService.findById(1L)).thenReturn(chatSessionVO(1L, 5L));
        when(runService.existsActiveRun(1L)).thenReturn(false);

        ApiResponse<Void> result = controller.deleteSession(studentRequest(5L), 1L);

        assertEquals(0, result.code());
        assertNull(result.data());
        verify(sessionService).deleteSession(1L, 5L);
    }

    // ==================== J8: SSE 流式对话 ====================

    @Test
    @DisplayName("J8 chatStream → 原样转发到 ChatStreamEntry")
    void chatStream_forwardsToChatStreamEntry() {
        ChatRequest chatRequest = new ChatRequest(1L, "什么是 RAG？");
        // 转发端点不读取请求属性，直接用裸 mock
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(chatStreamEntry.chat(req, resp, chatRequest)).thenReturn(emitter);

        var result = controller.chatStream(req, resp, chatRequest);

        assertSame(emitter, result);
    }
}
