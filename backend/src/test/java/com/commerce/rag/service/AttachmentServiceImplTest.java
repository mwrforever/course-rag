package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.service.impl.AttachmentServiceImpl;
import com.commerce.rag.storage.MinioStorageService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 附件上传校验与 MinIO 落盘测试
 *
 * <p>覆盖限额校验全路径：合法上传 / 超单文件限额 / 超个数 / 超合计 / 不支持类型 / 附件为空，
 * 并验证非法路径不触发 MinIO 落盘（校验先于落盘）。
 */
class AttachmentServiceImplTest {

    /** MinIO 存储服务 mock（校验不通过时不产生任何调用） */
    private final MinioStorageService minio = mock(MinioStorageService.class);
    /** 限额配置：图片 10MB、文档 50MB、单次最多 10 个、合计 ≤100MB、批大小 16 */
    private final AttachmentProperties props = new AttachmentProperties(
            10,
            50,
            10,
            100,
            100,
            30,
            16,
            60000,
            new AttachmentProperties.Executor(2, 4, 20, "attachment-"),
            "qwen3.7-max-2026-06-08");

    private final AttachmentServiceImpl service = new AttachmentServiceImpl(minio, props);

    @Test
    @DisplayName("上传合法图片 — 返回 image 类型附件记录（url=MinIO objectKey）")
    void upload_validImage() {
        MockMultipartFile f = new MockMultipartFile("files", "a.png", "image/png", new byte[1024]);
        when(minio.uploadFile(eq(0L), any(), any(), eq("png"))).thenReturn("0/abc.png");

        var result = service.upload(new MockMultipartFile[] {f});

        assertEquals(1, result.size());
        assertEquals("image", result.get(0).type());
        assertEquals("0/abc.png", result.get(0).url());
        assertEquals("a.png", result.get(0).name());
        assertEquals(1024L, result.get(0).size());
    }

    @Test
    @DisplayName("上传超过单文件限额（11MB > 图片 10MB）— BizException 400，不落盘")
    void upload_overSize() {
        byte[] big = new byte[11 * 1024 * 1024];
        MockMultipartFile f = new MockMultipartFile("files", "big.png", "image/png", big);

        BizException e = assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[] {f}));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("单次超过 10 个附件 — BizException 400，不落盘")
    void upload_tooMany() {
        MockMultipartFile[] files = new MockMultipartFile[11];
        for (int i = 0; i < 11; i++) {
            files[i] = new MockMultipartFile("files", "a" + i + ".txt", "text/plain", new byte[10]);
        }

        BizException e = assertThrows(BizException.class, () -> service.upload(files));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("附件合计超过 100MB（6×20MB 各自未超单文件限额）— BizException 400，不落盘")
    void upload_overTotal() {
        MockMultipartFile[] files = new MockMultipartFile[6];
        for (int i = 0; i < 6; i++) {
            // 20MB < 文档单文件 50MB 限额，但 6×20=120MB > 合计 100MB 限额
            files[i] = new MockMultipartFile("files", "d" + i + ".txt", "text/plain", new byte[20 * 1024 * 1024]);
        }

        BizException e = assertThrows(BizException.class, () -> service.upload(files));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("不支持的文件类型（.exe）— BizException 400，不落盘")
    void upload_unsupportedType() {
        MockMultipartFile f = new MockMultipartFile("files", "a.exe", "application/octet-stream", new byte[10]);

        BizException e = assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[] {f}));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("混合合法+非法附件 — 第一遍全量校验先拦截，合法前序文件也不落盘（防 MinIO 孤儿对象）")
    void upload_mixedValidAndInvalid_noOrphanObject() {
        // file[0] 合法（png 1KB）、file[1] 非法（exe，不在白名单）
        MockMultipartFile valid = new MockMultipartFile("files", "a.png", "image/png", new byte[1024]);
        MockMultipartFile invalid = new MockMultipartFile("files", "b.exe", "application/octet-stream", new byte[10]);

        BizException e =
                assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[] {valid, invalid}));

        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        // 任一非法即整批不落盘：既不会为 file[1] 落盘，也不会为合法的 file[0] 落盘
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("附件为空（null 或空数组）— BizException 400，不落盘")
    void upload_empty() {
        BizException nullEx = assertThrows(BizException.class, () -> service.upload(null));
        assertEquals(ErrorCode.BAD_REQUEST, nullEx.getErrorCode());

        BizException emptyEx = assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[0]));
        assertEquals(ErrorCode.BAD_REQUEST, emptyEx.getErrorCode());

        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("下载附件 — MinIO 返回流内容与下载字节一致")
    void download_success() {
        byte[] content = "file-content".getBytes();
        InputStream stream = new ByteArrayInputStream(content);
        when(minio.downloadFile("0/abc.png")).thenReturn(stream);

        byte[] result = service.download("0/abc.png");

        assertArrayEquals(content, result);
    }

    @Test
    @DisplayName("下载附件不存在（MinIO 抛异常）— BizException 404")
    void download_notFound() {
        when(minio.downloadFile("0/ghost.png")).thenThrow(new RuntimeException("no such object"));

        BizException e = assertThrows(BizException.class, () -> service.download("0/ghost.png"));

        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("下载知识库区 objectKey（123/ 前缀）— 拒绝 403 且不触达 MinIO（B3-2 越权防护）")
    void download_knowledgeBasePrefix_rejectedWithoutMinioAccess() {
        // 知识库文档 objectKey 形如 {kb_id}/{uuid}.{ext}，与附件同 bucket——不得经附件链路读取
        BizException e = assertThrows(BizException.class, () -> service.download("123/secret-doc.pdf"));

        assertEquals(ErrorCode.FORBIDDEN, e.getErrorCode());
        verify(minio, never()).downloadFile(any());
    }

    @Test
    @DisplayName("下载裸 key / null objectKey — 非附件区前缀一律拒绝 403")
    void download_bareOrNullKey_rejected() {
        // 无 "0/" 前缀的裸 uuid key（绕过目录直指对象）同样拒绝
        BizException bare = assertThrows(BizException.class, () -> service.download("abc123.pdf"));
        assertEquals(ErrorCode.FORBIDDEN, bare.getErrorCode());

        BizException nullKey = assertThrows(BizException.class, () -> service.download(null));
        assertEquals(ErrorCode.FORBIDDEN, nullKey.getErrorCode());

        verify(minio, never()).downloadFile(any());
    }
}
