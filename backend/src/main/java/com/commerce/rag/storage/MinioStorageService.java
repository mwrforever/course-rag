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
 * 文件路径规则：{@code {kb_id}/{uuid}.{ext}}（uuid 为调用方预生成的 32 位 hex，与业务主键解耦）
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
     * <p>P1-4 Bug 5 修复（用户裁决）：objectKey 用 uuid 标识（{kbId}/{uuid}.{ext}），
     * 与业务主键 docId 解耦——上传不再依赖 DB 记录先行，外部资源先占、DB 后落。
     *
     * @param kbId        知识库 ID
     * @param uuid        文件唯一标识（32 位 hex，去横线 UUID，调用方生成）
     * @param inputStream 文件输入流
     * @param ext         文件扩展名（如 pdf、docx）
     * @return objectKey（{kb_id}/{uuid}.{ext}）
     */
    public String uploadFile(Long kbId, String uuid, InputStream inputStream, String ext) {
        String objectKey = buildObjectKey(kbId, uuid, ext);
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
     * @param uuid  文件唯一标识（32 位 hex）
     * @param ext   文件扩展名
     * @return {kb_id}/{uuid}.{ext}
     */
    private String buildObjectKey(Long kbId, String uuid, String ext) {
        return kbId + "/" + uuid + "." + ext;
    }
}
