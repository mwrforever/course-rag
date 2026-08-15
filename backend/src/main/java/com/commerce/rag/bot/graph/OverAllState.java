package com.commerce.rag.bot.graph;

/**
 * 项目级 State 定义接口 —— 集中声明图状态键名与策略
 *
 * <p>设计文档 §2.2 要求独立接口文件。框架 {@code com.alibaba.cloud.ai.graph.OverAllState}
 * 在 SAA 1.1.2.0 中为 {@code final class}（不可继承），因此本接口作为项目级常量定义，
 * 声明所有 State Key 及其对应的 KeyStrategy。
 *
 * <p>实际运行时仍使用框架的 {@code OverAllState} 实例，KeyStrategyFactory 在
 * {@link com.commerce.rag.bot.graph.GraphConfig} 中以 {@code @Bean} 注册。
 *
 * <h2>State Key 定义</h2>
 * <table border="1">
 *   <tr><th>Key</th><th>策略</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>{@link #KEY_MESSAGES}</td><td>AppendStrategy</td><td>List&lt;Message&gt;</td><td>对话消息（SAA 框架要求 key 名必须 "messages"）</td></tr>
 *   <tr><td>{@link #KEY_REWRITTEN_QUERIES}</td><td>ReplaceStrategy</td><td>List&lt;String&gt;</td><td>查询重写结果（queryRewriteNode 写入）</td></tr>
 *   <tr><td>{@link #KEY_AGENT_OUTPUT}</td><td>ReplaceStrategy</td><td>String</td><td>ReactAgent 最终输出键</td></tr>
 *   <tr><td>{@link #KEY_SAFETY_WARNINGS}</td><td>AppendStrategy</td><td>List&lt;String&gt;</td><td>安全告警队列（WarningHook 写入）</td></tr>
 * </table>
 *
 * @author commerce-rag
 * @see com.commerce.rag.bot.graph.GraphConfig
 */
public interface OverAllState {

    /** State Key: 对话消息列表（SAA 框架要求 key 名必须为 "messages"） */
    String KEY_MESSAGES = "messages";

    /** State Key: 查询重写结果（queryRewriteNode 写入，ReplaceStrategy） */
    String KEY_REWRITTEN_QUERIES = "rewrittenQueries";

    /** State Key: ReactAgent 最终输出键（ReplaceStrategy） */
    String KEY_AGENT_OUTPUT = "agent_output";

    /** State Key: 安全告警队列（WarningHook 写入，AppendStrategy） */
    String KEY_SAFETY_WARNINGS = "safety_warnings";
}
