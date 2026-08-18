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
 * QU 过滤、chat_message 渲染、摘要提取不受污染）；每轮模型调用都重新注入（不做幂等
 * 检查）：ModelInterceptor 为瞬时注入，仅修改在途请求、不落 state/checkpoint，同一
 * 请求链内多次调用各自基于当前消息列表追加，不会产生可见重复；这样 ReactAgent 工具
 * 调用轮次（如 CourseApiTool 查价格/课表）之后的作答轮仍保有 document 接地。
 *
 * <p>注：此处刻意偏离 spec §3.3 的「幂等检查（已注入则不重复）」——终态评审
 * （Important-1）经字节码级验证确认注入为瞬时行为（不会产生可见重复），去幂等可修复
 * 「工具调用后作答轮丢失 document 接地」的主场景缺陷；该偏离已获 controller 裁定批准。
 *
 * @author commerce-rag
 */
@Component
public class DocumentAssemblerInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DocumentAssemblerInterceptor.class);

    /** metadata/context 键：检索节点写入的 <document> 文本（RetrieveNode 与拦截器共享） */
    public static final String KEY_DOCUMENT_CONTEXT = "document_context";

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

        // 追加独立 document UserMessage（消息末尾，与用户原文分离；每轮调用都重注——瞬时注入不落 state，无累积重复）
        List<Message> messages = new ArrayList<>(request.getMessages());
        messages.add(new UserMessage(String.valueOf(ctx.get(KEY_DOCUMENT_CONTEXT))));

        log.debug(
                "已注入 document 上下文（{} 字符）",
                String.valueOf(ctx.get(KEY_DOCUMENT_CONTEXT)).length());

        return handler.call(ModelRequest.builder(request).messages(messages).build());
    }
}
