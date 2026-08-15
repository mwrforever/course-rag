package com.commerce.rag.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * MinioStorageService 单元测试 —— Mock MinioClient
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class MinioStorageServiceTest {

    @Mock
    private MinioClient minioClient;

    private MinioStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new MinioStorageService(minioClient);
        ReflectionTestUtils.setField(storageService, "bucket", "test-bucket");
    }

    @Test
    @DisplayName("uploadFile 上传文件 — 返回 objectKey")
    void uploadFile_returnsObjectKey() throws Exception {
        InputStream inputStream = new ByteArrayInputStream("test content".getBytes());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        String objectKey = storageService.uploadFile(1L, 100L, inputStream, "pdf");

        assertEquals("1/100.pdf", objectKey);
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("uploadFile 上传失败 — 抛出 RuntimeException")
    void uploadFile_failure_throws() throws Exception {
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("connection refused"));

        assertThrows(RuntimeException.class, () -> storageService.uploadFile(1L, 100L, inputStream, "pdf"));
    }

    @Test
    @DisplayName("deleteFile 删除文件 — 调用 removeObject")
    void deleteFile_callsRemoveObject() throws Exception {
        storageService.deleteFile("1/100.pdf");
        verify(minioClient).removeObject(any());
    }

    @Test
    @DisplayName("deleteFile 删除失败 — 抛出 RuntimeException（不静默，供调用方阻断/重试）")
    void deleteFile_failure_throws() throws Exception {
        doThrow(new RuntimeException("not found")).when(minioClient).removeObject(any());

        assertThrows(RuntimeException.class, () -> storageService.deleteFile("1/100.pdf"));
    }
}
