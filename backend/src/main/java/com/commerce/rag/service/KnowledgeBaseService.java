package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 知识库服务 —— 封装 knowledge_base 表的 CRUD + 级联删除
 *
 * <p>级联软删策略：knowledge_base → document + document_chunk + Milvus deleteByKbId。
 * 教师只能操作自己创建的知识库（Service 层 created_by 校验）。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EtlPipeline etlPipeline;
    private final MinioStorageService minioStorageService;

    /**
     * 创建知识库
     *
     * @param name        知识库名称
     * @param description 描述
     * @param createdBy   创建者 ID（教师 user_id）
     * @return 已持久化的知识库实体
     */
    public KnowledgeBase create(String name, String description, Long createdBy) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setStatus("ACTIVE");
        kb.setCreatedBy(createdBy);
        knowledgeBaseMapper.insert(kb);
        log.info("创建知识库: kbId={}, name={}, createdBy={}", kb.getId(), name, createdBy);
        return kb;
    }

    /**
     * 按 ID 查询知识库
     *
     * @param id     知识库 ID
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时校验 ownership）
     * @return 知识库实体，不存在或无权访问返回 null
     */
    public KnowledgeBase findById(Long id, Long userId, String role) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            return null;
        }
        // TEACHER 只能查看自己创建的知识库（P2-3：null 前置防护，历史脏数据返回 null 而非 NPE→500）
        if ("TEACHER".equals(role)
                && (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(userId))) {
            return null;
        }
        return kb;
    }

    /**
     * 分页查询知识库
     *
     * @param page    页码（1-based）
     * @param size    每页条数
     * @param keyword 名称关键词（可选）
     * @param userId  当前用户 ID（TEACHER 数据权限过滤）
     * @param role    当前用户角色（TEACHER 时按 created_by 过滤）
     * @return 分页结果
     */
    public Page<KnowledgeBase> findPage(int page, int size, String keyword, Long userId, String role) {
        Page<KnowledgeBase> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getStatus, "ACTIVE")
                .orderByDesc(KnowledgeBase::getCreatedAt);
        if (keyword != null && !keyword.isBlank()) {
            wrapper.like(KnowledgeBase::getName, keyword);
        }
        // TEACHER 只能查看自己创建的知识库
        if ("TEACHER".equals(role) && userId != null) {
            wrapper.eq(KnowledgeBase::getCreatedBy, userId);
        }
        return knowledgeBaseMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 更新知识库
     *
     * @param id          知识库 ID
     * @param name        新名称
     * @param description 新描述
     * @param operatorId  操作者 ID（用于权限校验）
     * @param isAdmin     是否为超管（超管旁路）
     */
    public void update(Long id, String name, String description, Long operatorId, boolean isAdmin) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: id=" + id);
        }
        checkPermission(kb, operatorId, isAdmin);

        LambdaUpdateWrapper<KnowledgeBase> wrapper = new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .set(KnowledgeBase::getUpdatedAt, LocalDateTime.now());
        if (name != null) {
            wrapper.set(KnowledgeBase::getName, name);
        }
        if (description != null) {
            wrapper.set(KnowledgeBase::getDescription, description);
        }
        knowledgeBaseMapper.update(null, wrapper);
        log.info("更新知识库: kbId={}, operatorId={}", id, operatorId);
    }

    /**
     * 删除知识库（级联软删 + Milvus 清理）
     *
     * @param id         知识库 ID
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     */
    public void delete(Long id, Long operatorId, boolean isAdmin) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            return;
        }
        checkPermission(kb, operatorId, isAdmin);

        // 1. Milvus 清理
        etlPipeline.deleteFromMilvusByKbId(id);

        // P1-4 Bug 4: 删除 KB 下所有文档的 MinIO 源文件对象（失败上抛阻断级联，
        // 避免对象孤儿永久占存储；removeObject 幂等，重试收敛）
        // perf P1-2: 批量删除（一次请求删多个对象），替代循环单删 N 次网络往返
        List<Document> docs = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getKbId, id).select(Document::getSourcePath));
        List<String> sourcePaths = docs.stream()
                .map(Document::getSourcePath)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
        if (!sourcePaths.isEmpty()) {
            minioStorageService.deleteFiles(sourcePaths);
        }

        // 2. 软删 document_chunk
        LambdaUpdateWrapper<DocumentChunk> chunkWrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getKbId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, chunkWrapper);

        // 3. 软删 document
        LambdaUpdateWrapper<Document> docWrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getKbId, id)
                .set(Document::getDeleted, System.currentTimeMillis());
        documentMapper.update(null, docWrapper);

        // 4. 软删 knowledge_base
        LambdaUpdateWrapper<KnowledgeBase> kbWrapper = new LambdaUpdateWrapper<KnowledgeBase>()
                .eq(KnowledgeBase::getId, id)
                .set(KnowledgeBase::getDeleted, System.currentTimeMillis());
        knowledgeBaseMapper.update(null, kbWrapper);

        log.info("删除知识库（级联）: kbId={}, operatorId={}", id, operatorId);
    }

    /**
     * 权限校验：教师只能操作自己创建的知识库
     *
     * @param kb         知识库实体
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路，不校验 ownership）
     */
    private void checkPermission(KnowledgeBase kb, Long operatorId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        // P2-3：null 前置防护（历史库 created_by 为 NULL 时按无权处理，403 而非 NPE→500）
        if (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(operatorId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此知识库: kbId=" + kb.getId());
        }
    }
}
