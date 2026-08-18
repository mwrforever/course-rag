package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * DocumentAssemblerInterceptor 单元测试 —— document 瞬时注入（ModelInterceptor，不落 state）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentAssemblerInterceptor document 注入测试")
class DocumentAssemblerInterceptorTest {

    @Mock
    private ModelCallHandler handler;

    private final DocumentAssemblerInterceptor interceptor = new DocumentAssemblerInterceptor();

    @Test
    @DisplayName("interceptModel — context 有 document_context 时追加独立 UserMessage，放消息末尾")
    void interceptModel_withContext_appendsUserMessage() {
        UserMessage question = new UserMessage("高等数学怎么学");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, "<document>...</document>");
        ModelRequest request = ModelRequest.builder()
                .messages(new ArrayList<>(List.of(question)))
                .context(ctx)
                .build();

        interceptor.interceptModel(request, handler);

        // 断言传给下游 handler 的新 request 含 document UserMessage
        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(handler).call(captor.capture());
        List<Message> messages = captor.getValue().getMessages();
        assertEquals(2, messages.size(), "应追加一条 document UserMessage");
        UserMessage doc = (UserMessage) messages.get(1);
        assertEquals("<document>...</document>", doc.getText());
        assertEquals(question, messages.get(0), "用户原文消息保留在首位");
    }

    @Test
    @DisplayName("interceptModel — context 无 document_context 时不改消息直接透传")
    void interceptModel_noContext_passthrough() {
        ModelRequest request = ModelRequest.builder()
                .messages(new ArrayList<>(List.of(new UserMessage("你好"))))
                .context(new HashMap<>())
                .build();

        interceptor.interceptModel(request, handler);

        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(handler).call(captor.capture());
        assertEquals(1, captor.getValue().getMessages().size(), "无 document 时不追加消息");
    }

    @Test
    @DisplayName("interceptModel — 幂等：注入一次后同次请求后续调用不再重复注入")
    void interceptModel_idempotent_singleInjection() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put(DocumentAssemblerInterceptor.KEY_DOCUMENT_CONTEXT, "<document>D</document>");
        ModelRequest request = ModelRequest.builder()
                .messages(new ArrayList<>(List.of(new UserMessage("问"))))
                .context(ctx)
                .build();

        // 第一次调用注入
        interceptor.interceptModel(request, handler);
        // 第二次调用（同一 context Map，模拟 ReactAgent 多轮工具调用）不再注入
        interceptor.interceptModel(request, handler);

        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(handler, times(2)).call(captor.capture());
        List<ModelRequest> all = captor.getAllValues();
        assertEquals(2, all.get(0).getMessages().size(), "首次注入 1 条");
        assertEquals(1, all.get(1).getMessages().size(), "二次不再注入（幂等）");
    }

    @Test
    @DisplayName("interceptModel — context 为 null 时安全透传（不 NPE）")
    void interceptModel_nullContext_passthrough() {
        ModelRequest request = ModelRequest.builder()
                .messages(new ArrayList<>(List.of(new UserMessage("问"))))
                .build();

        interceptor.interceptModel(request, handler);

        ArgumentCaptor<ModelRequest> captor = ArgumentCaptor.forClass(ModelRequest.class);
        verify(handler).call(captor.capture());
        assertEquals(1, captor.getValue().getMessages().size(), "context 为 null 时消息不变");
    }
}
