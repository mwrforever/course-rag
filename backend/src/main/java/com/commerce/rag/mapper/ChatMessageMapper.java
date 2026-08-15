package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.ChatMessage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供。批量插入走 XML 多值 INSERT（run 结束后一次性写入）。
 *
 * @author commerce-rag
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /**
     * 批量插入消息（run 结束后一次性写入，替代原 JdbcTemplate.batchUpdate 语义）
     *
     * @param messages 消息列表（非空，ID 由调用方兜底分配）
     */
    void batchInsert(List<ChatMessage> messages);
}
