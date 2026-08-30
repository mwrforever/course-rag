package com.commerce.rag.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.convert.DocumentConverter;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.service.IDocumentService;
import com.commerce.rag.storage.MinioStorageService;
import com.commerce.rag.vo.DocumentVO;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档服务 —— 封装 document 表的 CRUD + MinIO 文件管理 + ETL 触发
 *
 * <p>upload：存 MinIO + 创建 document 记录 + 触发 ETL 异步管道。
 * delete：MinIO 删除 → Milvus deleteByDocId → 级联软删 chunk + document。
 * reparse：从 MinIO 拉原文件重新 ETL。
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements IDocumentService {

    private static final Logger log = LoggerFactory.getLogger(IDocumentService.class);

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final MinioStorageService minioStorageService;
    private final EtlPipeline etlPipeline;

    @Qualifier("etlPool")
    private final ThreadPoolExecutor etlPool;

    /** 文档转换器 —— Entity 出 service 边界前转 VO（sourcePath 不泄露） */
    private final DocumentConverter documentConverter;

    /** Dashboard 统计缓存失效（Spring Cache 注解化的写方统一出口，先写 DB 后失效——一致性铁律） */
    private final DashboardCacheEvictor dashboardCacheEvictor;

    /**
     * 上传文档
     *
     * <p>流程：存 MinIO → 创建 document 记录（含 course_id）→ 触发 ETL 异步管道
     *
     * @param kbId        知识库 ID
     * @param title       文档标题
     * @param inputStream 文件输入流
     * @param fileType    文件类型（pdf/docx/pptx/md/txt/xlsx/xls）
     * @param fileSize    文件大小（字节）
     * @param courseId    课程 ID（可空，空则 DEFAULT=通用资料库；分片继承该值写入 Milvus course_id）
     * @param createdBy   创建者 ID
     * @param isAdmin     是否为超管（超管旁路）
     * @return 已持久化文档的视图对象（不含内部路径 sourcePath）
     */
    public DocumentVO upload(
            Long kbId,
            String title,
            InputStream inputStream,
            String fileType,
            Long fileSize,
            String courseId,
            Long createdBy,
            boolean isAdmin) {
        // 校验知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: kbId=" + kbId);
        }

        // 归属校验：非超管只能上传到自己创建的知识库（P0-2c 跨库上传越权修复）
        if (!isAdmin && (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(createdBy))) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权向此知识库上传文档");
        }

        // uuid 先行（用户裁决，AGENTS.md 一致：先占外部资源再落库，单向补偿即可）：
        // objectKey 用 uuid（去横线）标识，与 docId 解耦；docId 由 MP 自动生成（ASSIGN_ID 雪花）
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String objectKey = minioStorageService.uploadFile(kbId, uuid, inputStream, fileType);

        // 创建 document 记录（sourcePath 一步带入，id 自动生成；course_id 空则 DEFAULT）
        Document doc = new Document();
        doc.setKbId(kbId);
        doc.setTitle(title);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setParseStatus("PENDING");
        doc.setChunkCount(0);
        doc.setMetadataJson("{}");
        doc.setCourseId(courseId != null && !courseId.isBlank() ? courseId : "DEFAULT");
        doc.setCreatedBy(createdBy);
        doc.setSourcePath(objectKey);
        try {
            documentMapper.insert(doc);
        } catch (Exception e) {
            // 单向补偿：唯一可能残留的方向是「MinIO 已传、DB 未落」→ 删已上传对象（幂等）后上抛
            log.error("文档落库失败，已补偿删除 MinIO 对象: objectKey={}", objectKey, e);
            minioStorageService.deleteFile(objectKey);
            throw e;
        }

        // 统计失效：文档数已变更（先写 DB 后失效，一致性铁律；ETL 终态由 EtlPipeline 失效）
        dashboardCacheEvictor.evictAll();

        log.info("文档已上传: docId={}, kbId={}, title={}, fileType={}", doc.getId(), kbId, title, fileType);

        // 触发 ETL 异步管道（M-7：队列满快速失败，不再 CallerRuns 内联阻塞上传请求线程）
        submitEtlOrFail(doc.getId());

        // Entity 出 service 边界前转 VO（sourcePath 因 VO 无此字段自然忽略）
        return documentConverter.toVO(doc);
    }

    /**
     * 批量查询文档标题（B3-3：检索链路按 doc_id 回填「来源文档」标注）
     *
     * <p>本 service 主表内置链式按需取列（id/title），单次 in 查询批量返回；
     * 纯读操作，无缓存（每次检索命中的 docId 集合不同，缓存收益低且引入失效复杂度）。
     * 调用方（SearchKnowledgeTool）负责回查失败的降级兜底。
     *
     * @param docIds 文档 ID 集合（null/空集合返回空 Map，不发起查询）
     * @return docId → title 映射；已删除/标题为 null 的文档不出现
     */
    @Override
    public Map<Long, String> mapTitlesByIds(Collection<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return Map.of();
        }
        // 本 service 主表：this.lambdaQuery() 链式 + 按需取列（宪法规范），一次 in 批量查询
        return this.lambdaQuery()
                .select(Document::getId, Document::getTitle)
                .in(Document::getId, docIds)
                .list()
                .stream()
                .filter(doc -> doc.getId() != null && doc.getTitle() != null)
                .collect(Collectors.toMap(Document::getId, Document::getTitle, (a, b) -> a));
    }

    /**
     * 按 ID 查询文档
     *
     * @param id     文档 ID
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时校验 ownership）
     * @return 文档视图对象（不含 sourcePath），不存在或无权访问返回 null
     */
    public DocumentVO findById(Long id, Long userId, String role) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return null;
        }
        // TEACHER 只能查看自己有权的文档（P2-1：文档属主 或 所属知识库属主——超管代传文档教师可管理）
        if ("TEACHER".equals(role) && !isOwnerOrKbOwner(doc, userId)) {
            return null;
        }
        return documentConverter.toVO(doc);
    }

    /**
     * 分页查询文档（P2-2 契约对齐：前端文档 :871 支持 status/q/sort 筛选）
     *
     * @param kbId   知识库 ID（可选，null = 查全部）
     * @param status 解析状态筛选（可选，parse_status 精确匹配）
     * @param q      标题关键词（可选，title like）
     * @param sort   排序（created=created_at 降序默认；updated=updated_at 降序；非法值按 created）
     * @param page   页码（1-based）
     * @param size   每页条数
     * @param userId 当前用户 ID（TEACHER 数据权限过滤）
     * @param role   当前用户角色（TEACHER 时按 created_by 过滤）
     * @return 分页结果（records 为文档视图对象，不含 sourcePath）
     */
    public IPage<DocumentVO> findPage(
            Long kbId, String status, String q, String sort, int page, int size, Long userId, String role) {
        Page<Document> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
        // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
        LambdaQueryWrapper<Document> wrapper = Wrappers.<Document>lambdaQuery()
                .eq(kbId != null, Document::getKbId, kbId)
                .eq(status != null && !status.isBlank(), Document::getParseStatus, status)
                .like(q != null && !q.isBlank(), Document::getTitle, q)
                .orderByDesc("updated".equals(sort) ? Document::getUpdatedAt : Document::getCreatedAt);
        if ("TEACHER".equals(role) && userId != null) {
            // P2-1: 教师可见 = 自己创建的文档 ∪ 自己知识库内的文档（超管代传文档在教师库内应可见可操作）
            List<Long> kbIds = knowledgeBaseMapper
                    .selectList(Wrappers.<KnowledgeBase>lambdaQuery()
                            .eq(KnowledgeBase::getCreatedBy, userId)
                            .select(KnowledgeBase::getId))
                    .stream()
                    .map(KnowledgeBase::getId)
                    .collect(Collectors.toList());
            if (kbIds.isEmpty()) {
                wrapper.eq(Document::getCreatedBy, userId);
            } else {
                wrapper.and(w -> w.eq(Document::getCreatedBy, userId).or().in(Document::getKbId, kbIds));
            }
        }
        Page<Document> entityPage = documentMapper.selectPage(pageObj, wrapper);
        // 实体分页 → VO 分页：records 逐条转换，total/current/size 分页语义保持
        Page<DocumentVO> voPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        voPage.setRecords(
                entityPage.getRecords().stream().map(documentConverter::toVO).collect(Collectors.toList()));
        return voPage;
    }

    /**
     * 更新文档标题
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能改自己的文档，超管旁路）。
     *
     * @param id         文档 ID
     * @param title      新标题
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     */
    public void update(Long id, String title, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能改名（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        LambdaUpdateWrapper<Document> wrapper = Wrappers.<Document>lambdaUpdate()
                .eq(Document::getId, id)
                .set(Document::getTitle, title)
                .set(Document::getUpdatedAt, LocalDateTime.now());
        documentMapper.update(null, wrapper);
        // 统计失效：文档已变更（先写 DB 后失效，一致性铁律）
        dashboardCacheEvictor.evictAll();
        log.info("更新文档: docId={}, title={}", id, title);
    }

    /**
     * 删除文档（级联：MinIO 删除 → Milvus 清理 → 软删 chunk + document）
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能删除自己的文档）
     * 删除顺序（P1-4 Bug 3 修复）：先删 MinIO 外部对象，失败上抛阻断，PG 记录保留可重试；
     * removeObject 幂等，重试可收敛。
     *
     * <p>B2-5 事务说明：document_chunk → document 两条软删 UPDATE 在同一事务内原子执行，
     * 中途失败整体回滚，避免留下"chunk 已删而 document 仍存活（chunk_count 非 0）"的中间态。
     * MinIO/Milvus 清理位于事务最前段：外部资源失败时事务内尚无任何 PG 写、回滚零代价；
     * 外部资源先行 + 幂等删除的既有重试收敛语义保持不变（事务注解不改变既有执行顺序）。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID（从 AuthInterceptor 注入的 userId 获取）
     * @param isAdmin    是否为超管（超管旁路）
     */
    @Transactional
    public void delete(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            return;
        }

        // 权限校验：只有文档创建者才能删除（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        // 1. MinIO 删除（P1-4 Bug 3 修复：先删外部资源，失败上抛阻断 → PG 记录保留可重试；
        //    removeObject 幂等，任一侧先失败重试均可收敛到"对象已删 + 记录已删"）
        if (doc.getSourcePath() != null) {
            minioStorageService.deleteFile(doc.getSourcePath());
        }

        // 2. Milvus 清理（filter 直删，失败上抛阻断）
        etlPipeline.deleteFromMilvusByDocId(id);

        // 3. 软删 document_chunk
        LambdaUpdateWrapper<DocumentChunk> chunkWrapper = Wrappers.<DocumentChunk>lambdaUpdate()
                .eq(DocumentChunk::getDocId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, chunkWrapper);

        // 4. 软删 document
        LambdaUpdateWrapper<Document> docWrapper = Wrappers.<Document>lambdaUpdate()
                .eq(Document::getId, id)
                .set(Document::getDeleted, System.currentTimeMillis());
        documentMapper.update(null, docWrapper);

        // 统计失效：文档/分片数已变更（先写 DB 后失效，一致性铁律）
        dashboardCacheEvictor.evictAll();

        log.info("删除文档（级联）: docId={}, operatorId={}", id, operatorId);
    }

    /**
     * 重新解析文档（从 MinIO 拉原文件重新 ETL）
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（TEACHER 只能重新解析自己的文档）。
     *
     * <p>B2-2 状态守卫（CAS）：重置 PENDING 采用条件更新——仅终态
     * （INDEXED=已索引 / FAILED=失败 / CHUNKED=分片完成可恢复）可重置；
     * PENDING（已排队）与中间执行态（PARSING/PARSED/CHUNKING/EMBEDDING）更新返回 0 行，
     * 抛 409 冲突。原实现无条件重置会把执行中文档改回 PENDING，绕过 EtlPipeline.process
     * 的抢占 CAS（其仅拦 PENDING/FAILED 竞争），第二个管道抢占成功与执行中管道双跑：
     * 分片 delete-then-insert 交错、parsedContentCache 互踩、终态互相覆盖、Milvus 双跑。
     *
     * <p>软删旧分片置于条件更新成功之后——执行中文档的分片不得被误删。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID（从 AuthInterceptor 注入的 userId 获取）
     * @param isAdmin    是否为超管（超管旁路）
     * @throws BizException 409——文档处于排队/执行中状态（未达终态），不可重解析
     */
    public void reparse(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能重新解析（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        // B2-2: CAS 重置——仅终态（INDEXED/FAILED/CHUNKED）可重置回 PENDING；
        // 返回 0 行 = 文档已排队或管道执行中（大 PDF 全程可达分钟级），拒绝并提示稍后重试
        // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
        int claimed = documentMapper.update(
                null,
                Wrappers.<Document>lambdaUpdate()
                        .eq(Document::getId, id)
                        .in(Document::getParseStatus, "INDEXED", "FAILED", "CHUNKED")
                        .set(Document::getParseStatus, "PENDING")
                        .set(Document::getErrorMessage, null)
                        .set(Document::getChunkCount, 0)
                        .set(Document::getUpdatedAt, LocalDateTime.now()));
        if (claimed == 0) {
            log.warn("重新解析被拒绝（文档解析执行中，未达终态）: docId={}, 当前状态={}", id, doc.getParseStatus());
            throw new BizException(ErrorCode.CONFLICT, "文档解析执行中，请稍后重试");
        }

        // 软删旧分片（B2-2: 置于 CAS 成功之后——执行中文档不得被误删分片）
        LambdaUpdateWrapper<DocumentChunk> chunkWrapper = Wrappers.<DocumentChunk>lambdaUpdate()
                .eq(DocumentChunk::getDocId, id)
                .set(DocumentChunk::getDeleted, System.currentTimeMillis());
        chunkMapper.update(null, chunkWrapper);

        // 重新触发 ETL（M-7：队列满快速失败，不再 CallerRuns 内联阻塞重解析请求线程）
        submitEtlOrFail(id);

        // 统计失效：文档状态已重置为 PENDING（先写 DB 后失效，一致性铁律；ETL 终态由 EtlPipeline 失效）
        dashboardCacheEvictor.evictAll();

        log.info("重新解析文档: docId={}, operatorId={}", id, operatorId);
    }

    /**
     * 提交 ETL 异步任务（M-7：etlPool 队列满时快速失败——回写文档 FAILED + 抛 503，
     * 替代原 CallerRunsPolicy 让上传/重解析的 HTTP 请求线程内联执行整个 ETL（分钟级阻塞））
     *
     * @param docId 文档 ID
     */
    private void submitEtlOrFail(Long docId) {
        try {
            etlPool.execute(() -> etlPipeline.process(docId));
        } catch (RejectedExecutionException e) {
            log.error("ETL 队列已满，文档快速失败: docId={}", docId, e);
            documentMapper.update(
                    null,
                    Wrappers.<Document>lambdaUpdate()
                            .eq(Document::getId, docId)
                            .set(Document::getParseStatus, "FAILED")
                            .set(Document::getErrorMessage, "ETL 队列已满，请稍后重试")
                            .set(Document::getUpdatedAt, LocalDateTime.now()));
            // 状态已变更（先写 DB 后失效，一致性铁律）
            dashboardCacheEvictor.evictAll();
            throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "文档解析队列繁忙，请稍后重试");
        }
    }

    /**
     * 下载文档原始文件
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（超管旁路）。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     * @return 文件输入流
     */
    public InputStream download(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能下载（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        if (doc.getSourcePath() == null) {
            throw new IllegalStateException("文档源文件路径为空: id=" + id);
        }
        return minioStorageService.downloadFile(doc.getSourcePath());
    }

    /**
     * 下载文档原始文件（含文件类型，perf P2-4：一次查询取实体，避免 controller 二次主键查询）
     *
     * <p>权限校验：operatorId 必须与文档 created_by 一致（超管旁路）。
     *
     * @param id         文档 ID
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路）
     * @return 下载结果（输入流 + 文件类型），供 controller 设置响应头/文件名
     */
    public IDocumentService.DocumentDownload downloadWithType(Long id, Long operatorId, boolean isAdmin) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new IllegalArgumentException("文档不存在: id=" + id);
        }

        // 权限校验：只有文档创建者才能下载（超管旁路）
        checkOwnership(doc, operatorId, isAdmin);

        if (doc.getSourcePath() == null) {
            throw new IllegalStateException("文档源文件路径为空: id=" + id);
        }
        return new IDocumentService.DocumentDownload(
                minioStorageService.downloadFile(doc.getSourcePath()), doc.getFileType());
    }

    /**
     * 权限校验 —— 校验操作者是否为文档属主或所属知识库属主
     *
     * <p>设计文档要求：TEACHER 只能操作自己创建的文档（checkOwnership(created_by)）。
     * 超级管理员可操作所有文档（isAdmin 旁路）。
     * P2-1 修复：增加「知识库属主」旁路——上传校验锚定 kb.createdBy（超管可代传，
     * doc.createdBy=超管），操作校验若仍只锚定 doc.createdBy，文档会躺在教师自己的
     * 知识库里却不可操作；两处锚定统一为「文档属主或知识库属主」。
     *
     * @param doc        文档实体（已查询）
     * @param operatorId 操作者 ID
     * @param isAdmin    是否为超管（超管旁路，不校验 ownership）
     * @throws ResponseStatusException 如果 operatorId 与文档/知识库属主均不匹配，抛出 403 FORBIDDEN
     */
    private void checkOwnership(Document doc, Long operatorId, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (!isOwnerOrKbOwner(doc, operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权操作此文档: 只有文档/知识库属主可以执行此操作");
        }
    }

    /**
     * 归属判定：文档属主 或 所属知识库属主（P2-1，findById/checkOwnership 共用）
     */
    private boolean isOwnerOrKbOwner(Document doc, Long operatorId) {
        if (doc.getCreatedBy() != null && doc.getCreatedBy().equals(operatorId)) {
            return true;
        }
        // 知识库属主旁路：超管代传的文档（createdBy=超管）在教师自己库内，教师应可管理
        if (doc.getKbId() != null) {
            KnowledgeBase kb = knowledgeBaseMapper.selectById(doc.getKbId());
            if (kb != null && kb.getCreatedBy() != null && kb.getCreatedBy().equals(operatorId)) {
                return true;
            }
        }
        return false;
    }
}
