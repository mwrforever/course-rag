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
 * 用户附件服务实现 —— 上传校验（spec §5.2 限额定稿）+ MinIO 落盘/下载
 *
 * <p>kbId 固定传 0L：附件是会话级局部上下文，不归属任何知识库（spec §5.1）。
 *
 * <p>校验顺序：附件非空 → 单次个数限额 → 合计大小限额 → 第一遍全量逐文件校验（类型白名单 →
 * 单文件大小限额，不落盘）→ 全部通过后第二遍逐个 MinIO 落盘。任一校验不过立即抛
 * BizException(400)，保证非法请求不产生任何落盘（杜绝混合上传时前序合法文件成为 MinIO
 * 孤儿对象）。
 *
 * <p>下载：按 objectKey 读 MinIO 字节流，对象不存在或读取失败统一抛 BizException(404)
 * （附件为短期会话资源，缺失视为「不存在或已过期」）。
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

        // 第一遍：全量校验（类型白名单 + 单文件大小限额），任一不过立即抛 BizException(400)
        // —— 校验先于任何落盘，避免「file[0] 合法已落盘、file[1] 非法」时 file[0] 成为 MinIO 孤儿对象
        for (MultipartFile file : files) {
            validateUpload(file);
        }
        // 第二遍：全部通过后才逐个落盘（uploadSingle 仅保留上传职责，此时不会再抛业务校验异常）
        List<AttachmentRecord> records = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            records.add(uploadSingle(file));
        }
        log.info("附件上传完成: count={}, totalSize={}B", files.length, total);
        return records;
    }

    /**
     * 单附件全量校验：类型白名单 → 单文件大小限额（spec §5.2，仅校验不落盘）
     *
     * <p>与 uploadSingle 解耦：本方法只做校验、不产生任何 MinIO 写入，供 upload 第一遍全量调用，
     * 保证任一文件非法时整批都不落盘。
     *
     * @param file 待校验的附件（未做任何副作用操作）
     * @throws BizException 类型不在白名单（ext 未知）或超过对应单文件限额时抛 BAD_REQUEST(400)
     */
    private void validateUpload(MultipartFile file) {
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
    }

    @Override
    public byte[] download(String objectKey) {
        // 从 MinIO 取附件字节流并一次性读全（objectKey 不存在/读取失败视为附件已消失）
        try (InputStream in = minioStorageService.downloadFile(objectKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("附件下载失败: objectKey={}, error={}", objectKey, e.getMessage());
            throw new BizException(ErrorCode.NOT_FOUND, "附件不存在或已过期");
        }
    }

    /** 单个附件落盘：MinIO 上传（uuid objectKey，外部资源 key 一律 uuid 先行）；调用方必须先过全量校验 */
    private AttachmentRecord uploadSingle(MultipartFile file) {
        String ext = extractExt(file.getOriginalFilename());
        // 已过第一遍全量校验，此处 classify 仅用于确定记录类型，不会再抛业务校验异常
        AttachmentType type = classify(ext);
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
