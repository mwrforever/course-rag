package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.commerce.rag.record.EpisodicMemoryRef;
import com.commerce.rag.service.EpisodicBlockService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 经历记忆注入拦截器 —— 将召回命中时的 &lt;episodic&gt; 块注入当次模型请求（spec §8.8）
 *
 * <p>与 {@link DocumentAssemblerInterceptor} 同源：读取 RetrieveNode 写入 metadata 的召回引用
 * （键 {@link #KEY_EPISODIC_CONTEXT}），组装 &lt;episodic&gt; 块后以 HumanMessage append 到
 * 消息序列末尾（与 document 同区；spec §8.8 仅检索命中时注入，非每轮）。
 *
 * <p>位置说明（本计划裁定 ⑥）：episodic 块随查询变化，置于消息末尾可保住 system + &lt;preference&gt;
 * （偏好块 30min 冻结）的前缀稳定区不被破坏（prefix cache 友好）；与偏好前端注入区解耦。
 *
 * <p>失败降级：metadata 无该键 / 引用为空 / 组装空块 → 原样透传。
 *
 * @author commerce-rag
 */
@Component
public class EpisodicInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EpisodicInterceptor.class);

    /** metadata 键：RetrieveNode 写入的经历记忆召回引用列表 */
    public static final String KEY_EPISODIC_CONTEXT = "episodic_context";

    private final EpisodicBlockService blockService;

    public EpisodicInterceptor(EpisodicBlockService blockService) {
        this.blockService = blockService;
    }

    @Override
    public String getName() {
        return "EpisodicInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> ctx = request.getContext();
        if (ctx == null || !(ctx.get(KEY_EPISODIC_CONTEXT) instanceof List<?> rawList)) {
            return handler.call(request);
        }
        // 召回引用类型过滤（防御 metadata 被污染/类型不符）
        @SuppressWarnings("unchecked")
        List<EpisodicMemoryRef> refs = rawList.stream()
                .filter(EpisodicMemoryRef.class::isInstance)
                .map(EpisodicMemoryRef.class::cast)
                .toList();
        if (refs.isEmpty()) {
            return handler.call(request);
        }
        String block = blockService.build(refs);
        if (block == null || block.isBlank()) {
            return handler.call(request);
        }
        // 消息末尾 append（与 document 同区，spec §8.8）
        List<Message> messages = new ArrayList<>(request.getMessages().size() + 1);
        messages.addAll(request.getMessages());
        messages.add(new UserMessage(block));
        log.debug("已尾部注入经历记忆块（{} 字符）, 引用={}条", block.length(), refs.size());
        return handler.call(ModelRequest.builder(request).messages(messages).build());
    }
}
