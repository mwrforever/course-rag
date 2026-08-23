package com.commerce.rag.constants;

/**
 * 附件业务常量 —— 附件区 MinIO 键空间约定
 *
 * <p>附件是会话级局部上下文，不归属任何知识库（spec §5.1）：落盘固定 kbId=0L，
 * objectKey 形如 {@code 0/{uuid}.{ext}}，与知识库文档（{@code {kb_id}/...}）共用同一 bucket。
 * 附件区前缀与 kbId <b>同源定义</b>（前缀由 kbId 拼接而来），防止两处数值漂移
 * 导致下载校验放行错误区域（B3-2 越权防护依据）。
 *
 * @author commerce-rag
 */
public interface AttachmentConstants {

    /** 附件落盘固定 kbId（附件不归属任何知识库，MinIO 键空间独立于 B 端知识库文档） */
    long ATTACHMENT_KB_ID = 0L;

    /** 附件区 objectKey 前缀（由 ATTACHMENT_KB_ID 拼接而来，下载侧据此校验越权） */
    String ATTACHMENT_OBJECT_KEY_PREFIX = ATTACHMENT_KB_ID + "/";
}
