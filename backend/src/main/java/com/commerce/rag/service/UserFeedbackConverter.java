package com.commerce.rag.service;

import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.vo.UserFeedbackVO;
import org.mapstruct.Mapper;

/**
 * 用户反馈转换器 —— UserFeedback 实体 → UserFeedbackVO
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface UserFeedbackConverter {

    /** 实体 → 用户反馈视图对象（全部业务字段同名映射） */
    UserFeedbackVO toVO(UserFeedback feedback);
}
