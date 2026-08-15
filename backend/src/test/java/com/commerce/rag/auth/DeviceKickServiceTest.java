package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
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
                List.of("WEB_DESKTOP"));
        // self 代理传 null（P0-5）：本测试不触发 Redis 降级路径（fallback 经 self 代理调用），
        // 降级链路的代理注入由 Spring 容器负责，不在本单测直测
        service = new DeviceKickService(
                redisTemplate, tokenService, authProperties, loginRecordMapper, tokenBlacklistMapper, null);
    }

    @Test
    @DisplayName("kickAndLogin → Lua 踢出旧设备后，PG 审计落盘（REVOKED + 双 jti 黑名单）")
    void kickAndLogin_kickedTrue_writesPgAudit() {
        // 旧设备存在（触发 TTL 计算分支）
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("old-at|old-rt|1");
        // Lua 返回：踢出成功
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
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
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
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("old-at|old-rt|1");
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
    @DisplayName("markRefreshTokenUsedAtomic — Redis 异常降级放行并写 PG 黑名单兜底")
    void markRefreshTokenUsedAtomic_redisFail_fallbackOpen() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("redis down"));

        // 降级放行（与原宽松降级语义一致），不抛异常
        assertTrue(service.markRefreshTokenUsedAtomic("jti-1"));
        // addToBlacklistPg 为 private 无法直接 verify，断言其公共可观察行为：PG 黑名单兜底写入
        ArgumentCaptor<SysTokenBlacklist> captor = ArgumentCaptor.forClass(SysTokenBlacklist.class);
        verify(tokenBlacklistMapper).insert(captor.capture());
        SysTokenBlacklist inserted = captor.getValue();
        assertEquals("jti-1", inserted.getJti());
        assertEquals("REFRESH", inserted.getTokenType());
        assertEquals("TOKEN_REUSE", inserted.getReason());
        assertNull(inserted.getUserId());
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
    @DisplayName("kickAndLoginPgFallback — 行锁查到旧记录 → REVOKED + 双 jti 黑名单")
    void kickAndLoginPgFallback_kicksOldDevice() {
        SysLoginRecord old = new SysLoginRecord();
        old.setId(5L);
        old.setJtiAt("old-at");
        old.setJtiRt("old-rt");
        when(loginRecordMapper.selectActiveForUpdate(1L, "WEB_DESKTOP")).thenReturn(List.of(old));

        DeviceKickService.KickResult result =
                service.kickAndLoginPgFallback(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertTrue(result.kicked());
        assertEquals("old-at", result.oldJtiAt());
        assertEquals("old-rt", result.oldJtiRt());
        verify(loginRecordMapper).updateStatusById(5L);
        verify(tokenBlacklistMapper, times(2)).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("kickAndLoginPgFallback — 无旧记录 → kicked=false 不落审计")
    void kickAndLoginPgFallback_noOldRecord_notKicked() {
        when(loginRecordMapper.selectActiveForUpdate(1L, "WEB_DESKTOP")).thenReturn(List.of());

        DeviceKickService.KickResult result =
                service.kickAndLoginPgFallback(1L, "WEB_DESKTOP", "new-at", "new-rt", 99L);

        assertFalse(result.kicked());
        verifyNoInteractions(tokenBlacklistMapper);
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

        service.addToBlacklist("jti-1", "ACCESS", 1L, 100L, "MANUAL_REVOKE", LocalDateTime.now().plusDays(1));

        verify(tokenBlacklistMapper).insert(any(SysTokenBlacklist.class));
    }

    @Test
    @DisplayName("addToBlacklist — TTL 已过期 → 跳过 Redis 写入，仍写 PG")
    void addToBlacklist_expiredTtl_skipsRedisWritesPg() {
        service.addToBlacklist("jti-1", "ACCESS", 1L, 100L, "MANUAL_REVOKE", LocalDateTime.now().minusDays(1));

        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(tokenBlacklistMapper).insert(any(SysTokenBlacklist.class));
    }
}
