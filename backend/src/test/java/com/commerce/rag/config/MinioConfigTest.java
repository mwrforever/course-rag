package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.properties.MinioProperties;
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
        // endpoint/凭据保持原测试语义，经属性类注入（bucket 不参与客户端构建，传占位值）
        MinioClient client = new MinioConfig()
                .minioClient(new MinioProperties("http://localhost:9000", "minioadmin", "minioadmin", "test-bucket"));

        assertNotNull(client);
    }
}
