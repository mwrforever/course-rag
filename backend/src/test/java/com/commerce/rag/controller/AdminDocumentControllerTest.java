package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.etl.EtlProperties;
import com.commerce.rag.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
