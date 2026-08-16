package com.commerce.rag.auth;

import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import com.commerce.rag.properties.AuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备互踢服务 —— Redis Lua 原子操作 + PG 降级
 *
 * <p>Redis 是执法层（纳秒级生效），PG 是审计层（持久化记录）。
 * <p>降级策略：Redis 不可用时用 PG FOR UPDATE 行锁事务保证原子性。
 * <p>PG 降级与审计的全部 SQL 走 MyBatis mapper XML（SysLoginRecordMapper /
 * SysTokenBlacklistMapper），业务层不拼 SQL；FOR UPDATE 行锁、status='ACTIVE'
 * 条件、updated_at = now() 语义与 XML 逐字等价。
 * <p>P3 A11：RT 一次性旋转的「检查 + 置位」原子化于单条 Lua 脚本（mark_rt_used.lua），
 * 消除并发 refresh 的 TOCTOU 双签窗口。
 *
 * <p>注：本类构造器为手写（非 Lombok），因需在构造器内加载 Lua 脚本
 * （DefaultRedisScript 设置 location/resultType），属工程宪法「特殊场景允许手写」的合法场景。
 *
 * @author commerce-rag
 */
@Service
public class DeviceKickService {

    private static final Logger log = LoggerFactory.getLogger(DeviceKickService.class);

    /** Redis Key 前缀 */
    private static final String CUR_KEY_PREFIX = "auth:cur:";

    private static final String BL_KEY_PREFIX = "auth:bl:";
    private static final String RT_USED_KEY_PREFIX = "auth:rt:used:";
    private static final String DISABLE_KEY_PREFIX = "auth:disable:";

    private final StringRedisTemplate redisTemplate;
    private final TokenService tokenService;
    private final AuthProperties authProperties;
    private final SysLoginRecordMapper loginRecordMapper;
    private final SysTokenBlacklistMapper tokenBlacklistMapper;
    private final ObjectMapper objectMapper;

    /** 自身代理（P0-5：@Transactional 基于 JDK 代理，同类自调用不经过代理导致注解失效；@Lazy 延迟解析避免循环依赖） */
    private final DeviceKickService self;

    private final DefaultRedisScript<String> kickAndLoginScript;
    private final DefaultRedisScript<String> disableUserScript;
    private final DefaultRedisScript<Long> markRtUsedScript;

    public DeviceKickService(
            StringRedisTemplate redisTemplate,
            TokenService tokenService,
            AuthProperties authProperties,
            SysLoginRecordMapper loginRecordMapper,
            SysTokenBlacklistMapper tokenBlacklistMapper,
            @Lazy DeviceKickService self) {
        this.redisTemplate = redisTemplate;
        this.tokenService = tokenService;
        this.authProperties = authProperties;
        this.loginRecordMapper = loginRecordMapper;
        this.tokenBlacklistMapper = tokenBlacklistMapper;
        this.self = self;
        this.objectMapper = new ObjectMapper();

        // 加载 Lua 脚本
        this.kickAndLoginScript = new DefaultRedisScript<>();
        this.kickAndLoginScript.setLocation(new ClassPathResource("lua/kick_and_login.lua"));
        this.kickAndLoginScript.setResultType(String.class);

        this.disableUserScript = new DefaultRedisScript<>();
        this.disableUserScript.setLocation(new ClassPathResource("lua/disable_user.lua"));
        this.disableUserScript.setResultType(String.class);

        // P3 A11: RT 一次性旋转原子标记脚本（检查+置位单条 Lua，消除 TOCTOU）
        this.markRtUsedScript = new DefaultRedisScript<>();
        this.markRtUsedScript.setLocation(new ClassPathResource("lua/mark_rt_used.lua"));
        this.markRtUsedScript.setResultType(Long.class);
    }

    /**
     * 设备互踢 + 登录（原子操作）
     *
     * <p>执行 kick_and_login.lua：
     * <ol>
     *   <li>GET 旧设备 jti</li>
     *   <li>SET 新设备指针（覆盖）</li>
     *   <li>SETEX 旧 jti 入黑名单</li>
     *   <li>返回 {kicked, old_jti_at, old_jti_rt}</li>
     * </ol>
     *
     * <p>H-2（性能报告 2026-08-16）：黑名单 TTL 固定传全量有效期（AT=accessTokenExpiry、
     * RT=refreshTokenExpiry），旧设备是否存在由 Lua 内 GET 决定——删除 Java 预读
     * （原实现"预读 TTL"与 Lua 内 GET 分离存在 TOCTOU：并发登录时 B 的预读读到 null，
     * 但 Lua 内实际 GET 到旧设备 → 旧 RT 漏进黑名单，互踢安全边界被绕过；
     * 且注释声称"剩余 TTL"实为恒传全量，注释与实现不符）。
     * 黑名单 TTL 多留无害（过期自动清理），Lua 原子性由单脚本保证。
     *
     * @param userId       用户 ID
     * @param deviceType   设备类型
     * @param newJtiAt     新 Access Token 的 jti
     * @param newJtiRt     新 Refresh Token 的 jti
     * @param newLoginId   新登录记录 ID
     * @return KickResult（是否踢出旧设备 + 旧 jti）
     */
    public KickResult kickAndLogin(Long userId, String deviceType, String newJtiAt, String newJtiRt, Long newLoginId) {
        String curKey = CUR_KEY_PREFIX + userId + ":" + deviceType;
        String newValue = newJtiAt + "|" + newJtiRt + "|" + newLoginId;
        long curKeyTtl = authProperties.refreshTokenExpiry();

        try {
            // 执行 Lua 脚本（旧设备检测 + 黑名单写入全部原子化于 Lua 内，无 Java 预读）
            String result = redisTemplate.execute(
                    kickAndLoginScript,
                    List.of(curKey),
                    newValue,
                    String.valueOf(curKeyTtl),
                    String.valueOf(authProperties.accessTokenExpiry()),
                    String.valueOf(authProperties.refreshTokenExpiry()),
                    "DEVICE_KICKED",
                    String.valueOf(System.currentTimeMillis()));

            log.info("设备互踢 Lua 执行: userId={}, deviceType={}, result={}", userId, deviceType, result);
            KickResult kickResult = parseKickResult(result);
            // Lua 成功且确实踢出旧设备 → PG 审计落盘（Redis 执法层 + PG 审计层双写）
            if (kickResult.kicked()) {
                kickPgAudit(userId, kickResult);
            }
            return kickResult;
        } catch (Exception e) {
            log.warn("Redis 设备互踢失败，降级到 PG 事务: userId={}, deviceType={}", userId, deviceType, e);
            // P0-5: 经 self 代理调用，保证 @Transactional 生效（同类自调用不经过代理）
            return self.kickAndLoginPgFallback(userId, deviceType, newJtiAt, newJtiRt, newLoginId);
        }
    }

    /**
     * 检查 jti 是否在黑名单中
     *
     * <p>先查 Redis（O(1)），Redis 不可用时降级查 PG。
     *
     * @param jti JWT ID
     * @return true=在黑名单中（已吊销）
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isEmpty()) {
            return false;
        }

        // 先查 Redis
        try {
            Boolean exists = redisTemplate.hasKey(BL_KEY_PREFIX + jti);
            if (exists != null && exists) {
                return true;
            }
        } catch (Exception e) {
            log.warn("Redis 黑名单查询失败，降级到 PG: jti={}", jti);
        }

        // 降级查 PG（mapper XML：COUNT(*) WHERE jti = ? AND deleted = 0）
        try {
            Long count = tokenBlacklistMapper.countByJti(jti);
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("PG 黑名单查询失败: jti={}", jti, e);
            return false;
        }
    }

    /**
     * 原子检查并标记 RT 为已使用（一次性旋转，P3 A11 Lua 化消除 TOCTOU）
     *
     * <p>单条 Lua（mark_rt_used.lua）完成「检查是否已标记 + 置位」，并发 refresh 仅一个能抢占成功；
     * Redis 异常降级放行（fail-open，不写 PG 黑名单——BUG-1 修复：179881e 曾在降级分支先写
     * PG 黑名单再返回 true，而 AuthController 随后 isBlacklisted 降级查 PG 会命中本方法刚写入的
     * TOKEN_REUSE 行 → Redis 故障期间每次 refresh 必 401 自拦截，且该行无清理任务导致 RT 被永久烧毁。
     * 恢复旧 fail-open 语义：降级期间同一 RT 的并发复用检测退化为无（Redis 不可用时无法原子判定）。）
     *
     * @param jtiRt RT 的 JWT ID（不允许为空）
     * @return true=首次使用（本次抢占成功）；false=已被使用（应拒绝）；jtiRt 为空时返回 false
     */
    public boolean markRefreshTokenUsedAtomic(String jtiRt) {
        if (jtiRt == null || jtiRt.isEmpty()) {
            return false;
        }
        try {
            Long result = redisTemplate.execute(
                    markRtUsedScript,
                    List.of(RT_USED_KEY_PREFIX + jtiRt),
                    String.valueOf(authProperties.refreshTokenExpiry()));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Redis RT 原子标记失败，降级放行（fail-open，不写 PG 黑名单避免 isBlacklisted 自拦截）: jtiRt={}", jtiRt, e);
            return true;
        }
    }

    /**
     * 禁用用户 —— 批量将该用户所有活跃 session jti 入黑名单
     *
     * <p>执行 disable_user.lua，防重入（NX + EX 300s）。
     *
     * @param userId       被禁用的用户 ID
     * @param adminUserId  操作的管理员 ID
     * @return 禁用的 jti 数量
     */
    public int disableUser(Long userId, Long adminUserId) {
        // 1. 查询该用户所有活跃 session 的 jti
        List<SysLoginRecord> activeRecords = findActiveLoginRecords(userId);
        if (activeRecords.isEmpty()) {
            log.info("禁用用户: 无活跃 session, userId={}", userId);
            return 0;
        }

        // 构建 Lua ARGV
        List<String> args = new ArrayList<>();
        args.add(String.valueOf(userId));
        args.add(String.valueOf(adminUserId));
        args.add("USER_DISABLED");
        args.add(String.valueOf(System.currentTimeMillis()));

        for (SysLoginRecord record : activeRecords) {
            long atTtl = authProperties.accessTokenExpiry();
            long rtTtl = authProperties.refreshTokenExpiry();
            args.add(record.getJtiAt());
            args.add(String.valueOf(atTtl));
            args.add(record.getJtiRt());
            args.add(String.valueOf(rtTtl));
        }

        String disableKey = DISABLE_KEY_PREFIX + userId;

        try {
            String result = redisTemplate.execute(disableUserScript, List.of(disableKey), args.toArray(new String[0]));

            log.info("禁用用户 Lua 执行: userId={}, result={}", userId, result);

            // 2. PG 审计落盘
            disableUserPgAudit(userId, adminUserId, activeRecords);

            return parseDisableCount(result);
        } catch (Exception e) {
            log.warn("Redis 禁用用户失败，降级到 PG 事务: userId={}", userId, e);
            // P0-5: 经 self 代理调用，保证 @Transactional 生效（同类自调用不经过代理）
            return self.disableUserPgFallback(userId, adminUserId, activeRecords);
        }
    }

    /**
     * 手动将单个 jti 加入黑名单（登出/手动吊销）
     *
     * @param jti           JWT ID
     * @param tokenType     ACCESS / REFRESH
     * @param userId        用户 ID
     * @param blacklistedBy 操作人 ID
     * @param reason        原因
     * @param expiresAt     Token 原始过期时间
     */
    public void addToBlacklist(
            String jti, String tokenType, Long userId, Long blacklistedBy, String reason, LocalDateTime expiresAt) {
        // 先写 Redis（执法层）
        try {
            long ttl = Duration.between(LocalDateTime.now(), expiresAt).getSeconds();
            if (ttl > 0) {
                redisTemplate
                        .opsForValue()
                        .set(BL_KEY_PREFIX + jti, reason + "|" + System.currentTimeMillis(), ttl, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            log.warn("Redis 黑名单写入失败，降级到 PG: jti={}", jti);
        }

        // 再写 PG（审计层）
        addToBlacklistPg(jti, tokenType, userId, blacklistedBy, reason, expiresAt);
    }

    // ========================================================================
    // PG 降级方法
    // ========================================================================

    /**
     * PG 降级：设备互踢（FOR UPDATE 行锁事务）
     */
    @Transactional
    public KickResult kickAndLoginPgFallback(
            Long userId, String deviceType, String newJtiAt, String newJtiRt, Long newLoginId) {
        // 行锁：锁定该用户+设备类型的活跃记录（XML 内 FOR UPDATE 行锁）
        List<SysLoginRecord> oldRecords = loginRecordMapper.selectActiveForUpdate(userId, deviceType);

        String oldJtiAt = "";
        String oldJtiRt = "";
        boolean kicked = false;

        if (!oldRecords.isEmpty()) {
            SysLoginRecord oldRecord = oldRecords.get(0);
            oldJtiAt = oldRecord.getJtiAt();
            oldJtiRt = oldRecord.getJtiRt();
            kicked = true;

            // 标记旧记录为 REVOKED（updated_at 数据库生成）
            loginRecordMapper.updateStatusById(oldRecord.getId());

            // 旧 jti 写入黑名单
            if (!oldJtiAt.isEmpty()) {
                addToBlacklistPg(oldJtiAt, "ACCESS", userId, null, "DEVICE_KICKED");
            }
            if (!oldJtiRt.isEmpty()) {
                addToBlacklistPg(oldJtiRt, "REFRESH", userId, null, "DEVICE_KICKED");
            }
        }

        log.info("PG 降级设备互踢: userId={}, deviceType={}, kicked={}", userId, deviceType, kicked);
        return new KickResult(kicked, oldJtiAt, oldJtiRt);
    }

    /**
     * PG 降级：禁用用户
     */
    @Transactional
    public int disableUserPgFallback(Long userId, Long adminUserId, List<SysLoginRecord> activeRecords) {
        int count = 0;
        for (SysLoginRecord record : activeRecords) {
            // 标记记录为 REVOKED（updated_at 数据库生成）
            loginRecordMapper.updateStatusById(record.getId());

            // jti 写入黑名单
            if (record.getJtiAt() != null && !record.getJtiAt().isEmpty()) {
                addToBlacklistPg(record.getJtiAt(), "ACCESS", userId, adminUserId, "USER_DISABLED");
                count++;
            }
            if (record.getJtiRt() != null && !record.getJtiRt().isEmpty()) {
                addToBlacklistPg(record.getJtiRt(), "REFRESH", userId, adminUserId, "USER_DISABLED");
                count++;
            }
        }

        log.info("PG 降级禁用用户: userId={}, disabledJtiCount={}", userId, count);
        return count;
    }

    /**
     * PG 审计落盘：禁用用户
     */
    private void disableUserPgAudit(Long userId, Long adminUserId, List<SysLoginRecord> activeRecords) {
        for (SysLoginRecord record : activeRecords) {
            // 标记记录为 REVOKED（仅 ACTIVE，幂等）
            loginRecordMapper.updateStatusByIdIfActive(record.getId());

            if (record.getJtiAt() != null && !record.getJtiAt().isEmpty()) {
                addToBlacklistPg(record.getJtiAt(), "ACCESS", userId, adminUserId, "USER_DISABLED");
            }
            if (record.getJtiRt() != null && !record.getJtiRt().isEmpty()) {
                addToBlacklistPg(record.getJtiRt(), "REFRESH", userId, adminUserId, "USER_DISABLED");
            }
        }
    }

    /**
     * PG 审计落盘：设备互踢（Redis Lua 成功路径）
     *
     * <p>与 {@link #disableUserPgAudit} 同款逻辑：旧 login_record 置 REVOKED +
     * 旧 jti 双写 PG 黑名单。审计失败仅告警，不影响登录主流程（Redis 执法层已生效）。
     *
     * @param userId 用户 ID（被踢设备的用户）
     * @param result Lua 返回的踢出结果（kicked=true 时调用）
     */
    private void kickPgAudit(Long userId, KickResult result) {
        try {
            // 1. 旧 login_record → REVOKED（条件 status='ACTIVE'，幂等）
            loginRecordMapper.updateStatusByUserAndJtiActive(userId, result.oldJtiAt());

            // 2. 旧 jti 双写 PG 黑名单（addToBlacklistPg 已忽略唯一索引冲突）
            if (!result.oldJtiAt().isEmpty()) {
                addToBlacklistPg(result.oldJtiAt(), "ACCESS", userId, null, "DEVICE_KICKED");
            }
            if (!result.oldJtiRt().isEmpty()) {
                addToBlacklistPg(result.oldJtiRt(), "REFRESH", userId, null, "DEVICE_KICKED");
            }

            log.info("设备互踢 PG 审计落盘: userId={}, oldJtiAt={}", userId, result.oldJtiAt());
        } catch (Exception e) {
            log.warn("设备互踢 PG 审计失败（不影响登录主流程）: userId={}", userId, e);
        }
    }

    /**
     * PG 写入黑名单
     */
    private void addToBlacklistPg(String jti, String tokenType, Long userId, Long blacklistedBy, String reason) {
        addToBlacklistPg(jti, tokenType, userId, blacklistedBy, reason, null);
    }

    private void addToBlacklistPg(
            String jti, String tokenType, Long userId, Long blacklistedBy, String reason, LocalDateTime expiresAt) {
        try {
            SysTokenBlacklist blacklist = new SysTokenBlacklist();
            blacklist.setJti(jti);
            blacklist.setTokenType(tokenType);
            blacklist.setUserId(userId);
            blacklist.setBlacklistedBy(blacklistedBy);
            blacklist.setReason(reason);
            // L-13：未显式传过期时间时按 token 类型取对应有效期（ACCESS=accessTokenExpiry，
            // REFRESH=refreshTokenExpiry）——原实现恒用 refreshTokenExpiry(7d)，AT 黑名单
            // 与 Lua 侧 15min TTL 不对称（无害但不一致）
            blacklist.setExpiresAt(
                    expiresAt != null
                            ? expiresAt
                            : LocalDateTime.now()
                                    .plusSeconds(
                                            "ACCESS".equals(tokenType)
                                                    ? authProperties.accessTokenExpiry()
                                                    : authProperties.refreshTokenExpiry()));
            tokenBlacklistMapper.insert(blacklist);
        } catch (Exception e) {
            // 可能是唯一索引冲突（重复插入），忽略
            log.debug("PG 黑名单写入（可能重复）: jti={}, reason={}", jti, reason);
        }
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    private List<SysLoginRecord> findActiveLoginRecords(Long userId) {
        return loginRecordMapper.selectActiveByUserId(userId);
    }

    private KickResult parseKickResult(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return new KickResult(
                    node.get("kicked").asBoolean(),
                    node.has("old_jti_at") ? node.get("old_jti_at").asText() : "",
                    node.has("old_jti_rt") ? node.get("old_jti_rt").asText() : "");
        } catch (Exception e) {
            log.warn("解析 kick_and_login 结果失败: {}", json, e);
            return new KickResult(false, "", "");
        }
    }

    private int parseDisableCount(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.has("disabled_jti_count")
                    ? node.get("disabled_jti_count").asInt()
                    : 0;
        } catch (Exception e) {
            log.warn("解析 disable_user 结果失败: {}", json, e);
            return 0;
        }
    }

    /**
     * 设备互踢结果
     */
    public record KickResult(boolean kicked, String oldJtiAt, String oldJtiRt) {}
}
