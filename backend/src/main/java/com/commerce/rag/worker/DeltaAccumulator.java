package com.commerce.rag.worker;

import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 正文/思考 delta 累加器（2026-08-28 对话流式时间线改版 Task 5）。
 *
 * <p>核心职责：在 worker 推送点（doOnNext transform 后）同步累积 transformer 产出的
 * DELTA / THINKING 事件片段，为取消/错误路径的落库提供「与前端已渲染严格一致」的事实源——
 * 流中断时图 state 往往没有终消息（in-flight 消息只在节点完成点进 state），
 * 直接取 state 汇总会落库空正文或与前端已渲染内容不一致；而本累加器记录的正是
 * 已推送给前端的事件序列，由此保证不变量「终态落库内容 ≡ 已推送事件序列」。
 *
 * <p>与 {@link com.commerce.rag.stream.ThinkingPusher} 的分工：ThinkingPusher 累积的是
 * 图内节点（QU / caption）经瞬时回调通道推送的 understanding / attachments 阶段思考；
 * 本类累积的是 transformer 产出的主链路事件——正文 DELTA 与 generating 阶段 THINKING。
 * 两者来源互斥不重叠，落库时由 ChatRequestWorker.persistMessages 分别消费。
 *
 * <p>不改 {@link com.commerce.rag.stream.SseEventTransformer} 的纯函数性：累积动作完全
 * 发生在 worker 推送点，transformer 仍只做 chunk → 事件的无状态转换。
 *
 * <p>线程安全：doOnNext 由 reactor 串行调用（onNext 串行契约），但落库读取发生在
 * worker 线程（catch 兜底分支），跨线程可见性经 accLock 的 synchronized 读写保证；
 * 快照读取返回深拷贝字符串，不暴露内部 StringBuilder 半写状态。
 *
 * <p>非线程安全的边界说明：单 run 单实例，run 结束随引用释放被 GC，无跨 run 复用。
 */
public class DeltaAccumulator {

    private static final Logger log = LoggerFactory.getLogger(DeltaAccumulator.class);

    /** payload 解析失败日志的事件摘要截断长度（禁止打印完整正文/思考内容） */
    private static final int LOG_PREVIEW_MAX_LENGTH = 60;

    /** payload JSON 解析器（Spring 单例注入，线程安全） */
    private final ObjectMapper objectMapper;

    /** 累加写入与快照读取的互斥锁（跨线程内存可见 + 防 StringBuilder 半写状态被读走） */
    private final Object accLock = new Object();

    /** 正文 delta 累加缓冲（DELTA 事件 payload.text 按推送顺序拼接） */
    private final StringBuilder textAcc = new StringBuilder();

    /** 按 stage 累加的思考缓冲（THINKING 事件 payload.delta，LinkedHashMap 保持首推顺序） */
    private final Map<String, StringBuilder> thinkingAcc = new LinkedHashMap<>();

    /**
     * 构造 per-run delta 累加器。
     *
     * @param objectMapper payload JSON 解析器（worker 持有的 Spring 单例，可为 null——
     *                     null 时所有解析降级失败，累加器恒为空，落库回退 state 汇总）
     */
    public DeltaAccumulator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 在推送点累积一个已转换事件（transform 后调用）。
     *
     * <p>事件映射：DELTA → 正文累加（payload.text）；THINKING → 按 stage 思考累加
     * （payload.delta + payload.stage）；其余事件类型（STAGE、SOURCES、TOOL_CALL、TOOL_RESULT、终态等）
     * 不参与落库正文语义，直接忽略。
     *
     * <p>边界条件：payload 解析失败仅记 warn 跳过该片段（不中断流、不抛异常）——
     * 丢失的片段由落库侧「累加器为空回退 state 汇总」兜底；objectMapper 为 null
     * 同理全部跳过。
     *
     * @param event transformer 产出的待推送事件（可为 null——null 直接忽略）
     */
    public void accumulate(SseEvent event) {
        if (event == null || objectMapper == null) {
            return;
        }
        if (event.type() != SseEventType.DELTA && event.type() != SseEventType.THINKING) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(event.payload());
            if (event.type() == SseEventType.DELTA) {
                // 正文片段：缺失 text 字段视为无效片段跳过
                String text = root.path("text").asText(null);
                if (text == null || text.isEmpty()) {
                    return;
                }
                synchronized (accLock) {
                    textAcc.append(text);
                }
            } else {
                // 思考片段：按 stage 归组累加（缺失 delta 跳过；stage 缺失按空串归组，与 ThinkingPusher 口径一致）
                String delta = root.path("delta").asText(null);
                if (delta == null || delta.isEmpty()) {
                    return;
                }
                String stage = root.path("stage").asText("");
                synchronized (accLock) {
                    thinkingAcc.computeIfAbsent(stage, k -> new StringBuilder()).append(delta);
                }
            }
        } catch (Exception e) {
            // 单片段解析失败不中断流：该片段不进累加器，落库侧按回退兜底
            log.warn(
                    "delta 累加器解析事件 payload 失败，跳过该片段: seqId={}, type={}, payload预览={}, err={}",
                    event.seqId(),
                    event.type(),
                    truncateForLog(event.payload()),
                    e.getMessage());
        }
    }

    /**
     * 取正文累加全文快照（落库用）。
     *
     * <p>适用场景：persistMessages 取消/错误路径的 assistant 正文行事实源；
     * 正常完成路径不消费本方法（state 汇总为权威）。
     *
     * @return 已推送 DELTA 片段按序拼接的全文（从未推送时为空串，never null）
     */
    public String text() {
        synchronized (accLock) {
            return textAcc.toString();
        }
    }

    /**
     * 取指定阶段思考累加全文快照（落库用）。
     *
     * @param stage 思考阶段键（如 generating；null 视为空串键，与 accumulate 归组口径一致）
     * @return 该阶段累加的思考全文；该阶段从未推送时返回 null（调用方按空处理）
     */
    public String thinking(String stage) {
        synchronized (accLock) {
            StringBuilder buf = thinkingAcc.get(stage == null ? "" : stage);
            return buf == null ? null : buf.toString();
        }
    }

    /**
     * 事件 payload 日志摘要截断：仅保留前 {@value #LOG_PREVIEW_MAX_LENGTH} 字符，禁止落完整内容。
     */
    private String truncateForLog(String payload) {
        if (payload == null) {
            return "";
        }
        if (payload.length() <= LOG_PREVIEW_MAX_LENGTH) {
            return payload;
        }
        return payload.substring(0, LOG_PREVIEW_MAX_LENGTH) + "...";
    }
}
