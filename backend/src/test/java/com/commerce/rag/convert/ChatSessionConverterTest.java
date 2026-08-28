package com.commerce.rag.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.ChatMessage;
import com.commerce.rag.entity.ChatSession;
import com.commerce.rag.vo.ChatMessageVO;
import com.commerce.rag.vo.ChatSessionVO;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ChatSessionConverter 转换器测试 —— 会话/消息实体 → 管理端视图对象字段映射 */
@DisplayName("ChatSessionConverter 转换器测试")
class ChatSessionConverterTest {

    private final ChatSessionConverter converter = new ChatSessionConverterImpl();

    private ChatSession session(Long id) {
        ChatSession s = new ChatSession();
        s.setId(id);
        s.setUserId(5L);
        s.setTitle("会话" + id);
        s.setStatus("ACTIVE");
        s.setLastMessageAt(LocalDateTime.of(2026, 8, 15, 10, 0));
        s.setModel("qwen3.8-max");
        s.setDeleted(0L);
        s.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 0));
        return s;
    }

    private ChatMessage message(Long id) {
        ChatMessage m = new ChatMessage();
        m.setId(id);
        m.setSessionId(1L);
        m.setRole("user");
        m.setContent("问题" + id);
        m.setMessageType("TEXT");
        m.setIntentType("knowledge_question");
        m.setSourcesJson("[1]");
        m.setTokenCount(10);
        m.setRunId(10L);
        m.setSeq(1);
        m.setTraceId("trace-1");
        m.setCreatedAt(LocalDateTime.of(2026, 8, 15, 9, 1));
        return m;
    }

    @Test
    @DisplayName("会话实体 → 摘要视图（剔除 deleted/updatedAt）")
    void toSummaryVO_mapsAllFields() {
        ChatSessionVO vo = converter.toSummaryVO(session(1L));

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.userId()).isEqualTo(5L);
        assertThat(vo.title()).isEqualTo("会话1");
        assertThat(vo.status()).isEqualTo("ACTIVE");
        assertThat(vo.lastMessageAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 10, 0));
        assertThat(vo.model()).isEqualTo("qwen3.8-max");
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 0));
        assertThat(vo)
                .isEqualTo(new ChatSessionVO(
                        1L,
                        5L,
                        "会话1",
                        "ACTIVE",
                        LocalDateTime.of(2026, 8, 15, 10, 0),
                        "qwen3.8-max",
                        LocalDateTime.of(2026, 8, 15, 9, 0)));
        assertThat(vo.toString()).contains("会话1");
    }

    @Test
    @DisplayName("消息实体 → 消息视图（剔除 sourcesJson/tokenCount/confidence/traceId 等内部字段）")
    void toMessageVO_mapsAllFields() {
        ChatMessageVO vo = converter.toMessageVO(message(1L));

        assertThat(vo.id()).isEqualTo(1L);
        assertThat(vo.role()).isEqualTo("user");
        assertThat(vo.content()).isEqualTo("问题1");
        assertThat(vo.messageType()).isEqualTo("TEXT");
        // 非 thinking 行无阶段键（2026-08-28 时间线改版新增字段，null 语义 = 前端降级 generating）
        assertThat(vo.thinkingStage()).isNull();
        assertThat(vo.intentType()).isEqualTo("knowledge_question");
        assertThat(vo.runId()).isEqualTo(10L);
        assertThat(vo.seq()).isEqualTo(1);
        assertThat(vo.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 15, 9, 1));
        assertThat(vo)
                .isEqualTo(new ChatMessageVO(
                        1L,
                        "user",
                        "问题1",
                        "TEXT",
                        null,
                        "knowledge_question",
                        10L,
                        1,
                        LocalDateTime.of(2026, 8, 15, 9, 1)));
        assertThat(vo.toString()).contains("问题1");
    }

    @Test
    @DisplayName("消息实体 → 消息视图：thinking 行 thinkingStage 同名映射（2026-08-28 时间线改版，replayFromPg 数据通道）")
    void toMessageVO_mapsThinkingStage() {
        // Given: thinking 行带阶段键（understanding/attachments/generating 之一）
        ChatMessage m = message(2L);
        m.setMessageType("thinking");
        m.setThinkingStage("understanding");

        // When / Then: 同名映射进 VO（回放与 B 端出参共用该通道）
        ChatMessageVO vo = converter.toMessageVO(m);
        assertThat(vo.messageType()).isEqualTo("thinking");
        assertThat(vo.thinkingStage()).isEqualTo("understanding");
    }
}
