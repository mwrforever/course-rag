package com.commerce.rag.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.commerce.rag.entity.SysLoginRecord;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录记录 Mapper —— MyBatis-Plus BaseMapper 接口
 *
 * <p>单表 CRUD 由 BaseMapper 提供；带 FOR UPDATE 行锁与幂等状态更新的
 * 复杂 SQL 在 XML 中实现（DeviceKickService PG 降级与审计落盘）。
 *
 * @author commerce-rag
 */
@Mapper
public interface SysLoginRecordMapper extends BaseMapper<SysLoginRecord> {

    /** 锁定某用户+设备类型的活跃登录记录（FOR UPDATE 行锁，PG 降级互踢用） */
    List<SysLoginRecord> selectActiveForUpdate(Long userId, String deviceType);

    /** 按 id 置 REVOKED（updated_at 数据库生成） */
    int updateStatusById(Long id);

    /** 按 id 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByIdIfActive(Long id);

    /** 按 user_id + jti_at 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByUserAndJtiActive(Long userId, String jtiAt);

    /** 查询某用户全部活跃登录记录 */
    List<SysLoginRecord> selectActiveByUserId(Long userId);
}
