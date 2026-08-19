package com.commerce.rag.service;

import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 附件处理编排 —— 下载 → 按类型分发（图片 caption / 文档局部语料）→ 组装 AttachmentContext
 *
 * <p>spec §5.1：消息发送后 worker 内处理；单项失败跳过不中断；Caffeine 缓存由各处理器内部完成。
 *
 * <p>依赖：IAttachmentService（MinIO 下载，Task 4）、AttachmentImageProcessor（VLM caption，
 * Task 5，字节下载由本类完成）、AttachmentDocumentProcessor（解析/切分/向量化，Task 6）。
 * 本类无共享可变状态，线程安全（worker runPool 多线程并发调用）。
 *
 * <p>附件上下文经 {@code AttachmentContext} 载体返回，由 worker 写入 RunnableConfig.metadata
 * （metadata 键见 {@link #KEY_ATTACHMENT_CONTEXT}），供 QU 节点与 RetrieveNode 后续消费（Task 10/11）。
 *
 * @author commerce-rag
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentOrchestrator {

    /** RunnableConfig.metadata 键：附件处理上下文（worker 写入，QU/RetrieveNode 消费，与 document_context 同通道） */
    public static final String KEY_ATTACHMENT_CONTEXT = "attachmentContext";

    private final IAttachmentService attachmentService;
    private final AttachmentImageProcessor imageProcessor;
    private final AttachmentDocumentProcessor documentProcessor;

    /**
     * 处理附件列表（下载/分发/处理；单项失败跳过不中断，spec §5.1 边界）
     *
     * @param attachments 附件记录列表（可为空列表或 null）
     * @return 附件处理结果载体（无附件/全部失败返回 {@link AttachmentContext#empty()}；不抛异常）
     */
    public AttachmentContext process(List<AttachmentRecord> attachments) {
        // 空输入按空上下文处理，不触发任何下载/处理
        if (attachments == null || attachments.isEmpty()) {
            return AttachmentContext.empty();
        }
        // 图片路径：统一收集字节与文件名后批量交给 imageProcessor（一次批量 caption，多图序号由 processor 内部标注）
        List<byte[]> imageBytes = new ArrayList<>();
        List<String> imageNames = new ArrayList<>();
        // 文档路径：逐附件下载 → 处理，结果以 objectKey（url）为键组装局部语料
        Map<String, List<DocumentLocalChunk>> documents = new HashMap<>();
        for (AttachmentRecord att : attachments) {
            try {
                // 下载附件字节（MinIO，Caffeine 缓存由各处理器内部完成，spec §5.1）
                byte[] bytes = attachmentService.download(att.url());
                if ("image".equals(att.type())) {
                    imageBytes.add(bytes);
                    imageNames.add(att.name());
                } else {
                    // 非图片按文档处理（局部检索语料，spec §5.4）
                    documents.put(att.url(), documentProcessor.processDocument(bytes, att.name()));
                }
            } catch (Exception e) {
                // 单项失败跳过，不中断整体（下载失败/处理异常统一兜底，spec §5.1）
                log.warn("附件下载/处理失败，跳过: url={}, error={}", att.url(), e.getMessage());
            }
        }
        // 图片批量 caption（无图片字节时不调用，避免空入参触发处理器）
        List<ImageCaptionResult> captions =
                imageBytes.isEmpty() ? List.of() : imageProcessor.processImages(imageBytes, imageNames);
        return new AttachmentContext(captions, documents);
    }
}
