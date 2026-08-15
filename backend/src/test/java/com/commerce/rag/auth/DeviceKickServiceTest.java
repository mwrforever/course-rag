package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;

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

    @Mock
    private JdbcTemplate jdbcTemplate;

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
                List.of("WEB_DESKTOP"));
        service = new DeviceKickService(
                redisTemplate, tokenService, authProperties, loginRecordMapper, tokenBlacklistMapper, jdbcTemplate);
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
        // 旧 login_record 置 REVOKED
        verify(jdbcTemplate).update(contains("sys_login_record SET status = 'REVOKED'"), eq(1L), eq("old-at"));
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
        verifyNoInteractions(jdbcTemplate);
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
        doThrow(new RuntimeException("DB 故障")).when(jdbcTemplate).update(anyString(), any(), any());

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
}
