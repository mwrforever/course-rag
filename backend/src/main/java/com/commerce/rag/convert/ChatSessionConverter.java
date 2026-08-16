package com.commerce.rag.convert;

import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatSessionVO;
import org.mapstruct.Mapper;

/**
 * 会话转换器 —— 会话/消息实体 → 视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 等内部字段因 VO 无对应组件而自然忽略。
 *
 * <p>会话详情（ChatSessionDetailVO）由 AdminSessionController 在 controller 内
 * 以 ChatSessionVO + 消息 VO 列表组装，本转换器不再承载实体级详情组装。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ChatSessionConverter {

    /** 会话实体 → 摘要视图对象（全部业务字段同名映射） */
    ChatSessionVO toSummaryVO(ChatSession session);

    /** 消息实体 → 消息视图对象（全部业务字段同名映射） */
    ChatMessageVO toMessageVO(ChatMessage message);
}
