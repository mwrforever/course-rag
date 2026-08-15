package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.ChatRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * Run 生命周期 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供，无需手写 SQL。
 *
 * @author commerce-rag
 */
@Mapper
public interface ChatRunMapper extends BaseMapper<ChatRun> {}
