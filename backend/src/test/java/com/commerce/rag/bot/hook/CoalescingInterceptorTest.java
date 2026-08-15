package com.commerce.rag.bot.hook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * CoalescingInterceptor 单元测试 —— SystemMessage 合并（0/1 条不合并，多条合并置前）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CoalescingInterceptor SystemMessage 合并测试")
class CoalescingInterceptorTest {

    private final CoalescingInterceptor interceptor = new CoalescingInterceptor();

    private ModelRequest request(List<Message> messages) {
        return ModelRequest.builder().messages(messages).build();
    }

    @Test
    @DisplayName("interceptModel → 空/单条消息直接透传原请求")
    void interceptModel_singleMessage_passesThrough() {
        ModelCallHandler handler = mock(ModelCallHandler.class);
        ModelResponse response = mock(ModelResponse.class);
        ModelRequest req = request(List.of(new UserMessage("你好")));
        when(handler.call(req)).thenReturn(response);

        ModelResponse result = interceptor.interceptModel(req, handler);

        assertSame(response, result);
        verify(handler).call(req);
    }

    @Test
    @DisplayName("interceptModel → 仅 0/1 条 SystemMessage 不合并")
    void interceptModel_oneSystemMessage_passesThrough() {
        ModelCallHandler handler = mock(ModelCallHandler.class);
        ModelResponse response = mock(ModelResponse.class);
        ModelRequest req = request(List.of(new SystemMessage("base"), new UserMessage("你好")));
        when(handler.call(req)).thenReturn(response);

        ModelResponse result = interceptor.interceptModel(req, handler);

        assertSame(response, result);
        verify(handler).call(req);
    }

    @Test
    @DisplayName("interceptModel → 多条 SystemMessage 合并为一条置于最前")
    void interceptModel_multipleSystemMessages_mergesToFront() {
        ModelCallHandler handler = mock(ModelCallHandler.class);
        List<Message> messages =
                List.of(new UserMessage("用户问题"), new SystemMessage("base prompt"), new SystemMessage("安全约束"));
        ModelRequest req = request(messages);
        when(handler.call(any(ModelRequest.class))).thenReturn(mock(ModelResponse.class));

        interceptor.interceptModel(req, handler);

        // 断言 handler 收到的请求：1 条合并 SM 在最前 + 原非 SM 消息
        verify(handler).call(argThat(newReq -> {
            List<Message> msgs = newReq.getMessages();
            if (msgs.size() != 2) return false;
            if (!(msgs.get(0) instanceof SystemMessage merged)) return false;
            // 合并内容包含两条 SM 的文本，且以分隔符连接
            if (!merged.getText().contains("base prompt") || !merged.getText().contains("安全约束")) return false;
            if (!merged.getText().contains("\n\n---\n\n")) return false;
            return msgs.get(1) instanceof UserMessage;
        }));
    }
}
