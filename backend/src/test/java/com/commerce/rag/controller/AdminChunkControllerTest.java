package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.BatchChunkUpdateRequest;
import com.commerce.rag.controller.dto.BatchCorrectedRequest;
import com.commerce.rag.controller.dto.ChunkCollectionTypeRequest;
import com.commerce.rag.controller.dto.ChunkContentUpdateRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.vo.DocumentChunkVO;
import com.commerce.rag.service.DocumentChunkService;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminChunkController 单元测试 —— 分片管理端点 D1-D9（含契约断言）
 *
 * <p>契约锁定（P2-2 前端文档对齐）：PUT /{id}、POST /batch-corrected。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminChunkController 分片管理端点测试")
class AdminChunkControllerTest {

    @Mock
    private DocumentChunkService chunkService;

    private AdminChunkController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminChunkController(chunkService);
    }

    private HttpServletRequest request(String role, Long userId) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(userId);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn(role);
        return req;
    }

    private DocumentChunkVO chunkVO(Long id) {
        return new DocumentChunkVO(
                id,
                1L,
                1L,
                1,
                "内容",
                "第一章",
                "小节",
                1,
                2,
                10,
                "TECHNICAL_QA",
                "DEFAULT",
                null,
                "milvus-pk",
                null,
                null,
                null,
                null,
                null,
                "NONE",
                LocalDateTime.now(),
                null);
    }

    // ==================== 契约断言 ====================

    @Test
    @DisplayName("契约 — 类级映射 @RequestMapping /api/v1/admin/chunks")
    void classLevelMapping() {
        RequestMapping mapping = AdminChunkController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "必须声明类级 @RequestMapping");
        assertArrayEquals(new String[] {"/api/v1/admin/chunks"}, mapping.value(), "前缀应为 /api/v1/admin/chunks");
    }

    @Test
    @DisplayName("契约 — updateContent 映射 PUT /{id}（前端文档 :933）")
    void updateContent_mapsToPutId() throws Exception {
        var method = AdminChunkController.class.getMethod(
                "updateContent", HttpServletRequest.class, Long.class, ChunkContentUpdateRequest.class);
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertNotNull(mapping, "必须为 @PutMapping");
        assertArrayEquals(new String[] {"/{id}"}, mapping.value(), "路径应为 /{id}");

        controller.updateContent(request("SUPER_ADMIN", 1L), 1L, new ChunkContentUpdateRequest("新内容"));
        verify(chunkService).updateContent(eq(1L), eq("新内容"), eq(1L), eq(true));
    }

    @Test
    @DisplayName("契约 — batchCorrected 映射 POST /batch-corrected（前端文档 :926）")
    void batchCorrected_mapsToPost() throws Exception {
        var method = AdminChunkController.class.getMethod(
                "batchCorrected", HttpServletRequest.class, BatchCorrectedRequest.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping, "必须为 @PostMapping");
        assertArrayEquals(new String[] {"/batch-corrected"}, mapping.value(), "路径应为 /batch-corrected");

        controller.batchCorrected(request("SUPER_ADMIN", 1L), new BatchCorrectedRequest(List.of(1L, 2L)));
        verify(chunkService).batchCorrected(eq(List.of(1L, 2L)), eq(1L), eq(true));
    }

    // ==================== 端点功能 ====================

    @Test
    @DisplayName("D1 findById → 存在时返回分片 VO")
    void findById_returnsVO() {
        when(chunkService.findById(1L, 7L, "TEACHER")).thenReturn(chunkVO(1L));

        ApiResponse<DocumentChunkVO> result = controller.findById(request("TEACHER", 7L), 1L);

        assertEquals(1L, result.data().id());
        verify(chunkService).findById(1L, 7L, "TEACHER");
    }

    @Test
    @DisplayName("D1 findById → 不存在抛 404")
    void findById_notFound_throws404() {
        when(chunkService.findById(99L, 1L, "SUPER_ADMIN")).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.findById(request("SUPER_ADMIN", 1L), 99L));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("分片不存在", ex.getReason());
    }

    @Test
    @DisplayName("D2 findPage → 透传筛选条件返回分页")
    void findPage_returnsPaged() {
        Page<DocumentChunkVO> paged = new Page<>(1, 20);
        paged.setRecords(List.of(chunkVO(1L)));
        paged.setTotal(1);
        when(chunkService.findPage(1L, 2L, 1, 20, 7L, "TEACHER")).thenReturn(paged);

        ApiResponse<PageResponse<DocumentChunkVO>> result = controller.findPage(request("TEACHER", 7L), 1L, 2L, 1, 20);

        assertEquals(1, result.data().records().size());
    }

    @Test
    @DisplayName("D3 updateContent → 教师透传 isAdmin=false")
    void updateContent_teacher_passesAdminFlagFalse() {
        controller.updateContent(request("TEACHER", 7L), 1L, new ChunkContentUpdateRequest("新内容"));

        verify(chunkService).updateContent(1L, "新内容", 7L, false);
    }

    @Test
    @DisplayName("D4 delete → 透传归属标记")
    void delete_passesThrough() {
        controller.delete(request("SUPER_ADMIN", 1L), 1L);

        verify(chunkService).delete(1L, 1L, true);
    }

    @Test
    @DisplayName("D5 updateCollectionType → 透传 collectionType/courseId")
    void updateCollectionType_passesThrough() {
        controller.updateCollectionType(
                request("TEACHER", 7L), 1L, new ChunkCollectionTypeRequest("COURSE_INFO", "10"));

        verify(chunkService).updateCollectionType(1L, "COURSE_INFO", "10", 7L, false);
    }

    @Test
    @DisplayName("D6 findContext → 返回 parent/prev/current/next 上下文 Map")
    void findContext_returnsMap() {
        Map<String, DocumentChunkVO> context = Map.of("current", chunkVO(1L));
        when(chunkService.findContext(1L, 7L, "TEACHER")).thenReturn(context);

        ApiResponse<Map<String, DocumentChunkVO>> result = controller.findContext(request("TEACHER", 7L), 1L);

        assertEquals(1L, result.data().get("current").id());
    }

    @Test
    @DisplayName("D7 batchUpdate → 透传 ID 列表与标量字段")
    void batchUpdate_passesThrough() {
        BatchChunkUpdateRequest req = new BatchChunkUpdateRequest(List.of(1L, 2L), "COURSE_INFO", "10");

        controller.batchUpdate(request("SUPER_ADMIN", 1L), req);

        verify(chunkService).batchUpdate(List.of(1L, 2L), "COURSE_INFO", "10", 1L, true);
    }

    @Test
    @DisplayName("D8 batchCorrected → 教师透传 isAdmin=false")
    void batchCorrected_teacher_passesAdminFlagFalse() {
        controller.batchCorrected(request("TEACHER", 7L), new BatchCorrectedRequest(List.of(1L)));

        verify(chunkService).batchCorrected(List.of(1L), 7L, false);
    }

    @Test
    @DisplayName("D9 findPending → 透传筛选条件返回待修正分页")
    void findPending_returnsPaged() {
        Page<DocumentChunkVO> paged = new Page<>(1, 20);
        when(chunkService.findPending(1L, 2L, 1, 20, 7L, "TEACHER")).thenReturn(paged);

        ApiResponse<PageResponse<DocumentChunkVO>> result =
                controller.findPending(request("TEACHER", 7L), 1L, 2L, 1, 20);

        assertNotNull(result.data());
        verify(chunkService).findPending(1L, 2L, 1, 20, 7L, "TEACHER");
    }
}
