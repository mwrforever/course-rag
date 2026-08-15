package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.controller.dto.DocumentUpdateRequest;
import com.commerce.rag.controller.dto.PageResponse;
import com.commerce.rag.controller.vo.DocumentVO;
import com.commerce.rag.etl.EtlProperties;
import com.commerce.rag.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminDocumentController 单元测试 —— 上传白名单与大小校验（P2-1）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDocumentController 上传校验测试")
class AdminDocumentControllerTest {

    @Mock
    private DocumentService documentService;

    private AdminDocumentController controller;

    @BeforeEach
    void setUp() {
        EtlProperties props =
                new EtlProperties(100, new EtlProperties.Executor(2, 4, 20, "etl-"), new EtlProperties.Chunk(768, 128));
        controller = new AdminDocumentController(documentService, props);
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("SUPER_ADMIN");
        return req;
    }

    @Test
    @DisplayName("upload 非法文件类型（.exe）→ 400，不触发上传")
    void upload_invalidType_throws400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[10]);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.upload(adminRequest(), 1L, "doc", null, file));
        assertEquals(400, ex.getStatusCode().value());
        verify(documentService, never()).upload(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upload 超过 maxFileSizeMb → 400，不触发上传")
    void upload_tooLarge_throws400() throws Exception {
        // 100MB 限制，构造 100MB+1 的流（仅 size 校验，不真正读内容）
        MockMultipartFile file =
                new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[100 * 1024 * 1024 + 1]);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.upload(adminRequest(), 1L, "doc", null, file));
        assertEquals(400, ex.getStatusCode().value());
        verify(documentService, never()).upload(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upload 合法类型（.pdf）→ 放行，调用 service")
    void upload_validType_succeeds() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile("file", "doc.pdf", "application/pdf", "内容".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> controller.upload(adminRequest(), 1L, "doc", null, file));
        verify(documentService).upload(eq(1L), eq("doc"), any(), eq("pdf"), eq(6L), isNull(), eq(1L), eq(true));
    }

    @Test
    @DisplayName("upload 无扩展名文件 → 按 bin 拒绝（400）")
    void upload_noExtension_throws400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "noext", "application/octet-stream", new byte[10]);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> controller.upload(adminRequest(), 1L, "doc", null, file));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    @DisplayName("C2 findById → 存在时返回文档 VO")
    void findById_returnsVO() {
        DocumentVO doc = new DocumentVO(1L, 1L, "文档一", "pdf", 100L, "PARSED", 5, null, null, "DEFAULT", 1L, null, null);
        when(documentService.findById(1L, 1L, "SUPER_ADMIN")).thenReturn(doc);

        ApiResponse<DocumentVO> result = controller.findById(adminRequest(), 1L);

        assertEquals("文档一", result.data().title());
    }

    @Test
    @DisplayName("C2 findById → 不存在抛 404")
    void findById_notFound_throws404() {
        when(documentService.findById(99L, 1L, "SUPER_ADMIN")).thenReturn(null);

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, () -> controller.findById(adminRequest(), 99L));

        assertEquals(404, ex.getStatusCode().value());
        assertEquals("文档不存在", ex.getReason());
    }

    @Test
    @DisplayName("C3 findPage → 透传筛选条件返回分页")
    void findPage_returnsPaged() {
        Page<DocumentVO> paged = new Page<>(1, 20);
        when(documentService.findPage(1L, "PARSED", "关键词", "created_desc", 1, 20, 1L, "SUPER_ADMIN"))
                .thenReturn(paged);

        ApiResponse<PageResponse<DocumentVO>> result =
                controller.findPage(adminRequest(), 1L, "PARSED", "关键词", "created_desc", 1, 20);

        assertNotNull(result.data());
    }

    @Test
    @DisplayName("C4 update → 透传标题与归属标记")
    void update_passesThrough() {
        controller.update(adminRequest(), 1L, new DocumentUpdateRequest("新标题"));

        verify(documentService).update(1L, "新标题", 1L, true);
    }

    @Test
    @DisplayName("C5 delete → 透传归属标记")
    void delete_passesThrough() {
        controller.delete(adminRequest(), 1L);

        verify(documentService).delete(1L, 1L, true);
    }

    @Test
    @DisplayName("C6 reparse → 透传归属标记")
    void reparse_passesThrough() {
        controller.reparse(adminRequest(), 1L);

        verify(documentService).reparse(1L, 1L, true);
    }

    @Test
    @DisplayName("C7 download → 返回带类型后缀文件名的 InputStreamResource")
    void download_returnsResourceWithFilename() {
        when(documentService.downloadWithType(1L, 1L, true))
                .thenReturn(new DocumentService.DocumentDownload(new ByteArrayInputStream(new byte[0]), "pdf"));

        Resource resource = controller.download(adminRequest(), 1L);

        assertEquals("document-1.pdf", resource.getFilename());
        assertInstanceOf(InputStreamResource.class, resource);
    }
}
