package com.commerce.rag.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.properties.AuthBlacklistProperties;
import com.commerce.rag.service.ISysLoginRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * TokenBlacklistCleanupScheduler 单元测试 —— 过期黑名单自动清理调用链（B1-4）
 *
 * <p>覆盖三层：
 * <ul>
 *   <li>清理动作委托 ISysLoginRecordService.cleanupExpiredBlacklist（分层约束：不直调 mapper）</li>
 *   <li>service 异常被吞掉（定时巡检不中断，下一轮自动重试）</li>
 *   <li>生命周期：start() 按配置间隔真实调度触发清理，stop() 优雅关闭</li>
 * </ul>
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistCleanupScheduler 黑名单定时清理测试")
class TokenBlacklistCleanupSchedulerTest {

    @Mock
    private ISysLoginRecordService sysLoginRecordService;

    private TokenBlacklistCleanupScheduler scheduler;

    @AfterEach
    void tearDown() {
        // 兜底关闭调度器（生命周期用例外，其余用例未 start 时为幂等空操作）
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    @DisplayName("cleanupExpiredBlacklist → 委托 service 清理过期黑名单行（不直调 mapper）")
    void cleanupExpiredBlacklist_delegatesToService() {
        scheduler = new TokenBlacklistCleanupScheduler(sysLoginRecordService, new AuthBlacklistProperties(3600));
        when(sysLoginRecordService.cleanupExpiredBlacklist()).thenReturn(3);

        scheduler.cleanupExpiredBlacklist();

        verify(sysLoginRecordService).cleanupExpiredBlacklist();
    }

    @Test
    @DisplayName("cleanupExpiredBlacklist → service 异常被吞掉，不中断定时巡检（下一轮重试）")
    void cleanupExpiredBlacklist_serviceFailure_doesNotThrow() {
        scheduler = new TokenBlacklistCleanupScheduler(sysLoginRecordService, new AuthBlacklistProperties(3600));
        when(sysLoginRecordService.cleanupExpiredBlacklist()).thenThrow(new RuntimeException("DB 故障"));

        assertDoesNotThrow(scheduler::cleanupExpiredBlacklist);
    }

    @Test
    @DisplayName("start → 按配置间隔真实调度清理（1s 间隔触发），stop 后停止")
    void start_schedulesCleanupAtConfiguredInterval() {
        // 间隔取最小合法值 1s，缩短生命周期用例耗时；timeout 上限 10s 保证慢 CI 环境稳定
        scheduler = new TokenBlacklistCleanupScheduler(sysLoginRecordService, new AuthBlacklistProperties(1));
        when(sysLoginRecordService.cleanupExpiredBlacklist()).thenReturn(0);

        scheduler.start();

        // 等待首次调度触发（初始延迟=间隔=1s）；触发后关闭调度器不再产生后续清理
        verify(sysLoginRecordService, timeout(10_000L).atLeastOnce()).cleanupExpiredBlacklist();
        scheduler.stop();
    }
}
