package com.commerce.rag.service.impl;

import com.commerce.rag.constants.CourseConstants;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.CourseProperties;
import com.commerce.rag.service.ICourseCoverService;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.vo.CourseCoverVO;
import io.minio.errors.ErrorResponseException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课程封面服务实现 —— B 端上传校验落盘 + C 端公开白名单代理（契约 D.2）
 *
 * <p>上传链路（契约 D.2.2）：非空校验 → 扩展名白名单（course.cover.allowed-extensions）→
 * MIME 与扩展名一致校验 → 大小上限（course.cover.max-size-mb）→ 32 位 hex uuid 预生成
 * （宪法 A.5.7 先占资源再落盘，键不可猜测）→ MinioStorageService.uploadFile 落盘
 * {@code 0/} 封面目录（雪花 kbId 恒为 19 位大数，0 前缀与知识库目录天然不冲突）。
 * 上传纯资源占用、无 DB 写、天然幂等（重传生成新 uuid 新键）；用户放弃保存表单产生的
 * 孤儿对象由 TASK.md 登记定期巡检清理（契约 D.2.5）。
 *
 * <p>访问链路（契约 D.2.3）——宪法 A.5.8「附件对外分发一律短时效 presigned URL，禁止
 * 公开 bucket 与长期匿名读取」的<b>显式豁免</b>（N1 审核独立复核成立，豁免理由）：
 * <ol>
 *   <li>场景不可行：C 端首页对游客公开，{@code <img>} 标签无法携带 Authorization 头，
 *       presigned URL 会过期且 cover_image 列持久存 URL 引用；</li>
 *   <li>数据本就公开：封面是公开列表端点 PublicCourseVO.coverImage 的下发字段，
 *       非 chat 附件等私有数据语义；</li>
 *   <li>暴露面收窄到单目录：bucket 保持私有（无匿名读策略），仅代理 {@code 0/} 封面目录，
 *       键由服务端 uuid 预生成（不可猜测）、内容不可变（换封面即换新键）。</li>
 * </ol>
 * 本豁免仅覆盖封面前缀 {@code 0/} 目录；任何其他前缀（chat 附件、知识库文档）加入本
 * 端点即违反本声明，禁止扩展。
 *
 * <p>防穿越/跨前缀读取（白名单正则全锚定 {@code ^...$}）：{@code ../} 与 URL 编码
 * {@code %2e%2e}（Spring 解码后）均含连续两点，不匹配 {@code .ext$} 结构；知识库
 * objectKey 前缀为 19 位雪花 kbId，不匹配 {@code ^0/}；大小写混写、非 hex 字符、
 * 多余路径段一律 404。正则的扩展名集合与上传白名单同源（course.cover.allowed-extensions，
 * 默认 {@code ^0/[0-9a-f]{32}\.(jpg|jpeg|png|webp)$}），配置变更两侧自动联动。
 *
 * <p>线程安全：无共享可变状态（Pattern 构造期固化、Matcher 每次调用新建），可并发调用。
 *
 * @author commerce-rag
 */
@Service
public class CourseCoverServiceImpl implements ICourseCoverService {

    private static final Logger log = LoggerFactory.getLogger(CourseCoverServiceImpl.class);

    /** 扩展名 → 图片 MIME 映射（上传 MIME 校验与下载 Content-Type 同源） */
    private static final Map<String, String> EXT_MIME =
            Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp");

    private final MinioStorageService minioStorageService;

    /** 课程封面配置（白名单 + 大小上限） */
    private final CourseProperties courseProperties;

    /** 封面 objectKey 白名单正则（全锚定，构造期由配置固化；见类注释防穿越论证） */
    private final Pattern objectKeyPattern;

    /**
     * 显式构造器：白名单正则依赖 CourseProperties 派生（构造期固化，字段初始化器无法引用构造参数）
     *
     * @param minioStorageService MinIO 存储服务（进程级单例 Bean）
     * @param courseProperties    课程域配置（course.cover.*）
     */
    public CourseCoverServiceImpl(MinioStorageService minioStorageService, CourseProperties courseProperties) {
        this.minioStorageService = minioStorageService;
        this.courseProperties = courseProperties;
        // 扩展名集合与上传白名单同源：配置默认值下即契约 D.2.3 字面正则 ^0/[0-9a-f]{32}\.(jpg|jpeg|png|webp)$
        String extAlternation = courseProperties.cover().allowedExtensions().stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));
        this.objectKeyPattern = Pattern.compile("^" + Pattern.quote(CourseConstants.COVER_OBJECT_KEY_PREFIX)
                + "[0-9a-f]{32}\\.(" + extAlternation + ")$");
    }

    /**
     * 上传课程封面（契约 D.2.2/D.2.4）
     *
     * @param file 封面文件（multipart 字段名 file，用户上传）
     * @return 上传结果 VO（objectKey + 相对访问 URL）
     * @throws BizException 400 文件为空/类型或 MIME 非法/超限（消息含文件名与允许清单）；
     *         503 MinIO 不可用；500 文件流读取失败
     */
    @Override
    public CourseCoverVO uploadCover(MultipartFile file) {
        // 非空校验（未选文件/空文件均拒绝）
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封面文件不能为空");
        }
        String allowedList = String.join(",", courseProperties.cover().allowedExtensions());
        String filename = file.getOriginalFilename();
        // 扩展名白名单校验（消息含文件名与允许清单，契约 D.2.4）
        String ext = extractExt(filename);
        if (!courseProperties.cover().allowedExtensions().contains(ext)) {
            log.warn("封面上传被拒（类型不在白名单）: filename={}, allowed={}", filename, allowedList);
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的封面类型: " + filename + "，允许的类型: " + allowedList);
        }
        // MIME 校验：Content-Type 须与扩展名对应的图片 MIME 一致（防改名伪装）；
        // expectedMime 为 null 说明扩展名虽在白名单但无内置 MIME 映射（配置引入 EXT_MIME 未覆盖
        // 的新类型）——一律 400 拒绝（防御配置漂移，避免 NPE 500）；
        // 取值一次入局部变量——重复调用 getContentType 无法被空检查保护（SpotBugs NP 风险）
        String expectedMime = EXT_MIME.get(ext);
        String contentType = file.getContentType();
        if (expectedMime == null || contentType == null || !expectedMime.equals(contentType.toLowerCase(Locale.ROOT))) {
            log.warn("封面上传被拒（MIME 不匹配）: filename={}, contentType={}", filename, contentType);
            throw new BizException(ErrorCode.BAD_REQUEST, "封面 MIME 类型不匹配: " + filename + "，允许的类型: " + allowedList);
        }
        // 大小上限校验（course.cover.max-size-mb 属性化）
        long maxBytes = courseProperties.cover().maxSizeMb() * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            log.warn(
                    "封面上传被拒（超过大小上限）: filename={}, size={}B, maxMb={}",
                    filename,
                    file.getSize(),
                    courseProperties.cover().maxSizeMb());
            throw new BizException(
                    ErrorCode.BAD_REQUEST,
                    "封面 " + filename + " 超过 " + courseProperties.cover().maxSizeMb() + "MB 限制");
        }
        // uuid 预生成先占资源（32 位 hex，A.5.7：先占资源再落库/引用，键不可猜测）
        String uuid = UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = file.getInputStream()) {
            // 落盘固定封面区域前缀 0/（与附件区共用 0 号键空间，与知识库目录隔离）
            String objectKey = minioStorageService.uploadFile(CourseConstants.COVER_AREA_PREFIX, uuid, in, ext);
            log.info("封面上传完成: objectKey={}, size={}B", objectKey, file.getSize());
            return new CourseCoverVO(objectKey, CourseConstants.COVER_URL_PREFIX + objectKey);
        } catch (IOException e) {
            log.error("封面文件流读取失败: filename={}", filename, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "封面文件读取失败: " + filename);
        } catch (RuntimeException e) {
            // MinioStorageService 上传失败包装 RuntimeException 上抛（MinIO 不可用/网络异常）
            log.error("封面上传到 MinIO 失败: filename={}", filename, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "封面存储服务不可用，请稍后重试");
        }
    }

    /**
     * 读取封面内容（契约 D.2.3，公开访问白名单代理）
     *
     * @param rawObjectKey 路径通配捕获原始值（Spring 6 {@code {*objectKey}} 带前导斜杠，如 /0/abc.png）
     * @return 封面内容（输入流 + Content-Type）
     * @throws BizException 404 键不合法（白名单不匹配）或对象不存在；503 MinIO 不可用
     */
    @Override
    public CoverContent downloadCover(String rawObjectKey) {
        // Spring 6 路径通配变量带 / 前缀（/0/abc.png），白名单匹配前先剥离前导斜杠
        String objectKey =
                rawObjectKey != null && rawObjectKey.startsWith("/") ? rawObjectKey.substring(1) : rawObjectKey;
        // 前缀白名单硬校验（全锚定正则，见类注释防穿越论证）——不匹配即 404，不泄露任何信息
        if (objectKey == null || !objectKeyPattern.matcher(objectKey).matches()) {
            log.warn("封面访问被拒（objectKey 不在封面白名单）: objectKey={}", objectKey);
            throw new BizException(ErrorCode.NOT_FOUND, "封面不存在");
        }
        String ext = extractExt(objectKey);
        MediaType contentType =
                MediaType.parseMediaType(EXT_MIME.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM_VALUE));
        try {
            // bucket 保持私有，经服务端凭证代理回读（非匿名直连 MinIO）
            InputStream in = minioStorageService.downloadFile(objectKey);
            return new CoverContent(in, contentType);
        } catch (RuntimeException e) {
            // NoSuchKey（对象不存在/已清理）→ 404；其余（MinIO 不可用/网络异常）→ 503
            if (isNoSuchKey(e)) {
                log.warn("封面对象不存在: objectKey={}", objectKey);
                throw new BizException(ErrorCode.NOT_FOUND, "封面不存在");
            }
            log.error("封面读取失败（MinIO 异常）: objectKey={}", objectKey, e);
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "封面存储服务不可用，请稍后重试");
        }
    }

    /**
     * 判断异常链根因是否为 MinIO NoSuchKey（对象不存在）
     *
     * @param e MinioStorageService 包装上抛的 RuntimeException
     * @return true=对象不存在（404 语义）；false=存储服务异常（503 语义）
     */
    private boolean isNoSuchKey(RuntimeException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ErrorResponseException errorResponse) {
            return "NoSuchKey".equals(errorResponse.errorResponse().code());
        }
        return false;
    }

    /**
     * 取扩展名（小写，无扩展名返回空串——空串必不在白名单，自然 400/404）
     *
     * @param filename 文件名或 objectKey（可为 null）
     * @return 小写扩展名（不含点号）
     */
    private static String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
