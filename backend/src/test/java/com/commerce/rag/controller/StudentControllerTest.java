package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.ChatRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.service.ChatSessionService;
import com.commerce.rag.service.CourseService;
import com.commerce.rag.service.DocumentChunkService;
import com.commerce.rag.service.EnrollmentService;
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
import org.springframework.web.server.ResponseStatusException;
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
    private CourseService courseService;

    @Mock
    private EnrollmentService enrollmentService;

    @Mock
    private ChatSessionService sessionService;

    @Mock
    private DocumentChunkService documentChunkService;

    @Mock
    private ChatController chatController;

    private StudentController controller;

    @BeforeEach
    void setUp() {
        controller = new StudentController(
                courseService, enrollmentService, sessionService, documentChunkService, chatController);
    }

    private HttpServletRequest studentRequest(Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        return req;
    }

    private CourseInfo course(Long id, String title) {
        CourseInfo c = new CourseInfo();
        c.setId(id);
        c.setTitle(title);
        c.setCoverImage("cover.png");
        c.setCategory("编程");
        c.setInstructorName("张老师");
        c.setDuration("10h");
        c.setRating(new BigDecimal("4.5"));
        c.setLearningCount(100);
        return c;
    }

    private DocumentChunk chunk(Long id, String courseId) {
        DocumentChunk c = new DocumentChunk();
        c.setId(id);
        c.setCourseId(courseId);
        c.setContent("内容-" + id);
        c.setHeadingPath("第一章");
        c.setChunkIndex(1);
        c.setParentTitle("小节");
        c.setStartPage(1);
        c.setEndPage(2);
        return c;
    }

    // ==================== J1: 我的课程 ====================

    @Test
    @DisplayName("J1 myCourses → 返回已选课列表（字段映射完整）")
    void myCourses_returnsEnrolledCourses() {
        when(enrollmentService.findStudentCourses(5L)).thenReturn(List.of(course(1L, "Java 入门"), course(2L, "Spring")));

        ApiResponse<List<Map<String, Object>>> result = controller.myCourses(studentRequest(5L));

        assertEquals(0, result.code());
        assertEquals(2, result.data().size());
        Map<String, Object> first = result.data().get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("Java 入门", first.get("title"));
        assertEquals("cover.png", first.get("coverImage"));
        assertEquals("编程", first.get("category"));
        assertEquals("张老师", first.get("instructorName"));
        assertEquals("10h", first.get("duration"));
        assertEquals(new BigDecimal("4.5"), first.get("rating"));
        assertEquals(100, first.get("learningCount"));
    }

    @Test
    @DisplayName("J1 myCourses → 无选课时返回空列表")
    void myCourses_noEnrollment_returnsEmpty() {
        when(enrollmentService.findStudentCourses(5L)).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> result = controller.myCourses(studentRequest(5L));

        assertTrue(result.data().isEmpty());
    }

    // ==================== J2: 课程专属资料 ====================

    @Test
    @DisplayName("J2 courseMaterials → 未选课抛 403")
    void courseMaterials_notEnrolled_throws403() {
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(false);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.courseMaterials(studentRequest(5L), 10L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(documentChunkService, never()).findByCourseId(anyLong());
    }

    @Test
    @DisplayName("J2 courseMaterials → 已选课返回课程专属 chunk 列表")
    void courseMaterials_enrolled_returnsChunks() {
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(true);
        DocumentChunk c = chunk(1L, "10");
        c.setParentChunkId(0L);
        when(documentChunkService.findByCourseId(10L)).thenReturn(List.of(c));

        ApiResponse<List<Map<String, Object>>> result = controller.courseMaterials(studentRequest(5L), 10L);

        Map<String, Object> first = result.data().get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("内容-1", first.get("content"));
        assertEquals("第一章", first.get("headingPath"));
        assertEquals(1, first.get("chunkIndex"));
        assertEquals("小节", first.get("parentTitle"));
        assertEquals(1, first.get("startPage"));
        assertEquals(2, first.get("endPage"));
    }

    // ==================== J3: 通用资料库 ====================

    @Test
    @DisplayName("J3 knowledgeBase → 分页返回 DEFAULT 通用库 chunk")
    void knowledgeBase_returnsPagedChunks() {
        Page<DocumentChunk> paged = new Page<>(2, 20);
        paged.setRecords(List.of(chunk(1L, "DEFAULT")));
        paged.setTotal(1);
        when(documentChunkService.findByCourseIdDefault(2, 20)).thenReturn(paged);

        ApiResponse<PageResponse<Map<String, Object>>> result = controller.knowledgeBase(2, 20);

        PageResponse<Map<String, Object>> data = result.data();
        assertEquals(1, data.records().size());
        assertEquals(1L, data.total());
        assertEquals(2, data.page());
        assertEquals(20, data.size());
        assertEquals("内容-1", data.records().get(0).get("content"));
    }

    // ==================== J4: 分片上下文 ====================

    @Test
    @DisplayName("J4 chunkContext → 分片不存在抛 404")
    void chunkContext_notFound_throws404() {
        when(documentChunkService.findById(99L)).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.chunkContext(studentRequest(5L), 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("J4 chunkContext → 课程专属 chunk 且未选课抛 403")
    void chunkContext_courseChunkNotEnrolled_throws403() {
        when(documentChunkService.findById(1L)).thenReturn(chunk(1L, "10"));
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(false);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.chunkContext(studentRequest(5L), 1L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("J4 chunkContext → DEFAULT 分片免选课校验，返回上下文")
    void chunkContext_defaultChunk_returnsContext() {
        DocumentChunk c = chunk(1L, "DEFAULT");
        c.setDocId(1L);
        c.setKbId(1L);
        when(documentChunkService.findById(1L)).thenReturn(c);

        ApiResponse<Map<String, Object>> result = controller.chunkContext(studentRequest(5L), 1L);

        assertEquals("DEFAULT", result.data().get("courseId"));
        assertEquals(1L, result.data().get("docId"));
        assertEquals(1L, result.data().get("kbId"));
        // 无关联 chunk 时不出现 parent/prev/next 键
        assertFalse(result.data().containsKey("parent"));
        assertFalse(result.data().containsKey("prev"));
        assertFalse(result.data().containsKey("next"));
        verify(enrollmentService, never()).isEnrolled(anyLong(), anyLong());
    }

    @Test
    @DisplayName("J4 chunkContext → 课程专属分片已选课，且带 parent/prev/next 关联")
    void chunkContext_withNeighbors_returnsContext() {
        DocumentChunk c = chunk(1L, "10");
        c.setParentChunkId(100L);
        c.setPrevChunkId(101L);
        c.setNextChunkId(102L);
        when(documentChunkService.findById(1L)).thenReturn(c);
        when(enrollmentService.isEnrolled(10L, 5L)).thenReturn(true);
        when(documentChunkService.findById(100L)).thenReturn(chunk(100L, "10"));
        when(documentChunkService.findById(101L)).thenReturn(chunk(101L, "10"));
        when(documentChunkService.findById(102L)).thenReturn(chunk(102L, "10"));

        ApiResponse<Map<String, Object>> result = controller.chunkContext(studentRequest(5L), 1L);

        Map<String, Object> data = result.data();
        assertEquals(100L, ((Map<?, ?>) data.get("parent")).get("id"));
        assertEquals(101L, ((Map<?, ?>) data.get("prev")).get("id"));
        assertEquals(102L, ((Map<?, ?>) data.get("next")).get("id"));
        // 关联 chunk 只输出摘要字段
        assertEquals("内容-100", ((Map<?, ?>) data.get("parent")).get("content"));
        assertFalse(((Map<?, ?>) data.get("parent")).containsKey("courseId"));
    }

    @Test
    @DisplayName("J4 chunkContext → 关联 chunk 缺失时静默跳过该键")
    void chunkContext_missingNeighbor_omitsKey() {
        DocumentChunk c = chunk(1L, "DEFAULT");
        c.setParentChunkId(100L);
        when(documentChunkService.findById(1L)).thenReturn(c);
        when(documentChunkService.findById(100L)).thenReturn(null);

        ApiResponse<Map<String, Object>> result = controller.chunkContext(studentRequest(5L), 1L);

        assertFalse(result.data().containsKey("parent"));
    }

    // ==================== J6: 我的会话 ====================

    @Test
    @DisplayName("J6 mySessions → 分页返回会话列表")
    void mySessions_returnsPagedSessions() {
        Page<ChatSession> paged = new Page<>(1, 20);
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setUserId(5L);
        s.setTitle("会话一");
        s.setStatus("ACTIVE");
        s.setLastMessageAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        s.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        paged.setRecords(List.of(s));
        paged.setTotal(1);
        when(sessionService.findSessionsByUser(5L, 1, 20)).thenReturn(paged);

        ApiResponse<PageResponse<Map<String, Object>>> result = controller.mySessions(studentRequest(5L), 1, 20);

        Map<String, Object> record = result.data().records().get(0);
        assertEquals("会话一", record.get("title"));
        assertEquals("ACTIVE", record.get("status"));
        assertNotNull(record.get("lastMessageAt"));
        assertNotNull(record.get("createdAt"));
    }

    // ==================== J7: 创建会话 ====================

    @Test
    @DisplayName("J7 createSession → 未传标题时使用默认标题「新对话」")
    void createSession_noTitle_usesDefault() {
        ChatSession s = new ChatSession();
        s.setId(1L);
        s.setTitle("新对话");
        s.setStatus("ACTIVE");
        s.setCreatedAt(LocalDateTime.now());
        when(sessionService.createSession(5L, "新对话")).thenReturn(s);

        ApiResponse<Map<String, Object>> result = controller.createSession(studentRequest(5L), Map.of());

        assertEquals("新对话", result.data().get("title"));
        verify(sessionService).createSession(5L, "新对话");
    }

    @Test
    @DisplayName("J7 createSession → 传入标题时透传创建")
    void createSession_withTitle_usesProvidedTitle() {
        ChatSession s = new ChatSession();
        s.setId(2L);
        s.setTitle("自定义标题");
        s.setStatus("ACTIVE");
        when(sessionService.createSession(5L, "自定义标题")).thenReturn(s);

        ApiResponse<Map<String, Object>> result =
                controller.createSession(studentRequest(5L), Map.of("title", "自定义标题"));

        assertEquals("自定义标题", result.data().get("title"));
    }

    // ==================== J8: SSE 流式对话 ====================

    @Test
    @DisplayName("J8 chatStream → 原样转发到 ChatController")
    void chatStream_forwardsToChatController() {
        ChatRequest chatRequest = new ChatRequest(1L, "什么是 RAG？");
        // 转发端点不读取请求属性，直接用裸 mock
        HttpServletRequest req = mock(HttpServletRequest.class);
        SseEmitter emitter = mock(SseEmitter.class);
        when(chatController.chat(req, chatRequest)).thenReturn(emitter);

        var result = controller.chatStream(req, chatRequest);

        assertSame(emitter, result);
    }
}
