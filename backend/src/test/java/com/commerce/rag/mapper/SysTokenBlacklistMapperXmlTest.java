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
 * <p>验证 PG 降级黑名单统计 SQL：按 jti 计数且仅统计未删除记录（deleted=0）。
 * 覆盖命中=1、未命中=0、软删记录排除=0 三种断言场景。
 *
 * <p>数据准备：基类 @BeforeEach 已清理 sys_token_blacklist，本类 INSERT 2 条：
 * 1 条未删除 + 1 条软删（同 jti 维度独立）。
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
        // 未删除黑名单记录（命中场景）
        jdbcTemplate.update(
                "INSERT INTO sys_token_blacklist (id, jti, token_type, user_id, reason, expires_at, deleted)"
                        + " VALUES (101, 'jti-black-1', 'ACCESS', 3001, 'DEVICE_KICKED', '2026-12-31 23:59:59', 0)");
        // 软删黑名单记录（deleted=1，应被 count 排除）
        jdbcTemplate.update(
                "INSERT INTO sys_token_blacklist (id, jti, token_type, user_id, reason, expires_at, deleted)"
                        + " VALUES (102, 'jti-black-2', 'REFRESH', 3001, 'DEVICE_KICKED', '2026-12-31 23:59:59', 1)");
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
}
