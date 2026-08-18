package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * Document 组装拦截器 —— 将检索节点产出的 <document> 瞬时注入当次模型请求（spec §3.3）
 *
 * <p>与 {@link CoalescingInterceptor} 同为 ModelInterceptor（瞬时，改单次请求，不落
 * state/checkpoint）：检索结果作为临时上下文，禁止进入会话状态（spec 设计原则 3）。
 *
 * <p><b>传递通道（SAA 源码实锤）：</b>RetrieveNode 把 document 文本写入
 * {@code RunnableConfig.metadata()}；AgentLlmNode 构建 ModelRequest 时
 * {@code context = RunnableConfig.metadata()}（同一共享 Map 引用）；本拦截器从
 * {@code request.getContext()} 读取。
 *
 * <p><b>注入形态（spec §3.3）：</b>追加一条独立 UserMessage 容器（与用户原文分离——
 * QU 过滤、chat_message 渲染、摘要提取不受污染）；幂等：注入后向 context 置标记，
 * ReactAgent 多轮工具调用的后续模型请求不重复注入。
 *
 * @author commerce-rag
 */
@Component
public class DocumentAssemblerInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssemblerInterceptor.class);

    /** metadata/context 键：检索节点写入的 <document> 文本（RetrieveNode 与拦截器共享） */
    public static final String KEY_DOCUMENT_CONTEXT = "document_context";

    /** context 内部幂等标记：注入后置 true，同请求后续调用不再注入 */
    private static final String KEY_DOCUMENT_INJECTED = "document_injected";

    @Override
    public String getName() {
        return "DocumentAssemblerInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> ctx = request.getContext();
        if (ctx == null || ctx.get(KEY_DOCUMENT_CONTEXT) == null) {
            return handler.call(request);
        }
        // 幂等：已注入过则直接透传（ReactAgent 多轮工具调用期间 document 只注入一次）
        if (Boolean.TRUE.equals(ctx.get(KEY_DOCUMENT_INJECTED))) {
            return handler.call(request);
        }

        // 追加独立 document UserMessage（消息末尾，与用户原文分离）
        List<Message> messages = new ArrayList<>(request.getMessages());
        messages.add(new UserMessage(String.valueOf(ctx.get(KEY_DOCUMENT_CONTEXT))));
        ctx.put(KEY_DOCUMENT_INJECTED, true);

        log.debug(
                "已注入 document 上下文（{} 字符）",
                String.valueOf(ctx.get(KEY_DOCUMENT_CONTEXT)).length());

        return handler.call(ModelRequest.builder(request).messages(messages).build());
    }
}
