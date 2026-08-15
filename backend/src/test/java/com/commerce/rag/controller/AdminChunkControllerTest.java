package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.BatchCorrectedRequest;
import com.commerce.rag.controller.dto.ChunkContentUpdateRequest;
import com.commerce.rag.service.DocumentChunkService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * AdminChunkController 契约测试 —— 端点与前端设计文档对齐（P2-2）
 *
 * <p>锁定契约：PUT /{id}（分片编辑）、POST /batch-corrected（批量标记已修正）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminChunkController 契约测试")
class AdminChunkControllerTest {

    @Mock
    private DocumentChunkService chunkService;

    private AdminChunkController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminChunkController(chunkService);
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("SUPER_ADMIN");
        return req;
    }

    @Test
    @DisplayName("契约 — updateContent 映射 PUT /{id}（前端文档 :933）")
    void updateContent_mapsToPutId() throws Exception {
        var method = AdminChunkController.class.getMethod(
                "updateContent", HttpServletRequest.class, Long.class, ChunkContentUpdateRequest.class);
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertNotNull(mapping, "必须为 @PutMapping");
        assertArrayEquals(new String[] {"/{id}"}, mapping.value(), "路径应为 /{id}");

        controller.updateContent(adminRequest(), 1L, new ChunkContentUpdateRequest("新内容"));
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

        controller.batchCorrected(adminRequest(), new BatchCorrectedRequest(List.of(1L, 2L)));
        verify(chunkService).batchCorrected(eq(List.of(1L, 2L)), eq(1L), eq(true));
    }
}
