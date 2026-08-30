package com.commerce.rag.constants;

/**
 * 课程域业务常量 —— 封面 MinIO 键空间与公开访问 URL 约定（契约 D.2.2 / D.2.3）
 *
 * <p>封面与用户附件（AttachmentConstants，同为 {@code 0/} 前缀）共用同一 bucket 的 0 号键空间，
 * 与 B 端知识库文档（19 位雪花 kbId 前缀目录）天然隔离；封面 objectKey 形如
 * {@code 0/{uuid32}.{ext}}，键由服务端 uuid 预生成（不可猜测、内容不可变——换封面即换新键）。
 *
 * @author commerce-rag
 */
public interface CourseConstants {

    /** 封面落盘固定区域前缀（0L：MinioStorageService.uploadFile 的 kbId 参数位，与雪花 kbId 空间不冲突） */
    long COVER_AREA_PREFIX = 0L;

    /** 封面 objectKey 前缀（由 COVER_AREA_PREFIX 拼接而来，与附件区 0/ 前缀共用键空间） */
    String COVER_OBJECT_KEY_PREFIX = COVER_AREA_PREFIX + "/";

    /** 封面公开访问 URL 前缀（相对路径，双前端 dev 代理同源转发 /api/v1 直用） */
    String COVER_URL_PREFIX = "/api/v1/public/covers/";
}
