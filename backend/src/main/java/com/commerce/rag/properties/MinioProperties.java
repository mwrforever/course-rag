package com.commerce.rag.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MinIO 业务文件存储配置属性（BUG-12 @Value 收敛）。
 * 绑定 application.yml 中 {@code minio.*} 配置项。
 *
 * <p>原 {@code config/MinioConfig}（endpoint/access-key/secret-key）与
 * {@code storage/MinioStorageService}（bucket）各自经 {@code @Value} 散落注入，
 * 现收敛为本属性类统一强类型绑定（宪法 A.2.2）。
 *
 * <p>四个键均无默认值（与原 {@code @Value} 必填语义一致）：endpoint/access-key/secret-key
 * 缺失时启动失败，bucket 缺失同样启动失败——MinIO 客户端构造与 bucket 初始化均为硬依赖。
 *
 * <pre>
 * minio:
 *   endpoint: http://localhost:9002
 *   access-key: xxx
 *   secret-key: xxx
 *   bucket: rag-documents
 * </pre>
 *
 * @param endpoint   MinIO 服务地址（含协议与端口；必填，来自 application.yml / MINIO_ENDPOINT 环境变量）
 * @param accessKey  访问密钥（必填，来自 MINIO_ACCESS_KEY 环境变量；敏感值禁止明文进 git）
 * @param secretKey  秘密密钥（必填，来自 MINIO_SECRET_KEY 环境变量；敏感值禁止明文进 git）
 * @param bucket     业务桶名称（必填，对象 key 前缀载体；来自 MINIO_BUCKET 环境变量）
 */
@Validated
@ConfigurationProperties(prefix = "minio")
public record MinioProperties(
        @NotBlank String endpoint, @NotBlank String accessKey, @NotBlank String secretKey, @NotBlank String bucket) {}
