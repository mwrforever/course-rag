package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.vo.ChunkBriefVO;
import com.commerce.rag.vo.ChunkContextVO;
import com.commerce.rag.vo.ChunkVO;
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
     * 查询分片上下文（父/前/当前/后，B 端管理用，TEACHER 数据权限）
     */
    Map<String, DocumentChunkVO> findContext(Long id, Long userId, String role);

    /**
     * 查询分片上下文（父/前/后，用于 C 端对话引用展示）
     *
     * <p>内部按主键查主分片与相邻分片后组装视图对象，主分片不存在返回 null。
     *
     * @param chunkId 分片 ID
     * @return 分片上下文视图对象（含 courseId 供选课校验），不存在返回 null
     */
    ChunkContextVO findContext(Long chunkId);

    /**
     * 按课程 ID 查询分片（C 端 J2：课程专属资料）
     *
     * @param courseId 课程 ID
     * @return 资料分片视图对象列表（按 chunk_index 排序）
     */
    List<ChunkVO> findByCourseIdAsVO(Long courseId);

    /**
     * 分页查询通用资料库分片（C 端 J3：course_id='DEFAULT'）
     *
     * @param page 页码（1-based）
     * @param size 每页条数（<=0 用默认 20）
     * @return 分页结果（records 为简略视图对象）
     */
    IPage<ChunkBriefVO> findByCourseIdDefaultAsVO(int page, int size);

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
