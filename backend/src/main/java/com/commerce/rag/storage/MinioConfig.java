package com.commerce.rag.storage;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置
 *
 * <p>创建 {@link MinioClient} Bean，供 {@link MinioStorageService} 注入使用。
 * 连接参数从 application.yml 的 {@code minio.*} 读取。
 * bucket 初始化由 {@link MinioStorageService} 的 {@code @PostConstruct} 完成。
 *
 * @author commerce-rag
 */
@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /**
     * 创建 MinIO 客户端 Bean
     *
     * @param endpoint   MinIO 服务地址
     * @param accessKey  访问密钥
     * @param secretKey  秘密密钥
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient(
            @Value("${minio.endpoint}") String endpoint,
            @Value("${minio.access-key}") String accessKey,
            @Value("${minio.secret-key}") String secretKey) {
        log.info("创建 MinIO 客户端: endpoint={}", endpoint);
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
