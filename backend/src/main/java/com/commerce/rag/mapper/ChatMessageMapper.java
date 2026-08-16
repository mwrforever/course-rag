package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供；批量插入走 IService.saveBatch（JDBC 批处理）。
 *
 * @author commerce-rag
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {}
