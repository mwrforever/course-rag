package com.commerce.rag.service;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.etl.ImageFilter;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.AssistantMessageSink;
import com.commerce.rag.record.ImageCaptionResult;
import com.commerce.rag.stream.SseEventTransformer;
import com.commerce.rag.stream.ThinkingPusher;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 图片附件 caption 处理器 —— 会话级图片统一 VLM caption 链路（spec §5.3）
 *
 * <p>流程：过滤小图标/装饰图 → Caffeine 按字节 hash 缓存（同图只 caption 一次）→
 * 经既有 ImageCaptionService 调 VLM caption（OpenAI 兼容协议，qwen3.7-flash）
 * → 每张图按上传顺序占用一个序号，标注"图片N"。
 *
 * <p>思考流式（2026-08-28 时间线改版 Task 4，评审 I-2 stage 级收口）：带 {@link ThinkingPusher}
 * 时 VLM 走 {@code captionStreaming} 聚合，reasoning 片段实时推 attachments 阶段；
 * THINKING_END 不在单图内调用，由本类在全部在途 caption 完成后按批统一补恰好一次
 * （仅当批内确实推过 reasoning；零 reasoning 批次不发孤儿 end）；pusher 为 null
 * （无 SSE 通道场景）保持原同步 caption 行为零变化。缓存命中不产生 reasoning，无推送。
 *
 * <p>P2-2 并行化：每张图一个 caption 任务提交附件池并行执行（VLM 单次 1~3s，
 * 多图总耗时从 Σ 降为约 max）；<b>序号按原始位置预分配</b>（"图片N"由下标决定，
 * 与完成顺序无关），完成后按位置组装；单图失败/被过滤/超时只影响自身，
 * 序号仍按原始位置占位。
 *
 * <p>字节下载由调用方（AttachmentOrchestrator）完成——本类只负责过滤/caption/缓存，
 * 不依赖 IAttachmentService。Caffeine get(key, fn) 原子单次计算保证并行下同图
 * 并发首次 miss 不重复触发 VLM 调用。
 *
 * <p>线程安全：无可变共享状态（每请求局部变量），附件池多线程并发调用安全。
 */
@Slf4j
@Service
public class AttachmentImageProcessor {

    /** 图片过滤最小尺寸（KB，与 ETL 同阈值，spec §5.2） */
    private static final int IMAGE_MIN_SIZE_KB = 10;

    private final ImageCaptionService imageCaptionService;
    private final AttachmentCacheService cacheService;

    /** 附件并行池（P2-2：caption 并行基底，与 ETL/检索池隔离） */
    private final Executor attachmentPool;

    /** 附件配置（读 processTimeoutMs 作为 caption 并行总超时） */
    private final AttachmentProperties properties;

    /**
     * 手写构造器（而非 @RequiredArgsConstructor）：注入语义在方法注释中显式说明，
     * 附件池以 @Qualifier 指名绑定（attachmentPool 与 etlPool/runPool 同为 Executor 类型，
     * 指名注入避免多 Bean 歧义）。
     *
     * @param imageCaptionService VLM caption 服务（ETL 既有组件）
     * @param cacheService        附件处理结果缓存（按字节 hash）
     * @param attachmentPool      附件并行池（P2-2 caption 并行基底）
     * @param properties          附件配置（读 processTimeoutMs 作为并行总超时）
     */
    public AttachmentImageProcessor(
            ImageCaptionService imageCaptionService,
            AttachmentCacheService cacheService,
            @Qualifier("attachmentPool") Executor attachmentPool,
            AttachmentProperties properties) {
        this.imageCaptionService = imageCaptionService;
        this.cacheService = cacheService;
        this.attachmentPool = attachmentPool;
        this.properties = properties;
    }

    /**
     * 处理一组图片字节（并行 caption，可选思考实时推送，按上传顺序标注图片1/2…）
     *
     * <p>序号语义：每张图占用一个序号，无论该图 success / caption 失败 / 被过滤 / 超时——
     * 序号按原始位置预分配（position+1），并发完成后按位置组装，与完成顺序无关。
     *
     * <p>思考成对契约（评审 I-2 stage 级收口）：pusher 非空时单图 captionStreaming 只推
     * reasoning 不自行 end；attachments 阶段的 THINKING_END 由本方法在<b>全部在途 caption
     * 完成后统一补恰好一次</b>（仅当批次内确实推过 reasoning，批级 {@code reasoningSeenAny}
     * 判定）——超时/取消/单图异常等未全部正常完成路径同样经本完成点收口，不残留思考态；
     * 零 reasoning 批次不发孤儿 end。
     *
     * @param images 图片字节列表（与上传顺序一致）
     * @param names  原始文件名列表（同序，MIME 识别用）
     * @param pusher per-run 思考推送通道（可为 null——null 时走原同步 caption，行为零变化）
     * @param sink   per-run LLM 调用捕获容器（2026-08-29 消息实体化；可为 null——null 时
     *               走原四参行为，不捕获 caption 调用）
     * @return caption 结果列表（"图片N:描述"；被过滤/失败/超时的图片不产生结果；全部失败返回空列表）
     */
    public List<ImageCaptionResult> processImages(
            List<byte[]> images, List<String> names, ThinkingPusher pusher, AssistantMessageSink sink) {
        // 批次共享标志：任一单图 captionStreaming 推过 reasoning 即置 true，决定批完成点是否补 end
        AtomicBoolean reasoningSeenAny = new AtomicBoolean(false);
        // 每图一个 caption 任务（序号按位置预分配），池满拒绝按该图失败降级（快速跳过不抛出）
        List<CompletableFuture<ImageCaptionResult>> futures = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            final int position = i;
            final byte[] bytes = images.get(i);
            final String name = names.get(i);
            try {
                futures.add(CompletableFuture.supplyAsync(
                        () -> captionOne(bytes, name, position + 1, pusher, reasoningSeenAny, sink), attachmentPool));
            } catch (RejectedExecutionException e) {
                // 池满快速失败：该图跳过（completedFuture(null) 占位保持位置对应），不影响其它图
                log.warn("图片 caption 提交被拒（池满），跳过: name={}", name);
                futures.add(CompletableFuture.completedFuture(null));
            }
        }
        if (futures.isEmpty()) {
            return List.of();
        }
        // 总超时控制：超时取消未完成 caption，仅保留已完成结果（慢图不拖垮整批）
        awaitWithTimeout(futures);
        // 思考态批级收口（评审 I-2）：全部在途 caption 完成（含超时/取消/异常路径，
        // awaitWithTimeout 永不抛出）后统一补 end——多图乱序/并发完成仍仅一个 END(attachments)，
        // 与批内全部 THINKING 事件按 pushLock 到达序天然配对
        if (pusher != null && reasoningSeenAny.get()) {
            pusher.end(SseEventTransformer.STAGE_ATTACHMENTS);
        }
        // 按位置组装（失败的图已在 captionOne 记日志返回 null，此处仅静默跳过）
        List<ImageCaptionResult> results = new ArrayList<>(images.size());
        for (CompletableFuture<ImageCaptionResult> future : futures) {
            ImageCaptionResult result = joinQuietly(future);
            if (result != null) {
                results.add(result);
            }
        }
        return results;
    }

    /**
     * 等待全部 caption future 完成（带总超时，超时取消未完成任务）
     *
     * @param futures caption 任务列表
     */
    private void awaitWithTimeout(List<CompletableFuture<ImageCaptionResult>> futures) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(properties.processTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // 调用线程被中断：取消未完成任务并交还中断标记（已完成的照常组装）
            Thread.currentThread().interrupt();
            log.warn("图片 caption 并行处理被中断，丢弃未完成图片");
            futures.forEach(f -> f.cancel(true));
        } catch (Exception e) {
            // 超时或单飞异常：取消未完成任务，已完成结果照常组装（单飞异常已在 captionOne 兜底，
            // 此处主要为超时路径与防御兜底）
            log.warn("图片 caption 并行处理未全部完成（超时 {}ms），丢弃未完成图片", properties.processTimeoutMs());
            futures.forEach(f -> f.cancel(true));
        }
    }

    /**
     * 静默 join：已完成取结果，取消/异常返回 null（组装时按跳过处理）
     *
     * @param future caption 任务
     * @return caption 结果或 null（取消/失败）
     */
    private ImageCaptionResult joinQuietly(CompletableFuture<ImageCaptionResult> future) {
        try {
            return future.join();
        } catch (CancellationException | CompletionException e) {
            return null;
        }
    }

    /**
     * 单图 caption（在附件池线程执行）：缓存 → 过滤 → VLM 调用
     *
     * @param bytes 图片字节
     * @param name  原始文件名（日志/MIME 识别）
     * @param index 预分配序号（原始位置 +1，与完成顺序无关）
     * @param pusher per-run 思考推送通道（可为 null，null 走同步 caption 不推思考）
     * @param reasoningSeenAny 批次共享标志（本批任一图推过 reasoning 即置 true，批完成点据此补 end）
     * @return caption 结果；被过滤/失败返回 null（不产生结果但序号已按位置占位）
     */
    private ImageCaptionResult captionOne(
            byte[] bytes,
            String name,
            int index,
            ThinkingPusher pusher,
            AtomicBoolean reasoningSeenAny,
            AssistantMessageSink sink) {
        try {
            String hash = cacheService.computeHash(bytes);
            // 同图只 caption 一次（Caffeine 按字节 hash 缓存，spec §5.1；原子单次计算并行安全；
            // 缓存命中直接复用 caption 文本，无 VLM 调用亦无 reasoning 推送、不捕获实体）
            String caption = cacheService.getOrProcess(
                    hash, b -> captionInternal(b, name, pusher, reasoningSeenAny, sink), bytes);
            if (caption == null) {
                return null;
            }
            return new ImageCaptionResult("图片" + index + ":" + caption, name);
        } catch (Exception e) {
            // 单图失败跳过，不中断整体（spec §5.3 边界）
            log.warn("图片 caption 处理失败，跳过: name={}, error={}", name, e.getMessage());
            return null;
        }
    }

    /**
     * caption 内部逻辑：过滤 → VLM 调用（返回 null 表示被过滤）
     *
     * <p>pusher 非空走流式聚合（reasoning 实时推 attachments 阶段、不自行 end——批完成点统一
     * 收口，评审 I-2），为 null 保持原同步路径——两路径最终 caption 文本语义完全一致，
     * 降级/超时行为由各自方法内部界定。
     *
     * @param bytes  图片字节
     * @param name   原始文件名（日志/MIME 识别）
     * @param pusher 思考推送通道（可为 null）
     * @param reasoningSeenAny 批次共享标志（流式路径推过 reasoning 即置 true）
     * @return caption 文本；被过滤返回 null
     */
    private String captionInternal(
            byte[] bytes,
            String name,
            ThinkingPusher pusher,
            AtomicBoolean reasoningSeenAny,
            AssistantMessageSink sink) {
        if (ImageFilter.isSmallIcon(bytes, IMAGE_MIN_SIZE_KB) || ImageFilter.isDecorative(bytes)) {
            log.info("图片过滤（小图标/装饰图）: name={}", name);
            return null;
        }
        if (pusher != null) {
            // SSE 链路：VLM 流式聚合，reasoning 片段实时推送（Task 4；end 由批完成点统一调用），
            // 调用完成点经 sink 捕获该次调用（消息实体化，spec §3.2）
            return imageCaptionService.captionStreaming(bytes, mimeOf(name), pusher, reasoningSeenAny, sink);
        }
        return imageCaptionService.caption(bytes, mimeOf(name));
    }

    /** 按文件名后缀取 MIME（与 ETL extensionOf 同语义） */
    private static String mimeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
