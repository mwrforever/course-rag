package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import com.commerce.rag.properties.AuthProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * DeviceKickService 单元测试 —— 设备互踢 Redis 成功路径的 PG 审计落盘
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceKickService 设备互踢审计测试")
class DeviceKickServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private TokenService tokenService;

    @Mock
    private SysLoginRecordMapper loginRecordMapper;

    @Mock
    private SysTokenBlacklistMapper tokenBlacklistMapper;

    private AuthProperties authProperties;
    private DeviceKickService service;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!",
                900,
                604800L,
                "commerce_token",
                "localhost",
                false,
                List.of("WEB_DESKTOP"),
                false);
        // self 代理传 null（P0-5）：本测试不触发 Redis 降级路径（fallback 经 self 代理调用），
        // 降级链路的代理注入由 Spring 容器负责，不在本单测直测
        service = new DeviceKickService(
                redisTemplate, tokenService, authProperties, loginRecordMapper, tokenBlacklistMapper, null);
    }

    @Test
    @DisplayName("kickAndLogin → Lua 踢出旧设备后，PG 审计落盘（REVOKED + 双 jti 黑名单）")
    void kickAndLogin_kickedTrue_writesPgAudit() {
        // H-2：黑名单 TTL 固定传全量有效期（无 Java 预读），旧设备检测原子化于 Lua
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":true,\"old_jti_at\":\"old-at\",\"old_jti_rt\":\"old-rt\"}");

        DeviceKickService.KickResult result = service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertTrue(result.kicked());
        // 旧 login_record 置 REVOKED（updateStatusByUserAndJtiActive: user_id + jti_at + ACTIVE）
        verify(loginRecordMapper).updateStatusByUserAndJtiActive(1L, "old-at");
        // 旧 AT/RT 双写 PG 黑名单：捕获两次 insert 的实体，断言审计载荷完整（jti/类型/原因/操作人，非仅次数）
        ArgumentCaptor<SysTokenBlacklist> captor = ArgumentCaptor.forClass(SysTokenBlacklist.class);
        verify(tokenBlacklistMapper, times(2)).insert(captor.capture());
        List<SysTokenBlacklist> inserted = captor.getAllValues();
        assertTrue(inserted.stream()
                .anyMatch(b -> "old-at".equals(b.getJti())
                        && "ACCESS".equals(b.getTokenType())
                        && "DEVICE_KICKED".equals(b.getReason())
                        && Long.valueOf(1L).equals(b.getUserId())
                        && b.getBlacklistedBy() == null));
        assertTrue(inserted.stream()
                .anyMatch(b -> "old-rt".equals(b.getJti())
                        && "REFRESH".equals(b.getTokenType())
                        && "DEVICE_KICKED".equals(b.getReason())
                        && Long.valueOf(1L).equals(b.getUserId())
                        && b.getBlacklistedBy() == null));
    }

    @Test
    @DisplayName("kickAndLogin → 无旧设备（kicked=false），不做 PG 审计")
    void kickAndLogin_notKicked_noPgAudit() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":false,\"old_jti_at\":\"\",\"old_jti_rt\":\"\"}");

        DeviceKickService.KickResult result = service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertFalse(result.kicked());
        verifyNoInteractions(loginRecordMapper);
        verifyNoInteractions(tokenBlacklistMapper);
    }

    @Test
    @DisplayName("kickAndLogin → PG 审计异常不影响登录主流程（返回正常结果）")
    void kickAndLogin_pgAuditFailure_stillReturnsResult() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"kicked\":true,\"old_jti_at\":\"old-at\",\"old_jti_rt\":\"old-rt\"}");
        // PG 审计落盘时 DB 故障
        doThrow(new RuntimeException("DB 故障"))
                .when(loginRecordMapper)
                .updateStatusByUserAndJtiActive(anyLong(), anyString());

        DeviceKickService.KickResult result =
                assertDoesNotThrow(() -> service.kickAndLogin(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L));

        assertTrue(result.kicked());
    }

    // ==================== markRefreshTokenUsedAtomic（P3 A11 原子标记）测试 ====================

    @Test
    @DisplayName("markRefreshTokenUsedAtomic — Lua 返回 1 → 首次使用抢占成功（true）")
    void markRefreshTokenUsedAtomic_firstUse_returnsTrue() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(service.markRefreshTokenUsedAtomic("jti-1"));
    }

    @Test
    @DisplayName("markRefreshTokenUsedAtomic — Lua 返回 0 → 已被使用（false）")
    void markRefreshTokenUsedAtomic_reuse_returnsFalse() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(0L);

        assertFalse(service.markRefreshTokenUsedAtomic("jti-1"));
    }

    @Test
    @DisplayName("markRefreshTokenUsedAtomic — Redis 异常降级放行（fail-open），不写 PG 黑名单（BUG-1 回归保护）")
    void markRefreshTokenUsedAtomic_redisFail_failOpenWithoutPgWrite() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        // 降级放行（恢复 179881e^ 旧 fail-open 语义），不抛异常
        assertTrue(service.markRefreshTokenUsedAtomic("jti-1"));
        // 关键断言：降级分支不得写 PG 黑名单——若写入，AuthController 随后 isBlacklisted
        // 降级查 PG 会命中本方法刚写入的 TOKEN_REUSE 行，Redis 故障期间每次 refresh 必 401
        // 自拦截，且该行无清理任务导致 RT 被永久烧毁（BUG-1）
        verify(tokenBlacklistMapper, never()).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("markRefreshTokenUsedAtomic — 空 jtiRt → false 不触 Redis")
    void markRefreshTokenUsedAtomic_blankJti_returnsFalse() {
        assertFalse(service.markRefreshTokenUsedAtomic(null));
        assertFalse(service.markRefreshTokenUsedAtomic(""));
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    // ==================== isBlacklisted 黑名单查询（Redis + PG 降级） ====================

    @Test
    @DisplayName("isBlacklisted — Redis 命中 → true，不查 PG")
    void isBlacklisted_redisHit_returnsTrue() {
        when(redisTemplate.hasKey("auth:bl:jti-1")).thenReturn(true);

        assertTrue(service.isBlacklisted("jti-1"));
        verify(tokenBlacklistMapper, never()).countByJti(anyString());
    }

    @Test
    @DisplayName("isBlacklisted — Redis 未命中 → 降级查 PG 命中")
    void isBlacklisted_redisMiss_fallsBackToPg() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(tokenBlacklistMapper.countByJti("jti-1")).thenReturn(1L);

        assertTrue(service.isBlacklisted("jti-1"));
    }

    @Test
    @DisplayName("isBlacklisted — Redis 异常 → 降级查 PG；PG 也异常 → false 兜底")
    void isBlacklisted_redisAndPgFail_returnsFalse() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));
        when(tokenBlacklistMapper.countByJti(anyString())).thenThrow(new RuntimeException("pg down"));

        assertFalse(service.isBlacklisted("jti-1"));
    }

    @Test
    @DisplayName("isBlacklisted — 空 jti → false")
    void isBlacklisted_blankJti_returnsFalse() {
        assertFalse(service.isBlacklisted(null));
        assertFalse(service.isBlacklisted(""));
    }

    // ==================== disableUser 禁用用户 ====================

    @Test
    @DisplayName("disableUser — 无活跃 session → 返回 0 不执行 Lua")
    void disableUser_noActiveRecords_returnsZero() {
        when(loginRecordMapper.selectActiveByUserId(1L)).thenReturn(List.of());

        assertEquals(0, service.disableUser(1L, 100L));
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    @DisplayName("disableUser — Redis 正常 → PG 审计落盘并返回禁用 jti 数")
    void disableUser_redisOk_returnsDisabledCount() {
        SysLoginRecord record = new SysLoginRecord();
        record.setId(1L);
        record.setJtiAt("at-1");
        record.setJtiRt("rt-1");
        when(loginRecordMapper.selectActiveByUserId(1L)).thenReturn(List.of(record));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenReturn("{\"disabled_jti_count\":2}");

        int count = service.disableUser(1L, 100L);

        assertEquals(2, count);
        // PG 审计：记录置 REVOKED（仅 ACTIVE 幂等）+ 双 jti 黑名单
        verify(loginRecordMapper).updateStatusByIdIfActive(1L);
        verify(tokenBlacklistMapper, times(2)).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("disableUser — Redis 异常 → 经 self 代理降级 PG 事务（P0-5）")
    void disableUser_redisFail_fallsBackToPg() {
        DeviceKickService mockSelf = mock(DeviceKickService.class);
        DeviceKickService svc = new DeviceKickService(
                redisTemplate, tokenService, authProperties, loginRecordMapper, tokenBlacklistMapper, mockSelf);
        SysLoginRecord record = new SysLoginRecord();
        record.setId(1L);
        record.setJtiAt("at-1");
        record.setJtiRt("rt-1");
        when(loginRecordMapper.selectActiveByUserId(1L)).thenReturn(List.of(record));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));
        when(mockSelf.disableUserPgFallback(eq(1L), eq(100L), anyList())).thenReturn(2);

        int count = svc.disableUser(1L, 100L);

        assertEquals(2, count);
        verify(mockSelf).disableUserPgFallback(eq(1L), eq(100L), anyList());
    }

    // ==================== PG 降级方法直测 ====================

    @Test
    @DisplayName("kickAndLoginPgFallback — 行锁查到旧记录（已排除新记录）→ REVOKED + 双 jti 黑名单")
    void kickAndLoginPgFallback_kicksOldDevice() {
        SysLoginRecord old = new SysLoginRecord();
        old.setId(5L);
        old.setJtiAt("old-at");
        old.setJtiRt("old-rt");
        // 修复后契约：查询携带 newLoginId 排除条件，仅返回旧记录
        when(loginRecordMapper.selectActiveForUpdate(1L, "WEB_DESKTOP", 99L)).thenReturn(List.of(old));

        DeviceKickService.KickResult result =
                service.kickAndLoginPgFallback(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertTrue(result.kicked());
        assertEquals("old-at", result.oldJtiAt());
        assertEquals("old-rt", result.oldJtiRt());
        // 契约证明：newLoginId 必须传入 mapper（SQL 排除条件的前提，B1-1）
        verify(loginRecordMapper).selectActiveForUpdate(1L, "WEB_DESKTOP", 99L);
        verify(loginRecordMapper).updateStatusById(5L);
        verify(tokenBlacklistMapper, times(2)).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("kickAndLoginPgFallback — 首次登录：结果仅含刚插入的新记录（生产时序）→ 不自吊销（B1-1）")
    void kickAndLoginPgFallback_onlyNewRecord_notKicked() {
        // 生产时序：AuthController 先 createLoginRecord（ACTIVE id=99）后互踢——
        // 降级查询结果即使含新记录（行序不定/脏数据），也不得把新会话误判为旧设备自吊销
        SysLoginRecord newRecord = new SysLoginRecord();
        newRecord.setId(99L);
        newRecord.setJtiAt("new-at");
        newRecord.setJtiRt("new-rt");
        when(loginRecordMapper.selectActiveForUpdate(1L, "WEB_DESKTOP", 99L)).thenReturn(List.of(newRecord));

        DeviceKickService.KickResult result =
                service.kickAndLoginPgFallback(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        // 新记录不得被置 REVOKED、新 jti 不得入黑名单（否则 Redis 故障期间登录即自吊销）
        assertFalse(result.kicked());
        verify(loginRecordMapper, never()).updateStatusById(anyLong());
        verify(tokenBlacklistMapper, never()).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("kickAndLoginPgFallback — 结果混含新+旧记录（行序不定）→ 降级踢除排除当前登录，只踢旧记录（B1-1）")
    void kickAndLoginPgFallback_mixedRecords_kicksOnlyOld() {
        SysLoginRecord newRecord = new SysLoginRecord();
        newRecord.setId(99L);
        newRecord.setJtiAt("new-at");
        newRecord.setJtiRt("new-rt");
        SysLoginRecord old = new SysLoginRecord();
        old.setId(5L);
        old.setJtiAt("old-at");
        old.setJtiRt("old-rt");
        // 新记录排首：模拟无 ORDER BY 时行序不定的最坏情况（原实现 get(0) 恰取到新记录自吊销）
        when(loginRecordMapper.selectActiveForUpdate(1L, "WEB_DESKTOP", 99L)).thenReturn(List.of(newRecord, old));

        DeviceKickService.KickResult result =
                service.kickAndLoginPgFallback(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        // 只踢旧设备：旧记录置 REVOKED、旧 jti 双入黑名单；新记录/新 jti 绝不吊销
        assertTrue(result.kicked());
        assertEquals("old-at", result.oldJtiAt());
        assertEquals("old-rt", result.oldJtiRt());
        verify(loginRecordMapper).updateStatusById(5L);
        verify(loginRecordMapper, never()).updateStatusById(99L);
        ArgumentCaptor<SysTokenBlacklist> captor = ArgumentCaptor.forClass(SysTokenBlacklist.class);
        verify(tokenBlacklistMapper, times(2)).insert(captor.capture());
        assertTrue(
                captor.getAllValues().stream()
                        .noneMatch(b -> "new-at".equals(b.getJti()) || "new-rt".equals(b.getJti())),
                "新会话 jti 不得入黑名单（自吊销）");
    }

    @Test
    @DisplayName("disableUserPgFallback — 逐记录 REVOKED + 非空 jti 双写黑名单并计数")
    void disableUserPgFallback_writesAuditAndCounts() {
        SysLoginRecord full = new SysLoginRecord();
        full.setId(1L);
        full.setJtiAt("at-1");
        full.setJtiRt("rt-1");
        SysLoginRecord emptyJti = new SysLoginRecord();
        emptyJti.setId(2L);
        emptyJti.setJtiAt("");

        int count = service.disableUserPgFallback(1L, 100L, List.of(full, emptyJti));

        // at-1 + rt-1 计 2；空 jti 跳过（不计数不写入）
        assertEquals(2, count);
        verify(loginRecordMapper).updateStatusById(1L);
        verify(loginRecordMapper).updateStatusById(2L);
        verify(tokenBlacklistMapper, times(2)).insert(any(SysTokenBlacklist.class));
    }

    // ==================== addToBlacklist 黑名单写入（Redis + PG 双写） ====================

    @Test
    @DisplayName("addToBlacklist — Redis 写入异常 → 降级仍写 PG 审计")
    void addToBlacklist_redisFail_stillWritesPg() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps)
                .set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        service.addToBlacklist(
                "jti-1",
                "ACCESS",
                1L,
                100L,
                "MANUAL_REVOKE",
                LocalDateTime.now().plusDays(1));

        verify(tokenBlacklistMapper).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("addToBlacklist — TTL 已过期 → 跳过 Redis 写入，仍写 PG")
    void addToBlacklist_expiredTtl_skipsRedisWritesPg() {
        service.addToBlacklist(
                "jti-1",
                "ACCESS",
                1L,
                100L,
                "MANUAL_REVOKE",
                LocalDateTime.now().minusDays(1));

        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(tokenBlacklistMapper).insert(any(SysTokenBlacklist.class));
    }
}
