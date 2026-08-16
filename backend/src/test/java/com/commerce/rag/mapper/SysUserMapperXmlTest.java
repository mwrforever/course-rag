package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.commerce.rag.entity.SysUser;
import com.commerce.rag.test.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * SysUserMapper.xml 执行级测试（真实 PG 执行 selectByIdsIn）
 *
 * <p>验证按需投影批量查询 SQL：
 * <ul>
 *   <li>SELECT 仅取 id / username / display_name 三列（其余字段映射为 null，验证投影生效）</li>
 *   <li>deleted=0 过滤：软删用户不返回</li>
 * </ul>
 *
 * <p>数据准备：基类 @BeforeEach 已清理 sys_user，本类 INSERT 3 个用户（含 1 个软删），
 * ID 使用 9001-9003 固定值，与基类 registerUser 递增序列（10001+）互不冲突。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class SysUserMapperXmlTest extends IntegrationTestBase {

    private final SysUserMapper sysUserMapper;

    @BeforeEach
    void setUpUsers() {
        // 清空用户表（基类已清理，此处显式再清一次保证本类数据形态可控）
        jdbcTemplate.update("DELETE FROM sys_user");
        insertUser(9001L, "stu_a", "学生A");
        insertUser(9002L, "stu_b", "学生B");
        // 软删用户：验证 selectByIdsIn 的 deleted=0 过滤
        insertUser(9003L, "stu_del", "已删除学生");
        jdbcTemplate.update("UPDATE sys_user SET deleted = 1 WHERE id = 9003");
    }

    /**
     * 预置单条用户记录（password_hash 使用占位哈希，等价管理端创建后的数据形态）。
     *
     * @param id          用户主键
     * @param username    登录名
     * @param displayName 显示名
     */
    private void insertUser(Long id, String username, String displayName) {
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, username, password_hash, display_name, role, status, created_by, deleted)"
                        + " VALUES (?, ?, 'test-hash', ?, 'STUDENT', 'ACTIVE', 1, 0)",
                id,
                username,
                displayName);
    }

    /**
     * selectByIdsIn 按需投影：IN 查询精确返回 2 条，且仅 id/username/display_name 三列有值，
     * 其余列（password_hash/role/status/deleted 等）为 null —— 验证 SELECT 投影而非 SELECT *。
     */
    @Test
    void selectByIdsIn按需投影返回指定用户() {
        List<SysUser> users = sysUserMapper.selectByIdsIn(List.of(9001L, 9002L));
        assertEquals(2, users.size(), "IN(2 个 id) 应返回 2 条用户");
        Set<Long> ids = users.stream().map(SysUser::getId).collect(Collectors.toSet());
        assertEquals(Set.of(9001L, 9002L), ids, "应精确返回预置的两个用户");

        for (SysUser user : users) {
            assertNotNull(user.getUsername(), "投影列 username 应有值");
            assertNotNull(user.getDisplayName(), "投影列 display_name 应有值");
            // SELECT 投影验证：未选择的列必须为 null（证明未走 SELECT *）
            assertNull(user.getPasswordHash(), "password_hash 未被 SELECT，应为 null");
            assertNull(user.getRole(), "role 未被 SELECT，应为 null");
            assertNull(user.getStatus(), "status 未被 SELECT，应为 null");
            assertNull(user.getDeleted(), "deleted 未被 SELECT，应为 null");
            assertNull(user.getCreatedAt(), "created_at 未被 SELECT，应为 null");
        }
    }

    /**
     * selectByIdsIn 软删过滤：IN 列表中包含软删用户时，只返回未删除用户。
     */
    @Test
    void selectByIdsIn排除软删用户() {
        List<SysUser> users = sysUserMapper.selectByIdsIn(List.of(9001L, 9003L));
        assertEquals(1, users.size(), "软删用户应被 deleted=0 过滤");
        assertEquals(9001L, users.get(0).getId(), "应只返回未删除用户 9001");
    }
}
