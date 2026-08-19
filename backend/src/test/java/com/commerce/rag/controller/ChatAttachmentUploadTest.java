package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.dto.ApiResponse;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IAttachmentService;
import com.commerce.rag.stream.ChatStreamEntry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * 附件上传端点测试 —— 委托 AttachmentService 并原样包装返回（薄控制器转发契约）
 *
 * <p>ChatController 构造器注入双依赖（ChatStreamEntry + IAttachmentService），
 * 本测试仅关注 /attachments 端点的参数绑定与委托返回，不涉及流式编排。
 */
class ChatAttachmentUploadTest {

    @Test
    @DisplayName("POST /chat/attachments — 委托 service 返回附件记录并包装为 ApiResponse")
    void upload_delegatesToService() {
        // Given
        IAttachmentService service = mock(IAttachmentService.class);
        ChatController controller = new ChatController(mock(ChatStreamEntry.class), service);
        MockMultipartFile f = new MockMultipartFile("files", "a.png", "image/png", new byte[1]);
        when(service.upload(any())).thenReturn(List.of(new AttachmentRecord("image", "0/uuid.png", "a.png", 1L)));

        // When
        ApiResponse<List<AttachmentRecord>> resp = controller.uploadAttachments(new MockMultipartFile[] {f});

        // Then: 原样委托上传 + 成功码包装 + 附件记录完整透出
        verify(service).upload(any(MultipartFile[].class));
        assertEquals(0, resp.code());
        assertEquals(1, resp.data().size());
        assertEquals("image", resp.data().get(0).type());
        assertEquals("0/uuid.png", resp.data().get(0).url());
        assertEquals("a.png", resp.data().get(0).name());
        assertEquals(1L, resp.data().get(0).size());
    }
}
