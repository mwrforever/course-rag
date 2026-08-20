package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.commerce.rag.service.PreferenceCacheService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 偏好注入拦截器 —— 将 &lt;preference&gt; 块前置注入当次模型请求（spec §7.7）
 *
 * <p>与 {@link DocumentAssemblerInterceptor} 同属 ModelInterceptor（瞬时，不落 state/checkpoint）：
 * 用户偏好作为临时上下文，禁止进入会话状态（spec 设计原则 3）。与 document 拦截器完全分离，
 * 不混用业务。
 *
 * <p><b>注入形态：</b>&lt;preference&gt; 标签 HumanMessage（OWASP LLM01：用户可影响数据不进
 * system）；位置=消息序列最前（紧跟 system prompt 后）——document 在末尾，两者互不冲突。
 *
 * <p><b>传递通道（SAA 源码实锤）：</b>ChatRequestWorker 构建 RunnableConfig 时写入
 * metadata["userId"]（字符串）；AgentLlmNode 构建 ModelRequest 时 context = RunnableConfig.metadata()
 * （同一共享 Map 引用），本拦截器从 {@code request.getContext()} 读取。
 *
 * <p>冻结机制：经 {@link PreferenceCacheService} 取偏好块（Caffeine 30min 冻结，spec §7.8），
 * 缓存期内内容字节不变 → 前缀稳定。无 userId/无偏好 → 原样透传。
 *
 * @author commerce-rag
 */
@Component
public class PreferenceInterceptor extends ModelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PreferenceInterceptor.class);

    /** metadata 键：当前用户 ID（ChatRequestWorker 写入 RunnableConfig.metadata，值 String） */
    public static final String KEY_USER_ID = "userId";

    private final PreferenceCacheService cacheService;

    public PreferenceInterceptor(PreferenceCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public String getName() {
        return "PreferenceInterceptor";
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        Map<String, Object> ctx = request.getContext();
        Object uid = ctx == null ? null : ctx.get(KEY_USER_ID);
        if (!(uid instanceof String userId) || userId.isBlank()) {
            return handler.call(request);
        }
        // 冻结缓存取偏好块（HTTP 与记忆 userId 均为服务端 Long 序列化，parse 安全）
        String block = cacheService.getOrBuild(Long.parseLong(userId));
        if (block == null || block.isBlank()) {
            return handler.call(request);
        }

        // 前置注入：<preference> HumanMessage 置于消息序列最前（紧跟 system 后，spec §7.7）
        List<Message> messages = new ArrayList<>(request.getMessages().size() + 1);
        messages.add(new UserMessage(block));
        messages.addAll(request.getMessages());

        log.debug("已前置注入偏好块（{} 字符）, userId={}", block.length(), userId);
        return handler.call(ModelRequest.builder(request).messages(messages).build());
    }
}
