package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.exception.BizException;
import com.commerce.rag.properties.CourseProperties;
import com.commerce.rag.service.impl.CourseCoverServiceImpl;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.vo.CourseCoverVO;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * ICourseCoverService 单元测试 —— 封面上传校验/落盘 + 公开访问白名单代理（契约 D.2）
 *
 * <p>覆盖：非空/白名单/MIME/大小四类 400 校验、uuid 先占资源上传、相对 URL 拼接、
 * 前导斜杠剥离、全锚定白名单正则防穿越（../、URL 编码、跨前缀、大小写、非 hex、
 * 多余路径段）、NoSuchKey 404 与 MinIO 异常 503 区分。
 *
 * @author commerce-rag
 */
class CourseCoverServiceTest {

    private MinioStorageService minioStorageService;

    private ICourseCoverService coverService;

    /** 默认配置（与 application.yml course.cover 段一致） */
    private static CourseProperties defaultProperties() {
        return new CourseProperties(
                "http://localhost:3000", new CourseProperties.Cover(List.of("jpg", "jpeg", "png", "webp"), 5));
    }

    @BeforeEach
    void setUp() {
        minioStorageService = mock(MinioStorageService.class);
        coverService = new CourseCoverServiceImpl(minioStorageService, defaultProperties());
    }

    /** 构造合法 png 封面文件 */
    private MockMultipartFile pngFile(String filename, int sizeBytes) {
        return new MockMultipartFile("file", filename, "image/png", new byte[sizeBytes]);
    }

    // ==================== uploadCover：校验分支（400） ====================

    @Test
    @DisplayName("上传 → 文件为空（null/空内容）拒绝 400")
    void uploadCover_emptyFile_throws400() {
        BizException nullFile = assertThrows(BizException.class, () -> coverService.uploadCover(null));
        BizException emptyFile = assertThrows(BizException.class, () -> coverService.uploadCover(pngFile("a.png", 0)));

        assertEquals(400, nullFile.getCode());
        assertEquals(400, emptyFile.getCode());
        verify(minioStorageService, never()).uploadFile(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("上传 → 扩展名不在白名单拒绝 400（消息含文件名与允许清单）")
    void uploadCover_badExtension_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "virus.gif", "image/gif", new byte[10]);

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("virus.gif"), "错误消息应包含文件名");
        assertTrue(ex.getMessage().contains("jpg,jpeg,png,webp"), "错误消息应包含允许清单");
        verify(minioStorageService, never()).uploadFile(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("上传 → 无扩展名拒绝 400")
    void uploadCover_noExtension_throws400() {
        MockMultipartFile file = new MockMultipartFile("file", "screenshot", "image/png", new byte[10]);

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("上传 → 文件名缺失（null）拒绝 400（空扩展名必不在白名单）")
    void uploadCover_nullFilename_throws400() {
        // MockMultipartFile 构造器对 null 文件名回退为字段名，须覆写 getOriginalFilename 模拟真实 null
        MockMultipartFile file = new MockMultipartFile("file", "f.png", "image/png", new byte[10]) {
            @Override
            public String getOriginalFilename() {
                return null;
            }
        };

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("上传 → MIME 与扩展名不匹配拒绝 400（防改名伪装）")
    void uploadCover_mimeMismatch_throws400() {
        // .png 扩展名 + image/jpeg MIME：白名单内但 MIME 不一致
        MockMultipartFile file = new MockMultipartFile("file", "fake.png", "image/jpeg", new byte[10]);

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(400, ex.getCode());
        verify(minioStorageService, never()).uploadFile(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("上传 → 白名单配置含 EXT_MIME 未覆盖类型（gif）时 400 拒绝（防配置漂移 NPE 500）")
    void uploadCover_extWithoutMimeMapping_throws400() {
        // 配置扩展 gif（下载侧白名单联动放行，但上传侧无内置 MIME 映射）：应 400 拒绝而非 NPE 500
        ICourseCoverService service = new CourseCoverServiceImpl(
                minioStorageService,
                new CourseProperties("http://localhost:3000", new CourseProperties.Cover(List.of("jpg", "gif"), 5)));
        MockMultipartFile file = new MockMultipartFile("file", "cover.gif", "image/gif", new byte[10]);

        BizException ex = assertThrows(BizException.class, () -> service.uploadCover(file));

        assertEquals(400, ex.getCode(), "无 MIME 映射的扩展名应 400 拒绝（不得 NPE 500）");
        verify(minioStorageService, never()).uploadFile(anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("上传 → 超过大小上限拒绝 400（course.cover.max-size-mb 属性化）")
    void uploadCover_overSizeLimit_throws400() {
        // 5MB 上限 → 6MB 文件拒绝（1 字节内容 + getSize 覆写超限，避免大数组开销）
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", new byte[1]) {
            @Override
            public long getSize() {
                return 6L * 1024 * 1024;
            }
        };

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("big.png"));
        assertTrue(ex.getMessage().contains("5MB"));
    }

    // ==================== uploadCover：成功与存储异常 ====================

    @Test
    @DisplayName("上传 → uuid 先占资源落盘 0/ 目录，返回 objectKey 与相对 URL")
    void uploadCover_success_returnsObjectKeyAndUrl() throws IOException {
        when(minioStorageService.uploadFile(eq(0L), anyString(), any(InputStream.class), eq("png")))
                .thenReturn("0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png");

        CourseCoverVO vo = coverService.uploadCover(pngFile("cover.png", 16));

        assertEquals("0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png", vo.objectKey());
        assertEquals("/api/v1/public/covers/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png", vo.url());
        // uuid 为 32 位 hex（服务端预生成，先占资源再落盘，A.5.7）
        verify(minioStorageService).uploadFile(eq(0L), anyString(), any(InputStream.class), eq("png"));
    }

    @Test
    @DisplayName("上传 → 文件流读取失败 500（不牵连存储层）")
    void uploadCover_streamReadFailure_throws500() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", new byte[1]) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("磁盘读取失败");
            }
        };

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(file));

        assertEquals(500, ex.getCode());
    }

    @Test
    @DisplayName("上传 → MinIO 失败转 503（SERVICE_UNAVAILABLE）")
    void uploadCover_minioFailure_throws503() {
        when(minioStorageService.uploadFile(anyLong(), anyString(), any(InputStream.class), anyString()))
                .thenThrow(new RuntimeException("文件上传失败: MinIO 不可用"));

        BizException ex = assertThrows(BizException.class, () -> coverService.uploadCover(pngFile("cover.png", 8)));

        assertEquals(503, ex.getCode());
    }

    // ==================== downloadCover：白名单校验（防穿越/跨前缀） ====================

    @Test
    @DisplayName("下载 → 合法键（剥离前导斜杠后）流式回读并按扩展名设 Content-Type")
    void downloadCover_validKey_streamsWithContentType() {
        InputStream stream = new ByteArrayInputStream(new byte[] {1, 2, 3});
        when(minioStorageService.downloadFile("0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png"))
                .thenReturn(stream);

        // Spring 6 {*objectKey} 捕获值带前导斜杠（/0/abc.png），服务端剥离后匹配白名单
        ICourseCoverService.CoverContent content =
                coverService.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png");

        assertNotNull(content.inputStream());
        assertEquals(org.springframework.http.MediaType.IMAGE_PNG, content.contentType());
    }

    @Test
    @DisplayName("下载 → 目录穿越（../）与 URL 编码（%2e%2e）键一律 404")
    void downloadCover_traversalKeys_throws404() {
        List<String> attackKeys = List.of(
                "/0/../1948633200000000001/secret.pdf",
                "/0/%2e%2e/1948633200000000001/secret.pdf",
                "/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png/../evil.png",
                "/0/..%2f3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png");

        for (String key : attackKeys) {
            BizException ex = assertThrows(BizException.class, () -> coverService.downloadCover(key), "键应被拒: " + key);
            assertEquals(404, ex.getCode(), "穿越键应 404: " + key);
        }
        verify(minioStorageService, never()).downloadFile(anyString());
    }

    @Test
    @DisplayName("下载 → 跨前缀（知识库/附件外键）、大小写混写、非 hex、长度错、多余段一律 404")
    void downloadCover_nonCoverKeys_throws404() {
        List<String> invalidKeys = List.of(
                // 19 位雪花 kbId 前缀（知识库文档目录），不匹配 ^0/
                "/1948633200000000001/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png",
                // 大写 hex 不匹配 [0-9a-f]
                "/0/3F2B8C6D4E5F6A7B8C9D0E1F2A3B4C9D.png",
                // 非 hex 字符
                "/0/zz2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png",
                // uuid 长度不足 32
                "/0/3f2b8c6d.png",
                // 多余路径段
                "/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d/extra.png",
                // 白名单外扩展名
                "/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.gif",
                // 空串
                "");

        for (String key : invalidKeys) {
            BizException ex = assertThrows(BizException.class, () -> coverService.downloadCover(key), "键应被拒: " + key);
            assertEquals(404, ex.getCode(), "非法键应 404: " + key);
        }
        // null 键（List.of 不允许 null 元素，单独断言）同样 404
        BizException nullKeyEx = assertThrows(BizException.class, () -> coverService.downloadCover(null));
        assertEquals(404, nullKeyEx.getCode());
        verify(minioStorageService, never()).downloadFile(anyString());
    }

    @Test
    @DisplayName("下载 → 对象不存在（MinIO NoSuchKey）转 404")
    void downloadCover_noSuchKey_throws404() {
        when(minioStorageService.downloadFile(anyString())).thenAnswer(invocation -> {
            // ErrorResponse 7 参构造：code/message/bucketName/objectName/resource/requestId/hostId
            ErrorResponse errorResponse = new ErrorResponse(
                    "NoSuchKey", null, null, invocation.getArgument(0, String.class), null, null, null);
            throw new RuntimeException("文件下载失败: 对象不存在", new ErrorResponseException(errorResponse, null, "xml"));
        });

        BizException ex = assertThrows(
                BizException.class, () -> coverService.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png"));

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("下载 → MinIO 不可用（非 NoSuchKey 异常）转 503")
    void downloadCover_minioUnavailable_throws503() {
        when(minioStorageService.downloadFile(anyString()))
                .thenThrow(new RuntimeException("文件下载失败: Connection refused"));

        BizException ex = assertThrows(
                BizException.class, () -> coverService.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png"));

        assertEquals(503, ex.getCode());
    }

    // ==================== 配置联动 ====================

    @Test
    @DisplayName("下载白名单与上传白名单同源联动（配置扩展 gif 后旧式 gif 键放行）")
    void downloadCover_whitelistFollowsConfig() {
        ICourseCoverService service = new CourseCoverServiceImpl(
                minioStorageService,
                new CourseProperties("http://localhost:3000", new CourseProperties.Cover(List.of("jpg", "gif"), 5)));
        when(minioStorageService.downloadFile(anyString())).thenReturn(new ByteArrayInputStream(new byte[1]));

        // jpg 仍在白名单；png 因配置移除而 404；gif 因配置新增而放行
        assertNotNull(service.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.jpg"));
        BizException pngRejected = assertThrows(
                BizException.class, () -> service.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png"));
        assertEquals(404, pngRejected.getCode());
        assertNotNull(service.downloadCover("/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.gif"));
    }

    @Test
    @DisplayName("上传成功响应为契约 VO 结构（objectKey + 相对 url，扩展名大小写归一）")
    void uploadCover_responseContract() throws IOException {
        when(minioStorageService.uploadFile(eq(0L), anyString(), any(InputStream.class), eq("jpg")))
                .thenAnswer(invocation -> "0/" + invocation.getArgument(1, String.class) + ".jpg");

        CourseCoverVO vo =
                coverService.uploadCover(new MockMultipartFile("file", "cover.JPG", "image/jpeg", new byte[4]));

        // 扩展名大小写归一（JPG → jpg）：objectKey 后缀为小写 hex uuid + 小写扩展名
        assertTrue(vo.objectKey().matches("0/[0-9a-f]{32}\\.jpg"));
        assertEquals("/api/v1/public/covers/" + vo.objectKey(), vo.url());
    }
}
