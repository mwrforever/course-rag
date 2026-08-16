package com.commerce.rag.convert;

import com.commerce.rag.entity.Document;
import com.commerce.rag.vo.DocumentVO;
import org.mapstruct.Mapper;

/**
 * 文档转换器 —— Document 实体 → DocumentVO（sourcePath 不入 VO）
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * sourcePath/deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface DocumentConverter {

    /** 实体 → 文档视图对象（全部业务字段同名映射） */
    DocumentVO toVO(Document document);
}
