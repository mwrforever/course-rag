package com.commerce.rag.auth;

import com.commerce.rag.properties.AuthBlacklistProperties;
import com.commerce.rag.service.ISysLoginRecordService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token 黑名单定时清理调度器 —— sys_token_blacklist 过期行自动软删（B1-4）
 *
 * <p>背景：黑名单写入遍布全链路（refresh 旋转 TOKEN_REUSE、互踢 DEVICE_KICKED、
 * 禁用用户 USER_DISABLED、登出 MANUAL_REVOKE），原实现仅有手动清理接口
 * （AdminLoginRecordController → cleanupExpiredBlacklist），无自动任务导致表单调增长；
 * 叠加 countByJti 不过滤 expires_at，过期行永久参与认证降级查询（已同步修复）。
 *
 * <p>调度模式：参照 ChatRequestWorker 的 ACTIVE run 巡检（@PostConstruct 创建单线程
 * 守护 ScheduledExecutorService + scheduleAtFixedRate + @PreDestroy 关闭），
 * 间隔经 {@link AuthBlacklistProperties} 全配置化，禁止硬编码。
 *
 * <p>依赖 {@link ISysLoginRecordService} 执行清理编排（分层约束：不直调 mapper）。
 * 线程安全：调度器单线程执行，无共享可变状态。
 *
 * @author commerce-rag
 */
@Component
public class TokenBlacklistCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistCleanupScheduler.class);

    /** 清理编排经 Service（分层约束：controller/组件不得直调 mapper） */
    private final ISysLoginRecordService sysLoginRecordService;

    /** 清理调度间隔（秒，配置化 auth.blacklist.cleanup-interval-seconds） */
    private final AuthBlacklistProperties blacklistProperties;

    /** 单线程守护调度器（与 ChatRequestWorker.sweepScheduler 同模式） */
    private ScheduledExecutorService scheduler;

    public TokenBlacklistCleanupScheduler(
            ISysLoginRecordService sysLoginRecordService, AuthBlacklistProperties blacklistProperties) {
        this.sysLoginRecordService = sysLoginRecordService;
        this.blacklistProperties = blacklistProperties;
    }

    /**
     * 启动定时清理（初始延迟=间隔，避免应用启动即触发清理与启动期初始化竞争）。
     */
    @PostConstruct
    public void start() {
        long intervalSeconds = blacklistProperties.cleanupIntervalSeconds();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "token-blacklist-cleaner");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::cleanupExpiredBlacklist, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Token 黑名单定时清理已启动: intervalSeconds={}", intervalSeconds);
    }

    /**
     * 优雅关闭调度器（进程停机时不再触发新一轮清理）。
     */
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        log.info("Token 黑名单定时清理调度器已关闭");
    }

    /**
     * 清理一轮过期黑名单（软删 expires_at &lt; now() 的行）。
     *
     * <p>委托 {@link ISysLoginRecordService#cleanupExpiredBlacklist()}（与手动清理接口同一实现，
     * 单条 UPDATE 幂等）；service 异常仅记录 error 由下一轮调度重试，不向调度器外传播
     * （scheduleAtFixedRate 任务抛异常会终止后续调度，必须吞掉）。
     */
    public void cleanupExpiredBlacklist() {
        try {
            int cleaned = sysLoginRecordService.cleanupExpiredBlacklist();
            if (cleaned > 0) {
                log.info("定时清理过期黑名单完成: count={}", cleaned);
            }
        } catch (Exception e) {
            log.error("定时清理过期黑名单失败（下一轮调度自动重试）", e);
        }
    }
}
