package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.vo.SysLoginRecordVO;
import com.commerce.rag.vo.SysTokenBlacklistVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AdminLoginRecordConverter 转换器测试 —— 登录记录/黑名单实体 → 视图对象字段映射 */
@DisplayName("AdminLoginRecordConverter 转换器测试")
class AdminLoginRecordConverterTest {

    private final AdminLoginRecordConverter converter = new AdminLoginRecordConverterImpl();

    @Test
    @DisplayName("登录记录实体完整映射到视图对象（剔除 deleted）")
    void toLoginRecordVO_mapsAllFields() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 20, 12, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 15, 9, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 8, 15, 9, 30);
        SysLoginRecord record = new SysLoginRecord();
        record.setId(1L);
        record.setUserId(5L);
        record.setJtiAt("jti-at-1");
        record.setJtiRt("jti-rt-1");
        record.setDeviceType("PC");
        record.setDeviceInfo("Chrome");
        record.setIpAddress("127.0.0.1");
        record.setExpiresAt(expiresAt);
        record.setStatus("ACTIVE");
        record.setDeleted(1L);
        record.setCreatedAt(createdAt);
        record.setUpdatedAt(updatedAt);

        SysLoginRecordVO vo = converter.toLoginRecordVO(record);

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.userId()).isEqualTo(5L);
        assertThat(vo.jtiAt()).isEqualTo("jti-at-1");
        assertThat(vo.jtiRt()).isEqualTo("jti-rt-1");
        assertThat(vo.deviceType()).isEqualTo("PC");
        assertThat(vo.deviceInfo()).isEqualTo("Chrome");
        assertThat(vo.ipAddress()).isEqualTo("127.0.0.1");
        assertThat(vo.expiresAt()).isEqualTo(expiresAt);
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.createdAt()).isEqualTo(createdAt);
        assertThat(vo.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("黑名单实体完整映射到视图对象（剔除 deleted）")
    void toBlacklistVO_mapsAllFields() {
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 22, 12, 0);
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 15, 9, 0);
        SysTokenBlacklist record = new SysTokenBlacklist();
        record.setId(2L);
        record.setJti("jti-1");
        record.setTokenType("ACCESS");
        record.setUserId(5L);
        record.setBlacklistedBy(1L);
        record.setReason("MANUAL_REVOKE");
        record.setExpiresAt(expiresAt);
        record.setCreatedAt(createdAt);

        SysTokenBlacklistVO vo = converter.toBlacklistVO(record);

        assertThat(vo.id()).isEqualTo(2L);
        assertThat(vo.jti()).isEqualTo("jti-1");
        assertThat(vo.tokenType()).isEqualTo("ACCESS");
        assertThat(vo.userId()).isEqualTo(5L);
        assertThat(vo.blacklistedBy()).isEqualTo(1L);
        assertThat(vo.reason()).isEqualTo("MANUAL_REVOKE");
        assertThat(vo.expiresAt()).isEqualTo(expiresAt);
        assertThat(vo.createdAt()).isEqualTo(createdAt);
        // record 值语义（equals/hashCode/toString 由 record 生成，供 JSON 序列化链路使用）
        assertThat(vo)
                .isEqualTo(
                        new SysTokenBlacklistVO(2L, "jti-1", "ACCESS", 5L, 1L, "MANUAL_REVOKE", expiresAt, createdAt));
        assertThat(vo.hashCode())
                .isEqualTo(new SysTokenBlacklistVO(2L, "jti-1", "ACCESS", 5L, 1L, "MANUAL_REVOKE", expiresAt, createdAt)
                        .hashCode());
        assertThat(vo.toString()).contains("jti-1");
    }
}
