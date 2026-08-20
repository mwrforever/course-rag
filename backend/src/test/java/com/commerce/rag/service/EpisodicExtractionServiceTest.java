package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.ExtractionInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 经历记忆提取服务测试 —— 记忆 JSON 解析 / type 白名单 / 分数夹取 / 失败降级 */
class EpisodicExtractionServiceTest {

    /** 构造测试服务：无 Spring 上下文，直接 new + mock ChatModel（模型走 MemoryProperties 默认配置） */
    private EpisodicExtractionService newService(ChatModel chatModel) {
        return new EpisodicExtractionService(chatModel, new PromptLoader(), new ObjectMapper(), new MemoryProperties());
    }

    @Test
    @DisplayName("parse — 合法 JSON 数组解析：字段/分数夹取/structured_facts 对象序列化/merge_target")
    void parse_extractsValidMemoriesWithClampedScores() {
        var svc = newService(mock(ChatModel.class));
        String json = "{\"episodic_memories\":["
                + "{\"is_memory\":true,\"action\":\"CREATE\",\"type\":\"learning_goal\","
                + "\"content\":\"用户准备 3 个月内转行 Python 开发\",\"summary\":\"计划转行 Python\","
                + "\"structured_facts\":{\"goal\":\"转行\",\"deadline\":\"3个月\"},"
                + "\"importance\":1.5,\"explicitness\":0.9,\"confidence\":-0.2,\"merge_target\":null},"
                + "{\"is_memory\":true,\"action\":\"UPDATE\",\"type\":\"learning_progress\","
                + "\"content\":\"Python 基础已学完，在学 Django\",\"summary\":\"在学 Django\","
                + "\"structured_facts\":\"plain-text\",\"importance\":0.8,\"explicitness\":0.9,\"confidence\":0.8,"
                + "\"merge_target\":\"Python 基础已学完\"}]}";
        EpisodicExtractionResult result = svc.parse(json);

        assertEquals(2, result.memories().size());
        var m0 = result.memories().get(0);
        assertTrue(m0.isMemory());
        assertEquals("CREATE", m0.action());
        assertEquals("learning_goal", m0.type());
        assertEquals("用户准备 3 个月内转行 Python 开发", m0.content());
        assertEquals("计划转行 Python", m0.summary());
        // structured_facts 对象 → JSON 文本
        assertEquals("{\"goal\":\"转行\",\"deadline\":\"3个月\"}", m0.structuredFacts());
        // 分数越界夹取：importance 1.5 → 1.0，confidence -0.2 → 0.0（spec §8.4）
        assertEquals(1.0, m0.importance());
        assertEquals(0.9, m0.explicitness());
        assertEquals(0.0, m0.confidence());
        // CREATE 的 merge_target 固定为 null
        assertNull(m0.mergeTarget());
        var m1 = result.memories().get(1);
        assertEquals("UPDATE", m1.action());
        // structured_facts 非对象 → null
        assertNull(m1.structuredFacts());
        assertEquals(0.8, m1.importance());
        // UPDATE 的 merge_target 透传原文
        assertEquals("Python 基础已学完", m1.mergeTarget());
    }

    @Test
    @DisplayName("parse — 未知 type 与空 content 条目被跳过，仅保留有效行")
    void parse_dropsUnknownTypeAndBlankContent() {
        var svc = newService(mock(ChatModel.class));
        String json = "{\"episodic_memories\":["
                + "{\"type\":\"exam_prep\",\"content\":\"考前突击复习\"},"
                + "{\"type\":\"learning_progress\",\"content\":\"   \"},"
                + "{\"type\":\"personal_context\",\"content\":\"在职，晚上学习\"}]}";
        EpisodicExtractionResult result = svc.parse(json);

        assertEquals(1, result.memories().size());
        assertEquals("personal_context", result.memories().get(0).type());
        assertEquals("在职，晚上学习", result.memories().get(0).content());
    }

    @Test
    @DisplayName("parse — is_memory=false 条目保留（决策侧统一过滤，此处不过滤）")
    void parse_isMemoryFalseKeptForDecision() {
        var svc = newService(mock(ChatModel.class));
        String json = "{\"episodic_memories\":["
                + "{\"is_memory\":false,\"type\":\"personal_context\",\"content\":\"今天问了几个 Java 问题\"}]}";
        EpisodicExtractionResult result = svc.parse(json);

        assertEquals(1, result.memories().size());
        assertEquals(false, result.memories().get(0).isMemory());
    }

    @Test
    @DisplayName("parse — markdown 代码块包裹的 JSON 正常提取")
    void parse_handlesMarkdownWrappedJson() {
        var svc = newService(mock(ChatModel.class));
        String json = "```json\n{\"episodic_memories\":["
                + "{\"is_memory\":true,\"type\":\"resolved_question\","
                + "\"content\":\"JVM 堆溢出已通过调大 -Xmx 解决\"}]}\n```";
        EpisodicExtractionResult result = svc.parse(json);

        assertEquals(1, result.memories().size());
        assertEquals("resolved_question", result.memories().get(0).type());
    }

    @Test
    @DisplayName("parse — 非法 JSON / 非数组根 / 空数组均返回空结果")
    void parse_returnsEmptyOnMalformedJson() {
        var svc = newService(mock(ChatModel.class));
        // JsonProcessingException 路径：非法 JSON 字符串
        assertTrue(svc.parse("not json").memories().isEmpty());
        // 合法 JSON 但 episodic_memories 不是数组（isArray 分支走空）
        assertTrue(svc.parse("{\"foo\":\"bar\"}").memories().isEmpty());
        // 空数组 → 空结果
        assertTrue(svc.parse("{\"episodic_memories\":[]}").memories().isEmpty());
    }

    @Test
    @DisplayName("parse — null 输入触发运行时防御降级返回空（不抛出）")
    void parse_nullContentReturnsEmpty() {
        var svc = newService(mock(ChatModel.class));
        EpisodicExtractionResult result = svc.parse(null);
        assertTrue(result.memories().isEmpty());
    }

    @Test
    @DisplayName("extract — 空白 current 输入直接返回空（不调用 LLM）")
    void extract_withBlankCurrent_returnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        EpisodicExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "  "), "无");
        assertTrue(result.memories().isEmpty());
        // 空 current 短路，必须未触发任何 LLM 调用
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("extract — null 输入直接返回空（不调用 LLM）")
    void extract_nullInput_returnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        EpisodicExtractionResult result = newService(chatModel).extract(null, "无");
        assertTrue(result.memories().isEmpty());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("extract — LLM 调用异常降级返回空（不抛出，不影响主链路）")
    void extract_llmFailure_returnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));
        EpisodicExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertTrue(result.memories().isEmpty());
    }

    @Test
    @DisplayName("extract — LLM 返回空白/空 content 时直接返回空结果")
    void extract_blankOrNullContentReturnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))));
        EpisodicExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertTrue(result.memories().isEmpty());
    }

    @Test
    @DisplayName("extract — 成功链路：占位符替换（context 空→空串、existing 空→无）+ 解析出记忆")
    void extract_success_returnsParsedMemories() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("{\"episodic_memories\":["
                        + "{\"is_memory\":true,\"type\":\"learning_goal\","
                        + "\"content\":\"计划转行数据分析\"}]}")))));
        EpisodicExtractionResult result = newService(chatModel).extract(new ExtractionInput(null, "当前对话"), null);
        assertEquals(1, result.memories().size());
        assertEquals("learning_goal", result.memories().get(0).type());
        assertEquals("计划转行数据分析", result.memories().get(0).content());
    }
}
