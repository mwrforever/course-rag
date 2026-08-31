package com.commerce.rag.config;

import com.commerce.rag.properties.MinioProperties;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置
 *
 * <p>创建 {@link MinioClient} Bean，供 {@link MinioStorageService} 注入使用。
 * 连接参数经 {@link MinioProperties}（minio.*）强类型注入（BUG-12 @Value 收敛，宪法 A.2.2）。
 * bucket 初始化由 {@link MinioStorageService} 的 {@code @PostConstruct} 完成。
 *
 * @author commerce-rag
 */
@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    /**
     * 创建 MinIO 客户端 Bean
     *
     * @param properties MinIO 连接配置（endpoint/accessKey/secretKey，非空，Spring 注入）
     * @return MinioClient 实例
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        log.info("创建 MinIO 客户端: endpoint={}", properties.endpoint());
        return MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }
}
