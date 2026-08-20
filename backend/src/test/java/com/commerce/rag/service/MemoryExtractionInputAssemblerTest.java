package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.ExtractionInput;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/** 提取输入组装测试 —— 摘要 + 最近三轮 context / 当前对话 current，与 QU buildContext 口径一致 */
class MemoryExtractionInputAssemblerTest {

    private final MemoryExtractionInputAssembler assembler = new MemoryExtractionInputAssembler();

    @Test
    @DisplayName("build — 摘要段进了 context、当前轮 User/Assistant 不进 context 只进 current")
    void build_contextExcludesCurrent() {
        List<Message> messages = List.of(
                new SystemMessage("## 对话摘要:用户在学 Python"),
                new UserMessage("旧问题 1"),
                new AssistantMessage("旧回答 1"),
                new UserMessage("当前问题"),
                new AssistantMessage("当前回答"));
        ExtractionInput input = assembler.build(messages);

        assertTrue(input.contextText().contains("用户在学 Python"), "摘要应进 context");
        assertTrue(input.contextText().contains("旧问题 1"), "最近三轮应进 context");
        assertFalse(input.contextText().contains("当前问题"), "当前轮不应进 context");
        assertTrue(input.currentText().contains("当前问题"));
        assertTrue(input.currentText().contains("当前回答"));
    }

    @Test
    @DisplayName("build — 空消息返回空输入（抽取方跳过）")
    void build_emptyReturnsBlank() {
        ExtractionInput input = assembler.build(List.of());
        assertTrue(input.contextText().isEmpty());
        assertTrue(input.currentText().isEmpty());
    }
}
