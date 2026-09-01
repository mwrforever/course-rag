package com.commerce.rag.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.commerce.rag.properties.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.messages.DeleteError;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
        // bucket=test-bucket（原测试语义）经属性类构造注入（endpoint/凭据为占位值，不参与本类行为）
        storageService = new MinioStorageService(
                minioClient, new MinioProperties("http://localhost:9002", "key", "secret", "test-bucket"));
    }

    @Test
    @DisplayName("uploadFile 上传文件 — 返回 objectKey（uuid 格式 {kbId}/{uuid}.{ext}）")
    void uploadFile_returnsObjectKey() throws Exception {
        InputStream inputStream = new ByteArrayInputStream("test content".getBytes());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        String objectKey = storageService.uploadFile(1L, "9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c", inputStream, "pdf");

        assertEquals("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf", objectKey);
        verify(minioClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    @DisplayName("uploadFile 上传失败 — 抛出 RuntimeException")
    void uploadFile_failure_throws() throws Exception {
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());
        when(minioClient.putObject(any(PutObjectArgs.class))).thenThrow(new RuntimeException("connection refused"));

        assertThrows(
                RuntimeException.class,
                () -> storageService.uploadFile(1L, "9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c", inputStream, "pdf"));
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

    @Test
    @DisplayName("initBucket bucket 已存在 — 跳过 makeBucket 不重复创建")
    void initBucket_bucketExists_skipsMakeBucket() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        storageService.initBucket();

        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("initBucket bucket 不存在 — 调用 makeBucket 创建")
    void initBucket_bucketMissing_createsBucket() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        storageService.initBucket();

        verify(minioClient).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("initBucket 探测异常 — warn 降级不抛出（服务未启动时不阻断应用）")
    void initBucket_bucketExistsThrows_swallowsWithWarn() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenThrow(new IOException("connection refused"));

        assertDoesNotThrow(() -> storageService.initBucket());
        verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
    }

    @Test
    @DisplayName("downloadFile 下载成功 — 返回 MinIO 输入流原样透传")
    void downloadFile_success_returnsStream() throws Exception {
        GetObjectResponse expected = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(expected);

        InputStream actual = storageService.downloadFile("1/100.pdf");

        assertSame(expected, actual);
    }

    @Test
    @DisplayName("downloadFile 下载失败 — 抛出 RuntimeException 供调用方阻断")
    void downloadFile_failure_throws() throws Exception {
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("object not found"));

        assertThrows(RuntimeException.class, () -> storageService.downloadFile("1/100.pdf"));
    }

    @Test
    @DisplayName("deleteFiles 超过 100 个对象 — 分两批调用 removeObjects")
    void deleteFiles_over100_splitsIntoTwoBatches() throws Exception {
        // 150 个对象：第一批 100 个 + 第二批 50 个
        List<String> objectKeys = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            objectKeys.add("1/" + i + ".pdf");
        }
        when(minioClient.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(Collections.emptyList());

        storageService.deleteFiles(objectKeys);

        verify(minioClient, times(2)).removeObjects(any(RemoveObjectsArgs.class));
    }

    @Test
    @DisplayName("deleteFiles 返回 DeleteError — 立即抛出 RuntimeException 阻断（不静默）")
    void deleteFiles_deleteError_throws() throws Exception {
        // 模拟 MinIO 返回删除错误对象（如对象不存在）
        DeleteError deleteError = mock(DeleteError.class);
        when(deleteError.objectName()).thenReturn("1/100.pdf");
        when(deleteError.code()).thenReturn("NoSuchKey");
        Result<DeleteError> result = mock(Result.class);
        when(result.get()).thenReturn(deleteError);
        when(minioClient.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(List.of(result));

        RuntimeException ex =
                assertThrows(RuntimeException.class, () -> storageService.deleteFiles(List.of("1/100.pdf")));

        assertTrue(ex.getMessage().contains("1/100.pdf"));
        assertTrue(ex.getMessage().contains("NoSuchKey"));
    }

    @Test
    @DisplayName("deleteFiles 结果遍历异常 — 包装为 RuntimeException 上抛")
    void deleteFiles_resultIterationFails_throws() throws Exception {
        Result<DeleteError> result = mock(Result.class);
        when(result.get()).thenThrow(new IOException("parse failed"));
        when(minioClient.removeObjects(any(RemoveObjectsArgs.class))).thenReturn(List.of(result));

        assertThrows(RuntimeException.class, () -> storageService.deleteFiles(List.of("1/100.pdf")));
    }

    @Test
    @DisplayName("deleteFiles 空列表 — 不调用 removeObjects 直接返回")
    void deleteFiles_emptyList_skips() {
        storageService.deleteFiles(List.of());

        verify(minioClient, never()).removeObjects(any(RemoveObjectsArgs.class));
    }

    @Test
    @DisplayName("deleteFiles null 列表 — 不调用 removeObjects 直接返回")
    void deleteFiles_nullList_skips() {
        storageService.deleteFiles(null);

        verify(minioClient, never()).removeObjects(any(RemoveObjectsArgs.class));
    }
}
