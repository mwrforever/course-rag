package com.commerce.rag.convert;

import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatSessionDetailVO;
import com.commerce.rag.vo.ChatSessionVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 会话转换器 —— 会话/消息实体 → 管理端视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 等内部字段因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ChatSessionConverter {

    /** 会话实体 → 摘要视图对象（全部业务字段同名映射） */
    ChatSessionVO toSummaryVO(ChatSession session);

    /** 消息实体 → 消息视图对象（全部业务字段同名映射） */
    ChatMessageVO toMessageVO(ChatMessage message);

    /** 会话实体 + 消息列表 → 详情视图对象（messages 来自第二参数） */
    @Mapping(target = "messages", source = "messages")
    ChatSessionDetailVO toDetailVO(ChatSession session, List<ChatMessage> messages);
}
