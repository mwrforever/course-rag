package com.commerce.rag.service.impl;

import com.commerce.rag.enums.AttachmentType;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IAttachmentService;
import com.commerce.rag.storage.MinioStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户附件服务实现 —— 上传校验（spec §5.2 限额定稿）+ MinIO 落盘
 *
 * <p>kbId 固定传 0L：附件是会话级局部上下文，不归属任何知识库（spec §5.1）。
 *
 * <p>校验顺序：附件非空 → 单次个数限额 → 合计大小限额 → 逐文件（类型白名单 → 单文件大小限额 →
 * MinIO 落盘），任一校验不过立即抛 BizException(400)，保证非法请求不产生任何落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements IAttachmentService {

    /** 图片扩展名白名单 */
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    /** 文档扩展名白名单（spec §5.2 首版：文本类文档，无内嵌图片提取） */
    private static final Set<String> DOCUMENT_EXTS = Set.of("pdf", "doc", "docx", "txt", "md");

    private final MinioStorageService minioStorageService;
    private final AttachmentProperties properties;

    @Override
    public List<AttachmentRecord> upload(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件不能为空");
        }
        if (files.length > properties.maxCount()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多上传 " + properties.maxCount() + " 个附件");
        }
        // 合计大小校验（单次 ≤ totalMaxSizeMb）
        long total = 0;
        for (MultipartFile f : files) {
            total += f.getSize();
        }
        if (total > properties.totalMaxSizeMb() * 1024L * 1024L) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件合计超过 " + properties.totalMaxSizeMb() + "MB 限制");
        }

        List<AttachmentRecord> records = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            records.add(uploadSingle(file));
        }
        log.info("附件上传完成: count={}, totalSize={}B", files.length, total);
        return records;
    }

    /** 单个附件：类型白名单 → 大小限额 → MinIO 落盘（uuid objectKey，外部资源 key 一律 uuid 先行） */
    private AttachmentRecord uploadSingle(MultipartFile file) {
        String ext = extractExt(file.getOriginalFilename());
        AttachmentType type = classify(ext);
        // 不同类型走不同单文件限额（图片 10MB / 文档 50MB）
        long maxBytes = (type == AttachmentType.IMAGE ? properties.imageMaxSizeMb() : properties.documentMaxSizeMb())
                * 1024L
                * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BizException(
                    ErrorCode.BAD_REQUEST,
                    "附件 " + file.getOriginalFilename() + " 超过 " + (maxBytes / 1024 / 1024) + "MB 限制");
        }
        // 生成 32 位 hex uuid 作 objectKey 主体（与业务主键解耦）
        String uuid = UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = file.getInputStream()) {
            String objectKey = minioStorageService.uploadFile(0L, uuid, in, ext);
            return new AttachmentRecord(
                    type.name().toLowerCase(Locale.ROOT), objectKey, file.getOriginalFilename(), file.getSize());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件读取失败: " + file.getOriginalFilename());
        }
    }

    /** 按扩展名分类（未知类型直接拒绝，防 .exe/.zip 堆积） */
    private AttachmentType classify(String ext) {
        if (IMAGE_EXTS.contains(ext)) {
            return AttachmentType.IMAGE;
        }
        if (DOCUMENT_EXTS.contains(ext)) {
            return AttachmentType.DOCUMENT;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型: " + ext);
    }

    /** 取扩展名（小写，无扩展名返回空串） */
    private static String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
