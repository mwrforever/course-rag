package com.commerce.rag.convert;

import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.vo.SysLoginRecordVO;
import com.commerce.rag.vo.SysTokenBlacklistVO;
import org.mapstruct.Mapper;

/**
 * 登录记录/黑名单转换器 —— 实体 → 管理端视图对象
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 * deleted 因 VO 无对应组件而自然忽略。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface AdminLoginRecordConverter {

    /** 登录记录实体 → 视图对象（全部业务字段同名映射） */
    SysLoginRecordVO toLoginRecordVO(SysLoginRecord record);

    /** 黑名单实体 → 视图对象（全部业务字段同名映射） */
    SysTokenBlacklistVO toBlacklistVO(SysTokenBlacklist record);
}
