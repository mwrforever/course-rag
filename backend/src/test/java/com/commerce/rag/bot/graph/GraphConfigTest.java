package com.commerce.rag.bot.graph;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.commerce.rag.config.GraphConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GraphConfig 单元测试 —— Agent 图基础设施配置（KeyStrategyFactory / CompileConfig）
 *
 * <p>postgresSaver 构造会真实连接 PostgreSQL（PGSimpleDataSource 建连），
 * 属外部依赖，不做单测（由集成环境验证）。
 *
 * @author commerce-rag
 */
@DisplayName("GraphConfig 图基础设施配置测试")
class GraphConfigTest {

    private final GraphConfig config = new GraphConfig();

    @Test
    @DisplayName("keyStrategyFactory → 注册 messages/rewrittenQueries/agent_output/safety_warnings 四键策略")
    void keyStrategyFactory_registersFourKeys() {
        KeyStrategyFactory factory = config.keyStrategyFactory();

        var strategies = factory.apply();
        // 四个 key 均注册（messages 与 safety_warnings 为 Append，其余为 Replace）
        assertEquals(4, strategies.size());
        assertNotNull(strategies.get("messages"));
        assertNotNull(strategies.get("rewrittenQueries"));
        assertNotNull(strategies.get("agent_output"));
        assertNotNull(strategies.get("safety_warnings"));
    }

    @Test
    @DisplayName("compileConfig → 注入 PostgresSaver 构建编译配置")
    void compileConfig_buildsWithSaver() {
        // saver 仅作为参数传入 SaverConfig 注册，mock 即可（不触发建连）
        PostgresSaver saver = mock(PostgresSaver.class);

        CompileConfig compileConfig = config.compileConfig(saver);

        assertNotNull(compileConfig);
    }
}
