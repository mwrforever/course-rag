package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.commerce.rag.entity.Document;
import com.commerce.rag.vo.DocumentVO;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;

/**
 * 文档管理服务接口 —— 上传/查询/更新/删除/重新解析/下载（主表 Document）
 *
 * @author commerce-rag
 */
public interface IDocumentService extends IService<Document> {

    /**
     * 上传文档（MinIO 落盘 + ETL 异步解析）
     *
     * @param kbId      知识库 ID
     * @param title     标题
     * @param inputStream 文件流
     * @param fileType  文件类型（白名单）
     * @param fileSize  文件大小（字节）
     * @param courseId  关联课程（可空）
     * @param createdBy 创建者 ID
     * @param isAdmin   是否超管（超管旁路归属校验）
     * @return 文档视图对象
     */
    DocumentVO upload(
            Long kbId,
            String title,
            InputStream inputStream,
            String fileType,
            Long fileSize,
            String courseId,
            Long createdBy,
            boolean isAdmin);

    /**
     * 按 ID 查询文档（带归属校验）
     */
    DocumentVO findById(Long id, Long userId, String role);

    /**
     * 批量查询文档标题（docId → title 映射，B3-3：检索链路按 doc_id 回填 KnowledgeChunk.docTitle）
     *
     * <p>单次 in 查询按需取列（id/title），供 bot/tool 检索结果组装「来源文档」标注
     * （spec §3.2）——@Tool 侧经 Service 封装访问 document 表，不直访数据层。
     *
     * @param docIds 文档 ID 集合（不允许为空集合以外的 null——空集合/null 直接返回空 Map）
     * @return docId → title 映射；文档不存在/标题为 null 的条目不出现（调用方按缺省处理）
     */
    Map<Long, String> mapTitlesByIds(Collection<Long> docIds);

    /**
     * 分页查询文档（支持知识库/状态/关键词/排序过滤 + 教师数据权限）
     */
    IPage<DocumentVO> findPage(
            Long kbId, String status, String q, String sort, int page, int size, Long userId, String role);

    /**
     * 更新文档标题
     */
    void update(Long id, String title, Long operatorId, boolean isAdmin);

    /**
     * 删除文档（级联软删分片 + MinIO/Milvus 清理）
     */
    void delete(Long id, Long operatorId, boolean isAdmin);

    /**
     * 重新解析文档（重置状态并重新提交 ETL）
     */
    void reparse(Long id, Long operatorId, boolean isAdmin);

    /**
     * 下载文档源文件（带归属校验）
     *
     * @return 文件流，不存在返回 null
     */
    InputStream download(Long id, Long operatorId, boolean isAdmin);

    /**
     * 下载文档源文件（带类型信息）
     *
     * @return 文件流 + 文件类型
     */
    DocumentDownload downloadWithType(Long id, Long operatorId, boolean isAdmin);

    /**
     * 文档下载结果 —— 文件流 + 文件类型（供 controller 设置响应头）
     *
     * @param inputStream 文件流
     * @param fileType    文件类型
     */
    record DocumentDownload(InputStream inputStream, String fileType) {}
}
