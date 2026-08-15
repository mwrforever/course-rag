package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档服务 —— 封装 document 表的 CRUD + MinIO 文件管理 + ETL 触发
 *
 * <p>upload：存 MinIO + 创建 document 记录 + 触发 ETL 异步管道。
 * delete：MinIO 删除 → Milvus deleteByDocId → 级联软删 chunk + document。
 * reparse：从 MinIO 拉原文件重新 ETL。
 *
 * @author commerce-rag
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentChunkMapper chunkMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private MinioStorageService minioStorageService;

    @Autowired
    private EtlPipeline etlPipeline;

    @Autowired
    @Qualifier("etlPool")
    private ThreadPoolExecutor etlPool;

    /**
     * 上传文档
     *
     * <p>流程：存 MinIO → 创建 document 记录 → 触发 ETL 异步管道
     *
     * @param kbId        知识库 ID
     * @param title       文档标题
     * @param inputStream 文件输入流
     * @param fileType    文件类型（pdf/docx/pptx/md）
     * @param fileSize    文件大小（字节）
     * @param createdBy   创建者 ID
     * @param isAdmin     是否为超管（超管旁路）
     * @return 已持久化的文档实体
     */
    public Document upload(
            Long kbId,
            String title,
            InputStream inputStream,
            String fileType,
            Long fileSize,
            Long createdBy,
            boolean isAdmin) {
        // 校验知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: kbId=" + kbId);
        }

        // 归属校验：非超管只能上传到自己创建的知识库（P0-2c 跨库上传越权修复）
        if (!isAdmin && (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(createdBy))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权向此知识库上传文档");
        }

        // uuid 先行（用户裁决，AGENTS.md 一致：先占外部资源再落库，单向补偿即可）：
        // objectKey 用 uuid（去横线）标识，与 docId 解耦；docId 由 MP 自动生成（ASSIGN_ID 雪花）
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String objectKey = minioStorageService.uploadFile(kbId, uuid, inputStream, fileType);

        // 创建 document 记录（sourcePath 一步带入，id 自动生成）
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTitle(title);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setParseStatus("PENDING");
        doc.setChunkCount(0);
        doc.setMetadataJson("{}");
        doc.setCreatedBy(createdBy);
        doc.setSourcePath(objectKey);
        try {
            documentMapper.insert(doc);
        } catch (Exception e) {
            // 单向补偿：唯一可能残留的方向是「MinIO 已传、DB 未落」→ 删已上传对象（幂等）后上抛
            log.error("文档落库失败，已补偿删除 MinIO 对象: objectKey={}", objectKey, e);
            minioStorageService.deleteFile(objectKey);
            throw e;
        }

        log.info("文档已上传: docId={}, kbId={}, title={}, fileType={}", doc.getId(), kbId, title, fileType);

        // 触发 ETL 异步管道
        etlPool.execute(() -> etlPipeline.process(doc.getId()));

        return doc;
    }

    /**
     * 按 ID 查询文档
     *
     * @param id     文档 ID
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时校验 ownership）
     * @return 文档实体，不存在或无权访问返回 null
     */
    public Document findById(Long id, Long userId, String role) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        // TEACHER 只能查看自己创建的文档
        if ("TEACHER".equals(role)
                && doc.getCreatedBy() != null
                && !doc.getCreatedBy().equals(userId)) {
            return null;
        }
        return doc;
    }

    /**
     * 分页查询文档（P2-2 契约对齐：前端文档 :871 支持 status/q/sort 筛选）
     *
     * @param kbId   知识库 ID（可选，null = 查全部）
     * @param status 解析状态筛选（可选，parse_status 精确匹配）
     * @param q      标题关键词（可选，title like）
     * @param sort   排序（created=created_at 降序默认；updated=updated_at 降序；非法值按 created）
     * @param page   页码（1-based）
     * @param size   每页条数
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时按 created_by 过滤）
     * @return 分页结果
     */
    public IPage<Document> findPage(
            Long kbId, String status, String q, String sort, int page, int size, Long userId, String role) {
        Page<Document> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
        LambdaQueryWrapper<Document> wrapper = Wrappers.<Document>lambdaQuery()
                .eq(kbId != null, Document::getKbId, kbId)
                .eq(status != null && !status.isBlank(), Document::getParseStatus, status)
                .like(q != null && !q.isBlank(), Document::getTitle, q)
                // TEACHER 只能查看自己创建的文档（role 为 null 时条件为 false，链式 eq 不生效、不抛 NPE）
                .eq("TEACHER".equals(role) && userId != null, Document::getCreatedBy, userId)
                .orderByDesc("updated".equals(sort) ? Document::getUpdatedAt : Document::getCreatedAt);
        return documentMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 更新文档标题
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能改自己的文档，超管旁路）。
     *
     * @param id         文档 ID
     * @param title      新标题
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     */
    public void update(Long id, String title, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能改名（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        LambdaUpdateWrapper<Document> wrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getTitle, title)
                .set(Document::getUpdatedAt, LocalDateTime.now());
        documentMapper.update(null, wrapper);
        log.info("更新文档: docId={}, title={}", id, title);
    }

    /**
     * 删除文档（级联：MinIO 删除 → Milvus 清理 → 软删 chunk + document）
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能删除自己的文档）
     * 删除顺序（P1-4 Bug 3 修复）：先删 MinIO 外部对象，失败上抛阻断，PG 记录保留可重试；
     * removeObject 幂等，重试可收敛。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID（从 AuthInterceptor 注入的 userId 获取）
     * @param isAdmin    是否为超管（超管旁路）
     */
    public void delete(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return;
        }

        // 权限校验：只有文档创建者才能删除（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        // 1. MinIO 删除（P1-4 Bug 3 修复：先删外部资源，失败上抛阻断 → PG 记录保留可重试；
        //    removeObject 幂等，任一侧先失败重试均可收敛到"对象已删 + 记录已删"）
        if (doc.getSourcePath() != null) {
            minioStorageService.deleteFile(doc.getSourcePath());
        }

        // 2. Milvus 清理（filter 直删，失败上抛阻断）
        etlPipeline.deleteFromMilvusByDocId(id);

        // 3. 软删 document_chunk
        LambdaUpdateWrapper<DocumentChunk> chunkWrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, chunkWrapper);

        // 4. 软删 document
        LambdaUpdateWrapper<Document> docWrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getDeleted, System.currentTimeMillis());
        documentMapper.update(null, docWrapper);

        log.info("删除文档（级联）: docId={}, operatorId={}", id, operatorId);
    }

    /**
     * 重新解析文档（从 MinIO 拉原文件重新 ETL）
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能重新解析自己的文档）
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID（从 AuthInterceptor 注入的 userId 获取）
     * @param isAdmin    是否为超管（超管旁路）
     */
    public void reparse(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能重新解析（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        // 软删旧分片
        LambdaUpdateWrapper<DocumentChunk> chunkWrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getDocId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, chunkWrapper);

        // 重置状态
        LambdaUpdateWrapper<Document> docWrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getParseStatus, "PENDING")
                .set(Document::getErrorMessage, null)
                .set(Document::getChunkCount, 0)
                .set(Document::getUpdatedAt, LocalDateTime.now());
        documentMapper.update(null, docWrapper);

        // 重新触发 ETL
        etlPool.execute(() -> etlPipeline.process(id));

        log.info("重新解析文档: docId={}, operatorId={}", id, operatorId);
    }

    /**
     * 下载文档原始文件
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（超管旁路）。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     * @return 文件输入流
     */
    public InputStream download(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能下载（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        if (doc.getSourcePath() == null) {
            throw new IllegalStateException("文档源文件路径为空: id=" + id);
        }
        return minioStorageService.downloadFile(doc.getSourcePath());
    }

    /**
     * 获取文档文件类型（用于下载时设置 Content-Type）
     */
    public String getFileType(Long id) {
        Document doc = documentMapper.selectById(id);
        return doc != null ? doc.getFileType() : null;
    }

    /**
     * 权限校验 —— 校验操作者是否为文档创建者
     *
     * <p>设计文档要求：TEACHER 只能删除/重新解析自己创建的文档（checkOwnership(created_by)）。
     * 超级管理员可操作所有文档（isAdmin 旁路）。
     *
     * @param doc        文档实体（已查询）
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路，不校验 ownership）
     * @throws ResponseStatusException 如果 operatorId 与文档 created_by 不匹配，抛出 403 FORBIDDEN
     */
    private void checkOwnership(Document doc, Long operatorId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (doc.getCreatedBy() == null || !doc.getCreatedBy().equals(operatorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此文档: 只有文档创建者可以执行此操作");
        }
    }
}
