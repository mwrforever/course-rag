package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import java.time.LocalDateTime;

/**
 * 登录记录与黑名单服务接口 —— 登录审计、吊销、黑名单管理（主表 SysLoginRecord）
 *
 * @author commerce-rag
 */
public interface ISysLoginRecordService extends IService<SysLoginRecord> {

    /**
     * 分页查询登录记录
     *
     * @param page       页码
     * @param size       每页条数
     * @param userId     用户 ID 筛选（可空）
     * @param deviceType 设备类型筛选（可空）
     * @param status     状态筛选（可空）
     * @return 分页结果
     */
    IPage<SysLoginRecord> findPage(int page, int size, Long userId, String deviceType, String status);

    /**
     * 按 ID 查询登录记录
     */
    SysLoginRecord findById(Long id);

    /**
     * 吊销登录记录（管理员操作，置为 REVOKED 并加入黑名单）
     */
    void revoke(Long id, Long adminUserId);

    /**
     * 清理过期登录记录（定时任务）
     *
     * @return 清理条数
     */
    int cleanupExpired();

    /**
     * 分页查询黑名单
     */
    IPage<SysTokenBlacklist> findBlacklistPage(int page, int size, Long userId, String jti, String tokenType);

    /**
     * 手动添加黑名单记录
     */
    void addToBlacklist(
            String jti, String tokenType, Long userId, Long blacklistedBy, String reason, LocalDateTime expiresAt);

    /**
     * 删除黑名单记录（软删除）
     */
    void deleteFromBlacklist(Long id);

    /**
     * 清理过期黑名单记录（定时任务）
     *
     * @return 清理条数
     */
    int cleanupExpiredBlacklist();
}
