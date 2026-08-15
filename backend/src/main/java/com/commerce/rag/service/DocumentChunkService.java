package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档分片服务 —— 封装 document_chunk 表的 CRUD + 旁路修正 + 重新向量化
 *
 * <p>核心策略：
 * <ul>
 *   <li>改 collection_type/course_id → 不重新向量化（标量字段）</li>
 *   <li>改 content → 必须重新调 embedding API</li>
 *   <li>batchCorrected → 批量标记 correction_status=CORRECTED</li>
 *   <li>findPending → 按 kb_id/doc_id 筛选 correction_status=PENDING</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DocumentChunkService {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunkService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EtlPipeline etlPipeline;

    /**
     * 按 ID 查询分片（无权限校验，用于 C 端学生端点）
     *
     * @param id 分片 ID
     * @return 分片实体，不存在返回 null
     */
    public DocumentChunk findById(Long id) {
        return chunkMapper.selectById(id);
    }

    /**
     * 按 ID 查询分片（B 端管理，含权限校验）
     *
     * @param id     分片 ID
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时校验 ownership）
     * @return 分片实体，不存在或无权访问返回 null
     */
    public DocumentChunk findById(Long id, Long userId, String role) {
        DocumentChunk chunk = chunkMapper.selectById(id);
        if (chunk == null) {
            return null;
        }
        // TEACHER 只能查看自己创建的文档的分片
        if ("TEACHER".equals(role)) {
            checkOwnership(id, userId, false);
        }
        return chunk;
    }

    /**
     * 分页查询分片
     *
     * @param docId  文档 ID（可选）
     * @param kbId   知识库 ID（可选）
     * @param page   页码
     * @param size   每页条数
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时按 doc_id→document.created_by 过滤）
     * @return 分页结果
     */
    public IPage<DocumentChunk> findPage(Long docId, Long kbId, int page, int size, Long userId, String role) {
        Page<DocumentChunk> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        // perf P3-2: 教师数据权限走 mapper XML 子查询（doc_id IN (SELECT id FROM document WHERE created_by=?)），
        // 避免应用层取全量 doc id + 数千 id 的 IN 列表（SQL 超长、执行计划退化）
        if ("TEACHER".equals(role) && userId != null) {
            return chunkMapper.selectPageFilteredByTeacher(pageObj, docId, kbId, false, userId);
        }
        LambdaQueryWrapper<DocumentChunk> wrapper =
                new LambdaQueryWrapper<DocumentChunk>().orderByAsc(DocumentChunk::getChunkIndex);
        if (docId != null) {
            wrapper.eq(DocumentChunk::getDocId, docId);
        }
        if (kbId != null) {
            wrapper.eq(DocumentChunk::getKbId, kbId);
        }
        return chunkMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 更新分片内容（重新 embedding + Milvus upsert）
     *
     * @param id      分片 ID
     * @param content 新内容
     * @param userId  操作者 ID（用于权限校验）
     * @param isAdmin 是否为超管（超管旁路）
     */
    public void updateContent(Long id, String content, Long userId, boolean isAdmin) {
        DocumentChunk chunk = chunkMapper.selectById(id);
        if (chunk == null) {
            throw new IllegalArgumentException("分片不存在: id=" + id);
        }
        checkOwnership(id, userId, isAdmin);

        // 更新 PG content
        LambdaUpdateWrapper<DocumentChunk> wrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getId, id)
                .set(DocumentChunk::getContent, content)
                .set(DocumentChunk::getUpdatedAt, LocalDateTime.now());
        chunkMapper.update(null, wrapper);

        // 重新 embedding + Milvus upsert
        etlPipeline.reEmbedAndUpsert(id);

        log.info("分片内容已更新（含重新向量化）: chunkId={}", id);
    }

    /**
     * 删除分片（Milvus 清理）
     *
     * @param id      分片 ID
     * @param userId  操作者 ID（用于权限校验）
     * @param isAdmin 是否为超管（超管旁路）
     */
    public void delete(Long id, Long userId, boolean isAdmin) {
        DocumentChunk chunk = chunkMapper.selectById(id);
        if (chunk == null) {
            return;
        }
        checkOwnership(id, userId, isAdmin);

        // Milvus 清理
        etlPipeline.deleteFromMilvusByChunkId(String.valueOf(id));

        // 软删
        LambdaUpdateWrapper<DocumentChunk> wrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, wrapper);

        log.info("删除分片: chunkId={}", id);
    }

    /**
     * 更新分片的 collection_type 和 course_id（不重新向量化）
     *
     * <p>P0-1：course_id/collection_type 变更后同步 Milvus（delete-then-insert 重建行），
     * 否则 Milvus 侧 course_id 恒为 DEFAULT——课程删除清理不到向量、学生端课程过滤失效。
     *
     * @param id             分片 ID
     * @param collectionType 新 collection_type
     * @param courseId       新 course_id
     * @param userId         操作者 ID（用于权限校验）
     * @param isAdmin        是否为超管（超管旁路）
     */
    public void updateCollectionType(Long id, String collectionType, String courseId, Long userId, boolean isAdmin) {
        checkOwnership(id, userId, isAdmin);
        LambdaUpdateWrapper<DocumentChunk> wrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .eq(DocumentChunk::getId, id)
                .set(DocumentChunk::getUpdatedAt, LocalDateTime.now());
        if (collectionType != null) {
            wrapper.set(DocumentChunk::getCollectionType, collectionType);
        }
        if (courseId != null) {
            wrapper.set(DocumentChunk::getCourseId, courseId);
        }
        chunkMapper.update(null, wrapper);
        // P0-1: 标注同步 Milvus（失败上抛阻断，可重试收敛）
        etlPipeline.syncChunkToMilvus(id);
        log.info("更新分片标量字段: chunkId={}, collectionType={}, courseId={}", id, collectionType, courseId);
    }

    /**
     * 查询分片上下文（父/前/当前/后）
     *
     * @param id     分片 ID
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时校验 ownership）
     * @return 包含 parent / prev / current / next 的 Map
     */
    public Map<String, DocumentChunk> findContext(Long id, Long userId, String role) {
        DocumentChunk current = chunkMapper.selectById(id);
        if (current == null) {
            throw new IllegalArgumentException("分片不存在: id=" + id);
        }
        // TEACHER 只能查看自己创建的文档的分片
        if ("TEACHER".equals(role)) {
            checkOwnership(id, userId, false);
        }

        DocumentChunk parent = null;
        DocumentChunk prev = null;
        DocumentChunk next = null;

        if (current.getParentChunkId() != null) {
            parent = chunkMapper.selectById(current.getParentChunkId());
        }
        if (current.getPrevChunkId() != null) {
            prev = chunkMapper.selectById(current.getPrevChunkId());
        }
        if (current.getNextChunkId() != null) {
            next = chunkMapper.selectById(current.getNextChunkId());
        }

        Map<String, DocumentChunk> context = new HashMap<>();
        context.put("parent", parent);
        context.put("prev", prev);
        context.put("current", current);
        context.put("next", next);
        return context;
    }

    /**
     * 按课程 ID 查询分片列表（C 端 J2：课程专属资料）
     *
     * @param courseId 课程 ID
     * @return 分片列表（按 chunk_index 排序）
     */
    public List<DocumentChunk> findByCourseId(Long courseId) {
        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getCourseId, String.valueOf(courseId))
                .orderByAsc(DocumentChunk::getChunkIndex);
        return chunkMapper.selectList(wrapper);
    }

    /**
     * 查询通用资料库分片（C 端 J3：course_id='DEFAULT'）
     *
     * @param page 页码（1-based）
     * @param size 每页条数
     * @return 分页结果
     */
    public IPage<DocumentChunk> findByCourseIdDefault(int page, int size) {
        Page<DocumentChunk> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getCourseId, "DEFAULT")
                .orderByAsc(DocumentChunk::getChunkIndex);
        return chunkMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 批量更新 collection_type 和 course_id（不重新向量化）
     *
     * @param ids            分片 ID 列表
     * @param collectionType 新 collection_type
     * @param courseId       新 course_id
     * @param userId         操作者 ID（用于权限校验）
     * @param isAdmin        是否为超管（超管旁路）
     */
    public void batchUpdate(List<Long> ids, String collectionType, String courseId, Long userId, boolean isAdmin) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // perf P2-1: 批量校验（2 次批量查询替代逐 id 2N 次主键查询）
        checkOwnershipBatch(ids, userId, isAdmin);

        LambdaUpdateWrapper<DocumentChunk> wrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .in(DocumentChunk::getId, ids)
                .set(DocumentChunk::getUpdatedAt, LocalDateTime.now());
        if (collectionType != null) {
            wrapper.set(DocumentChunk::getCollectionType, collectionType);
        }
        if (courseId != null) {
            wrapper.set(DocumentChunk::getCourseId, courseId);
        }
        chunkMapper.update(null, wrapper);
        // P0-1 + 用户裁决（2026-08-15）：标注同步走文档级（syncDocToMilvus）——
        // 按涉及的 docId 去重后逐文档重建 Milvus 行（调用次数 = 文档数而非分片数）；
        // 失败上抛阻断，可重试收敛
        Set<Long> docIds = chunkMapper.selectBatchIds(new HashSet<>(ids)).stream()
                .map(DocumentChunk::getDocId)
                .collect(Collectors.toSet());
        for (Long docId : docIds) {
            etlPipeline.syncDocToMilvus(docId);
        }
        log.info("批量更新分片标量字段: count={}, collectionType={}, courseId={}", ids.size(), collectionType, courseId);
    }

    /**
     * 批量标记 correction_status=CORRECTED
     *
     * @param ids     分片 ID 列表
     * @param userId  操作者 ID（用于权限校验）
     * @param isAdmin 是否为超管（超管旁路）
     */
    public void batchCorrected(List<Long> ids, Long userId, boolean isAdmin) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        // perf P2-1: 批量校验（2 次批量查询替代逐 id 2N 次主键查询）
        checkOwnershipBatch(ids, userId, isAdmin);

        LambdaUpdateWrapper<DocumentChunk> wrapper = new LambdaUpdateWrapper<DocumentChunk>()
                .in(DocumentChunk::getId, ids)
                .set(DocumentChunk::getCorrectionStatus, "CORRECTED")
                .set(DocumentChunk::getUpdatedAt, LocalDateTime.now());
        chunkMapper.update(null, wrapper);
        log.info("批量标记已修正: count={}", ids.size());
    }

    /**
     * 查询待修正分片（correction_status=PENDING）
     *
     * @param kbId   知识库 ID（可选）
     * @param docId  文档 ID（可选）
     * @param page   页码
     * @param size   每页条数
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时按 doc_id→document.created_by 过滤）
     * @return 分页结果
     */
    public IPage<DocumentChunk> findPending(Long kbId, Long docId, int page, int size, Long userId, String role) {
        Page<DocumentChunk> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        // perf P3-2: 教师数据权限走 mapper XML 子查询（与 findPage 同型）
        if ("TEACHER".equals(role) && userId != null) {
            return chunkMapper.selectPageFilteredByTeacher(pageObj, docId, kbId, true, userId);
        }
        LambdaQueryWrapper<DocumentChunk> wrapper = new LambdaQueryWrapper<DocumentChunk>()
                .eq(DocumentChunk::getCorrectionStatus, "PENDING")
                .orderByAsc(DocumentChunk::getChunkIndex);
        if (kbId != null) {
            wrapper.eq(DocumentChunk::getKbId, kbId);
        }
        if (docId != null) {
            wrapper.eq(DocumentChunk::getDocId, docId);
        }
        return chunkMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 批量权限校验（perf P2-1：2 次批量查询替代逐 id 2N 次主键查询）
     *
     * <p>与 {@link #checkOwnership} 相同规则：分片→文档→归属（文档属主或知识库属主）。
     *
     * @param chunkIds 分片 ID 列表
     * @param userId   操作者 ID
     * @param isAdmin  是否为超管（超管旁路）
     */
    private void checkOwnershipBatch(List<Long> chunkIds, Long userId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        // 1 次批量查全部分片（selectBatchIds 自动过滤软删；去重后数量不匹配说明存在不存在的 id）
        Set<Long> uniqueIds = new HashSet<>(chunkIds);
        List<DocumentChunk> chunks = chunkMapper.selectBatchIds(uniqueIds);
        if (chunks.size() != uniqueIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分片不存在");
        }
        // 1 次批量查涉及的文档
        Set<Long> docIds = chunks.stream().map(DocumentChunk::getDocId).collect(Collectors.toSet());
        Map<Long, Document> docMap =
                documentMapper.selectBatchIds(docIds).stream().collect(Collectors.toMap(Document::getId, d -> d));
        for (DocumentChunk chunk : chunks) {
            Document doc = docMap.get(chunk.getDocId());
            if (doc == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: docId=" + chunk.getDocId());
            }
            // P2-1: 文档属主 或 所属知识库属主（与 DocumentService 归属规则一致）
            if (doc.getCreatedBy() != null && doc.getCreatedBy().equals(userId)) {
                continue;
            }
            if (doc.getKbId() != null) {
                KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
                if (kb != null && kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId)) {
                    continue;
                }
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此分片: 只有文档/知识库属主可以执行此操作");
        }
    }

    /**
     * 权限校验 —— 经 doc_id→document.created_by 校验分片 ownership（P2-1：含知识库属主旁路）
     *
     * @param chunkId 分片 ID
     * @param userId  操作者 ID
     * @param isAdmin 是否为超管（超管旁路）
     */
    private void checkOwnership(Long chunkId, Long userId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        DocumentChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分片不存在: id=" + chunkId);
        }
        Document doc = documentMapper.selectById(chunk.getDocId());
        if (doc == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: docId=" + chunk.getDocId());
        }
        // P2-1: 文档属主 或 所属知识库属主（与 DocumentService 归属规则一致——超管代传文档教师可操作）
        if (doc.getCreatedBy() != null && doc.getCreatedBy().equals(userId)) {
            return;
        }
        if (doc.getKbId() != null) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
            if (kb != null && kb.getCreatedBy() != null && kb.getCreatedBy().equals(userId)) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此分片: 只有文档/知识库属主可以执行此操作");
    }
}
