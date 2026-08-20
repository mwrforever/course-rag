package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.entity.UserPreference;
import com.commerce.rag.properties.MemoryProperties;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 偏好缓存测试 —— 同一 userId 二次取：DB 只查一次（30min 冻结，spec §7.8） */
class PreferenceCacheServiceTest {

    @Test
    @DisplayName("getOrBuild — Caffeine 命中后 IPreferenceService 只查一次")
    void getOrBuild_cachesBlock() {
        IPreferenceService prefService = mock(IPreferenceService.class);
        UserPreference row = new UserPreference();
        row.setKey("response_language");
        row.setValue("中文");
        row.setStatus("active");
        row.setWriteScore(BigDecimal.valueOf(0.9));
        when(prefService.findActiveForInjection(7L)).thenReturn(List.of(row));

        PreferenceCacheService cache = new PreferenceCacheService(
                new MemoryProperties(), prefService, new PreferenceBlockService(new MemoryProperties()));
        String first = cache.getOrBuild(7L);
        String second = cache.getOrBuild(7L);

        assertEquals(first, second);
        verify(prefService, times(1)).findActiveForInjection(7L);
    }

    @Test
    @DisplayName("getOrBuild — 无 active 偏好返回空串并同样缓存（避免每轮查 DB）")
    void getOrBuild_emptyBlockCached() {
        IPreferenceService prefService = mock(IPreferenceService.class);
        when(prefService.findActiveForInjection(8L)).thenReturn(List.of());
        PreferenceCacheService cache = new PreferenceCacheService(
                new MemoryProperties(), prefService, new PreferenceBlockService(new MemoryProperties()));
        assertEquals("", cache.getOrBuild(8L));
        assertEquals("", cache.getOrBuild(8L));
        verify(prefService, times(1)).findActiveForInjection(8L);
    }
}
