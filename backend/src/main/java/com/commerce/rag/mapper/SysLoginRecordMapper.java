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

    /**
     * 锁定某用户+设备类型的活跃登录记录（FOR UPDATE 行锁，PG 降级互踢用）。
     *
     * <p>B1-1：newLoginId 为本次登录刚插入的新记录主键（登录时序为先 createLoginRecord 后互踢），
     * SQL 层以 {@code id != #{newLoginId}} 排除，防止降级互踢把新会话误判为旧设备导致自吊销。
     * 参数非空（由 insert 回填主键，恒有值）；传 null 将导致 PG {@code id != NULL} 恒为假而返回空集。
     *
     * @param userId     用户 ID
     * @param deviceType 设备类型
     * @param newLoginId 本次登录的新记录主键（排除条件，非空）
     */
    List<SysLoginRecord> selectActiveForUpdate(Long userId, String deviceType, Long newLoginId);

    /** 按 id 置 REVOKED（updated_at 数据库生成） */
    int updateStatusById(Long id);

    /** 按 id 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByIdIfActive(Long id);

    /** 按 user_id + jti_at 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByUserAndJtiActive(Long userId, String jtiAt);

    /** 查询某用户全部活跃登录记录 */
    List<SysLoginRecord> selectActiveByUserId(Long userId);
}
