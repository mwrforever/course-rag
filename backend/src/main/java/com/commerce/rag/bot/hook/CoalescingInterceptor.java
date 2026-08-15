package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.stereotype.Component;

/**
 * SystemMessage 合并拦截器 —— 将多条 SM 合并为一条
 *
 * <p><b>为什么需要此组件：</b>
 * SAA 的 {@code appendSystemPromptIfNeeded} 仅前置 base SM 到 index 0，
 * 其余 SM 原样保留在 messages 列表中；&gt;2 SM 仅打警告，不做合并。
 * 这与 LangGraph 的 {@code SystemMessageCoalescingMiddleware} 行为不同，
 * 因此 SAA 项目必须自研此能力。
 *
 * <p><b>职责边界：</b>
 * 这是 ModelInterceptor（瞬时，改单次请求），不改变 State/checkpoint，
 * 与 Hook（持久，改 State）职责严格分离。
 *
 * @author commerce-rag
 */
@Component
public class CoalescingInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CoalescingInterceptor.class);
    private static final String SEPARATOR = "\n\n---\n\n";

    @Override
    public String getName() {
        return "CoalescingInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        List<Message> messages = request.getMessages();
        if (messages == null || messages.size() <= 1) {
            return handler.call(request);
        }

        // 分离 SystemMessage 和非 SystemMessage
        List<SystemMessage> systemMessages = new ArrayList<>();
        List<Message> nonSystemMessages = new ArrayList<>();

        for (Message m : messages) {
            if (m instanceof SystemMessage sm) {
                systemMessages.add(sm);
            } else {
                nonSystemMessages.add(m);
            }
        }

        // 只有 0 或 1 条 SM，无需合并
        if (systemMessages.size() <= 1) {
            return handler.call(request);
        }

        // 合并多条 SM
        String merged = systemMessages.stream()
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(SEPARATOR));

        log.debug("合并 {} 条 SystemMessage → 1 条 ({} 字符)", systemMessages.size(), merged.length());

        // 组装新 messages：合并后的 SM 放在最前面
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new SystemMessage(merged));
        newMessages.addAll(nonSystemMessages);

        // 使用 Builder 重建 ModelRequest
        ModelRequest newRequest =
                ModelRequest.builder(request).messages(newMessages).build();

        return handler.call(newRequest);
    }
}
