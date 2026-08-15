package com.commerce.rag.bot.rewrite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.bot.graph.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * QueryRewriter 单元测试 —— 查询重写（LLM 调用 / JSON 解析 / 降级）
 *
 * <p>构造器内 {@code ChatClient.builder(chatModel).build()} 生成真实 DefaultChatClient，
 * 通过 mock {@link ChatModel#call(Prompt)} 模拟 LLM 返回。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QueryRewriter 查询重写测试")
class QueryRewriterTest {

    @Mock
    private ChatModel chatModel;
    @Mock
    private PromptLoader promptLoader;

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new QueryRewriter(chatModel, promptLoader, new ObjectMapper());
    }

    private void stubTemplate() {
        when(promptLoader.load("query-rewrite.yml"))
                .thenReturn("system: 你是一个查询优化专家\ninstruction: 输出覆盖性查询");
    }

    @Test
    @DisplayName("rewrite → 空/空白查询直接返回空列表，不调用 LLM")
    void rewrite_blankQuery_returnsEmpty() {
        assertEquals(List.of(), rewriter.rewrite(null));
        assertEquals(List.of(), rewriter.rewrite("  "));

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @DisplayName("rewrite → LLM 返回 JSON 数组时解析为查询列表")
    void rewrite_validJson_parsesQueries() {
        stubTemplate();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("[\"什么是 RAG\",\"RAG 原理\",\"RAG 应用\"]")))));

        List<String> result = rewriter.rewrite("解释一下 RAG");

        assertEquals(3, result.size());
        assertEquals("什么是 RAG", result.get(0));
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    @DisplayName("rewrite → LLM 返回 markdown 代码块包裹时剥离后解析")
    void rewrite_markdownWrapped_parsesQueries() {
        stubTemplate();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(
                        List.of(new Generation(new AssistantMessage("```json\n[\"查询一\",\"查询二\"]\n```")))));

        List<String> result = rewriter.rewrite("课程信息");

        assertEquals(2, result.size());
        assertEquals("查询一", result.get(0));
    }

    @Test
    @DisplayName("rewrite → LLM 返回非 JSON 时降级为原始查询单元素列表")
    void rewrite_invalidJson_fallsBackToOriginal() {
        stubTemplate();
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("抱歉，我无法回答")))));

        List<String> result = rewriter.rewrite("原始问题");

        assertEquals(List.of("原始问题"), result);
    }

    @Test
    @DisplayName("rewrite → LLM 调用异常时降级为原始查询单元素列表")
    void rewrite_modelException_fallsBackToOriginal() {
        stubTemplate();
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("LLM 超时"));

        List<String> result = rewriter.rewrite("原始问题");

        assertEquals(List.of("原始问题"), result);
    }
}
