package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.UserEpisodicMemory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户经历记忆 Mapper（MP BaseMapper，spec §8.5 user_episodic_memory 表）
 *
 * @author commerce-rag
 */
@Mapper
public interface UserEpisodicMemoryMapper extends BaseMapper<UserEpisodicMemory> {}
