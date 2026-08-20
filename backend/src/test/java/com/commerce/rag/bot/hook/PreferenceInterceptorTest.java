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
import com.commerce.rag.service.PreferenceCacheService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 偏好注入拦截器测试 —— metadata userId → &lt;preference&gt; HumanMessage 前置
 *
 * @author commerce-rag
 */
class PreferenceInterceptorTest {

    // 注意：SAA 1.1.2.0 的 ModelResponse 无 builder()（仅构造器 + of() 工厂），
    // 此前置常量仅作 handler 透传返回值（引用相等断言），用构造器构造等价。
    private static final ModelResponse RESPONSE = new ModelResponse(List.of(new UserMessage("ok")));

    private final ModelCallHandler handler = new ModelCallHandler() {
        @Override
        public ModelResponse call(ModelRequest request) {
            return RESPONSE;
        }
    };

    @Test
    @DisplayName("metadata 有 userId + 有偏好块 → 前置注入 <preference> HumanMessage")
    void injectsPreferenceAtFront() throws Exception {
        PreferenceCacheService cache = mock(PreferenceCacheService.class);
        when(cache.getOrBuild(7L)).thenReturn("<preference>\n回答语言:中文\n</preference>");
        PreferenceInterceptor interceptor = new PreferenceInterceptor(cache);

        // 海绵式断言：自定义 handler 捕获 handler.call 收到的在途请求，验证拦截器把注入后的消息传给模型调用
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
                        .context(Map.of("userId", "7"))
                        .messages(List.of(new UserMessage("用户问题")))
                        .build(),
                capturing);

        assertNotNull(captured[0], "handler 应被调用（注入后透传）");
        assertEquals(2, captured[0].getMessages().size(), "注入后应比原消息多 1 条");
        assertTrue(captured[0].getMessages().get(0).getText().contains("<preference>"), "首条应为偏好块（前置注入）");
        assertEquals("用户问题", captured[0].getMessages().get(1).getText());
    }

    @Test
    @DisplayName("无 userId / 偏好块为空 → 原样透传不注入")
    void passesThrough_whenNoUserOrBlank() throws Exception {
        PreferenceCacheService cache = mock(PreferenceCacheService.class);
        when(cache.getOrBuild(9L)).thenReturn("");
        PreferenceInterceptor interceptor = new PreferenceInterceptor(cache);

        ModelRequest noUid = ModelRequest.builder()
                .context(Map.of())
                .messages(List.of(new UserMessage("问题")))
                .build();
        ModelResponse r1 = interceptor.interceptModel(noUid, handler);
        assertEquals(RESPONSE, r1);

        ModelRequest blankBlock = ModelRequest.builder()
                .context(Map.of("userId", "9"))
                .messages(List.of(new UserMessage("问题")))
                .build();
        interceptor.interceptModel(blankBlock, handler);
        verify(cache).getOrBuild(9L);
    }

    @Test
    @DisplayName("防御分支：context 为 null / userId 非 String / 空串 userId / 偏好块为 null → 均原样透传")
    void passesThrough_defensiveBranches() throws Exception {
        PreferenceCacheService cache = mock(PreferenceCacheService.class);
        PreferenceInterceptor interceptor = new PreferenceInterceptor(cache);

        // context 为 null（Builder 默认把 context 初始化为空 HashMap，须用 mock 返回 null 才可触达该防御分支）：
        // 读取 metadata 前先判空，不 NPE 直接透传
        ModelRequest nullCtx = mock(ModelRequest.class);
        when(nullCtx.getContext()).thenReturn(null);
        ModelResponse r1 = interceptor.interceptModel(nullCtx, handler);
        assertEquals(RESPONSE, r1, "context 为 null 时应原样透传");

        // userId 为非 String 类型（防御 worker 契约被破坏）：不匹配 instanceof 直接透传
        Map<String, Object> nonStringCtx = new HashMap<>();
        nonStringCtx.put(PreferenceInterceptor.KEY_USER_ID, 7L);
        ModelResponse r2 = interceptor.interceptModel(
                ModelRequest.builder()
                        .context(nonStringCtx)
                        .messages(List.of(new UserMessage("问题")))
                        .build(),
                handler);
        assertEquals(RESPONSE, r2, "userId 非 String 时应原样透传");

        // userId 为空串/空白（worker 契约被破坏）：命中 isBlank() 直接透传，不触发 getOrBuild
        ModelResponse r3 = interceptor.interceptModel(
                ModelRequest.builder()
                        .context(Map.of("userId", "  "))
                        .messages(List.of(new UserMessage("问题")))
                        .build(),
                handler);
        assertEquals(RESPONSE, r3, "userId 为空白时应原样透传");

        // 偏好块为 null（getOrBuild 契约为空串，防御旧实现返回 null）：视为无偏好透传
        when(cache.getOrBuild(9L)).thenReturn(null);
        ModelResponse r4 = interceptor.interceptModel(
                ModelRequest.builder()
                        .context(Map.of("userId", "9"))
                        .messages(List.of(new UserMessage("问题")))
                        .build(),
                handler);
        assertEquals(RESPONSE, r4, "偏好块为 null 时应原样透传");
    }

    @Test
    @DisplayName("getName — 返回固定钩子名 PreferenceInterceptor（Task 10 注册依据）")
    void getName_returnsStableName() {
        assertEquals("PreferenceInterceptor", new PreferenceInterceptor(mock(PreferenceCacheService.class)).getName());
    }
}
