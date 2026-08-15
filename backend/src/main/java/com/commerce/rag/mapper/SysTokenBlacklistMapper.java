package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.SysTokenBlacklist;
import org.apache.ibatis.annotations.Mapper;

/**
 * Token 黑名单 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供；带业务条件的统计查询在 XML 中实现
 * （DeviceKickService PG 降级黑名单查询）。
 *
 * @author commerce-rag
 */
@Mapper
public interface SysTokenBlacklistMapper extends BaseMapper<SysTokenBlacklist> {

    /** 按 jti 统计黑名单记录数（deleted=0 未删除），DeviceKickService PG 降级查询用 */
    Long countByJti(String jti);
}
