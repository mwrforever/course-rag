package com.commerce.rag.bot.hook;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.commerce.rag.properties.LoopDetectionProperties;
import com.commerce.rag.properties.TokenBudgetProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * 安全告警延迟注入 + F#3 死循环/Token 预算防护 Hook
 *
 * <p><b>BEFORE_MODEL（已有）：</b>排空 {@code State.safety_warnings} 队列，
 * 每条告警注入一条 {@code UserMessage}（SAA 无 HumanMessage 类，用 UserMessage + metadata name 模拟，
 * 对应设计 §4.3），内容为 {@code "⚠️ [告警内容]"}。
 * 延迟一轮注入的原因：避免在同一个模型调用中插入额外消息打破流式输出。
 *
 * <p><b>AFTER_MODEL（F#3 新增）：</b>三套检测逻辑
 * <ol>
 *   <li><b>Token Budget</b>（最高优先级）— 累计 token 估算超限 → 告警/软停</li>
 *   <li><b>Loop Hash</b>（次高优先级）— SHA-256 滑动窗口检测重复内容 → 告警/软停</li>
 *   <li><b>Loop Tool Freq</b>（最低优先级）— per-tool 调用频率计数 → 告警/软停</li>
 * </ol>
 *
 * <p><b>软停行为</b>（不抛异常）：
 * <ul>
 *   <li>清除 toolCalls + metadata 中的 tool_calls/function_call</li>
 *   <li>设置 metadata["finish_reason"] = "stop"</li>
 *   <li>追加 {@code "[FORCED STOP]"} 到消息末尾</li>
 *   <li>返回 UpdatePolicy.REPLACE，让 run 自然结束</li>
 * </ul>
 *
 * <p><b>优先级</b>：TokenBudget HardStop > Loop Hash HardStop > Loop ToolFreq HardStop。
 * 遇到 HardStop 条件时，高优先级先触发，设置标记位阻止低优先级重复处理。
 *
 * @author commerce-rag
 */
@Component
public class WarningHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(WarningHook.class);

    /** 软停标记，追加到消息末尾 */
    private static final String FORCED_STOP_MARKER = "[FORCED STOP]";

    /** metadata 中 finish_reason 的键名 */
    private static final String FINISH_REASON_KEY = "finish_reason";

    /** metadata 中 tool_calls 的键名 */
    private static final String TOOL_CALLS_KEY = "tool_calls";

    /** metadata 中 function_call 的键名 */
    private static final String FUNCTION_CALL_KEY = "function_call";

    /** 当 ThreadState 不可用时，用作降级的本地告警缓冲 */
    private final List<String> fallbackWarnings = new CopyOnWriteArrayList<>();

    /** F#3 死循环检测配置 */
    private final LoopDetectionProperties loopDetectionProperties;

    /** F#3 Token 预算配置 */
    private final TokenBudgetProperties tokenBudgetProperties;

    /**
     * Per-thread 检测状态映射
     * key = threadId, value = 该线程的检测状态（滑动窗口 + 计数器 + token 累计）
     */
    private final ConcurrentHashMap<String, DetectionState> detectionStates = new ConcurrentHashMap<>();

    /**
     * 构造函数 —— Spring 自动注入 F#3 配置
     *
     * @param loopDetectionProperties 死循环检测配置
     * @param tokenBudgetProperties   Token 预算配置
     */
    public WarningHook(LoopDetectionProperties loopDetectionProperties, TokenBudgetProperties tokenBudgetProperties) {
        this.loopDetectionProperties = loopDetectionProperties;
        this.tokenBudgetProperties = tokenBudgetProperties;
    }

    @Override
    public String getName() {
        return "WarningHook";
    }

    // ==================== BEFORE_MODEL：告警延迟注入（保持不变） ====================

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        // 1. 读取告警队列
        List<String> warnings = drainWarnings(config);

        if (warnings.isEmpty()) {
            // 无需变更，返回原 messages
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        // 2. 每条告警注入一条 UserMessage（SAA 无 HumanMessage 类，Spring AI 用 UserMessage；
        //    UserMessage 不支持构造时设置 name 参数，通过 metadata name 模拟 HumanMessage(name=)，
        //    这是 SAA/Spring AI 框架 API 限制下的变通方案，对应设计 §4.3）
        List<Message> newMessages = new ArrayList<>(messages);
        for (String warning : warnings) {
            String text = "⚠️ [" + warning + "]";
            UserMessage msg = new UserMessage(text);
            msg.getMetadata().put("name", "loop_warning");
            newMessages.add(msg);
        }

        log.warn("已注入 {} 条安全告警", warnings.size());
        return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
    }

    // ==================== AFTER_MODEL：F#3 三套检测逻辑 ====================

    /**
     * AFTER_MODEL 回调 —— 模型输出后执行三套检测
     *
     * <p>检测优先级（HardStop 同序 Warn）：
     * <ol>
     *   <li>Token Budget（最高优先级）</li>
     *   <li>Loop Hash（次高优先级）</li>
     *   <li>Loop Tool Frequency（最低优先级）</li>
     * </ol>
     * 遇到 HardStop 时，高优先级先触发并设置标记位，阻止低优先级重复处理。
     *
     * @param messages 模型调用后的完整消息列表（最后一条为模型输出）
     * @param config   运行配置（含 threadId）
     * @return AgentCommand（可能含软停后的修改消息）
     */
    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        if (messages == null || messages.isEmpty()) {
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        // 获取最后一条消息（模型输出）
        Message lastMessage = messages.get(messages.size() - 1);
        if (!(lastMessage instanceof AssistantMessage assistantMsg)) {
            // 非 AssistantMessage（如 ToolResponseMessage），跳过检测
            return new AgentCommand(messages, UpdatePolicy.REPLACE);
        }

        // 获取或创建 per-thread 检测状态
        String threadId = resolveThreadId(config);
        DetectionState state = detectionStates.computeIfAbsent(threadId, k -> new DetectionState());

        List<String> warnings = new ArrayList<>();
        boolean hardStop = false;
        String hardStopReason = "";

        // ── 1. Token Budget 检测（最高优先级）──
        TokenCheckResult tokenResult = checkTokenBudget(messages, state);
        if (tokenResult.hardStop) {
            hardStop = true;
            hardStopReason = tokenResult.hardStopReason;
        }
        if (tokenResult.warning != null) {
            warnings.add(tokenResult.warning);
        }

        // ── 2. Loop Hash 检测（次高优先级，Token 未硬停时才检查）──
        if (!hardStop) {
            HashCheckResult hashResult = checkLoopHash(assistantMsg, state);
            if (hashResult.hardStop) {
                hardStop = true;
                hardStopReason = hashResult.hardStopReason;
            }
            if (hashResult.warning != null) {
                warnings.add(hashResult.warning);
            }
        }

        // ── 3. Loop Tool Frequency 检测（最低优先级，未硬停时才检查）──
        if (!hardStop) {
            ToolFreqCheckResult toolResult = checkLoopToolFreq(assistantMsg, state);
            if (toolResult.hardStop) {
                hardStop = true;
                hardStopReason = toolResult.hardStopReason;
            }
            if (toolResult.warning != null) {
                warnings.add(toolResult.warning);
            }
        }

        // ── 写入告警到 State.safety_warnings ──
        for (String warning : warnings) {
            writeWarning(config, warning);
        }

        // ── 软停处理 ──
        if (hardStop) {
            List<Message> newMessages = applySoftStop(messages, assistantMsg, hardStopReason);
            // 软停后 run 将自然结束，清理 per-thread 检测状态
            cleanupThreadState(threadId);
            return new AgentCommand(newMessages, UpdatePolicy.REPLACE);
        }

        // ── 自然结束清理：模型无 tool_calls 表示 run 即将结束 ──
        if (!assistantMsg.hasToolCalls()) {
            cleanupThreadState(threadId);
        }

        return new AgentCommand(messages, UpdatePolicy.REPLACE);
    }

    // ==================== 检测逻辑 ====================

    /**
     * Token Budget 检测 —— 累计 token 估算超限
     *
     * <p>估算方式：消息列表总字符数 / 4 ≈ token 数
     *
     * @param messages 完整消息列表
     * @param state    per-thread 检测状态
     * @return 检测结果（warning / hardStop）
     */
    private TokenCheckResult checkTokenBudget(List<Message> messages, DetectionState state) {
        // 估算 token 用量（4 字符 ≈ 1 token）
        long estimatedTokens = messages.stream()
                .mapToLong(m -> m.getText() != null ? m.getText().length() / 4L : 0)
                .sum();
        // 取历史最大值（防止摘要压缩后 token 数下降导致误判）
        state.totalTokens = Math.max(state.totalTokens, estimatedTokens);

        long maxTokens = tokenBudgetProperties.maxTokensPerRun();
        long warnThreshold = (long) (maxTokens * tokenBudgetProperties.warnRatio());
        long hardStopThreshold = (long) (maxTokens * tokenBudgetProperties.hardStopRatio());

        if (state.totalTokens >= hardStopThreshold) {
            log.warn("Token 预算硬停: {} >= {}", state.totalTokens, hardStopThreshold);
            return TokenCheckResult.hardStop("Token 预算超限: " + state.totalTokens + " >= " + hardStopThreshold);
        }

        if (state.totalTokens >= warnThreshold) {
            log.warn("Token 预算告警: {} / {}", state.totalTokens, maxTokens);
            return TokenCheckResult.warn("Token 预算告警: 已用 " + state.totalTokens + " / " + maxTokens);
        }

        return TokenCheckResult.ok();
    }

    /**
     * Loop Hash 检测 —— SHA-256 滑动窗口检测重复内容
     *
     * <p>每次模型输出后，计算 AssistantMessage 文本的 SHA-256 hash，
     * 统计滑动窗口内相同 hash 出现次数。
     *
     * @param assistantMsg 模型输出的 AssistantMessage
     * @param state        per-thread 检测状态
     * @return 检测结果（warning / hardStop）
     */
    private HashCheckResult checkLoopHash(AssistantMessage assistantMsg, DetectionState state) {
        String content = assistantMsg.getText();
        if (content == null || content.isBlank()) {
            return HashCheckResult.ok();
        }

        // 计算 SHA-256 hash
        String hash = sha256(content);

        // 添加到滑动窗口
        LinkedList<String> window = state.hashWindow;
        window.addLast(hash);

        // 维护窗口大小
        int windowSize = loopDetectionProperties.hash().windowSize();
        while (window.size() > windowSize) {
            window.removeFirst();
        }

        // 统计窗口内相同 hash 出现次数
        long count = window.stream().filter(h -> h.equals(hash)).count();

        int warnThreshold = loopDetectionProperties.hash().warn();
        int hardStopThreshold = loopDetectionProperties.hash().hardStop();

        if (count >= hardStopThreshold) {
            log.warn("循环检测(hash) 硬停: 重复内容出现 {} 次", count);
            return HashCheckResult.hardStop("循环检测(hash): 重复内容出现 " + count + " 次");
        }

        if (count >= warnThreshold) {
            log.warn("循环检测(hash) 告警: 重复内容出现 {} 次", count);
            return HashCheckResult.warn("循环告警(hash): 重复内容出现 " + count + " 次");
        }

        return HashCheckResult.ok();
    }

    /**
     * Loop Tool Frequency 检测 —— per-tool 调用频率计数
     *
     * <p>每次 AFTER_MODEL 检查 AssistantMessage 的 toolCalls，
     * 对每个工具调用递增计数器，超过阈值时告警/软停。
     *
     * @param assistantMsg 模型输出的 AssistantMessage
     * @param state        per-thread 检测状态
     * @return 检测结果（warning / hardStop）
     */
    private ToolFreqCheckResult checkLoopToolFreq(AssistantMessage assistantMsg, DetectionState state) {
        if (!assistantMsg.hasToolCalls()) {
            return ToolFreqCheckResult.ok();
        }

        List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return ToolFreqCheckResult.ok();
        }

        String firstHardStopReason = null;
        List<String> warnings = new ArrayList<>();

        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            if (toolName == null || toolName.isBlank()) {
                continue;
            }

            // 递增 per-tool 计数器
            AtomicInteger counter = state.toolCounters.computeIfAbsent(toolName, k -> new AtomicInteger(0));
            int count = counter.incrementAndGet();

            // 获取该工具的有效阈值（override 或 default）
            LoopDetectionProperties.ToolThreshold threshold = loopDetectionProperties.getThreshold(toolName);

            if (count >= threshold.hardStop() && firstHardStopReason == null) {
                firstHardStopReason = "循环检测(工具): " + toolName + " 调用 " + count + " 次（阈值 " + threshold.hardStop() + "）";
                log.warn("循环检测(工具) 硬停: {} 调用 {} 次", toolName, count);
            } else if (count >= threshold.warn()) {
                String warning = "循环告警(工具): " + toolName + " 调用 " + count + " 次（阈值 " + threshold.warn() + "）";
                warnings.add(warning);
                log.warn("循环检测(工具) 告警: {} 调用 {} 次", toolName, count);
            }
        }

        if (firstHardStopReason != null) {
            // 硬停时合并所有告警
            return ToolFreqCheckResult.hardStop(firstHardStopReason, warnings);
        }

        if (!warnings.isEmpty()) {
            return ToolFreqCheckResult.warn(String.join("; ", warnings));
        }

        return ToolFreqCheckResult.ok();
    }

    // ==================== 软停行为 ====================

    /**
     * 应用软停 —— 清除 tool_calls，追加 [FORCED STOP]，不抛异常
     *
     * <p>三处同清：
     * <ol>
     *   <li>{@code message.getToolCalls()} → 创建新 AssistantMessage，toolCalls 设为空</li>
     *   <li>{@code metadata["tool_calls"]} → 从 metadata 中移除</li>
     *   <li>{@code metadata["function_call"]} → 从 metadata 中移除</li>
     * </ol>
     * 然后设置 {@code metadata["finish_reason"] = "stop"}，追加 {@code [FORCED STOP]} 到消息末尾。
     *
     * @param messages      完整消息列表
     * @param original      原始 AssistantMessage
     * @param hardStopReason 软停原因
     * @return 修改后的消息列表（最后一条替换为软停后的 AssistantMessage）
     */
    private List<Message> applySoftStop(List<Message> messages, AssistantMessage original, String hardStopReason) {
        List<Message> newMessages = new ArrayList<>(messages);
        int lastIndex = newMessages.size() - 1;

        // 构建新内容：原内容 + [FORCED STOP] + 原因
        String originalContent = original.getText() != null ? original.getText() : "";
        String newContent = originalContent + "\n\n" + FORCED_STOP_MARKER + " " + hardStopReason;

        // 复制 metadata，清除 tool_calls 和 function_call，设置 finish_reason
        Map<String, Object> newMetadata = new HashMap<>();
        if (original.getMetadata() != null) {
            newMetadata.putAll(original.getMetadata());
        }
        newMetadata.remove(TOOL_CALLS_KEY);
        newMetadata.remove(FUNCTION_CALL_KEY);
        newMetadata.put(FINISH_REASON_KEY, "stop");

        // 使用 builder 创建新的 AssistantMessage（空 toolCalls）
        AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(newContent)
                .properties(newMetadata)
                .toolCalls(List.of());

        // 保留原 media（如果有）
        if (original.getMedia() != null && !original.getMedia().isEmpty()) {
            builder.media(original.getMedia());
        }

        AssistantMessage newMsg = builder.build();
        newMessages.set(lastIndex, newMsg);

        log.warn("触发软停: {}", hardStopReason);
        return newMessages;
    }

    // ==================== 告警写入 ====================

    /**
     * 写入告警到 ThreadState.safety_warnings 队列
     *
     * <p>通过 CompiledGraph.updateState 方法写入，走 State 的 AppendStrategy reducer，
     * 而非直接 mutate threadState Map 中的 List。
     *
     * <p>如果 ThreadState 不可用，降级写入本地 fallbackWarnings 缓冲。
     *
     * @param config  运行配置
     * @param warning 告警内容
     */
    private void writeWarning(RunnableConfig config, String warning) {
        try {
            if (getAgent() != null && config != null && config.threadId().isPresent()) {
                // 通过 CompiledGraph.updateState 走 State reducer（AppendStrategy），
                // 而非直接 mutate threadState Map 中的 List
                getAgent().getCompiledGraph().updateState(config, Map.of("safety_warnings", List.of(warning)));
                log.debug("告警写入 safety_warnings (via updateState): {}", warning);
                return;
            }
        } catch (Exception e) {
            log.debug("写入 safety_warnings 失败: {}", e.getMessage());
        }
        // 降级：写入本地缓冲
        addWarningFallback(warning);
    }

    /**
     * 排空告警队列
     *
     * <p>读取后通过 {@code CompiledGraph.updateState} 走 State reducer 清空
     * {@code safety_warnings}，确保 checkpoint 一致（而非直接 mutate threadState List）。
     */
    @SuppressWarnings("unchecked")
    private List<String> drainWarnings(RunnableConfig config) {
        List<String> warnings = new ArrayList<>();
        try {
            if (getAgent() != null && config != null && config.threadId().isPresent()) {
                Map<String, Object> threadState =
                        getAgent().getThreadState(config.threadId().get());
                if (threadState != null && threadState.containsKey("safety_warnings")) {
                    List<String> stateWarnings = (List<String>) threadState.get("safety_warnings");
                    if (stateWarnings != null && !stateWarnings.isEmpty()) {
                        warnings.addAll(stateWarnings);
                        // 通过 CompiledGraph.updateState 走 reducer 清空，确保 checkpoint 一致
                        getAgent()
                                .getCompiledGraph()
                                .updateState(config, Map.of("safety_warnings", Collections.emptyList()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("读取 safety_warnings 失败: {}", e.getMessage());
        }

        // 降级：读取本地缓冲
        if (warnings.isEmpty() && !fallbackWarnings.isEmpty()) {
            warnings.addAll(fallbackWarnings);
            fallbackWarnings.clear();
        }

        return warnings;
    }

    /**
     * 降级路径：当 State 不可用时，通过此方法写入本地缓冲
     */
    public void addWarningFallback(String warning) {
        fallbackWarnings.add(warning);
        log.debug("告警写入本地缓冲: {}", warning);
    }

    // ==================== Per-thread 状态管理 ====================

    /**
     * 解析 threadId，无配置时使用 "default"
     */
    private String resolveThreadId(RunnableConfig config) {
        if (config != null && config.threadId().isPresent()) {
            return config.threadId().get();
        }
        return "default";
    }

    /**
     * 清理指定线程的检测状态（run 结束后调用）
     *
     * @param threadId 线程 ID
     */
    private void cleanupThreadState(String threadId) {
        DetectionState removed = detectionStates.remove(threadId);
        if (removed != null) {
            log.debug(
                    "清理 threadId={} 的检测状态: hashWindow={}, toolCounters={}",
                    threadId,
                    removed.hashWindow.size(),
                    removed.toolCounters.size());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 计算 SHA-256 hash
     *
     * @param input 输入字符串
     * @return SHA-256 十六进制 hash 字符串
     */
    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashBytes.length * 2);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 内置算法，理论上不会抛出
            log.error("SHA-256 算法不可用，降级使用 hashCode", e);
            return Integer.toHexString(input.hashCode());
        }
    }

    // ==================== 检测结果记录类 ====================

    /** Token Budget 检测结果 */
    private record TokenCheckResult(boolean hardStop, String hardStopReason, String warning) {
        static TokenCheckResult ok() {
            return new TokenCheckResult(false, null, null);
        }

        static TokenCheckResult warn(String warning) {
            return new TokenCheckResult(false, null, warning);
        }

        static TokenCheckResult hardStop(String reason) {
            return new TokenCheckResult(true, reason, null);
        }
    }

    /** Loop Hash 检测结果 */
    private record HashCheckResult(boolean hardStop, String hardStopReason, String warning) {
        static HashCheckResult ok() {
            return new HashCheckResult(false, null, null);
        }

        static HashCheckResult warn(String warning) {
            return new HashCheckResult(false, null, warning);
        }

        static HashCheckResult hardStop(String reason) {
            return new HashCheckResult(true, reason, null);
        }
    }

    /** Loop Tool Frequency 检测结果 */
    private record ToolFreqCheckResult(boolean hardStop, String hardStopReason, String warning) {
        static ToolFreqCheckResult ok() {
            return new ToolFreqCheckResult(false, null, null);
        }

        static ToolFreqCheckResult warn(String warning) {
            return new ToolFreqCheckResult(false, null, warning);
        }

        static ToolFreqCheckResult hardStop(String reason, List<String> warnings) {
            // 硬停时将告警合并到 reason 中
            String fullReason = reason;
            if (warnings != null && !warnings.isEmpty()) {
                fullReason = reason + " | " + String.join("; ", warnings);
            }
            return new ToolFreqCheckResult(true, fullReason, null);
        }
    }

    /**
     * Per-thread 检测状态
     */
    private static class DetectionState {
        /** hash 滑动窗口（存储 AssistantMessage 文本的 SHA-256 hash） */
        final LinkedList<String> hashWindow = new LinkedList<>();
        /** per-tool 调用计数器（key = 工具名, value = 调用次数） */
        final ConcurrentHashMap<String, AtomicInteger> toolCounters = new ConcurrentHashMap<>();
        /** 累计 token 估算值（取历史最大值，防止摘要压缩后误判） */
        volatile long totalTokens = 0;
    }
}
