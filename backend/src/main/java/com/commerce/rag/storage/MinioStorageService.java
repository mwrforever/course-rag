package com.commerce.rag.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * MinIO 文件存储服务
 *
 * <p>封装 MinIO 文件上传/下载/删除操作。
 * 文件路径规则：{@code {kb_id}/{doc_id}.{ext}}
 *
 * <p>bucket 名称从配置读取（默认 rag-documents）。
 *
 * @author commerce-rag
 */
@Service
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;

    @Value("${minio.bucket}")
    private String bucket;

    public MinioStorageService(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    /**
     * 启动时检查并创建 bucket（如不存在）
     */
    @PostConstruct
    public void initBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("MinIO bucket 已创建: {}", bucket);
            } else {
                log.info("MinIO bucket 已存在: {}", bucket);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket 初始化失败（服务可能未启动）: {}", e.getMessage());
        }
    }

    /**
     * 上传文件到 MinIO
     *
     * @param kbId        知识库 ID
     * @param docId       文档 ID
     * @param inputStream 文件输入流
     * @param ext         文件扩展名（如 pdf、docx）
     * @return objectKey（{kb_id}/{doc_id}.{ext}）
     */
    public String uploadFile(Long kbId, Long docId, InputStream inputStream, String ext) {
        String objectKey = buildObjectKey(kbId, docId, ext);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(inputStream, -1, 10 * 1024 * 1024)
                            .contentType("application/octet-stream")
                            .build());
            log.info("文件已上传到 MinIO: objectKey={}", objectKey);
            return objectKey;
        } catch (Exception e) {
            log.error("MinIO 上传失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 MinIO 下载文件
     *
     * @param objectKey 文件路径
     * @return 文件输入流
     */
    public InputStream downloadFile(String objectKey) {
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception e) {
            log.error("MinIO 下载失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件下载失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 MinIO 删除文件
     *
     * <p>P1-4 Bug 3 修复：删除失败抛异常（不再静默吞掉）——调用方（文档/知识库删除）
     * 先删 MinIO 再软删 PG，失败上抛阻断保证"对象删不掉则记录保留"，可重试收敛
     * （MinIO removeObject 对不存在对象幂等成功）。
     *
     * @param objectKey 文件路径
     * @throws RuntimeException MinIO 删除失败
     */
    public void deleteFile(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.info("文件已从 MinIO 删除: objectKey={}", objectKey);
        } catch (Exception e) {
            log.error("MinIO 删除失败: objectKey={}", objectKey, e);
            throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造 MinIO objectKey
     *
     * @param kbId  知识库 ID
     * @param docId 文档 ID
     * @param ext   文件扩展名
     * @return {kb_id}/{doc_id}.{ext}
     */
    private String buildObjectKey(Long kbId, Long docId, String ext) {
        return kbId + "/" + docId + "." + ext;
    }
}
