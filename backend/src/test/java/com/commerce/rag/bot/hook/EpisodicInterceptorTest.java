package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.service.EpisodicBlockService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 经历记忆注入拦截器测试 —— metadata episodic_context → &lt;episodic&gt; 尾部注入 / 失败降级透传
 *
 * <p>沿用继承链路已有的 {@link PreferenceInterceptorTest} 风格：自定义
 * ModelCallHandler 捕获在途请求（海绵式断言）。
 *
 * @author commerce-rag
 */
class EpisodicInterceptorTest {

    private static final ModelResponse RESPONSE = new ModelResponse(List.of(new UserMessage("ok")));

    private final ModelCallHandler handler = new ModelCallHandler() {
        @Override
        public ModelResponse call(ModelRequest request) {
            return RESPONSE;
        }
    };

    private final EpisodicMemoryRef ref =
            new EpisodicMemoryRef(1L, "learning_progress", "已完成 Spring Boot 基础", "", "active", 0.9);

    private static final String BLOCK = "<episodic>\n学习进度(当前):已完成 Spring Boot 基础\n</episodic>";

    @Test
    @DisplayName("metadata 有 episodic_context + 块非空 → <episodic> UserMessage 追加到消息末尾")
    void inject_appendsEpisodicBlockAtTail() throws Exception {
        EpisodicBlockService blockService = mock(EpisodicBlockService.class);
        when(blockService.build(List.of(ref))).thenReturn(BLOCK);
        EpisodicInterceptor interceptor = new EpisodicInterceptor(blockService);

        // 海绵式断言：捕获 handler.call 收到的在途请求，验证块被追加到消息序列末尾
        final ModelRequest[] captured = new ModelRequest[1];
        ModelCallHandler capturing = new ModelCallHandler() {
            @Override
            public ModelResponse call(ModelRequest req) {
                captured[0] = req;
                return RESPONSE;
            }
        };
        interceptor.interceptModel(
                ModelRequest.builder()
                        .context(Map.of(EpisodicInterceptor.KEY_EPISODIC_CONTEXT, List.of(ref)))
                        .messages(List.of(new UserMessage("用户问题")))
                        .build(),
                capturing);

        assertNotNull(captured[0], "handler 应被调用（注入后透传）");
        assertEquals(2, captured[0].getMessages().size(), "注入后应比原消息多 1 条");
        assertEquals("用户问题", captured[0].getMessages().get(0).getText(), "原消息应保持在首位");
        assertTrue(captured[0].getMessages().get(1).getText().contains("<episodic>"), "末尾应为经历记忆块（尾部注入）");
    }

    @Test
    @DisplayName("metadata 无 episodic_context → 原消息透传不注入")
    void inject_noContext_passthrough() throws Exception {
        EpisodicBlockService blockService = mock(EpisodicBlockService.class);
        EpisodicInterceptor interceptor = new EpisodicInterceptor(blockService);

        ModelRequest noCtx = ModelRequest.builder()
                .context(Map.of())
                .messages(List.of(new UserMessage("问题")))
                .build();
        ModelResponse r = interceptor.interceptModel(noCtx, handler);
        assertEquals(RESPONSE, r, "无 context 键时应原样透传（引用相等）");
    }

    @Test
    @DisplayName("metadata episodic_context 为空列表 → 原样透传")
    void inject_emptyRefs_passthrough() throws Exception {
        EpisodicBlockService blockService = mock(EpisodicBlockService.class);
        EpisodicInterceptor interceptor = new EpisodicInterceptor(blockService);

        ModelRequest empty = ModelRequest.builder()
                .context(Map.of(EpisodicInterceptor.KEY_EPISODIC_CONTEXT, List.of()))
                .messages(List.of(new UserMessage("问题")))
                .build();
        assertEquals(RESPONSE, interceptor.interceptModel(empty, handler), "空引用列表应透传");
    }

    @Test
    @DisplayName("blockService.build 返回空串 → 原样透传（组装阈值外）")
    void inject_blockBlank_passthrough() throws Exception {
        EpisodicBlockService blockService = mock(EpisodicBlockService.class);
        when(blockService.build(List.of(ref))).thenReturn("");
        EpisodicInterceptor interceptor = new EpisodicInterceptor(blockService);

        ModelRequest withRef = ModelRequest.builder()
                .context(Map.of(EpisodicInterceptor.KEY_EPISODIC_CONTEXT, List.of(ref)))
                .messages(List.of(new UserMessage("问题")))
                .build();
        assertEquals(RESPONSE, interceptor.interceptModel(withRef, handler), "空块应透传不注入");
    }

    @Test
    @DisplayName("metadata 混入非 EpisodicMemoryRef 元素 → 过滤只留有效引用传给 build")
    void inject_mixedRefsFiltersType() throws Exception {
        EpisodicBlockService blockService = mock(EpisodicBlockService.class);
        when(blockService.build(List.of(ref))).thenReturn(BLOCK);
        EpisodicInterceptor interceptor = new EpisodicInterceptor(blockService);

        // 混入 String 污染元素，过滤后只剩有效 ref
        ModelRequest dirty = ModelRequest.builder()
                .context(Map.of(EpisodicInterceptor.KEY_EPISODIC_CONTEXT, List.of(ref, "污染数据", 42L)))
                .messages(List.of(new UserMessage("问题")))
                .build();
        interceptor.interceptModel(dirty, handler);

        // 过滤后只把有效 EpisodicMemoryRef 列表交给块组装
        verify(blockService).build(List.of(ref));
    }
}
