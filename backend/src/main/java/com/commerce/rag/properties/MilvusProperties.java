package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Milvus 向量数据库连接调优配置属性（PERF-04）。
 * 绑定 application.yml 中 {@code milvus.rpc-deadline-ms} 配置项。
 *
 * <p>宪法 D.5.10：SDK 显式配置 rpcDeadlineMs——Milvus SDK 默认 rpcDeadlineMs=0 表示
 * 无截止时间，gRPC 调用挂起时调用线程（检索/ETL）会被永久占用；显式设置后挂起从
 * 「线程永久占用」收窄为有界超时。
 *
 * <pre>
 * milvus:
 *   rpc-deadline-ms: 30000
 * </pre>
 *
 * @param rpcDeadlineMs 单次 RPC 调用截止时间（毫秒，默认 30000=30s，从 30s 起步防误杀
 *                     大 batch 插入/检索；允许为空——未配置时走 @DefaultValue 默认值，
 *                     配置来源为 application.yml，运维按部署环境调整）
 */
@Validated
@ConfigurationProperties(prefix = "milvus")
public record MilvusProperties(@Min(1000) @DefaultValue("30000") long rpcDeadlineMs) {}
