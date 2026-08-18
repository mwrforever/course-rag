package com.commerce.rag.bot.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * RetrieveNode 测试工具 —— 直调 AsyncNodeActionWithConfig.apply 并同步取结果
 *
 * @author commerce-rag
 */
final class RetrieveNodeTestUtil {

    private RetrieveNodeTestUtil() {}

    static Map<String, Object> apply(RetrieveNode node, OverAllState state, RunnableConfig config) throws Exception {
        CompletableFuture<Map<String, Object>> future = node.apply(state, config);
        return future.get(5, TimeUnit.SECONDS);
    }
}
