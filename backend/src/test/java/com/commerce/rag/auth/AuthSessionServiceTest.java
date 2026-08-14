package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.config.AuthProperties;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.test.MybatisPlusTestHelper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AuthSessionService 单元测试 —— 登录记录编排（创建/刷新更新/登出吊销）
 *
 * <p>覆盖 createLoginRecord 字段正确性、updateLoginRecordOnRefresh 降级、
 * revokeOnLogout 三场景（ACTIVE record 双入黑名单+REVOKED / 无 record 仅 AT / mapper 异常不抛出）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthSessionService 登录会话编排测试")
class AuthSessionServiceTest {

    /** 初始化 MyBatis-Plus TableInfo 缓存（LambdaUpdateWrapper 列名解析依赖，项目测试惯例） */
    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private SysLoginRecordMapper loginRecordMapper;

    @Mock
    private DeviceKickService deviceKickService;

    private AuthSessionService authSessionService;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties(
                "test-secret-key-must-be-at-least-256-bits-long-for-hs256!!",
                900,
                604800L,
                "commerce_token",
                "localhost",
                List.of("WEB_DESKTOP"));
        authSessionService = new AuthSessionService(loginRecordMapper, deviceKickService, props);
    }

    @Test
    @DisplayName("createLoginRecord → 构建 ACTIVE 记录并 insert，字段完整正确")
    void createLoginRecord_buildsActiveRecordAndInserts() {
        SysLoginRecord result = authSessionService.createLoginRecord(
                123L, "jti-at", "jti-rt", "WEB_DESKTOP", "test-agent", "127.0.0.1");

        ArgumentCaptor<SysLoginRecord> captor = ArgumentCaptor.forClass(SysLoginRecord.class);
        verify(loginRecordMapper).insert(captor.capture());
        SysLoginRecord record = captor.getValue();
        assertEquals(123L, record.getUserId());
        assertEquals("jti-at", record.getJtiAt());
        assertEquals("jti-rt", record.getJtiRt());
        assertEquals("WEB_DESKTOP", record.getDeviceType());
        assertEquals("test-agent", record.getDeviceInfo());
        assertEquals("127.0.0.1", record.getIpAddress());
        assertEquals("ACTIVE", record.getStatus());
        assertNotNull(record.getExpiresAt());
        // expiresAt = now + RT 有效期（7d），允许秒级误差
        assertTrue(record.getExpiresAt().isAfter(LocalDateTime.now().plusSeconds(604700)));
        assertSame(record, result);
    }

    @Test
    @DisplayName("updateLoginRecordOnRefresh → 调用 mapper.update 更新 jti_at/jti_rt")
    void updateLoginRecordOnRefresh_updatesLoginRecord() {
        authSessionService.updateLoginRecordOnRefresh(123L, "old-jti-rt", "new-jti-at", "new-jti-rt");

        verify(loginRecordMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("updateLoginRecordOnRefresh → mapper 异常被吞掉，不向外抛出")
    void updateLoginRecordOnRefresh_updateFailure_doesNotThrow() {
        when(loginRecordMapper.update(isNull(), any())).thenThrow(new RuntimeException("DB 故障"));

        assertDoesNotThrow(
                () -> authSessionService.updateLoginRecordOnRefresh(123L, "old-jti-rt", "new-jti-at", "new-jti-rt"));
    }

    @Test
    @DisplayName("revokeOnLogout → ACTIVE record 存在：AT+RT 双入黑名单 + login_record 置 REVOKED")
    void revokeOnLogout_withActiveRecord_blacklistsAtAndRtAndRevokes() {
        LocalDateTime rtExpiresAt = LocalDateTime.now().plusDays(7);
        SysLoginRecord record = new SysLoginRecord();
        record.setId(10L);
        record.setUserId(123L);
        record.setJtiAt("jti-at");
        record.setJtiRt("jti-rt");
        record.setStatus("ACTIVE");
        record.setExpiresAt(rtExpiresAt);
        when(loginRecordMapper.selectOne(any())).thenReturn(record);

        authSessionService.revokeOnLogout(123L, "jti-at");

        // AT 吊销
        verify(deviceKickService)
                .addToBlacklist(
                        eq("jti-at"), eq("ACCESS"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), any(LocalDateTime.class));
        // RT 吊销（TTL 取 login_record 真实过期时间）
        verify(deviceKickService)
                .addToBlacklist(eq("jti-rt"), eq("REFRESH"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), eq(rtExpiresAt));
        // login_record → REVOKED
        verify(loginRecordMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("revokeOnLogout → 无 ACTIVE record：仅 AT 入黑名单，RT 不入")
    void revokeOnLogout_noRecord_onlyBlacklistsAt() {
        when(loginRecordMapper.selectOne(any())).thenReturn(null);

        authSessionService.revokeOnLogout(123L, "jti-at");

        // 黑名单调用次数恰好 1 次（仅 AT）
        verify(deviceKickService, times(1))
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), any());
        // login_record 置 REVOKED 的 update 仍执行（幂等降级语义）
        verify(loginRecordMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("revokeOnLogout → login_record update 异常被吞掉，不向外抛出")
    void revokeOnLogout_updateFailure_doesNotThrow() {
        when(loginRecordMapper.selectOne(any())).thenReturn(null);
        when(loginRecordMapper.update(isNull(), any())).thenThrow(new RuntimeException("DB 故障"));

        assertDoesNotThrow(() -> authSessionService.revokeOnLogout(123L, "jti-at"));

        // 黑名单（AT）仍正常执行
        verify(deviceKickService)
                .addToBlacklist(
                        eq("jti-at"), eq("ACCESS"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), any(LocalDateTime.class));
    }
}
