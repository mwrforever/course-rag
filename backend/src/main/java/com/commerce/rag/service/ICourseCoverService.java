package com.commerce.rag.service;

import com.commerce.rag.vo.CourseCoverVO;
import java.io.InputStream;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

/**
 * 课程封面服务接口 —— B 端封面上传 + C 端公开封面访问（契约 D.2）
 *
 * <p>上传：MIME/扩展名白名单 + 大小校验（course.cover.* 配置化）→ uuid 预生成先占资源
 * （宪法 A.5.7）→ 复用 MinioStorageService 落盘 {@code 0/} 封面目录。
 *
 * <p>访问：前缀白名单硬校验（全锚定正则，仅代理封面目录，非通用文件代理）→
 * MinIO 流式回读。宪法 A.5.8 豁免声明见实现类注释（封面目录长期匿名读取的显式偏离）。
 *
 * @author commerce-rag
 */
public interface ICourseCoverService {

    /**
     * 上传课程封面
     *
     * @param file 封面文件（multipart 字段名 file，用户上传的原始文件）
     * @return 上传结果 VO（objectKey + 相对访问 URL）
     * @throws com.commerce.rag.exception.BizException 400 文件为空/扩展名或 MIME 不在白名单/超过大小上限；
     *         503 MinIO 存储不可用；500 请求文件流读取失败
     */
    CourseCoverVO uploadCover(MultipartFile file);

    /**
     * 读取封面内容（公开访问端点用，免登录 + 白名单收窄）
     *
     * @param objectKey 路径通配捕获的原始值（{@code {*objectKey}} 带前导斜杠，服务端剥离后校验）
     * @return 封面内容（输入流 + 按扩展名解析的 Content-Type）
     * @throws com.commerce.rag.exception.BizException 404 键不合法（白名单不匹配）或对象不存在；
     *         503 MinIO 存储不可用
     */
    CoverContent downloadCover(String objectKey);

    /**
     * 封面内容载体 —— 输入流与 Content-Type 配对出参
     *
     * @param inputStream MinIO 对象输入流（调用方负责消费，流关闭由响应框架兜底）
     * @param contentType 按扩展名解析的图片 MIME 类型
     */
    record CoverContent(InputStream inputStream, MediaType contentType) {}
}
