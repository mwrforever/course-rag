package com.commerce.rag.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.vo.KnowledgeBaseVO;

/**
 * 知识库服务接口 —— 封装 knowledge_base 的 CRUD + 级联删除（主表 KnowledgeBase）
 *
 * @author commerce-rag
 */
public interface IKnowledgeBaseService extends IService<KnowledgeBase> {

    /**
     * 创建知识库
     *
     * @param name        知识库名称
     * @param description 描述
     * @param createdBy   创建者 ID（教师 user_id）
     * @return 已持久化知识库的视图对象
     */
    KnowledgeBaseVO create(String name, String description, Long createdBy);

    /**
     * 按 ID 查询知识库（TEACHER 数据权限过滤）
     *
     * @return 知识库视图对象，不存在或无权访问返回 null
     */
    KnowledgeBaseVO findById(Long id, Long userId, String role);

    /**
     * 分页查询知识库（TEACHER 按 created_by 过滤）
     */
    Page<KnowledgeBaseVO> findPage(int page, int size, String keyword, Long userId, String role);

    /**
     * 更新知识库
     */
    void update(Long id, String name, String description, Long operatorId, boolean isAdmin);

    /**
     * 删除知识库（级联软删 + Milvus/MinIO 清理）
     */
    void delete(Long id, Long operatorId, boolean isAdmin);
}
