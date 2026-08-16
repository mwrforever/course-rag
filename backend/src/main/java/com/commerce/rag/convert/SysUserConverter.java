package com.commerce.rag.convert;

import com.commerce.rag.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import org.mapstruct.Mapper;

/**
 * 系统用户转换器 —— SysUser 实体 ↔ UserDTO
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface SysUserConverter {

    /** 实体 → 用户 DTO（6 字段全同名，无需 @Mapping） */
    UserDTO toDTO(SysUser user);
}
