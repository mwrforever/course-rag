package com.commerce.rag.service;

import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.vo.KnowledgeBaseVO;
import org.mapstruct.Mapper;

/**
 * 知识库转换器 —— KnowledgeBase 实体 → KnowledgeBaseVO
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface KnowledgeBaseConverter {

    /** 实体 → 知识库视图对象（全部业务字段同名映射） */
    KnowledgeBaseVO toVO(KnowledgeBase knowledgeBase);
}
