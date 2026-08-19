package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V9/V10 迁移集成测试 —— chat_run/chat_message attachments_json 列存在且为 TEXT（V10 由 JSONB 修正为 TEXT） */
class ChatAttachmentsSchemaTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("chat_run 与 chat_message 均有 attachments_json TEXT 列")
    void attachmentsJsonColumnsExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[] {"chat_run", "chat_message"}) {
                try (ResultSet rs = conn.getMetaData().getColumns(null, "public", table, "attachments_json")) {
                    assertTrue(rs.next(), table + " 应有 attachments_json 列");
                    // V10 已将 JSONB 修正为 TEXT（与既有 JSON 列一致），JDBC TYPE_NAME 实返 "text"
                    assertEquals("text", rs.getString("TYPE_NAME"), table + " attachments_json 应为 TEXT（V10 修正）");
                }
            }
        }
    }
}
