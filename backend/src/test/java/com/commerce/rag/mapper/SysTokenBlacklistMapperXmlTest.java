package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.test.IntegrationTestBase;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * SysTokenBlacklistMapper.xml 执行级测试（真实 PG 执行 countByJti）
 *
 * <p>验证 PG 降级黑名单统计 SQL：按 jti 计数且仅统计未删除、未过期的记录
 * （deleted=0 且 expires_at &gt; now）。覆盖命中=1、未命中=0、软删排除=0、
 * 过期排除=0（B1-4）四种断言场景。
 *
 * <p>数据准备：基类 @BeforeEach 已清理 sys_token_blacklist，本类 INSERT 3 条：
 * 1 条未删除未过期 + 1 条软删 + 1 条已过期（同 jti 维度独立）。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class SysTokenBlacklistMapperXmlTest extends IntegrationTestBase {

    private final SysTokenBlacklistMapper blacklistMapper;

    @BeforeEach
    void setUpBlacklist() {
        // 清空黑名单（基类已清理，此处显式再清一次保证本类数据形态可控）
        jdbcTemplate.update("DELETE FROM sys_token_blacklist");
        // 未删除且未过期的黑名单记录（命中场景；expires_at 取远期固定值，避免随时间推移失效）
        jdbcTemplate.update(
                "INSERT INTO sys_token_blacklist (id, jti, token_type, user_id, reason, expires_at, deleted)"
                        + " VALUES (101, 'jti-black-1', 'ACCESS', 3001, 'DEVICE_KICKED', '2099-12-31 23:59:59', 0)");
        // 软删黑名单记录（deleted=1，应被 count 排除）
        jdbcTemplate.update(
                "INSERT INTO sys_token_blacklist (id, jti, token_type, user_id, reason, expires_at, deleted)"
                        + " VALUES (102, 'jti-black-2', 'REFRESH', 3001, 'DEVICE_KICKED', '2099-12-31 23:59:59', 1)");
        // 已过期的黑名单记录（B1-4：token 原始有效期已过，jti 计数不得再拦截）
        jdbcTemplate.update(
                "INSERT INTO sys_token_blacklist (id, jti, token_type, user_id, reason, expires_at, deleted)"
                        + " VALUES (103, 'jti-black-expired', 'ACCESS', 3001, 'TOKEN_REUSE', '2000-01-01 00:00:00', 0)");
    }

    /** countByJti 命中：未删除的黑名单 jti 应计数为 1。 */
    @Test
    void countByJti命中返回1() {
        assertEquals(1L, blacklistMapper.countByJti("jti-black-1"), "未删除黑名单 jti 应计数 1");
    }

    /** countByJti 未命中：不在黑名单的 jti 应计数为 0。 */
    @Test
    void countByJti未命中返回0() {
        assertEquals(0L, blacklistMapper.countByJti("jti-black-none"), "未入黑名单的 jti 应计数 0");
    }

    /** countByJti 软删排除：deleted=1 的黑名单 jti 应计数为 0（PG 降级查询与 Redis 执法层语义一致）。 */
    @Test
    void countByJti排除软删记录() {
        assertEquals(0L, blacklistMapper.countByJti("jti-black-2"), "软删黑名单 jti 应计数 0");
    }

    /**
     * B1-4：countByJti 过期排除——expires_at 已过的黑名单行不应计数。
     *
     * <p>黑名单行的吊销意义随 token 原始有效期结束而消失（jti 为一次性随机值不会复用），
     * 不过滤会让过期行永久参与认证降级查询，且与 Redis 黑名单 TTL 到期自动放行的语义不一致。
     */
    @Test
    void countByJti排除已过期记录() {
        assertEquals(0L, blacklistMapper.countByJti("jti-black-expired"), "已过期黑名单 jti 应计数 0");
    }
}
