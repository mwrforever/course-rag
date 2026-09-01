package com.commerce.rag.properties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * QU（Query Understanding）流水线配置属性 —— 绑定 YAML 路径 {@code rag.query-understanding.*}
 *
 * <p>BUG-12：{@code model} 与 {@code maxQueries} 原 {@code bot/rewrite/QueryUnderstandingService}
 * 经 {@code @Value} 散落注入，并入本属性类统一强类型绑定（宪法 A.2.2）；默认值与原兜底值相同。
 *
 * <p>核心约束：{@code streamTimeout} 是 QU 流式思考聚合的**硬超时**，必须有界。
 * 实证依据（2026-08-28 评审 C1）：
 * <ul>
 *   <li>旧 {@code .call()} 同步路径走 JDK HttpClient 阻塞栈，SDK 硬编码 read 180s，天然有界；</li>
 *   <li>{@code chatModel.stream()} 走 WebClient 响应式栈，SDK responseTimeout 60s 仅覆盖至
 *       响应建立（reactor-netty read timeout 以收到响应头为止），**chunk 间静默无 idle 保护**——
 *       模型 hang 在两个 chunk 之间时流永不终结；</li>
 *   <li>worker 外层 {@code blockLast(Duration.ofMinutes(5))} 与 QU 节点内层阻塞在同一线程栈上，
 *       内层不返回外层不可达，救不了节点内 hang；runPool core=max=8，数次挂死即对话管线瘫痪。</li>
 * </ul>
 * 因此 {@code QueryUnderstandingService.streamContent} 必须以 {@code blockLast(streamTimeout)}
 * 自界，超时抛 IllegalStateException 落入既有降级链（CAS 关思考态 → QueryPlan.fallback，
 * unknown 不拒答）。
 *
 * <p>注记：jakarta @Positive 无 Duration 支持实现（HV000030，RegisterProperties 同款注记），
 * 正性约束由紧凑构造器承载——绑定阶段即抛 IllegalArgumentException，非法配置阻断启动
 * （宪法 A.2.2 语义）。
 *
 * @param model         QU 独立模型名（不复用主对话模型，独立配置；默认 qwen3.7-max-2026-06-08）
 * @param maxQueries    单次 LLM 调用签出的最大重写查询条数（spec §2.2 上限 3；默认 3）
 * @param streamTimeout QU 流式聚合硬超时（chunk 间静默累计上限；必须为正时长），默认 60s——
 *                      对齐响应式栈 transport 量级，正常 QU 输出（短 JSON）远小于此值
 * @author commerce-rag
 */
@Validated
@ConfigurationProperties(prefix = "rag.query-understanding")
public record QueryUnderstandingProperties(
        @DefaultValue("qwen3.7-max-2026-06-08") @NotBlank String model,
        @DefaultValue("3") @Min(1) int maxQueries,
        @DefaultValue("PT60S") Duration streamTimeout) {

    /** 紧凑构造器：正性校验在属性绑定时执行，非法配置直接阻断应用启动 */
    public QueryUnderstandingProperties {
        if (streamTimeout == null || streamTimeout.isNegative() || streamTimeout.isZero()) {
            throw new IllegalArgumentException("rag.query-understanding.stream-timeout 必须为正时长");
        }
    }
}
