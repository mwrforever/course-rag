package com.commerce.rag.convert;

import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.vo.ChatRunVO;
import org.mapstruct.Mapper;

/**
 * Run 转换器 —— ChatRun 实体 → 视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * modelCalls/traceId/errorMessage/metaJson/deleted/startedAt/endedAt
 * 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ChatRunConverter {

    /** Run 实体 → 视图对象（全部业务字段同名映射） */
    ChatRunVO toVO(ChatRun run);
}
