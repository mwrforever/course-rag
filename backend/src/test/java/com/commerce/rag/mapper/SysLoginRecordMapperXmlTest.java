package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.entity.SysLoginRecord;
import com.commerce.rag.test.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.transaction.annotation.Transactional;

/**
 * SysLoginRecordMapper.xml 执行级测试（真实 PG 执行登录记录 SQL）
 *
 * <p>覆盖 DeviceKickService 使用的五个 XML 方法：
 * <ul>
 *   <li>selectActiveForUpdate：FOR UPDATE 行锁查询（事务内执行不报错，仅断言结果集，不实际并发）</li>
 *   <li>updateStatusById：按主键置 REVOKED（updated_at 数据库生成）</li>
 *   <li>updateStatusByIdIfActive：仅 ACTIVE 记录生效，REVOKED 记录幂等（返回 0 行）</li>
 *   <li>updateStatusByUserAndJtiActive：按 user_id + jti_at 置 REVOKED，幂等</li>
 *   <li>selectActiveByUserId：返回活跃未删除记录（软删排除）</li>
 * </ul>
 *
 * <p>数据准备：基类 @BeforeEach 已清理 sys_login_record，本类直接 INSERT 预置
 * 2 条 ACTIVE + 1 条 REVOKED（同用户同设备）+ 1 条他用户 ACTIVE + 1 条软删 ACTIVE。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class SysLoginRecordMapperXmlTest extends IntegrationTestBase {

    /** 被测用户 ID */
    private static final long USER_ID = 3001L;
    /** 另一用户 ID（验证 user_id 过滤） */
    private static final long OTHER_USER_ID = 3002L;
    /** 设备类型（与生产默认一致） */
    private static final String DEVICE = "WEB_DESKTOP";

    private final SysLoginRecordMapper loginRecordMapper;

    @BeforeEach
    void setUpLoginRecords() {
        // 清空登录记录（基类已清理，此处显式再清一次保证本类数据形态可控）
        jdbcTemplate.update("DELETE FROM sys_login_record");
        insertRecord(101L, USER_ID, "jti-at-1", "jti-rt-1", "ACTIVE", 0L);
        insertRecord(102L, USER_ID, "jti-at-2", "jti-rt-2", "ACTIVE", 0L);
        insertRecord(103L, USER_ID, "jti-at-3", "jti-rt-3", "REVOKED", 0L);
        insertRecord(104L, OTHER_USER_ID, "jti-at-4", "jti-rt-4", "ACTIVE", 0L);
        // 软删的 ACTIVE 记录：验证 deleted=0 过滤（selectActiveForUpdate / selectActiveByUserId 均排除）
        insertRecord(105L, USER_ID, "jti-at-5", "jti-rt-5", "ACTIVE", 1L);
    }

    /**
     * 预置单条登录记录。
     *
     * @param id       记录主键
     * @param userId   用户 ID
     * @param jtiAt    Access Token 的 JWT ID
     * @param jtiRt    Refresh Token 的 JWT ID
     * @param status   状态（ACTIVE / REVOKED）
     * @param deleted  逻辑删除标记（0 = 未删除，1 = 已删除）
     */
    private void insertRecord(Long id, Long userId, String jtiAt, String jtiRt, String status, Long deleted) {
        jdbcTemplate.update(
                "INSERT INTO sys_login_record (id, user_id, jti_at, jti_rt, device_type, device_info, ip_address,"
                        + " expires_at, status, deleted, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, '集成测试设备', '127.0.0.1', '2026-12-31 23:59:59', ?, ?, now(), now())",
                id,
                userId,
                jtiAt,
                jtiRt,
                DEVICE,
                status,
                deleted);
    }

    /**
     * selectActiveForUpdate：FOR UPDATE 行锁查询在事务内真实执行不报错，
     * 且只返回该用户该设备的 ACTIVE 未删除记录（REVOKED / 软删 / 他用户均排除）。
     * newLoginId 传 0（不命中任何预置记录，验证不干扰其它过滤条件）。
     */
    @Test
    @Transactional
    void selectActiveForUpdate仅返回活跃未删除记录() {
        List<SysLoginRecord> records = loginRecordMapper.selectActiveForUpdate(USER_ID, DEVICE, 0L);
        assertEquals(2, records.size(), "应只返回 2 条 ACTIVE 记录（REVOKED/软删/他用户均排除）");
        assertTrue(records.stream().allMatch(r -> "ACTIVE".equals(r.getStatus())), "结果应全部为 ACTIVE");
        Set<Long> ids = records.stream().map(SysLoginRecord::getId).collect(Collectors.toSet());
        assertEquals(Set.of(101L, 102L), ids, "应精确命中预置的两条活跃记录");
    }

    /**
     * B1-1：selectActiveForUpdate 必须排除指定的当前登录记录——
     * 登录时序为「先 createLoginRecord 插入 ACTIVE 新记录，后执行互踢」，
     * PG 降级互踢若把新记录查回会把刚登录的会话误判为旧设备（自吊销、新 jti 入黑名单，
     * Redis 故障期间登录链路完全不可用）。排除 101（模拟刚插入的新记录）后应仅返回 102。
     */
    @Test
    @Transactional
    void selectActiveForUpdate排除当前登录记录() {
        List<SysLoginRecord> records = loginRecordMapper.selectActiveForUpdate(USER_ID, DEVICE, 101L);
        assertEquals(1, records.size(), "排除新登录记录后应仅返回 1 条旧 ACTIVE 记录");
        assertEquals(102L, records.get(0).getId(), "应返回未被排除的旧活跃记录 102");
        assertTrue(records.stream().noneMatch(r -> r.getId().equals(101L)), "被排除的新登录记录不得出现在结果集（B1-1 自吊销保护）");
    }

    /**
     * updateStatusById：按主键置 REVOKED，影响行数=1，DB 状态真实变更且可被活跃查询感知。
     */
    @Test
    void updateStatusById置为REVOKED() {
        int updated = loginRecordMapper.updateStatusById(101L);
        assertEquals(1, updated, "应恰好更新 1 行");
        String status = jdbcTemplate.queryForObject("SELECT status FROM sys_login_record WHERE id = 101", String.class);
        assertEquals("REVOKED", status, "DB 中状态应已置为 REVOKED");

        // 吊销后的记录不再出现在活跃查询中
        List<SysLoginRecord> active = loginRecordMapper.selectActiveByUserId(USER_ID);
        assertTrue(active.stream().noneMatch(r -> r.getId().equals(101L)), "已吊销记录不应再返回");
    }

    /**
     * updateStatusByIdIfActive 幂等：对 ACTIVE 记录生效（1 行），对已 REVOKED 记录不生效（0 行）。
     */
    @Test
    void updateStatusByIdIfActive对已吊销记录幂等() {
        // 对 ACTIVE 记录生效
        int updated = loginRecordMapper.updateStatusByIdIfActive(102L);
        assertEquals(1, updated, "ACTIVE 记录应被更新");
        assertEquals(
                "REVOKED",
                jdbcTemplate.queryForObject("SELECT status FROM sys_login_record WHERE id = 102", String.class),
                "DB 中状态应已置为 REVOKED");

        // 再次对同一记录（现已 REVOKED）调用：幂等不生效
        int again = loginRecordMapper.updateStatusByIdIfActive(102L);
        assertEquals(0, again, "对已 REVOKED 记录再次调用应不影响任何行");

        // 预置即为 REVOKED 的记录同样不生效
        int onRevoked = loginRecordMapper.updateStatusByIdIfActive(103L);
        assertEquals(0, onRevoked, "预置 REVOKED 记录不应被影响");
    }

    /**
     * updateStatusByUserAndJtiActive：按 user_id + jti_at 定位并置 REVOKED；
     * 他人 jti（user_id 不匹配）不生效；重复调用幂等。
     */
    @Test
    void updateStatusByUserAndJtiActive按jti生效() {
        int updated = loginRecordMapper.updateStatusByUserAndJtiActive(USER_ID, "jti-at-1");
        assertEquals(1, updated, "按 user_id + jti_at 应定位到 1 行");
        assertEquals(
                "REVOKED",
                jdbcTemplate.queryForObject("SELECT status FROM sys_login_record WHERE id = 101", String.class),
                "DB 中状态应已置为 REVOKED");

        // 同 jti 但 user_id 不匹配：不应生效（jti 归属校验）
        int wrongUser = loginRecordMapper.updateStatusByUserAndJtiActive(OTHER_USER_ID, "jti-at-1");
        assertEquals(0, wrongUser, "非本人 jti 不应被吊销");

        // 已 REVOKED 后重复调用：幂等不生效
        int again = loginRecordMapper.updateStatusByUserAndJtiActive(USER_ID, "jti-at-1");
        assertEquals(0, again, "重复吊销同一 jti 应不影响任何行");
    }

    /**
     * selectActiveByUserId：返回用户全部 ACTIVE 未删除记录，REVOKED 与软删记录排除。
     */
    @Test
    void selectActiveByUserId返回活跃记录() {
        List<SysLoginRecord> records = loginRecordMapper.selectActiveByUserId(USER_ID);
        assertEquals(2, records.size(), "应返回 2 条 ACTIVE 记录（REVOKED 与软删 ACTIVE 均排除）");
        assertTrue(records.stream().allMatch(r -> "ACTIVE".equals(r.getStatus())), "结果应全部为 ACTIVE");
        Set<Long> ids = records.stream().map(SysLoginRecord::getId).collect(Collectors.toSet());
        assertEquals(Set.of(101L, 102L), ids, "应精确命中两条活跃记录");

        // 无活跃记录的用户返回空列表
        List<SysLoginRecord> empty = loginRecordMapper.selectActiveByUserId(9999L);
        assertTrue(empty.isEmpty(), "无登录记录的用户应返回空列表");
    }
}
