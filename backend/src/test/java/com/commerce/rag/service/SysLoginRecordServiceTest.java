package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.convert.AdminLoginRecordConverter;
import com.commerce.rag.convert.AdminLoginRecordConverterImpl;
import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.entity.SysTokenBlacklist;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.mapper.SysLoginRecordMapper;
import com.commerce.rag.mapper.SysTokenBlacklistMapper;
import com.commerce.rag.service.impl.SysLoginRecordServiceImpl;
import com.commerce.rag.test.MybatisPlusTestHelper;
import com.commerce.rag.vo.SysLoginRecordVO;
import com.commerce.rag.vo.SysTokenBlacklistVO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * ISysLoginRecordService 单元测试 —— 登录记录 + Token 黑名单管理（K1-K7）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ISysLoginRecordService 登录记录与黑名单测试")
class SysLoginRecordServiceTest {

    @BeforeAll
    static void initMybatisPlus() {
        MybatisPlusTestHelper.initTableInfo();
    }

    @Mock
    private SysLoginRecordMapper loginRecordMapper;

    @Mock
    private SysTokenBlacklistMapper tokenBlacklistMapper;

    @Mock
    private DeviceKickService deviceKickService;

    /** 转换器用真实实现（MapStruct 生成类），转换行为由 AdminLoginRecordConverterTest 单独覆盖 */
    @Spy
    private AdminLoginRecordConverter converter = new AdminLoginRecordConverterImpl();

    @InjectMocks
    private SysLoginRecordServiceImpl service;

    private SysLoginRecord activeRecord(Long id) {
        SysLoginRecord r = new SysLoginRecord();
        r.setId(id);
        r.setUserId(5L);
        r.setJtiAt("jti-at-1");
        r.setJtiRt("jti-rt-1");
        r.setStatus("ACTIVE");
        r.setExpiresAt(LocalDateTime.now().plusDays(1));
        return r;
    }

    private SysTokenBlacklist blacklistRecord(Long id) {
        SysTokenBlacklist b = new SysTokenBlacklist();
        b.setId(id);
        b.setJti("jti-1");
        b.setTokenType("ACCESS");
        b.setUserId(5L);
        b.setBlacklistedBy(1L);
        b.setReason("MANUAL_REVOKE");
        b.setExpiresAt(LocalDateTime.now().plusDays(7));
        return b;
    }

    // ==================== 登录记录 K1-K3 ====================

    @Test
    @DisplayName("findPage → 全条件筛选分页查询并转换为 VO 分页")
    void findPage_withAllFilters() {
        Page<SysLoginRecord> page = new Page<>(1, 20);
        page.setRecords(List.of(activeRecord(1L)));
        page.setTotal(1);
        when(loginRecordMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<SysLoginRecordVO> result = service.findPage(1, 20, 5L, "PC", "ACTIVE");

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals(5L, result.getRecords().get(0).userId());
        assertEquals("jti-at-1", result.getRecords().get(0).jtiAt());
        verify(loginRecordMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("findPage → 无筛选条件时同样分页查询")
    void findPage_noFilters() {
        Page<SysLoginRecord> page = new Page<>(1, 20);
        page.setRecords(List.of(activeRecord(1L)));
        when(loginRecordMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<SysLoginRecordVO> result = service.findPage(1, 20, null, null, null);

        assertEquals(1, result.getRecords().size());
        verify(loginRecordMapper).selectPage(any(Page.class), any());
    }

    @Test
    @DisplayName("findById → 记录存在返回详情")
    void findById_exists_returnsRecord() {
        when(loginRecordMapper.selectById(1L)).thenReturn(activeRecord(1L));

        SysLoginRecord result = service.findById(1L);

        assertEquals(5L, result.getUserId());
    }

    @Test
    @DisplayName("findById → 记录不存在抛 404")
    void findById_missing_throws404() {
        when(loginRecordMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.findById(99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
    }

    @Test
    @DisplayName("revoke → 活跃记录标记 REVOKED 且 jtiAt/jtiRt 双入黑名单")
    void revoke_activeRecord_revokesAndBlacklists() {
        SysLoginRecord record = activeRecord(1L);
        when(loginRecordMapper.selectById(1L)).thenReturn(record);

        service.revoke(1L, 9L);

        verify(loginRecordMapper).update(isNull(), any());
        verify(deviceKickService).addToBlacklist("jti-at-1", "ACCESS", 5L, 9L, "MANUAL_REVOKE", record.getExpiresAt());
        verify(deviceKickService).addToBlacklist("jti-rt-1", "REFRESH", 5L, 9L, "MANUAL_REVOKE", record.getExpiresAt());
    }

    @Test
    @DisplayName("revoke → 记录不存在抛 404")
    void revoke_missing_throws404() {
        when(loginRecordMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.revoke(99L, 9L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
    }

    @Test
    @DisplayName("revoke → 非活跃记录抛 400")
    void revoke_notActive_throws400() {
        SysLoginRecord revoked = activeRecord(1L);
        revoked.setStatus("REVOKED");
        when(loginRecordMapper.selectById(1L)).thenReturn(revoked);

        BizException ex = assertThrows(BizException.class, () -> service.revoke(1L, 9L));

        assertEquals(HttpStatus.BAD_REQUEST.value(), ex.getCode());
        verify(deviceKickService, never()).addToBlacklist(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("cleanupExpired → 返回过期标记数量")
    void cleanupExpired_returnsCount() {
        when(loginRecordMapper.update(isNull(), any())).thenReturn(3);

        int count = service.cleanupExpired();

        assertEquals(3, count);
    }

    // ==================== Token 黑名单 K4-K7 ====================

    @Test
    @DisplayName("findBlacklistPage → 全条件筛选分页查询并转换为 VO 分页")
    void findBlacklistPage_withAllFilters() {
        Page<SysTokenBlacklist> page = new Page<>(1, 20);
        page.setRecords(List.of(blacklistRecord(1L)));
        page.setTotal(1);
        when(tokenBlacklistMapper.selectPage(any(Page.class), any())).thenReturn(page);

        IPage<SysTokenBlacklistVO> result = service.findBlacklistPage(1, 20, 5L, "jti-1", "ACCESS");

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("jti-1", result.getRecords().get(0).jti());
        assertEquals(5L, result.getRecords().get(0).userId());
    }

    @Test
    @DisplayName("addToBlacklist → 委托 DeviceKickService 入黑名单")
    void addToBlacklist_delegatesToDeviceKick() {
        LocalDateTime expires = LocalDateTime.now().plusDays(7);

        service.addToBlacklist("jti-1", "ACCESS", 5L, 9L, "MANUAL_REVOKE", expires);

        verify(deviceKickService).addToBlacklist("jti-1", "ACCESS", 5L, 9L, "MANUAL_REVOKE", expires);
    }

    @Test
    @DisplayName("deleteFromBlacklist → 记录存在时软删")
    void deleteFromBlacklist_exists_softDeletes() {
        SysTokenBlacklist record = new SysTokenBlacklist();
        record.setId(1L);
        record.setJti("jti-1");
        when(tokenBlacklistMapper.selectById(1L)).thenReturn(record);

        service.deleteFromBlacklist(1L);

        verify(tokenBlacklistMapper).update(isNull(), any());
    }

    @Test
    @DisplayName("deleteFromBlacklist → 记录不存在抛 404")
    void deleteFromBlacklist_missing_throws404() {
        when(tokenBlacklistMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.deleteFromBlacklist(99L));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getCode());
    }

    @Test
    @DisplayName("cleanupExpiredBlacklist → 单条 UPDATE 软删全部过期记录并返回行数（M-6）")
    void cleanupExpiredBlacklist_softDeletesEach() {
        // M-6: 单条 UPDATE 替代「先全表查回 + 循环逐条 UPDATE」（与 cleanupExpired 同范式）
        when(tokenBlacklistMapper.update(isNull(), any())).thenReturn(2);

        int count = service.cleanupExpiredBlacklist();

        assertEquals(2, count);
        verify(tokenBlacklistMapper, never()).selectList(any());
        verify(tokenBlacklistMapper, times(1)).update(isNull(), any());
    }

    @Test
    @DisplayName("cleanupExpiredBlacklist → 无过期记录时返回 0")
    void cleanupExpiredBlacklist_none_returnsZero() {
        when(tokenBlacklistMapper.update(isNull(), any())).thenReturn(0);

        int count = service.cleanupExpiredBlacklist();

        assertEquals(0, count);
        verify(tokenBlacklistMapper, never()).selectList(any());
    }
}
