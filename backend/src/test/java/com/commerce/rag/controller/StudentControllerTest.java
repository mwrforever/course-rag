package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.dto.ChatRequest;
import com.commerce.rag.dto.PageResponse;
import com.commerce.rag.exception.BizException;
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
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
    private IDocumentChunkService documentChunkService;

    @Mock
    private ChatStreamEntry chatStreamEntry;

    private StudentController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentController(enrollmentService, sessionService, documentChunkService, chatStreamEntry);
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
    @DisplayName("J6 mySessions → 分页返回会话列表（VO）")
    void mySessions_returnsPagedSessions() {
        Page<SessionVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(sessionVO(1L, "会话一")));
        paged.setTotal(1);
        when(sessionService.findSessionsByUser(5L, 1, 20)).thenReturn(paged);

        ApiResponse<PageResponse<SessionVO>> result = controller.mySessions(studentRequest(5L), 1, 20);

        SessionVO record = result.data().records().get(0);
        assertEquals("会话一", record.title());
        assertEquals("ACTIVE", record.status());
        assertNotNull(record.lastMessageAt());
        assertNotNull(record.createdAt());
    }

    // ==================== J7: 创建会话 ====================

    @Test
    @DisplayName("J7 createSession → 未传标题时使用默认标题「新对话」")
    void createSession_noTitle_usesDefault() {
        when(sessionService.createSession(5L, "新对话")).thenReturn(sessionVO(1L, "新对话"));

        ApiResponse<SessionVO> result = controller.createSession(studentRequest(5L), Map.of());

        assertEquals("新对话", result.data().title());
        verify(sessionService).createSession(5L, "新对话");
    }

    @Test
    @DisplayName("J7 createSession → 传入标题时透传创建")
    void createSession_withTitle_usesProvidedTitle() {
        when(sessionService.createSession(5L, "自定义标题")).thenReturn(sessionVO(2L, "自定义标题"));

        ApiResponse<SessionVO> result = controller.createSession(studentRequest(5L), Map.of("title", "自定义标题"));

        assertEquals("自定义标题", result.data().title());
    }

    // ==================== J8: SSE 流式对话 ====================

    @Test
    @DisplayName("J8 chatStream → 原样转发到 ChatStreamEntry")
    void chatStream_forwardsToChatStreamEntry() {
        ChatRequest chatRequest = new ChatRequest(1L, "什么是 RAG？");
        // 转发端点不读取请求属性，直接用裸 mock
        HttpServletRequest req = mock(HttpServletRequest.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(chatStreamEntry.chat(req, chatRequest)).thenReturn(emitter);

        var result = controller.chatStream(req, chatRequest);

        assertSame(emitter, result);
    }
}
