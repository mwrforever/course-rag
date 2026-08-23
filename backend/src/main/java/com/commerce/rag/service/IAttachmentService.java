package com.commerce.rag.service;

import com.commerce.rag.record.AttachmentRecord;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户附件服务 —— 上传校验/落盘/下载/类型分发（spec §5）
 *
 * <p>附件不进系统知识库：上传仅存 MinIO 返回 objectKey；图片 caption、文档解析均延迟到
 * 消息发送后 worker 内处理（Caffeine 按字节 hash 缓存）。
 */
public interface IAttachmentService {

    /**
     * 校验并上传附件到 MinIO（kbId=0L 占位，附件不归属任何知识库）
     *
     * @param files 上传文件数组（数量/类型/大小/合计均校验，不允许为 null 或空）
     * @return 附件记录列表（type/url/name/size）
     */
    List<AttachmentRecord> upload(MultipartFile[] files);

    /**
     * 按 objectKey 从 MinIO 下载附件字节
     *
     * <p>下载前校验 objectKey 前缀必须是附件区（"0/"，B3-2 越权防护——objectKey 客户端可控，
     * 非附件区前缀一律拒绝 403，防止经附件链路直读同 bucket 的知识库文档等任意对象）。
     *
     * @param objectKey 上传时返回的对象键（必须以附件区前缀开头，不允许为 null）
     * @return 文件字节（前缀非法抛 BizException 403；不存在/读取失败抛 BizException 404）
     */
    byte[] download(String objectKey);
}
