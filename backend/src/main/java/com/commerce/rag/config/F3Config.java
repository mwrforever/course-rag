package com.commerce.rag.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * F#3 防护层配置注册 —— 注册三组 @ConfigurationProperties 为 Spring Bean
 *
 * <p>注册的配置类：
 * <ul>
 *   <li>{@link LoopDetectionProperties} — rag.loop-detection.*（死循环检测）</li>
 *   <li>{@link TokenBudgetProperties} — rag.token-budget.*（Token 预算）</li>
 *   <li>{@link ConfidenceProperties} — rag.confidence.*（置信度阈值）</li>
 * </ul>
 *
 * <p>遵循项目既有模式：与 {@link WorkerConfig} 一样使用
 * {@code @EnableConfigurationProperties} 注册 @ConfigurationProperties record。
 *
 * @author commerce-rag
 * @see WorkerConfig
 */
@Configuration
@EnableConfigurationProperties({LoopDetectionProperties.class, TokenBudgetProperties.class, ConfidenceProperties.class})
public class F3Config {}
