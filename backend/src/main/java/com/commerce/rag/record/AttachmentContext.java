package com.commerce.rag.record;

import java.util.List;
import java.util.Map;

/**
 * 附件处理结果载体（orchestrator 组装 → config.metadata 传 QU/RetrieveNode，不落 state）
 *
 * <p>spec §5：worker 消息发送后调用 AttachmentOrchestrator.process 一次性产出；
 * 空上下文/全部失败统一走 {@link #empty()}，调用方（worker）据 {@link #hasAny()} 决定
 * 是否把本载体写入 RunnableConfig.metadata（与 document 组装同通道，瞬时注入不落 checkpoint）。
 *
 * @param captions  图片 caption 结果（"图片N:描述"，按上传顺序）
 * @param documents 文档局部语料（key=附件 objectKey，value=分片列表）
 */
public record AttachmentContext(List<ImageCaptionResult> captions, Map<String, List<DocumentLocalChunk>> documents) {

    /** 无附件上下文（captions 与 documents 均为空） */
    public static AttachmentContext empty() {
        return new AttachmentContext(List.of(), Map.of());
    }

    /**
     * 是否有任何附件上下文
     *
     * @return true=captions 或 documents 任一非空；false=空上下文（调用方无需注入 metadata）
     */
    public boolean hasAny() {
        return (captions != null && !captions.isEmpty()) || (documents != null && !documents.isEmpty());
    }
}
