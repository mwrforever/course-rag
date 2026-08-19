package com.commerce.rag.dto;

import com.commerce.rag.record.AttachmentRecord;
import java.util.List;

/**
 * 对话请求 DTO。
 *
 * @param sessionId   可选：null=新建会话，非 null=已有会话
 * @param query       必填：用户问题
 * @param attachments 可选：本次输入的用户附件记录列表（上传接口返回的 type/url/name/size；
 *                    null=无附件；spec §5.1 双存决策：随消息入队后落 chat_run/chat_message 的 attachments_json）
 */
public record ChatRequest(Long sessionId, String query, List<AttachmentRecord> attachments) {

    /** 无附件构造（既有两参调用兼容，attachments 委托为 null） */
    public ChatRequest(Long sessionId, String query) {
        this(sessionId, query, null);
    }
}
