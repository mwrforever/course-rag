package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
 * <p>覆盖 createLoginRecord 字段正确性与主键返回、updateLoginRecordOnRefresh 降级与 expires_at 滑动、
 * revokeOnLogout 场景（ACTIVE record 双入黑名单+REVOKED / RT 黑名单 TTL 上限兜底 / 无 record 仅 AT /
 * mapper 异常不抛出）。
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
                false,
                List.of("WEB_DESKTOP"));
        authSessionService = new AuthSessionService(loginRecordMapper, deviceKickService, props);
    }

    @Test
    @DisplayName("createLoginRecord → 构建 ACTIVE 记录并 insert，返回主键")
    void createLoginRecord_buildsActiveRecordAndInserts() {
        // mock insert 回填主键（模拟 MyBatis-Plus IdType.ASSIGN_ID 行为）
        doAnswer(inv -> {
                    SysLoginRecord rec = inv.getArgument(0);
                    rec.setId(10L);
                    return 1;
                })
                .when(loginRecordMapper)
                .insert(any(SysLoginRecord.class));

        Long result = authSessionService.createLoginRecord(
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
        // 返回值为登录记录主键（Entity 不出 service 边界）
        assertEquals(10L, result);
        assertEquals(10L, record.getId());
    }

    @Test
    @DisplayName("updateLoginRecordOnRefresh → 更新 jti_at/jti_rt/expires_at（RT 旋转后滑动过期时间）")
    void updateLoginRecordOnRefresh_updatesLoginRecord() {
        authSessionService.updateLoginRecordOnRefresh(123L, "old-jti-rt", "new-jti-at", "new-jti-rt");

        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(loginRecordMapper).update(isNull(), captor.capture());
        String setSql = captor.getValue().getSqlSet();
        assertTrue(setSql.contains("jti_at"), "SET 应包含 jti_at");
        assertTrue(setSql.contains("jti_rt"), "SET 应包含 jti_rt");
        assertTrue(setSql.contains("expires_at"), "SET 应包含 expires_at（rotation 滑动 RT 有效期）");
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
    @DisplayName("revokeOnLogout → record.expiresAt 晚于 now+7d：RT 黑名单 TTL 取上限兜底")
    void revokeOnLogout_expiresAtBeyondCap_capsRtBlacklistTtl() {
        SysLoginRecord record = new SysLoginRecord();
        record.setId(11L);
        record.setUserId(123L);
        record.setJtiAt("jti-at");
        record.setJtiRt("jti-rt");
        record.setStatus("ACTIVE");
        // 构造晚于 now+7d 的过期时间（模拟历史脏数据），兜底应截断到 now+7d
        record.setExpiresAt(LocalDateTime.now().plusDays(30));
        when(loginRecordMapper.selectOne(any())).thenReturn(record);

        authSessionService.revokeOnLogout(123L, "jti-at");

        ArgumentCaptor<LocalDateTime> expiresCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deviceKickService, times(2))
                .addToBlacklist(anyString(), anyString(), anyLong(), anyLong(), anyString(), expiresCaptor.capture());
        // 第 2 次调用为 RT 黑名单，TTL 应被截断到 now+7d 上限（而非 30d）
        LocalDateTime rtBlacklistExpiry = expiresCaptor.getAllValues().get(1);
        LocalDateTime cap = LocalDateTime.now().plusSeconds(604800L);
        assertTrue(rtBlacklistExpiry.isAfter(cap.minusSeconds(5)), "RT 黑名单 TTL 应接近 now+7d 上限");
        assertTrue(rtBlacklistExpiry.isBefore(cap.plusSeconds(5)), "RT 黑名单 TTL 不应晚于 now+7d 上限");
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

    @Test
    @DisplayName("P0-3 revokeOnLogout → 按 userId+ACTIVE 定位（不按 jti_at）——旧 AT 登出时新 RT 仍被吊销")
    void revokeOnLogout_locateByUserAndActive_notByJtiAt() {
        // 模拟 refresh 旋转后的场景：login_record.jti_at 已是新值，登出携带旧 AT 的 jti
        LocalDateTime rtExpiresAt = LocalDateTime.now().plusDays(7);
        SysLoginRecord record = new SysLoginRecord();
        record.setId(10L);
        record.setUserId(123L);
        record.setJtiAt("new-jti-at"); // 旋转后的新 jti_at，与登出携带的旧 jti 不同
        record.setJtiRt("rotated-jti-rt");
        record.setStatus("ACTIVE");
        record.setExpiresAt(rtExpiresAt);
        when(loginRecordMapper.selectOne(any())).thenReturn(record);

        authSessionService.revokeOnLogout(123L, "old-jti-at");

        // 定位条件必须按 userId + ACTIVE（而非 jti_at），保证旋转后的 RT 入黑名单
        ArgumentCaptor<LambdaQueryWrapper> queryCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(loginRecordMapper).selectOne(queryCaptor.capture());
        String sqlSegment = queryCaptor.getValue().getCustomSqlSegment();
        assertFalse(sqlSegment.contains("jti_at"), "定位条件不得包含 jti_at（可变字段）");
        assertTrue(sqlSegment.contains("user_id"), "定位条件应包含 user_id");
        // MP wrapper 参数化：条件值在 paramNameValuePairs 中（列名在 SQL 段）
        assertTrue(queryCaptor.getValue().getParamNameValuePairs().containsValue("ACTIVE"), "定位条件应包含 status=ACTIVE");
        // 旋转后的 RT 必须被吊销（旧 AT 登出攻击场景：攻击者新 RT 不可续命）
        verify(deviceKickService)
                .addToBlacklist(eq("rotated-jti-rt"), eq("REFRESH"), eq(123L), eq(123L), eq("MANUAL_REVOKE"), any());
        // login_record 置 REVOKED 同样按 userId+ACTIVE（不按 jti_at）
        ArgumentCaptor<LambdaUpdateWrapper> updateCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(loginRecordMapper).update(isNull(), updateCaptor.capture());
        String updateSql = updateCaptor.getValue().getSqlSet();
        assertTrue(updateSql.contains("status"), "REVOKED 更新应包含 status");
    }
}
