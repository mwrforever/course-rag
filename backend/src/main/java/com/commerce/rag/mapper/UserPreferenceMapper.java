package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.UserPreference;
import org.apache.ibatis.annotations.Mapper;

/** 用户偏好 Mapper（MyBatis-Plus 数据访问，不含业务逻辑） */
@Mapper
public interface UserPreferenceMapper extends BaseMapper<UserPreference> {}
