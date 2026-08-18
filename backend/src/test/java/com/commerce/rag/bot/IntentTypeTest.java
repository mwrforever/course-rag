package com.commerce.rag.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IntentType 单元测试 —— S1 值域 knowledge_question/chat/unknown 与宽松映射
 *
 * @author commerce-rag
 */
class IntentTypeTest {

    @Test
    @DisplayName("值域 — 三个意图枚举存在且顺序稳定（条件边路由依赖 name()）")
    void enumValues_threeIntents() {
        assertEquals(3, IntentType.values().length);
        // 注：Enum.name() 返回常量标识符本身（Java 语义），Task 10 条件边路由键需与 name() 一致（大写）
        assertEquals("KNOWLEDGE_QUESTION", IntentType.KNOWLEDGE_QUESTION.name());
        assertEquals("CHAT", IntentType.CHAT.name());
        assertEquals("UNKNOWN", IntentType.UNKNOWN.name());
    }

    @Test
    @DisplayName("fromString — 合法字符串映射到对应枚举（不区分大小写）")
    void fromString_knownValues_maps() {
        assertEquals(IntentType.KNOWLEDGE_QUESTION, IntentType.fromString("knowledge_question"));
        assertEquals(IntentType.KNOWLEDGE_QUESTION, IntentType.fromString("Knowledge_Question"));
        assertEquals(IntentType.CHAT, IntentType.fromString("chat"));
        assertEquals(IntentType.UNKNOWN, IntentType.fromString("unknown"));
    }

    @Test
    @DisplayName("fromString — 未知/空字符串一律 UNKNOWN（意图识别失败不拒答）")
    void fromString_unknownValues_fallbackUnknown() {
        assertEquals(IntentType.UNKNOWN, IntentType.fromString("course_info"));
        assertEquals(IntentType.UNKNOWN, IntentType.fromString(""));
        assertEquals(IntentType.UNKNOWN, IntentType.fromString(null));
    }
}
