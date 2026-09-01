package com.commerce.rag.config;

import com.commerce.rag.properties.AdminSeedProperties;
import com.commerce.rag.properties.AuthProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * 敏感环境变量启动校验器（BUG-7 JWT 密钥校验 + BUG-17 扩展）
 *
 * <p>application.yml 中以下敏感值均带内置默认值（漏配环境变量时兜底，保证 dev/test/CI
 * 无 .env 也能启动），但这些默认值随 git 仓库可枚举——生产环境漏配注入即构成弱口令：
 * <ul>
 *   <li>{@code auth.secret}（JWT 签名密钥，漏配可伪造任意身份 Token）</li>
 *   <li>{@code spring.datasource.password}（PG 口令，漏配即已知口令直连数据库）</li>
 *   <li>{@code spring.data.redis.password}（Redis 口令，同上）</li>
 *   <li>{@code auth.admin-seed.password}（出厂超管口令 admin123，漏配即弱口令超管）</li>
 * </ul>
 *
 * <p>校验策略（BUG-7 先例沿用，全配置化）：
 * <ul>
 *   <li>{@code auth.strict-secret=true}（生产必须设置）：四项任一为空/等于内置默认值 →
 *       汇总缺失项一次性抛 {@link IllegalStateException} 拒绝启动（免运维逐项试错）</li>
 *   <li>{@code auth.strict-secret=false}（默认，开发可用）：仅对默认值逐项打 WARN 提醒</li>
 * </ul>
 *
 * <p>生产判定口径：项目为单一 application.yml（宪法 A.2.4），无 Spring profile 划分，
 * 沿用 BUG-7 的<b>纯开关口径</b>——由运维在生产环境显式设 {@code auth.strict-secret=true}，
 * 与先例一致且 dev/test/CI 零行为变化。
 *
 * <p>依赖注入：Lombok @RequiredArgsConstructor（四依赖均为 @ConfigurationProperties 绑定 Bean
 * /自动配置属性 Bean，可纯单元测试直接构造）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class AuthSecretValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(AuthSecretValidator.class);

    /** application.yml 中 auth.secret 的内置默认值（与 ${JWT_SECRET:...} 兜底值保持一致） */
    static final String DEFAULT_SECRET = "commerce-rag-secret-key-2026-must-be-at-least-256-bits-long-for-hs256";

    /** application.yml 中 spring.datasource.password 的内置默认值（与 ${PG_PASSWORD:...} 兜底值保持一致） */
    static final String DEFAULT_DATASOURCE_PASSWORD = "rag_pg_2024";

    /** application.yml 中 spring.data.redis.password 的内置默认值（与 ${REDIS_PASSWORD:...} 兜底值保持一致） */
    static final String DEFAULT_REDIS_PASSWORD = "rag_redis_2024";

    /** 认证安全配置（auth.*，提供 secret 与 strictSecret 开关） */
    private final AuthProperties authProperties;

    /** PG 数据源属性（spring.datasource.*，取 password 校验 PG_PASSWORD 注入情况） */
    private final DataSourceProperties dataSourceProperties;

    /** Redis 属性（spring.data.redis.*，取 password 校验 REDIS_PASSWORD 注入情况） */
    private final RedisProperties redisProperties;

    /** 超管种子属性（auth.admin-seed.*，取 password 校验 AUTH_ADMIN_SEED_PASSWORD 注入情况） */
    private final AdminSeedProperties adminSeedProperties;

    /**
     * 启动校验：严格模式汇总四项敏感值缺失并拒绝启动；非严格模式仅逐项告警提醒。
     *
     * <p>判定「未注入」的口径：值为 null/空白，或等于 application.yml 的内置默认值
     * （默认值随 git 可枚举，等同于弱口令）。
     *
     * @throws IllegalStateException strict-secret=true 且任一敏感值为空或内置默认值时拒绝启动，
     *                               异常消息中文列明全部缺失的环境变量名
     */
    @Override
    public void afterPropertiesSet() {
        // 四项敏感值逐项判定「未注入」（空/默认值），命中者记录对应环境变量名
        List<String> missing = new ArrayList<>();
        collectMissing(missing, "JWT_SECRET", authProperties.secret(), DEFAULT_SECRET);
        collectMissing(missing, "PG_PASSWORD", dataSourceProperties.getPassword(), DEFAULT_DATASOURCE_PASSWORD);
        collectMissing(missing, "REDIS_PASSWORD", redisProperties.getPassword(), DEFAULT_REDIS_PASSWORD);
        collectMissing(
                missing,
                "AUTH_ADMIN_SEED_PASSWORD",
                adminSeedProperties.password(),
                AdminSeedInitializer.FACTORY_DEFAULT_PASSWORD);

        if (authProperties.strictSecret()) {
            if (!missing.isEmpty()) {
                // 严格模式：汇总一次性抛出，运维一次看全缺失项（BUG-17：拒绝弱口令启动）
                throw new IllegalStateException("auth.strict-secret=true 时以下敏感环境变量未注入（为空或仍为内置默认值）: "
                        + String.join(", ", missing) + "（生产漏配即 git 可枚举的弱口令，拒绝启动）");
            }
            log.info("敏感环境变量严格模式已生效（JWT/PG/Redis/超管种子口令均为非默认值）");
            return;
        }
        // 非严格模式：仅对默认值逐项告警（dev/test/CI 零行为变化）
        warnIfDefault("JWT_SECRET", authProperties.secret(), DEFAULT_SECRET);
        warnIfDefault("PG_PASSWORD", dataSourceProperties.getPassword(), DEFAULT_DATASOURCE_PASSWORD);
        warnIfDefault("REDIS_PASSWORD", redisProperties.getPassword(), DEFAULT_REDIS_PASSWORD);
        warnIfDefault(
                "AUTH_ADMIN_SEED_PASSWORD",
                adminSeedProperties.password(),
                AdminSeedInitializer.FACTORY_DEFAULT_PASSWORD);
    }

    /**
     * 判定单个敏感值是否「未注入」（空/内置默认值），命中则登记环境变量名
     *
     * @param missing      缺失项收集器（输出参数，登记环境变量名）
     * @param envName      该敏感值对应的环境变量名（用于异常消息与告警定位）
     * @param actual       绑定后的实际值（可能为 null=完全未配置）
     * @param builtInDefault application.yml 内置默认值（null 安全比较）
     */
    private void collectMissing(List<String> missing, String envName, String actual, String builtInDefault) {
        if (actual == null || actual.isBlank() || actual.equals(builtInDefault)) {
            missing.add(envName);
        }
    }

    /**
     * 非严格模式告警：值等于内置默认值时打 WARN（null 不告警——只有默认值才是「dev 兜底生效」场景）
     *
     * @param envName  该敏感值对应的环境变量名
     * @param actual   绑定后的实际值
     * @param builtInDefault application.yml 内置默认值
     */
    private void warnIfDefault(String envName, String actual, String builtInDefault) {
        if (builtInDefault.equals(actual)) {
            log.warn("敏感配置 {} 仍为内置默认值（仅限开发环境；生产请注入对应环境变量并设 auth.strict-secret=true）", envName);
        }
    }
}
