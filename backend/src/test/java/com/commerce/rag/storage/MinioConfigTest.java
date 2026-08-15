package com.commerce.rag.storage;

import static org.junit.jupiter.api.Assertions.*;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MinioConfig 单元测试 —— 验证 MinIO 客户端 Bean 构建（纯对象组装，无网络请求）
 *
 * @author commerce-rag
 */
@DisplayName("MinioConfig 客户端配置测试")
class MinioConfigTest {

    @Test
    @DisplayName("minioClient 携带 endpoint 与凭据返回客户端实例")
    void minioClient_buildsClientWithCredentials() {
        MinioClient client = new MinioConfig().minioClient("http://localhost:9000", "minioadmin", "minioadmin");

        assertNotNull(client);
    }
}
