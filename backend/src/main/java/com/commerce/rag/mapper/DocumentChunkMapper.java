package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.DocumentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 文档分片 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供，无需手写 SQL；
 * 教师数据权限分页查询（doc_id IN 子查询，perf P3-2）在 DocumentChunkMapper.xml 中映射实现。
 *
 * @author commerce-rag
 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /**
     * 教师数据权限分页查询（perf P3-2：doc_id IN 子查询替代应用层全量 doc id + IN 列表）
     *
     * <p>子查询在 DB 侧完成 created_by 过滤（document.created_by 有索引），教师文档量大时
     * 避免 IN 列表超长与执行计划退化；自定义 SQL 不经过 @TableLogic，deleted=0 在 SQL 内显式过滤。
     *
     * @param page        分页参数（MP 分页插件自动拼接 count + limit）
     * @param docId       文档 ID 筛选（可选）
     * @param kbId        知识库 ID 筛选（可选）
     * @param pendingOnly 仅查 correction_status=PENDING（findPending 用）
     * @param userId      教师用户 ID（文档 created_by 过滤）
     * @return 分页结果（按 chunk_index 升序）
     */
    IPage<DocumentChunk> selectPageFilteredByTeacher(
            Page<DocumentChunk> page,
            @Param("docId") Long docId,
            @Param("kbId") Long kbId,
            @Param("pendingOnly") boolean pendingOnly,
            @Param("userId") Long userId);
}
