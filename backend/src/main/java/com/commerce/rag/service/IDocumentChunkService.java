package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.vo.DocumentChunkVO;
import java.util.List;
import java.util.Map;

/**
 * 分片管理服务接口 —— 封装 document_chunk 的查询/内容修正/批量操作（主表 DocumentChunk）
 *
 * @author commerce-rag
 */
public interface IDocumentChunkService extends IService<DocumentChunk> {

    /**
     * 按 ID 查询分片（无权限过滤）
     */
    DocumentChunk findById(Long id);

    /**
     * 按 ID 查询分片（带归属校验，TEACHER 数据权限）
     */
    DocumentChunkVO findById(Long id, Long userId, String role);

    /**
     * 分页查询分片（支持文档/知识库过滤 + 教师数据权限）
     */
    IPage<DocumentChunkVO> findPage(Long docId, Long kbId, int page, int size, Long userId, String role);

    /**
     * 更新分片内容
     */
    void updateContent(Long id, String content, Long userId, boolean isAdmin);

    /**
     * 删除分片（软删）
     */
    void delete(Long id, Long userId, boolean isAdmin);

    /**
     * 更新分片收集类型与课程关联
     */
    void updateCollectionType(Long id, String collectionType, String courseId, Long userId, boolean isAdmin);

    /**
     * 查询分片上下文（前/后文，用于对话引用展示）
     */
    Map<String, DocumentChunkVO> findContext(Long id, Long userId, String role);

    /**
     * 按课程 ID 查询分片（开放问答检索用）
     */
    List<DocumentChunk> findByCourseId(Long courseId);

    /**
     * 分页查询全部分片（默认按课程维度，管理端用）
     */
    IPage<DocumentChunk> findByCourseIdDefault(int page, int size);

    /**
     * 批量更新分片的收集类型与课程关联
     */
    void batchUpdate(List<Long> ids, String collectionType, String courseId, Long userId, boolean isAdmin);

    /**
     * 批量标记分片为已修正
     */
    void batchCorrected(List<Long> ids, Long userId, boolean isAdmin);

    /**
     * 分页查询待修正分片（管理端）
     */
    IPage<DocumentChunkVO> findPending(Long kbId, Long docId, int page, int size, Long userId, String role);
}
