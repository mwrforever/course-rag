package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V11 迁移集成测试 —— user_preference 表结构与唯一索引 */
class UserPreferenceSchemaTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("user_preference 存在核心列（user_id/key/value/status/write_score/deleted）")
    void coreColumnsExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String col : List.of(
                    "id",
                    "user_id",
                    "key",
                    "value",
                    "status",
                    "observation_count",
                    "version",
                    "write_score",
                    "deleted")) {
                try (ResultSet rs = conn.getMetaData().getColumns(null, "public", "user_preference", col)) {
                    assertTrue(rs.next(), "user_preference 应含列 " + col);
                    assertNotNull(rs.getString("TYPE_NAME"), col + " 类型不应为空");
                }
            }
        }
    }

    @Test
    @DisplayName("单值 key 唯一索引存在（deleted=0 且 status=active）")
    void singleActiveUniqueIndexExists() throws Exception {
        try (Connection conn = dataSource.getConnection();
                ResultSet rs = conn.getMetaData().getIndexInfo(null, "public", "user_preference", false, false)) {
            boolean found = false;
            while (rs.next()) {
                if ("uk_user_pref_single_active".equals(rs.getString("INDEX_NAME"))) {
                    found = true;
                }
            }
            assertTrue(found, "应存在 uk_user_pref_single_active 唯一索引");
        }
    }
}
