package com.commerce.rag.auth;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.properties.AuthProperties;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 登录会话编排服务 —— sys_login_record 生命周期（创建/刷新更新/登出吊销）
 *
 * <p>承接原 AuthController 对 loginRecordMapper 的直调逻辑，修复
 * 「controller 直调 mapper」分层违规：controller → service → mapper。
 *
 * <p>依赖：
 * <ul>
 *   <li>SysLoginRecordMapper — sys_login_record 持久化（insert/selectOne/update）</li>
 *   <li>DeviceKickService — jti 黑名单（addToBlacklist）</li>
 *   <li>AuthProperties — Token 有效期配置（accessTokenExpiry/refreshTokenExpiry）</li>
 * </ul>
 *
 * <p>注意：update 类操作均内置 try/catch 降级（失败仅 warn 日志，不阻断调用方主流程），
 * 与迁移前 AuthController 内的语义完全一致。
 *
 * @author commerce-rag
 */
@Service
public class AuthSessionService {

    private static final Logger log = LoggerFactory.getLogger(AuthSessionService.class);

    private final SysLoginRecordMapper loginRecordMapper;
    private final DeviceKickService deviceKickService;
    private final AuthProperties authProperties;

    public AuthSessionService(
            SysLoginRecordMapper loginRecordMapper,
            DeviceKickService deviceKickService,
            AuthProperties authProperties) {
        this.loginRecordMapper = loginRecordMapper;
        this.deviceKickService = deviceKickService;
        this.authProperties = authProperties;
    }

    /**
     * 创建登录记录（登录成功时调用）
     *
     * <p>构建 status=ACTIVE 的 sys_login_record，expiresAt = now + RT 有效期（7d），
     * insert 后返回登录记录主键 ID（Entity 不出 service 边界），供调用方执行设备互踢。
     *
     * @param userId     用户 ID（来自 SysUser.id，非空）
     * @param jtiAt      本次登录 AT 的 jti（系统生成，非空）
     * @param jtiRt      本次登录 RT 的 jti（系统生成，非空）
     * @param deviceType 设备类型（默认 WEB_DESKTOP）
     * @param deviceInfo 设备信息（请求 User-Agent，允许为空）
     * @param ipAddress  客户端 IP（允许为空）
     * @return 插入后的登录记录主键 ID（数据库回填）
     */
    public Long createLoginRecord(
            Long userId, String jtiAt, String jtiRt, String deviceType, String deviceInfo, String ipAddress) {
        SysLoginRecord loginRecord = new SysLoginRecord();
        loginRecord.setUserId(userId);
        loginRecord.setJtiAt(jtiAt);
        loginRecord.setJtiRt(jtiRt);
        loginRecord.setDeviceType(deviceType);
        loginRecord.setDeviceInfo(deviceInfo);
        loginRecord.setIpAddress(ipAddress);
        loginRecord.setExpiresAt(LocalDateTime.now().plusSeconds(authProperties.refreshTokenExpiry()));
        loginRecord.setStatus("ACTIVE");
        loginRecordMapper.insert(loginRecord);
        return loginRecord.getId();
    }

    /**
     * RT 一次性旋转后更新 login_record（刷新成功时调用）
     *
     * <p>按 userId + 旧 jti_rt + ACTIVE 定位记录，覆盖 jti_at/jti_rt/expires_at/updated_at。
     * expires_at 随 RT 旋转同步滑动（now + RT 有效期），保证登出时 RT 黑名单 TTL
     * 能完整覆盖旋转后 RT 的密码学生命周期。更新失败仅 warn 降级（不阻断刷新主流程）。
     *
     * @param userId    用户 ID
     * @param oldJtiRt  旧 RT 的 jti（用于定位记录）
     * @param newJtiAt  新 AT 的 jti
     * @param newJtiRt  新 RT 的 jti
     */
    public void updateLoginRecordOnRefresh(Long userId, String oldJtiRt, String newJtiAt, String newJtiRt) {
        try {
            LambdaUpdateWrapper<SysLoginRecord> wrapper = Wrappers.<SysLoginRecord>lambdaUpdate()
                    .eq(SysLoginRecord::getUserId, userId)
                    .eq(SysLoginRecord::getJtiRt, oldJtiRt)
                    .eq(SysLoginRecord::getStatus, "ACTIVE")
                    .set(SysLoginRecord::getJtiAt, newJtiAt)
                    .set(SysLoginRecord::getJtiRt, newJtiRt)
                    .set(
                            SysLoginRecord::getExpiresAt,
                            LocalDateTime.now().plusSeconds(authProperties.refreshTokenExpiry()))
                    .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
            loginRecordMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("刷新时更新 login_record 失败: userId={}, oldJtiRt={}", userId, oldJtiRt, e);
        }
    }

    /**
     * 登出吊销：AT jti + 同会话 RT jti 双入黑名单，login_record 置 REVOKED
     *
     * <p>RT 必须吊销：RT 有效期（7d）远长于 AT（15min），
     * 否则登出后旧 RT 仍可 refresh 出全新 Token 对。
     *
     * <p>流程：
     * <ol>
     *   <li>AT jti 入黑名单（ACCESS/MANUAL_REVOKE，TTL = AT 有效期）</li>
     *   <li>查该会话 ACTIVE login_record 取 jti_rt，RT 入黑名单
     *       （REFRESH/MANUAL_REVOKE，TTL 取记录真实过期时间，且不超过 now+7d 上限）</li>
     *   <li>login_record → REVOKED（update 失败仅 warn 降级，幂等）</li>
     * </ol>
     *
     * @param userId 用户 ID（来自 AT claims）
     * @param jtiAt  AT 的 jti（来自 AT claims）
     */
    public void revokeOnLogout(Long userId, String jtiAt) {
        // 1. AT jti 入黑名单
        deviceKickService.addToBlacklist(
                jtiAt,
                "ACCESS",
                userId,
                userId,
                "MANUAL_REVOKE",
                LocalDateTime.now().plusSeconds(authProperties.accessTokenExpiry()));

        // 2. 查该会话 ACTIVE login_record 取 jti_rt（软删由 @TableLogic 自动过滤）。
        //    P0-3 修复：不按 jti_at 定位（RT 旋转会覆盖 jti_at/jti_rt）——旧 AT 登出时
        //    （15min 内仍有效）jti_at 已不匹配，会导致新 RT 漏吊销而永久续命；
        //    改为按 userId + ACTIVE 定位当前会话（系统单设备互踢，同一用户最多一条 ACTIVE），
        //    保证旋转后的新 RT 同样被吊销。
        SysLoginRecord record = loginRecordMapper.selectOne(
                Wrappers.<SysLoginRecord>lambdaQuery()
                        .eq(SysLoginRecord::getUserId, userId)
                        .eq(SysLoginRecord::getStatus, "ACTIVE"),
                false);
        if (record != null && record.getJtiRt() != null && !record.getJtiRt().isEmpty()) {
            // RT 入黑名单，TTL 取 login_record 记录的真实过期时间，
            // 并以 now+RT 有效期（7d）为上限兜底：防止历史脏数据导致黑名单超期，
            // 确保 RT 完整密码学生命周期内始终被吊销
            LocalDateTime rtExpiry = LocalDateTime.now().plusSeconds(authProperties.refreshTokenExpiry());
            deviceKickService.addToBlacklist(
                    record.getJtiRt(),
                    "REFRESH",
                    userId,
                    userId,
                    "MANUAL_REVOKE",
                    record.getExpiresAt() != null && record.getExpiresAt().isBefore(rtExpiry)
                            ? record.getExpiresAt()
                            : rtExpiry);
        }

        // 3. login_record → REVOKED（update 失败仅 warn 降级，幂等）
        revokeLoginRecord(userId, jtiAt);

        log.info("用户登出: userId={}, jtiAt={}", userId, jtiAt);
    }

    /**
     * 将指定会话 login_record 置 REVOKED（update 失败仅 warn 降级）
     *
     * <p>P0-3：与吊销定位一致按 userId + ACTIVE（不按 jti_at），旧 AT 登出时仍能置 REVOKED。
     *
     * @param userId 用户 ID
     * @param jtiAt  会话 AT 的 jti（仅用于日志）
     */
    private void revokeLoginRecord(Long userId, String jtiAt) {
        try {
            LambdaUpdateWrapper<SysLoginRecord> wrapper = Wrappers.<SysLoginRecord>lambdaUpdate()
                    .eq(SysLoginRecord::getUserId, userId)
                    .eq(SysLoginRecord::getStatus, "ACTIVE")
                    .set(SysLoginRecord::getStatus, "REVOKED")
                    .set(SysLoginRecord::getUpdatedAt, LocalDateTime.now());
            loginRecordMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("登出时更新 login_record 失败: userId={}, jtiAt={}", userId, jtiAt, e);
        }
    }
}
