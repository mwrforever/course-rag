package com.commerce.rag.service;

import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.vo.DocumentChunkVO;
import org.mapstruct.Mapper;

/**
 * 文档分片转换器 —— DocumentChunk 实体 → DocumentChunkVO（denseVector 不入 VO）
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * denseVector/deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface DocumentChunkConverter {

    /** 实体 → 文档分片视图对象（全部业务字段同名映射） */
    DocumentChunkVO toVO(DocumentChunk chunk);
}
