package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.ExtractionInput;
import com.commerce.rag.record.PreferenceExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** 偏好提取服务测试 —— 候选 JSON 解析 / key 白名单 / value 归一化 / 失败降级 */
class PreferenceExtractionServiceTest {

    private PreferenceExtractionService newService(ChatModel chatModel) {
        MemoryProperties props = new MemoryProperties();
        props.getPreference().getValueSynonyms().put("response_verbosity", Map.of("brief", "简洁", "short", "简洁"));
        return new PreferenceExtractionService(chatModel, new PromptLoader(), new ObjectMapper(), props);
    }

    @Test
    @DisplayName("extract — 合法候选 JSON 解析为候选列表（value 归一化 + 未知 key 过滤）")
    void extract_parsesCandidates() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage(
                                                        "{\"candidates\":[{\"key\":\"response_verbosity\",\"value\":\"brief\",\"explicitness\":0.9,\"confidence\":0.8},{\"key\":\"not_exist_key\",\"value\":\"x\",\"explicitness\":0.9,\"confidence\":0.8}],\"deletions\":[{\"key\":\"course_direction\",\"value\":\"前端\"}]}")))));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("历史上下文", "当前对话"), "无");

        assertEquals(1, result.candidates().size());
        assertEquals("response_verbosity", result.candidates().get(0).key());
        assertEquals("简洁", result.candidates().get(0).value(), "brief 应归一化为 简洁");
        assertEquals(1, result.deletions().size());
        assertEquals("course_direction", result.deletions().get(0).key());
    }

    @Test
    @DisplayName("extract — markdown 代码块包裹的 JSON 也能解析")
    void extract_stripsCodeFence() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage(
                                                        "```json\n{\"candidates\":[{\"key\":\"response_language\",\"value\":\"中文\",\"explicitness\":1.0,\"confidence\":0.9}]}\n```")))));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertEquals(1, result.candidates().size());
        assertEquals("中文", result.candidates().get(0).value());
    }

    @Test
    @DisplayName("extract — LLM 调用异常降级返回空（不抛出，不影响主链路）")
    void extract_llmFailureReturnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.deletions().isEmpty());
    }

    @Test
    @DisplayName("extract — 空白 current 输入直接返回空（不调用 LLM）")
    void extract_blankCurrentSkips() {
        ChatModel chatModel = mock(ChatModel.class);
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "  "), "无");
        assertTrue(result.candidates().isEmpty());
        // 空 current 短路，必须未触发任何 LLM 调用（审查 M-1 实证）
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("extract — explicitness/confidence 越界值被夹取到 [0,1]（spec §7.4 强制）")
    void extract_clampsScores() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                new AssistantMessage(
                                                        "{\"candidates\":[{\"key\":\"response_verbosity\",\"value\":\"简洁\",\"explicitness\":1.5,\"confidence\":-0.2}]}")))));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertEquals(1, result.candidates().size());
        // 越界值夹取：explicitness 1.5 → 1.0，confidence -0.2 → 0.0
        assertEquals(1.0, result.candidates().get(0).explicitness());
        assertEquals(0.0, result.candidates().get(0).confidence());
    }

    @Test
    @DisplayName("extract — LLM 返回非法 JSON 降级返回空（不抛出，不影响主链路）")
    void extract_invalidJsonReturnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("not json")))));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.deletions().isEmpty());
    }

    @Test
    @DisplayName("extract — LLM 返回空白 content 直接返回空结果")
    void extract_blankContentReturnsEmpty() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("  ")))));
        PreferenceExtractionResult result = newService(chatModel).extract(new ExtractionInput("", "当前对话"), "无");
        assertTrue(result.candidates().isEmpty());
        assertTrue(result.deletions().isEmpty());
    }

    @Test
    @DisplayName("normalizeValue — 词表命中归一化；未命中/开放型 key 按原值")
    void normalizeValue_mapsOrKeeps() {
        var svc = newService(mock(ChatModel.class));
        assertEquals("简洁", svc.normalizeValue("response_verbosity", "brief"));
        assertEquals("原始值", svc.normalizeValue("response_verbosity", "原始值"));
        assertEquals("Python", svc.normalizeValue("course_direction", "Python"));
    }
}
