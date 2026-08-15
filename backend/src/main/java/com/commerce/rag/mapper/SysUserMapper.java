package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.SysUser;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供，复杂查询走 XML 映射。
 *
 * @author commerce-rag
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 按 ID 批量查询用户（仅 id/username/displayName 列，供选课学生列表组装）
     *
     * @param ids 用户 ID 列表（非空）
     * @return 用户列表（不含已软删用户）
     */
    List<SysUser> selectByIdsIn(List<Long> ids);
}
