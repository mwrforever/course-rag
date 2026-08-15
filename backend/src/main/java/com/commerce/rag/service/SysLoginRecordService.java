package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 登录记录服务 —— 管理登录会话记录 + Token 黑名单
 *
 * <p>对应 CRUD 模块 K（K1-K7）：
 * <ul>
 *   <li>登录记录分页/查看/踢出设备</li>
 *   <li>Token 黑名单分页/手动添加/删除/清理过期</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
public class SysLoginRecordService {

    private static final Logger log = LoggerFactory.getLogger(SysLoginRecordService.class);

    private final SysLoginRecordMapper loginRecordMapper;
    private final SysTokenBlacklistMapper tokenBlacklistMapper;
    private final DeviceKickService deviceKickService;

    public SysLoginRecordService(
            SysLoginRecordMapper loginRecordMapper,
            SysTokenBlacklistMapper tokenBlacklistMapper,
            DeviceKickService deviceKickService) {
        this.loginRecordMapper = loginRecordMapper;
        this.tokenBlacklistMapper = tokenBlacklistMapper;
        this.deviceKickService = deviceKickService;
    }

    // ========================================================================
    // 登录记录管理（K1-K3）
    // ========================================================================

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
    public IPage<SysLoginRecord> findPage(int page, int size, Long userId, String deviceType, String status) {
        Page<SysLoginRecord> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SysLoginRecord> wrapper =
                Wrappers.<SysLoginRecord>lambdaQuery().orderByDesc(SysLoginRecord::getCreatedAt);
        if (userId != null) {
            wrapper.eq(SysLoginRecord::getUserId, userId);
        }
        if (deviceType != null && !deviceType.isEmpty()) {
            wrapper.eq(SysLoginRecord::getDeviceType, deviceType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysLoginRecord::getStatus, status);
        }
        return loginRecordMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 查看登录记录详情
     */
    public SysLoginRecord findById(Long id) {
        SysLoginRecord record = loginRecordMapper.selectById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "登录记录不存在");
        }
        return record;
    }

    /**
     * 踢出设备（撤销登录记录 + jti 入黑名单）
     *
     * @param id            登录记录 ID
     * @param adminUserId   操作的管理员 ID
     */
    public void revoke(Long id, Long adminUserId) {
        SysLoginRecord record = loginRecordMapper.selectById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "登录记录不存在");
        }

        if (!"ACTIVE".equals(record.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该记录已非活跃状态");
        }

        // 标记为 REVOKED
        LambdaUpdateWrapper<SysLoginRecord> wrapper = Wrappers.<SysLoginRecord>lambdaUpdate()
                .eq(SysLoginRecord::getId, id)
                .set(SysLoginRecord::getStatus, "REVOKED")
                .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
        loginRecordMapper.update(null, wrapper);

        // jti 入黑名单
        if (record.getJtiAt() != null && !record.getJtiAt().isEmpty()) {
            deviceKickService.addToBlacklist(
                    record.getJtiAt(),
                    "ACCESS",
                    record.getUserId(),
                    adminUserId,
                    "MANUAL_REVOKE",
                    record.getExpiresAt());
        }
        if (record.getJtiRt() != null && !record.getJtiRt().isEmpty()) {
            deviceKickService.addToBlacklist(
                    record.getJtiRt(),
                    "REFRESH",
                    record.getUserId(),
                    adminUserId,
                    "MANUAL_REVOKE",
                    record.getExpiresAt());
        }

        log.info("踢出设备: recordId={}, userId={}, operator={}", id, record.getUserId(), adminUserId);
    }

    /**
     * 清理过期登录记录（标记为 EXPIRED）
     */
    public int cleanupExpired() {
        LambdaUpdateWrapper<SysLoginRecord> wrapper = Wrappers.<SysLoginRecord>lambdaUpdate()
                .eq(SysLoginRecord::getStatus, "ACTIVE")
                .lt(SysLoginRecord::getExpiresAt, LocalDateTime.now())
                .set(SysLoginRecord::getStatus, "EXPIRED")
                .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
        int updated = loginRecordMapper.update(null, wrapper);
        log.info("清理过期登录记录: count={}", updated);
        return updated;
    }

    // ========================================================================
    // Token 黑名单管理（K4-K7）
    // ========================================================================

    /**
     * 分页查询 Token 黑名单
     *
     * @param page      页码
     * @param size      每页条数
     * @param userId    用户 ID 筛选（可空）
     * @param jti       jti 筛选（可空）
     * @param tokenType 类型筛选（可空）
     * @return 分页结果
     */
    public IPage<SysTokenBlacklist> findBlacklistPage(int page, int size, Long userId, String jti, String tokenType) {
        Page<SysTokenBlacklist> pageObj = new Page<>(page, size);
        LambdaQueryWrapper<SysTokenBlacklist> wrapper =
                Wrappers.<SysTokenBlacklist>lambdaQuery().orderByDesc(SysTokenBlacklist::getCreatedAt);
        if (userId != null) {
            wrapper.eq(SysTokenBlacklist::getUserId, userId);
        }
        if (jti != null && !jti.isEmpty()) {
            wrapper.eq(SysTokenBlacklist::getJti, jti);
        }
        if (tokenType != null && !tokenType.isEmpty()) {
            wrapper.eq(SysTokenBlacklist::getTokenType, tokenType);
        }
        return tokenBlacklistMapper.selectPage(pageObj, wrapper);
    }

    /**
     * 手动添加 Token 到黑名单
     *
     * @param jti           JWT ID
     * @param tokenType     ACCESS / REFRESH
     * @param userId        用户 ID
     * @param blacklistedBy 操作人 ID
     * @param reason        原因
     * @param expiresAt     过期时间
     */
    public void addToBlacklist(
            String jti, String tokenType, Long userId, Long blacklistedBy, String reason, LocalDateTime expiresAt) {
        deviceKickService.addToBlacklist(jti, tokenType, userId, blacklistedBy, reason, expiresAt);
        log.info("手动添加黑名单: jti={}, userId={}, operator={}", jti, userId, blacklistedBy);
    }

    /**
     * 删除黑名单记录（软删除）
     *
     * @param id 黑名单记录 ID
     */
    public void deleteFromBlacklist(Long id) {
        SysTokenBlacklist record = tokenBlacklistMapper.selectById(id);
        if (record == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "黑名单记录不存在");
        }
        LambdaUpdateWrapper<SysTokenBlacklist> wrapper = Wrappers.<SysTokenBlacklist>lambdaUpdate()
                .eq(SysTokenBlacklist::getId, id)
                .set(SysTokenBlacklist::getDeleted, System.currentTimeMillis());
        tokenBlacklistMapper.update(null, wrapper);
        log.info("删除黑名单记录: id={}, jti={}", id, record.getJti());
    }

    /**
     * 清理过期黑名单记录
     *
     * @return 清理数量
     */
    public int cleanupExpiredBlacklist() {
        LambdaQueryWrapper<SysTokenBlacklist> queryWrapper =
                Wrappers.<SysTokenBlacklist>lambdaQuery().lt(SysTokenBlacklist::getExpiresAt, LocalDateTime.now());
        List<SysTokenBlacklist> expired = tokenBlacklistMapper.selectList(queryWrapper);
        for (SysTokenBlacklist record : expired) {
            LambdaUpdateWrapper<SysTokenBlacklist> wrapper = Wrappers.<SysTokenBlacklist>lambdaUpdate()
                    .eq(SysTokenBlacklist::getId, record.getId())
                    .set(SysTokenBlacklist::getDeleted, System.currentTimeMillis());
            tokenBlacklistMapper.update(null, wrapper);
        }
        log.info("清理过期黑名单记录: count={}", expired.size());
        return expired.size();
    }
}
