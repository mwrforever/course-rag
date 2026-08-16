package com.commerce.rag.config;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactory;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql.PostgresSaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.commerce.rag.bot.graph.LeadAgentGraph;
import java.sql.SQLException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 图基础设施配置 —— PostgresSaver & KeyStrategyFactory & CompileConfig
 *
 * <p>PostgresSaver 负责图执行状态的 checkpoint 持久化（中断恢复）。
 * KeyStrategyFactory 定义 OverAllState 每个 key 的 reducer 策略。
 *
 * <p>注意：SAA 框架类名是 {@code PostgresSaver}（非 PostgreSqlSaver），
 * 位于 {@code com.alibaba.cloud.ai.graph.checkpoint.savers.postgresql} 包。
 *
 * @author commerce-rag
 */
@Configuration
public class GraphConfig {

    /**
     * PostgreSQL checkpoint saver。
     * 使用与业务库同一个数据库实例；checkpoint 三表 DDL 由 Flyway V7 管理。
     */
    @Bean
    public PostgresSaver postgresSaver(
            @Value("${spring.datasource.url}") String jdbcUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password)
            throws SQLException {
        // 解析 JDBC URL: jdbc:postgresql://host:port/database?params
        String afterScheme = jdbcUrl.substring(jdbcUrl.indexOf("://") + 3);
        int slashIdx = afterScheme.indexOf("/");
        String hostPort = slashIdx > 0 ? afterScheme.substring(0, slashIdx) : afterScheme;
        String db = slashIdx > 0 ? afterScheme.substring(slashIdx + 1) : "";
        if (db.contains("?")) {
            db = db.substring(0, db.indexOf("?"));
        }
        int colonIdx = hostPort.indexOf(":");
        String host = colonIdx > 0 ? hostPort.substring(0, colonIdx) : hostPort;
        int port = colonIdx > 0 ? Integer.parseInt(hostPort.substring(colonIdx + 1)) : 5432;
        return PostgresSaver.builder()
                .host(host)
                .port(port)
                .user(username)
                .password(password)
                .database(db)
                // checkpoint 三表 DDL 由 Flyway V7 幂等管理（SAA 1.1.2.0 自动建表
                // 的 CREATE INDEX 无 IF NOT EXISTS，二次启动必然报 already exists），
                // 此处关闭框架自动建表
                .createTables(false)
                .dropTablesFirst(false)
                .build();
    }

    /**
     * State Key 策略工厂 —— 定义 OverAllState 4 个 key 的 reducer 行为。
     *
     * <ul>
     *   <li><code>messages</code> — AppendStrategy（对话消息累积，SAA 框架要求 key 名必须 "messages"）</li>
     *   <li><code>rewrittenQueries</code> — ReplaceStrategy（每次 run 替换）</li>
     *   <li><code>agent_output</code> — ReplaceStrategy（ReactAgent 最终输出）</li>
     *   <li><code>safety_warnings</code> — AppendStrategy（安全告警队列累积）</li>
     * </ul>
     *
     * <p>SAA 内置策略仅 2 种：ReplaceStrategy + AppendStrategy（无 MergeStrategy）。
     */
    @Bean
    public KeyStrategyFactory keyStrategyFactory() {
        return new KeyStrategyFactoryBuilder()
                .addStrategy("messages", new AppendStrategy())
                .addStrategy("rewrittenQueries", new ReplaceStrategy())
                .addStrategy("agent_output", new ReplaceStrategy())
                .addStrategy("safety_warnings", new AppendStrategy())
                .build();
    }

    /**
     * 编译配置 —— 通过 SaverConfig 注入 PostgresSaver。
     * PostgresSaver 配在 StateGraph 的 CompileConfig，而非 ReactAgent.builder().saver()。
     */
    @Bean
    public CompileConfig compileConfig(PostgresSaver postgresSaver) {
        SaverConfig saverConfig = SaverConfig.builder().register(postgresSaver).build();
        return CompileConfig.builder().saverConfig(saverConfig).build();
    }

    /**
     * 编译后的 Agent 图 —— 单例，所有请求复用
     *
     * <p>图构建业务逻辑在 {@link LeadAgentGraph#build()}（bot/graph/ 模块），
     * 此处仅做 Bean 注册（工程宪法：Bean 注册统一在 config/ 管理）。
     */
    @Bean("leadAgent")
    public CompiledGraph leadAgent(LeadAgentGraph leadAgentGraph) throws Exception {
        return leadAgentGraph.build();
    }
}
