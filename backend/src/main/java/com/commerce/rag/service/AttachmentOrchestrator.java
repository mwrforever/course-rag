package com.commerce.rag.service;

import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.stream.ThinkingPusher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 附件处理编排 —— 下载 → 按类型分发（图片 caption / 文档局部语料）→ 组装 AttachmentContext
 *
 * <p>spec §5.1：消息发送后 worker 内处理；单项失败跳过不中断；Caffeine 缓存由各处理器内部完成。
 *
 * <p>P2-2 并行化：附件级并行（每附件一个「下载 + 处理」任务提交附件池，allOf 汇总），
 * 多附件处理时长从 Σ 降为约 max；<b>总超时控制</b>（attachment.process-timeout-ms 配置）：
 * 超时后取消未完成附件、保留已完成结果，避免慢附件阻塞 SSE 首 token；
 * 单飞失败隔离：任一附件下载/处理失败只跳过自身（fail-soft，不影响其它附件）。
 *
 * <p>图片字节按<b>原始附件顺序</b>收集（futures 按提交顺序组装，与完成顺序无关），
 * 统一批量交给 imageProcessor 一次并行 caption（多图序号由 processor 按位置预分配）。
 *
 * <p>依赖：IAttachmentService（MinIO 下载，Task 4）、AttachmentImageProcessor（VLM caption，
 * Task 5，字节下载由本类完成）、AttachmentDocumentProcessor（解析/切分/批量向量化，Task 6）。
 * 本类无共享可变状态，线程安全（worker runPool 多线程并发调用）。
 *
 * <p>附件上下文经 {@code AttachmentContext} 载体返回，由 worker 写入 RunnableConfig.metadata
 * （metadata 键见 {@link #KEY_ATTACHMENT_CONTEXT}），供 QU 节点与 RetrieveNode 后续消费（Task 10/11）。
 *
 * <p>思考流式与取消即时性（2026-08-28 时间线改版 Task 4）：带 ThinkingPusher 时图片 VLM caption
 * 走流式聚合并实时推 attachments 阶段 reasoning；批循环每文件提交前检查取消源
 * （{@link BooleanSupplier}），已取消立即停止提交剩余文件（在途附件仍由总超时兜底回收）。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
public class AttachmentOrchestrator {

    /** RunnableConfig.metadata 键：附件处理上下文（worker 写入，QU/RetrieveNode 消费，与 document_context 同通道） */
    public static final String KEY_ATTACHMENT_CONTEXT = "attachmentContext";

    private final IAttachmentService attachmentService;
    private final AttachmentImageProcessor imageProcessor;
    private final AttachmentDocumentProcessor documentProcessor;

    /** 附件并行池（P2-2：附件级下载/处理并行基底，与 ETL/检索/记忆池隔离，禁止复用 etlPool） */
    private final Executor attachmentPool;

    /** 附件配置（读 processTimeoutMs 作为并行处理总超时） */
    private final AttachmentProperties properties;

    /**
     * 手写构造器（而非 @RequiredArgsConstructor）：附件池以 @Qualifier 指名绑定
     * （attachmentPool 与 etlPool/runPool 同为 Executor 类型，指名注入避免多 Bean 歧义）。
     *
     * @param attachmentService   附件下载服务（MinIO）
     * @param imageProcessor      图片 caption 处理器
     * @param documentProcessor   文档局部语料处理器
     * @param attachmentPool      附件并行池
     * @param properties          附件配置（processTimeoutMs 总超时）
     */
    public AttachmentOrchestrator(
            IAttachmentService attachmentService,
            AttachmentImageProcessor imageProcessor,
            AttachmentDocumentProcessor documentProcessor,
            @Qualifier("attachmentPool") Executor attachmentPool,
            AttachmentProperties properties) {
        this.attachmentService = attachmentService;
        this.imageProcessor = imageProcessor;
        this.documentProcessor = documentProcessor;
        this.attachmentPool = attachmentPool;
        this.properties = properties;
    }

    /**
     * 处理附件列表（附件级并行：下载/文档处理并发执行；单项失败跳过不中断，spec §5.1 边界）
     *
     * <p>执行流程：每附件一个 future（下载 → 图片收集字节 / 文档同步处理）→ allOf 带
     * 总超时等待 → 超时取消未完成 → 按原始顺序组装 → 图片批量并行 caption。
     *
     * <p>取消检查点（Task 4）：批循环内每文件提交前检查取消源，已取消则跳过剩余文件——
     * 取消语义下不再为不会再消费的附件耗下载/VLM 配额；在途任务仍由总超时兜底回收。
     *
     * @param attachments 附件记录列表（可为空列表或 null）
     * @param pusher      per-run 思考推送通道（可为 null——null 时 caption 走同步路径不推思考，
     *                    保持离线/测试兼容）
     * @param cancelled   取消源（worker 注入 run 级取消标志读取器；不允许为 null，无取消场景传
     *                    {@code () -> false}；批循环每文件提交前调用一次）
     * @return 附件处理结果载体（无附件/全部失败/全部超时/已取消返回 {@link AttachmentContext#empty()}；不抛异常）
     */
    public AttachmentContext process(
            List<AttachmentRecord> attachments, ThinkingPusher pusher, BooleanSupplier cancelled) {
        // 空输入按空上下文处理，不触发任何下载/处理
        if (attachments == null || attachments.isEmpty()) {
            return AttachmentContext.empty();
        }
        // 阶段 1：附件级并行（futures 按原始附件顺序提交，组装时保持相对顺序）
        List<CompletableFuture<AttachmentOutcome>> futures = new ArrayList<>(attachments.size());
        for (int i = 0; i < attachments.size(); i++) {
            AttachmentRecord att = attachments.get(i);
            if (cancelled.getAsBoolean()) {
                // 取消即时检查点：停止提交剩余文件（已提交的不回退，总超时兜底），
                // 不抛异常——worker 后续 doOnNext 检查点会以既有 CancelledException 收敛终态
                log.info("附件处理检测到取消，跳过剩余文件: 已提交={}/{}个", i, attachments.size());
                break;
            }
            try {
                futures.add(CompletableFuture.supplyAsync(() -> downloadAndHandle(att), attachmentPool));
            } catch (RejectedExecutionException e) {
                // 池满快速失败：该附件按失败跳过（不加入 futures，其余附件照常处理）
                log.warn("附件处理提交被拒（池满），跳过: url={}, error={}", att.url(), e.getMessage());
            }
        }
        // 阶段 2：总超时控制——超时/中断时取消未完成任务，仅收集已完成部分
        awaitWithTimeout(futures);
        // 阶段 3：按原始顺序组装（图片字节按位置收集 → 批量 caption；文档以 url 为键）
        List<byte[]> imageBytes = new ArrayList<>();
        List<String> imageNames = new ArrayList<>();
        Map<String, List<DocumentLocalChunk>> documents = new HashMap<>();
        for (CompletableFuture<AttachmentOutcome> future : futures) {
            AttachmentOutcome outcome = joinQuietly(future);
            if (outcome == null) {
                continue;
            }
            if (outcome.image()) {
                imageBytes.add(outcome.bytes());
                imageNames.add(outcome.name());
            } else {
                // 文档处理结果（含空语料）按原语义入 map（失败已由 downloadAndHandle 兜底跳过）
                documents.put(outcome.url(), outcome.chunks());
            }
        }
        // 图片批量并行 caption（无图片字节时不调用，避免空入参触发处理器）；
        // pusher 透传：SSE 链路 reasoning 实时推 attachments 阶段，null 走同步原语义
        List<ImageCaptionResult> captions =
                imageBytes.isEmpty() ? List.of() : imageProcessor.processImages(imageBytes, imageNames, pusher);
        return new AttachmentContext(captions, documents);
    }

    /**
     * 单附件「下载 + 处理」（在附件池线程执行；单飞失败返回 null 由组装阶段跳过）
     *
     * @param att 附件记录
     * @return 处理结果（图片=字节收集；文档=局部语料）；下载/处理失败返回 null
     */
    private AttachmentOutcome downloadAndHandle(AttachmentRecord att) {
        try {
            // 下载附件字节（MinIO，Caffeine 缓存由各处理器内部完成，spec §5.1）
            byte[] bytes = attachmentService.download(att.url());
            if ("image".equals(att.type())) {
                return AttachmentOutcome.image(bytes, att.name());
            }
            // 非图片按文档处理（局部检索语料，spec §5.4；内含批量向量化）
            return AttachmentOutcome.document(att.url(), documentProcessor.processDocument(bytes, att.name()));
        } catch (Exception e) {
            // 单项失败跳过，不中断整体（下载失败/处理异常统一兜底，spec §5.1）
            log.warn("附件下载/处理失败，跳过: url={}, error={}", att.url(), e.getMessage());
            return null;
        }
    }

    /**
     * 等待全部附件 future 完成（带总超时，超时取消未完成任务）
     *
     * @param futures 附件处理任务列表
     */
    private void awaitWithTimeout(List<CompletableFuture<AttachmentOutcome>> futures) {
        if (futures.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(properties.processTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 总超时：取消未完成附件（中断在途下载），已完成结果照常组装
            log.warn("附件并行处理总超时({}ms)，丢弃未完成附件", properties.processTimeoutMs());
            futures.forEach(f -> f.cancel(true));
        } catch (InterruptedException e) {
            // 调用线程被中断：取消未完成任务并交还中断标记
            Thread.currentThread().interrupt();
            log.warn("附件并行处理被中断，丢弃未完成附件");
            futures.forEach(f -> f.cancel(true));
        } catch (ExecutionException e) {
            // 单飞异常已在 downloadAndHandle 兜底为 null，理论上不会到达；防御性兜底
            log.warn("附件并行处理出现未兜底异常，丢弃未完成附件: error={}", e.getCause().getMessage());
            futures.forEach(f -> f.cancel(true));
        }
    }

    /**
     * 静默 join：已完成取结果，取消/异常返回 null（组装时按跳过处理）
     *
     * @param future 附件处理任务
     * @return 处理结果或 null（取消/失败）
     */
    private AttachmentOutcome joinQuietly(CompletableFuture<AttachmentOutcome> future) {
        try {
            return future.join();
        } catch (CancellationException | CompletionException e) {
            return null;
        }
    }

    /** 单附件处理中间结果（仅本类内部流转：图片收集字节 / 文档产出局部语料） */
    private record AttachmentOutcome(
            boolean image, byte[] bytes, String name, String url, List<DocumentLocalChunk> chunks) {

        /** 图片附件结果（字节待批量 caption） */
        static AttachmentOutcome image(byte[] bytes, String name) {
            return new AttachmentOutcome(true, bytes, name, null, null);
        }

        /** 文档附件结果（局部语料，可能为空列表） */
        static AttachmentOutcome document(String url, List<DocumentLocalChunk> chunks) {
            return new AttachmentOutcome(false, null, null, url, chunks);
        }
    }
}
