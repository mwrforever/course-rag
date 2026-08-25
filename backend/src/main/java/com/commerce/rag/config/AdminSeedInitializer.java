package com.commerce.rag.config;

import com.commerce.rag.properties.AdminSeedProperties;
import com.commerce.rag.service.ISysUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 管理后台默认账户种子初始化器 —— 应用启动时写入系统唯一超管账户
 *
 * <p>实现 {@link ApplicationRunner}：Spring 上下文刷新完成（Flyway 迁移已执行）后自动运行，
 * 无 {@code @PostConstruct} 与迁移时序竞态，与 {@link MilvusCollectionInitializer} 同款启动模式，
 * 也即 V6 迁移注释中「正常流程应通过 ApplicationRunner 动态注入」的设计意图。
 *
 * <p>凭证来源：{@link AdminSeedProperties}（application.yml {@code auth.admin-seed}，
 * 环境变量 {@code AUTH_ADMIN_SEED_PASSWORD} 等可覆盖真实值）；
 * 幂等（create-if-missing + 未改密则刷新）写入逻辑见 {@link ISysUserService#ensureSeedSuperAdmin}，
 * 永不覆盖管理员已自行改密的既有超管。
 *
 * @author commerce-rag
 * @see MilvusCollectionInitializer
 * @see AdminSeedProperties
 */
@Component
@RequiredArgsConstructor
public class AdminSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeedInitializer.class);

    /** 出厂默认种子密码明文——与 V6__full_schema_v5.sql 迁移预置、auth.admin-seed.password 默认值一致，
     *  作为识别「仍未改密」超管账户的标记：密码非此值时视为管理员已自行改密，跳过刷新不覆盖 */
    private static final String FACTORY_DEFAULT_PASSWORD = "admin123";

    /** 默认账户种子配置（auth.admin-seed，env 可覆盖真实值） */
    private final AdminSeedProperties adminSeedProperties;

    /** 用户服务（幂等写入超管） */
    private final ISysUserService sysUserService;

    /**
     * 启动执行入口：将配置的超管种子委托给服务层幂等写入
     *
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        sysUserService.ensureSeedSuperAdmin(
                adminSeedProperties.username(),
                adminSeedProperties.password(),
                adminSeedProperties.displayName(),
                FACTORY_DEFAULT_PASSWORD);
        log.info("超管账户种子初始化完成: username={}", adminSeedProperties.username());
    }
}
