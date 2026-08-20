package com.commerce.rag.service;

import com.commerce.rag.properties.MemoryProperties;
import com.commerce.rag.record.EpisodicExtractionResult;
import com.commerce.rag.record.ExtractionInput;
import com.commerce.rag.record.PreferenceCandidate;
import com.commerce.rag.record.PreferenceExtractionResult;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;

/**
 * 记忆提取流水线 —— run 完成后异步触发 + 30s 防抖 + 独立线程池（偏好 spec §7.6 / 经历 spec §8.4）
 *
 * <p>双通道：偏好提取与经历记忆提取各持独立 pending/futures Map + 独立执行器，
 * 同 userId 同窗口互不取消对方（spec §8.4 两流水线互不阻塞），共用同一防抖调度器。
 *
 * <p>机制：
 * <ol>
 *   <li>{@link #submit}：偏好通道，按 user_id 投递，窗口内同用户消息合并（最新语义覆盖，等价防抖去重）；
 *       重复调度会取消上一个 ScheduledFuture（ScheduledThreadPoolExecutor.cancel 语义）</li>
 *   <li>{@link #submitEpisodic}：经历通道，同样按 user_id 投递 + 30s 防抖合并，pending/futures 独立于偏好通道</li>
 *   <li>窗口到期 → {@link #execute}（偏好）/ {@link #executeEpisodic}（经历）：取最新批次 → 组装提取输入
 *       → 已读记忆（偏好同义收敛 / 经历 merge_target 参考）→ 提取 LLM（CompletableFuture + get(timeout) 超时）
 *       → 决策 → PG 原子写（applyExtraction 事务）</li>
 *   <li>失败降级：提取失败/JSON 解析失败/超时 → 丢弃本批 + 记日志，不重试、不影响主链路</li>
 * </ol>
 *
 * <p>线程模型：偏好/经历各独立执行小线程池（不占 runPool/ETL 线程、互不阻塞），
 * 共用同一防抖调度器 {@code memory.extraction.threads}，调度器与执行器 daemon 线程随 JVM 退出。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class MemoryExtractionPipeline {

    private final ScheduledExecutorService scheduler;
    private final ExecutorService extractionExecutor;
    private final int windowSeconds;
    private final long timeoutMs;

    private final MemoryExtractionInputAssembler inputAssembler;
    private final PreferenceExtractionService extractionService;
    private final IPreferenceService preferenceService;
    private final EpisodicExtractionService episodicExtractionService;
    private final IEpisodicMemoryService episodicMemoryService;

    /** 每用户待处理消息（key=userId，latest wins 防抖合并） */
    private final Map<Long, List<Message>> pending = new ConcurrentHashMap<>();
    /** 每用户已调度的执行任务（重复 submit 取消旧任务） */
    private final Map<Long, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    /** 每用户待处理的经历记忆消息（key=userId，latest wins 防抖合并；独立于偏好通道） */
    private final Map<Long, List<Message>> pendingEpisodic = new ConcurrentHashMap<>();
    /** 每用户已调度的经历记忆执行任务 */
    private final Map<Long, ScheduledFuture<?>> futuresEpisodic = new ConcurrentHashMap<>();
    /** 经历记忆提取执行器（独立线程池，与偏好互不阻塞，spec §8.4） */
    private final ExecutorService episodicExecutor;

    /**
     * 手写构造器：需要在构造内完成属性绑定 + 创建独立线程池（调度/执行分离，偏好/经历执行器互不阻塞），
     * 因此不使用 @RequiredArgsConstructor 生成式构造器。
     *
     * @param properties                   记忆体系配置（extraction 段：防抖窗口/超时/线程数）
     * @param inputAssembler               提取输入组装器
     * @param extractionService            偏好提取服务（LLM 提取 + JSON 解析）
     * @param preferenceService            偏好服务（已读偏好 + 决策落库）
     * @param episodicExtractionService    经历记忆提取服务（LLM 提取 + JSON 解析，spec §8.4）
     * @param episodicMemoryService        经历记忆服务（已读记忆 + 决策落库，spec §8.5）
     */
    public MemoryExtractionPipeline(
            MemoryProperties properties,
            MemoryExtractionInputAssembler inputAssembler,
            PreferenceExtractionService extractionService,
            IPreferenceService preferenceService,
            EpisodicExtractionService episodicExtractionService,
            IEpisodicMemoryService episodicMemoryService) {
        this.windowSeconds = properties.getExtraction().getDebounceWindowSeconds();
        this.timeoutMs = properties.getExtraction().getTimeoutMs();
        this.inputAssembler = inputAssembler;
        this.extractionService = extractionService;
        this.preferenceService = preferenceService;
        this.episodicExtractionService = episodicExtractionService;
        this.episodicMemoryService = episodicMemoryService;
        int threads = Math.max(1, properties.getExtraction().getThreads());
        // 防抖调度器：偏好/经历共用（memory.extraction.threads 线程数，pairwise 独立通道避免互相阻塞）
        this.scheduler = Executors.newScheduledThreadPool(threads, r -> {
            Thread t = new Thread(r, "memory-extract-");
            t.setDaemon(true);
            return t;
        });
        // 偏好提取执行器（独立线程池，spec §7.6）
        this.extractionExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "memory-extract-call-");
            t.setDaemon(true);
            return t;
        });
        // 经历提取执行器（独立线程池，与偏好互不阻塞，spec §8.4）
        this.episodicExecutor = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "episodic-extract-call-");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        extractionExecutor.shutdownNow();
        episodicExecutor.shutdownNow();
    }

    /**
     * 投递一次 run 完成的提取请求（run COMPLETED 后由 worker 调用，Task 9 消费 submit()）
     *
     * @param userId   所属用户（硬隔离过滤键）
     * @param messages 本次 run 消息列表（自最终 state 读取；空/空消息直接跳过）
     */
    public void submit(Long userId, List<Message> messages) {
        if (userId == null || messages == null || messages.isEmpty()) {
            log.debug("偏好提取跳过: 无有效输入 userId={}", userId);
            return;
        }
        // 深拷贝源列表（调用方 state 可能被后续回收，防抖窗口内独立持有）
        pending.put(userId, new ArrayList<>(messages));
        // 取消上一窗口任务并由最新调度取代（30s 防抖合并，spec §7.6）
        ScheduledFuture<?> prev = futures.get(userId);
        if (prev != null) {
            prev.cancel(false);
        }
        futures.put(userId, scheduler.schedule(() -> execute(userId), windowSeconds, TimeUnit.SECONDS));
        log.debug("偏好提取已投递，防抖窗口 {}s: userId={}", windowSeconds, userId);
    }

    /** 调度到期的执行入口（防抖合并后取最新批次，仅保留每用户一份待执行任务） */
    void execute(Long userId) {
        futures.remove(userId);
        List<Message> messages = pending.remove(userId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        executeInternal(userId, messages);
    }

    /**
     * 执行提取-决策-落库链路（真实调度与直测共用，包可见供单测直测）
     *
     * @param userId   所属用户
     * @param messages 本批消息（最新语义）
     */
    void executeInternal(Long userId, List<Message> messages) {
        try {
            ExtractionInput input = inputAssembler.build(messages);
            if (input.currentText() == null || input.currentText().isBlank()) {
                log.debug("偏好提取跳过: 无当前对话 userId={}", userId);
                return;
            }
            String existing = preferenceService.findExistingValuesText(userId);
            // 提取 LLM 调用（独立执行器 + 超时控制，spec §7.6：同步 + 超时默认 10s）
            Future<PreferenceExtractionResult> future =
                    CompletableFuture.supplyAsync(() -> extractionService.extract(input, existing), extractionExecutor);
            PreferenceExtractionResult result;
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("偏好提取超时，丢弃本批: userId={}, timeoutMs={}", userId, timeoutMs);
                future.cancel(true);
                return;
            }
            if (result == null
                    || (result.candidates().isEmpty() && result.deletions().isEmpty())) {
                log.debug("偏好提取无候选: userId={}", userId);
                return;
            }
            // M-5: 同 (key,value) 重复候选去重保首（顺序不变）；同 key 异质 value 保留
            // （单值唯一索引冲突由 applyExtraction 事务原子回滚，spec §7.1 §7.5）
            List<PreferenceCandidate> candidates = dedupeCandidates(result.candidates());
            int written = preferenceService.applyExtraction(
                    userId, new PreferenceExtractionResult(candidates, result.deletions()));
            log.info("偏好提取流水线完成: userId={}, 生效动作={}, 候选去重后={}", userId, written, candidates.size());
        } catch (Exception e) {
            // 失败降级：丢弃本批 + 记日志，不重试、不影响主链路（spec §7.6）
            log.warn("偏好提取失败，丢弃本批: userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 按 (key,value) 去重候选（保首，顺序不变）
     *
     * <p>用于同一批 LLM 候选中的完全重复项（同 key 同 value 仅保留第一条）；
     * 同 key 不同 explicit value 视为不同偏好意图，原样保留（交 applyExtraction 决策）。
     *
     * @param candidates 原始候选列表
     * @return 去重后候选列表（顺序与首次出现位置一致）
     */
    private List<PreferenceCandidate> dedupeCandidates(List<PreferenceCandidate> candidates) {
        Set<String> seen = new HashSet<>();
        List<PreferenceCandidate> out = new ArrayList<>();
        for (PreferenceCandidate c : candidates) {
            // key 与 value 以分隔符拼接成唯一键，避免 key/value 拼接歧义
            if (seen.add(c.key() + "\u0000" + c.value())) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * 投递一次 run 完成的经历记忆提取请求（run COMPLETED 后由 worker 调用，spec §8.4 独立触发）
     *
     * <p>与偏好通道 {@link #submit} 同构：按 userId 防抖合并（latest wins），独立 pending/futures 表，
     * 同 userId 同窗口与偏好通道互不取消（spec §8.4 两流水线互不阻塞）。
     *
     * @param userId    所属用户（硬隔离过滤键）
     * @param sessionId 来源会话 ID（记忆 source_session_id 落库）
     * @param messages  本次 run 消息列表（空/空消息直接跳过）
     */
    public void submitEpisodic(Long userId, Long sessionId, List<Message> messages) {
        if (userId == null || messages == null || messages.isEmpty()) {
            log.debug("经历记忆提取跳过: 无有效输入 userId={}", userId);
            return;
        }
        // 深拷贝源列表（调用方 state 可能被后续回收，防抖窗口内独立持有）
        pendingEpisodic.put(userId, new ArrayList<>(messages));
        // 取消上一窗口任务并由最新调度取代（30s 防抖合并，spec §8.4）
        ScheduledFuture<?> prev = futuresEpisodic.get(userId);
        if (prev != null) {
            prev.cancel(false);
        }
        futuresEpisodic.put(
                userId, scheduler.schedule(() -> executeEpisodic(userId, sessionId), windowSeconds, TimeUnit.SECONDS));
        log.debug("经历记忆提取已投递，防抖窗口 {}s: userId={}", windowSeconds, userId);
    }

    /** 经历记忆调度到期的执行入口（防抖合并后取最新批次，仅保留每用户一份待执行任务） */
    void executeEpisodic(Long userId, Long sessionId) {
        futuresEpisodic.remove(userId);
        List<Message> messages = pendingEpisodic.remove(userId);
        if (messages == null || messages.isEmpty()) {
            return;
        }
        executeEpisodicInternal(userId, sessionId, messages);
    }

    /**
     * 执行经历记忆提取-决策-落库链路（真实调度与直测共用，包可见供单测直测）
     *
     * @param userId    所属用户（硬隔离过滤键）
     * @param sessionId 来源会话 ID（记忆 source_session_id 落库）
     * @param messages  本批消息（最新语义）
     */
    void executeEpisodicInternal(Long userId, Long sessionId, List<Message> messages) {
        try {
            ExtractionInput input = inputAssembler.build(messages);
            if (input.currentText() == null || input.currentText().isBlank()) {
                log.debug("经历记忆提取跳过: 无当前对话 userId={}", userId);
                return;
            }
            // 该用户已有经历记忆文本（提取 prompt merge_target 原文引用参考，spec §8.4）
            String existing = episodicMemoryService.findActiveMemoriesText(userId);
            // 提取 LLM 调用（独立执行器 + 超时控制，spec §8.4：同步 + 超时丢弃本批）
            Future<EpisodicExtractionResult> future = CompletableFuture.supplyAsync(
                    () -> episodicExtractionService.extract(input, existing), episodicExecutor);
            EpisodicExtractionResult result;
            try {
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                log.warn("经历记忆提取超时，丢弃本批: userId={}, timeoutMs={}", userId, timeoutMs);
                future.cancel(true);
                return;
            }
            if (result == null || result.memories().isEmpty()) {
                log.debug("经历记忆提取无条目: userId={}", userId);
                return;
            }
            // PG 原子写（applyExtraction 事务，spec §8.5：is_memory/分数/重复决策由决策引擎产出）
            int written = episodicMemoryService.applyExtraction(userId, sessionId, result);
            log.info(
                    "经历记忆提取流水线完成: userId={}, 生效动作={}, 条目={}",
                    userId,
                    written,
                    result.memories().size());
        } catch (Exception e) {
            // 失败降级：丢弃本批 + 记日志，不重试、不影响主链路（与偏好 executeInternal 同模式，spec §8.4）
            log.warn("经历记忆提取失败，丢弃本批: userId={}, error={}", userId, e.getMessage());
        }
    }
}
