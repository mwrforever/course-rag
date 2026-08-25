package com.commerce.rag.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 管理后台默认账户种子配置属性（AdminSeedInitializer 启动初始化用）。
 * 绑定 application.yml 中 auth.admin-seed 配置块。
 *
 * <p>用途：应用启动时由 {@code AdminSeedInitializer}（ApplicationRunner）动态写入
 * 系统唯一超管账户，替代迁移脚本硬编码凭证——迁移一经合入冻结不可改（宪法 A.4.1），
 * 凭证改由本配置驱动。
 *
 * <p>覆盖方式：
 * <ul>
 *   <li>password 默认 {code admin123}（与 V6 迁移写入的出厂种子一致）；生产环境通过
 *       环境变量 {@code AUTH_ADMIN_SEED_PASSWORD} 覆盖为真实值，或首次登录后改密</li>
 *   <li>username / display-name 同样可由 {@code AUTH_ADMIN_SEED_USERNAME} /
 *       {@code AUTH_ADMIN_SEED_DISPLAY_NAME} 覆盖</li>
 * </ul>
 *
 * @param username    超管登录名（默认 admin）
 * @param password    超管明文密码（默认 admin123，BCrypt 存储；env 可覆盖真实值）
 * @param displayName 超管显示名（默认 系统管理员）
 */
@Validated
@ConfigurationProperties(prefix = "auth.admin-seed")
public record AdminSeedProperties(
        @NotBlank @DefaultValue("admin") String username,
        @NotBlank @DefaultValue("admin123") String password,
        @NotBlank @DefaultValue("系统管理员") String displayName) {}
