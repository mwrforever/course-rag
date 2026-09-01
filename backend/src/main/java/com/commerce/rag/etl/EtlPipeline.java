package com.commerce.rag.etl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.cache.DashboardCacheEvictor;
import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.properties.EtlProperties;
import com.commerce.rag.record.ChunkLinkPair;
import com.commerce.rag.record.ChunkVectorUpdate;
import com.commerce.rag.record.ContentHash;
import com.commerce.rag.storage.MinioStorageService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ETL 异步管道 —— 文档解析 → 文本分片（TokenTextSplitter）→ 向量化 + Milvus 索引
 *
 * <p>状态机：PENDING → PARSING → PARSED → CHUNKING → CHUNKED → EMBEDDING → INDEXED
 * 任何阶段失败 → FAILED（不阻断，记录 error_message）。
 *
 * <p>旁路修正模式：一次性跑完到 INDEXED，知识库立即可检索。
 * B 端后续批量修正 chunk 元数据（collection_type / course_id）。
 *
 * <p>线程池：core-size=2, max-size=4, queue-capacity=20, thread-name-prefix=etl-
 * （由 EtlConfig.etlPool 提供，调用方通过 execute() 提交）；
 * 图片 upload+caption 子任务走独立 etlImagePool（P2-2b，防主任务占用 etlPool 时子任务自锁）。
 *
 * <p>Milvus upsert 策略：delete-then-insert。
 * PG 冗余 dense_vector（BYTEA）避免回查 Milvus。
 *
 * <p>依赖注入：Lombok @RequiredArgsConstructor 构造器注入（12 个 private final 依赖：
 * DocumentMapper / DocumentChunkMapper / MinioStorageService / EmbeddingModel /
 * MilvusClientV2 / EtlProperties / etlImagePool / dashboardStatsCache / XhtmlDocumentParser /
 * TableChunker / ImageCaptionService / TransactionTemplate）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class EtlPipeline {

    private static final Logger log = LoggerFactory.getLogger(EtlPipeline.class);

    /** Milvus Collection 名称（引用 MilvusCollectionInitializer 公开常量） */
    private static final String COLLECTION_NAME = MilvusCollectionInitializer.COLLECTION_NAME;

    /** Milvus dense 向量字段名（引用 MilvusCollectionInitializer 公开常量） */
    private static final String VECTOR_FIELD_NAME = MilvusCollectionInitializer.FIELD_DENSE_VECTOR;

    /** content 字段最大长度（Milvus VARCHAR 限制） */
    private static final int MAX_CONTENT_LENGTH = 65535;

    /** 影响 Dashboard 统计口径的解析状态（分片落库/终态；中间态不改变统计） */
    private static final Set<String> STATS_AFFECTING_STATUSES = Set.of("CHUNKED", "INDEXED", "FAILED");

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final MinioStorageService minioStorageService;
    private final EmbeddingModel embeddingModel;
    private final MilvusClientV2 milvusClientV2;
    private final EtlProperties etlProperties;

    /**
     * ETL 图片并行池（P2-2b：图片 upload+caption 子任务专用，与 etlPool 隔离——
     * ETL 主任务占用 etlPool 时子任务同池排队自锁死；池大小同时是 VLM 并发上限）
     */
    @Qualifier("etlImagePool")
    private final ThreadPoolExecutor etlImagePool;

    /** Dashboard 统计缓存失效（Spring Cache 注解化的写方统一出口，先写 DB 后失效——一致性铁律） */
    private final DashboardCacheEvictor dashboardCacheEvictor;

    /** XHTML 结构解析器（纯函数，Tika 解析 → 结构化分区） */
    private final XhtmlDocumentParser xhtmlDocumentParser;

    /** 表格分片器（HTML 表格 → Markdown，大表按行分组/表头重复/overlap 行，Task 6 接入） */
    private final TableChunker tableChunker;

    /** 图片描述（caption）服务（VLM 生成中文图片描述，Task 7 接入图片分片） */
    private final ImageCaptionService imageCaptionService;

    /**
     * 编程式事务模板（P2-4：分片落库「软删 + 批插 + 链回填」三写原子化）。
     * 用 TransactionTemplate 而非 @Transactional——process 异步线程内同类自调用
     * （this.chunkDocument()）不经过 Spring 代理，注解事务不生效。
     */
    private final TransactionTemplate transactionTemplate;

    /** 解析内容内存缓存（docId → 结构化分区），仅在同一线程内有效 */
    private final ConcurrentHashMap<Long, ParsedContent> parsedContentCache = new ConcurrentHashMap<>();

    /**
     * 文档实体缓存（docId → Document，P1-4 顺带：process 链 selectById 收敛）。
     * process 入口查询一次后传递给三阶段（parse/chunk/embed），消除同请求内 3 次重复主键查询；
     * 三阶段独立调用时 computeIfAbsent 回源查库。管道自身只改 parse_status/error_message/
     * chunk_count 等字段，三阶段消费的 sourcePath/title/kbId/courseId 在管道期间不变，复用安全。
     */
    private final ConcurrentHashMap<Long, Document> documentCache = new ConcurrentHashMap<>();

    // ========================================================================
    // 完整管道入口
    // ========================================================================

    /**
     * 执行完整 ETL 管道（异步调用）
     *
     * <p>流程：parseDocument → chunkDocument → embedAndIndex
     * 任何阶段失败 → 设置 status=FAILED，记录 error_message
     *
     * <p>P2-1 状态守卫：入口先做原子抢占（条件 UPDATE，CAS 语义），
     * 仅 PENDING/FAILED 状态能抢到 PARSING；抢不到（已在执行/已完成）直接跳过，
     * 从根上消除并发双跑。
     */
    public void process(Long docId) {
        log.info("ETL 管道启动: docId={}", docId);
        try {
            Document doc = documentMapper.selectById(docId);
            if (doc == null) {
                throw new IllegalStateException("文档不存在: docId=" + docId);
            }
            // P2-1: 原子抢占状态——仅 PENDING/FAILED 可抢到 PARSING（条件更新返回行数=0
            // 说明已在执行/已完成，跳过；CAS 语义消除并发双跑）
            // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
            int claimed = documentMapper.update(
                    null,
                    Wrappers.<Document>lambdaUpdate()
                            .eq(Document::getId, docId)
                            .in(Document::getParseStatus, "PENDING", "FAILED")
                            .set(Document::getParseStatus, "PARSING")
                            .set(Document::getUpdatedAt, LocalDateTime.now()));
            if (claimed == 0) {
                log.warn("ETL 跳过: docId={} 非 PENDING/FAILED 状态（已在执行或已完成）", docId);
                return;
            }
            // P1-4: 文档实体入缓存，三阶段复用（消除 parse/chunk/embed 各自查库的同请求重复主键查询）
            documentCache.put(docId, doc);
            parseDocument(docId);
            chunkDocument(docId);
            embedAndIndex(docId);
            log.info("ETL 管道完成: docId={}", docId);
        } catch (Exception e) {
            log.error("ETL 管道失败: docId={}", docId, e);
            updateDocStatus(docId, "FAILED", e.getMessage());
        } finally {
            // perf P3-3: 任何路径（含异常）都清理解析内容缓存——parse 成功但 chunk/embed
            // 失败时缓存残留会随 docId 递增持续增长（反复 reparse 失败即内存泄漏）；
            // P1-4: 文档实体缓存同窗口清理（三阶段复用仅限本次管道执行）
            parsedContentCache.remove(docId);
            documentCache.remove(docId);
        }
    }

    // ========================================================================
    // 阶段 1：文档解析（Tika）
    // ========================================================================

    /**
     * Tika 解析文档 → XHTML 结构化解析（标题路径 + 内嵌图片捕获 + 分区）
     *
     * <p>状态：PENDING → PARSING → PARSED
     */
    public void parseDocument(Long docId) throws Exception {
        // P1-4: process 链内命中文档缓存；独立调用时回源查库
        Document doc = documentCache.computeIfAbsent(docId, documentMapper::selectById);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: docId=" + docId);
        }

        updateDocStatus(docId, "PARSING", null);
        log.info("开始解析文档: docId={}, title={}", docId, doc.getTitle());

        // 从 MinIO 下载文件
        // P2-1: try-with-resources——Tika 解析异常/损坏文件时流必关（防 MinIO 句柄泄漏）
        try (InputStream inputStream = minioStorageService.downloadFile(doc.getSourcePath())) {
            // Tika 解析 → XHTML（保留 table/img/标题结构，供结构化解析）
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ToHTMLContentHandler handler = new ToHTMLContentHandler(out, "UTF-8");
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            // PDF 图片提取必须显式开启：Tika 2.9.2 默认 extractInlineImages=false
            // （extractImages 入口直接 return，XObject 图片也不提取，字节码实锤），
            // 开启后由 ImageGraphicsEngine 按 RAW_IMAGES 策略提取原始图片字节路由到 TikaImageExtractor
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(true);
            pdfConfig.setImageStrategy(PDFParserConfig.IMAGE_STRATEGY.RAW_IMAGES);
            context.set(PDFParserConfig.class, pdfConfig);
            TikaImageExtractor imageExtractor = new TikaImageExtractor();
            context.set(EmbeddedDocumentExtractor.class, imageExtractor);
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, context);

            String xhtml = out.toString(StandardCharsets.UTF_8);
            ParsedContent parsed = xhtmlDocumentParser.parse(xhtml, imageExtractor.getImages());
            log.info(
                    "文档解析完成: docId={}, XHTML字符数={}, 捕获图片数={}",
                    docId,
                    xhtml.length(),
                    imageExtractor.getImages().size());

            // 将解析结果暂存到内存缓存（供 chunkDocument 阶段使用）
            parsedContentCache.put(docId, parsed);
        }

        updateDocStatus(docId, "PARSED", null);
    }

    // ========================================================================
    // 阶段 2：文本分片（TokenTextSplitter）
    // ========================================================================

    /**
     * 文本分片 —— TokenTextSplitter 按 token 分片（etl.chunk.size=768，1.1.2 无 overlap 参数）
     *
     * <p>新分片模型：buildChunkSpecs 按文档顺序组装 ChunkSpec（文本/表格/图片统一载体，Task 6/7
     * 扩展），删除手写递归分片的父子段落关联；文档内以 prev/next_chunk_id 线性链串联。
     *
     * <p>P1-4/P2-4 落库批量化：先组装全部实体再一次（分批）batchInsert
     * （原逐条 insert N 次往返 → ceil(N/批上限) 次）；实体 ID 由 MP MybatisParameterHandler
     * 在 batchInsert 调用时自动填充 ASSIGN_ID 雪花（实测见 BatchInsertIdFillTest），故
     * prev/next 链组装移到插入后，经 batchUpdateChunkLinks 单条 CASE WHEN 回填双向指针。
     *
     * <p>P2-4 事务：软删旧 chunk + 批插 + 链回填三写包在编程式事务内（TransactionTemplate——
     * process 异步线程无外层事务，且同类自调用下 @Transactional 代理不生效），失败整体回滚，
     * 保持 delete-then-insert 幂等。
     *
     * <p>状态：PARSED → CHUNKING → CHUNKED
     */
    public void chunkDocument(Long docId) {
        // P1-4: process 链内命中文档缓存；独立调用时回源查库
        Document doc = documentCache.computeIfAbsent(docId, documentMapper::selectById);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: docId=" + docId);
        }

        updateDocStatus(docId, "CHUNKING", null);
        log.info("开始分片: docId={}", docId);

        ParsedContent parsed = parsedContentCache.get(docId);
        if (parsed == null) {
            throw new IllegalStateException("解析结果为空或未找到: docId=" + docId);
        }

        // 组装待落库分片（按文档顺序：文本/表格/图片）
        List<ChunkSpec> rawSpecs = buildChunkSpecs(doc, parsed);
        if (rawSpecs.isEmpty()) {
            throw new IllegalStateException("分片结果为空: docId=" + docId);
        }
        // SHA256 全局去重（spec §4.4）：批内去重 + 查库跳过，全局唯一硬约束
        List<ChunkSpec> specs = deduplicateSpecs(rawSpecs, doc.getId());
        if (specs.isEmpty()) {
            log.info("全部内容已存在（SHA256 去重），无新分片入库: docId={}", docId);
            updateDocChunkCount(docId, 0);
            parsedContentCache.remove(docId);
            updateDocStatus(docId, "CHUNKED", null);
            return;
        }

        // P1-4: 组装全部待落库实体（chunkIndex 按序递增；ID 待 batchInsert 时由 MP 填充，
        // prev/next 链延后到插入后组装——插入前 ID 未知）
        List<DocumentChunk> chunks = new ArrayList<>(specs.size());
        for (ChunkSpec spec : specs) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(docId);
            chunk.setKbId(doc.getKbId());
            chunk.setChunkIndex(chunks.size());
            chunk.setContent(spec.content());
            chunk.setSha256(ContentHash.of(spec.content()).sha256());
            chunk.setHeadingPath(spec.headingPath());
            chunk.setContentType(spec.contentType());
            chunk.setImageUrl(spec.imageUrl());
            chunk.setMetadataJson(spec.metadataJson() != null ? spec.metadataJson() : "{}");
            chunk.setTokenCount(TokenEstimator.estimate(spec.content()));
            // PG 遗留列：检索不再使用，保持既有默认（admin 校正工作流依赖该列）
            chunk.setCollectionType("TECHNICAL_QA");
            // 课程归属：优先取文档级 course_id（上传时前端可指定），空则 DEFAULT=通用资料库
            chunk.setCourseId(
                    doc.getCourseId() != null && !doc.getCourseId().isBlank() ? doc.getCourseId() : "DEFAULT");
            chunk.setCharOffsetStart(spec.charOffsetStart());
            chunk.setCharOffsetEnd(spec.charOffsetEnd());
            chunk.setCorrectionStatus("PENDING");
            chunks.add(chunk);
        }

        // 落库（P2-7 幂等 + P2-4 事务：软删旧 chunk → 批插新分片 → 链回填，三写原子）
        transactionTemplate.executeWithoutResult(txStatus -> persistChunks(docId, chunks));

        // 更新文档分片数（实际入库数）
        updateDocChunkCount(docId, chunks.size());

        // 清理缓存
        parsedContentCache.remove(docId);

        updateDocStatus(docId, "CHUNKED", null);
    }

    /**
     * 分片落库（事务内三写：软删旧 chunk → 批量插入 → prev/next 链回填）
     *
     * <p>P1-4/P2-4：批量插入按 chunk-insert-batch-size 上限分批（防超长 SQL），
     * 实体 ID 由 MP MybatisParameterHandler 在 INSERT 时自动填充 ASSIGN_ID 雪花；
     * 插入后 ID 已知，组装线性链并单条 CASE WHEN 批量回填双向指针
     * （原逐条 insert 时 prev 随行落库，批插后统一回填，行为等价）。
     *
     * @param docId  文档 ID（软删旧分片的过滤键）
     * @param chunks 待落库分片实体列表（按文档顺序；ID 由批插填充后用于链组装）
     */
    private void persistChunks(Long docId, List<DocumentChunk> chunks) {
        // P2-7: delete-then-insert 幂等化——先软删该文档旧 chunk，再插入新分片
        chunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getDocId, docId)
                        .set(DocumentChunk::getDeleted, System.currentTimeMillis()));

        // P1-4: 批量插入（原逐条 insert N 次往返 → ceil(N/批上限) 次批处理）
        int batchSize = etlProperties.chunkInsertBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            chunkMapper.batchInsert(chunks.subList(start, Math.min(start + batchSize, chunks.size())));
        }

        // P1-4/M-1: 插入后组装线性链并批量回填 prev/next 双向指针（单条 CASE WHEN UPDATE）
        List<ChunkLinkPair> linkPairs = new ArrayList<>(chunks.size() - 1);
        for (int i = 1; i < chunks.size(); i++) {
            Long prevId = chunks.get(i - 1).getId();
            Long currId = chunks.get(i).getId();
            chunks.get(i).setPrevChunkId(prevId);
            linkPairs.add(new ChunkLinkPair(prevId, currId));
        }
        if (!linkPairs.isEmpty()) {
            chunkMapper.batchUpdateChunkLinks(linkPairs);
        }
    }

    /**
     * 组装待落库分片 —— 按文档顺序遍历结构分区，按类型分片
     * （文本走 TokenTextSplitter；表格走 TableChunker；图片 upload+caption 并行，P2-2b）
     *
     * <p>P2-2b 图片并行编排（两遍式）：
     * <ol>
     *   <li>第一遍串行：文本/表格分片原位组装；图片做<b>本地判定</b>（小图标/装饰图过滤 +
     *       字节去重，无远程 IO）——判定顺序与结果和串行实现一致；有效图片提交
     *       {@code etlImagePool} 并行执行 MinIO 上传 + VLM caption（每图独立 future +
     *       单图超时），槽位按文档原序占位</li>
     *   <li>第二遍回填：按文档原序 join——chunkIndex 与图片位置语义不随完成顺序变化；
     *       单图失败/超时仅跳过该图（记 warn），文档 ETL 继续（spec §4.2）</li>
     * </ol>
     *
     * @param doc    文档实体（图片分片 MinIO 上传需要 kbId）
     * @param parsed 解析后的结构化分区
     */
    @SuppressWarnings("unchecked")
    private List<ChunkSpec> buildChunkSpecs(Document doc, ParsedContent parsed) {
        // 槽位列表：文本/表格为已成品 spec；图片为并行 future（join 后按位回填）
        List<Object> slots = new ArrayList<>();
        // 图片字节级去重表（sha256 已提交标记），仅本文件内有效（同图只处理一次）
        Set<String> processedImageHashes = new HashSet<>();
        for (ParsedContent.ParsedSection section : parsed.sections()) {
            if (section instanceof ParsedContent.TextSection text) {
                slots.addAll(splitTextSection(text));
            } else if (section instanceof ParsedContent.TableSection table) {
                slots.addAll(tableChunker.chunk(table.html(), table.headingPath()));
            } else if (section instanceof ParsedContent.ImageSection image) {
                // 第一遍（串行本地判定）：小图标/装饰图过滤 + 字节去重，与串行实现语义一致
                if (ImageFilter.isSmallIcon(image.bytes(), etlProperties.imageMinSizeKb())) {
                    log.info(
                            "图片过滤（小于 {}KB）: docId={}, resource={}",
                            etlProperties.imageMinSizeKb(),
                            doc.getId(),
                            image.resourceName());
                    continue;
                }
                if (ImageFilter.isDecorative(image.bytes())) {
                    log.info("图片过滤（装饰图）: docId={}, resource={}", doc.getId(), image.resourceName());
                    continue;
                }
                String byteHash = ContentHash.sha256Hex(image.bytes());
                if (!processedImageHashes.add(byteHash)) {
                    log.info("图片字节去重（同图只处理一次）: docId={}, resource={}", doc.getId(), image.resourceName());
                    continue;
                }
                // 第二段：upload+caption 远程 IO 并行（池内独立执行，单图超时由 orTimeout 隔离；
                // 队列满拒绝（AbortPolicy）同样走单图失败跳过语义）
                // P2-2b orTimeout 语义：超时仅使 future 异常完成、放弃等待结果——不中断
                // etlImagePool 线程内进行中的 MinIO/VLM 调用（CompletableFuture 无底层线程中断能力），
                // 池线程自然跑完当前任务后归还；超时期间该线程仍被占用，由有界队列 +
                // AbortPolicy（EtlConfig.etlImagePool）兜底限流，不至无界堆积
                slots.add(CompletableFuture.supplyAsync(() -> processImageSpec(doc, image), etlImagePool)
                        .orTimeout(etlProperties.imageExecutor().processTimeoutSeconds(), TimeUnit.SECONDS));
            }
        }
        // 第二遍：按文档原序 join 回填（future 异常 = 单图失败/超时 → 跳过该图，文档 ETL 继续）
        List<ChunkSpec> specs = new ArrayList<>(slots.size());
        for (Object slot : slots) {
            if (slot instanceof ChunkSpec spec) {
                specs.add(spec);
                continue;
            }
            CompletableFuture<ChunkSpec> future = (CompletableFuture<ChunkSpec>) slot;
            try {
                ChunkSpec spec = future.join();
                if (spec != null) {
                    specs.add(spec);
                }
            } catch (CompletionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("图片处理失败/超时，跳过该图（文档 ETL 继续）: docId={}, error={}", doc.getId(), cause.getMessage());
            }
        }
        return specs;
    }

    /**
     * 单图处理：MinIO 上传 → VLM caption → 图片分片规格（P2-2b：在 etlImagePool 线程并行执行）
     *
     * <p>容错语义与串行实现一致：caption 为空返回 null（该图跳过）；
     * 上传/caption 异常与单图超时由调用方统一按「跳过该图」捕获处理（spec §4.2）。
     *
     * @param doc   文档实体（kbId 用于 MinIO objectKey）
     * @param image 图片分区（字节 + MIME + 章节路径）
     * @return 图片分片规格；caption 为空返回 null
     */
    private ChunkSpec processImageSpec(Document doc, ParsedContent.ImageSection image) {
        String objectKey = uploadImage(doc, image);
        String caption = imageCaptionService.caption(image.bytes(), image.mimeType());
        if (caption == null || caption.isBlank()) {
            log.warn("图片 caption 为空，跳过该图: docId={}, resource={}", doc.getId(), image.resourceName());
            return null;
        }
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("resourceName", image.resourceName());
        if (image.headingPath() != null && !image.headingPath().isBlank()) {
            meta.put("headingPath", image.headingPath());
        }
        return new ChunkSpec(caption, image.headingPath(), "image", objectKey, new Gson().toJson(meta), null, null);
    }

    /**
     * SHA256 内容去重 —— 批内（同 hash 保留首个）+ 查库（跳过其他文档既有 hash；deleted=0 由 @TableLogic 自动过滤）
     *
     * <p>spec §4.4：同 sha256 全库只存一条（全局唯一硬约束）；检索侧防御去重在计划 2/5。
     *
     * @param specs 待去重的分片规格列表（批内同 hash 保留首个）
     * @param docId 当前文档 ID——查询排除本文档自身既有 chunk（失败重跑/预留重跑路径下旧 chunk 不应作为
     *              「已存在」去重依据，delete-then-insert 幂等语义先软删再重插接管）；仅跨文档去重
     */
    private List<ChunkSpec> deduplicateSpecs(List<ChunkSpec> specs, Long docId) {
        Map<String, ChunkSpec> byHash = new LinkedHashMap<>();
        for (ChunkSpec spec : specs) {
            byHash.putIfAbsent(ContentHash.of(spec.content()).sha256(), spec);
        }
        List<String> hashes = new ArrayList<>(byHash.keySet());
        Set<String> existing = chunkMapper
                .selectList(Wrappers.<DocumentChunk>lambdaQuery()
                        .select(DocumentChunk::getSha256)
                        .in(DocumentChunk::getSha256, hashes)
                        // 排除本文档自身既有 chunk——金融/重跑路径下旧 chunk 不应作为「已存在」去重依据，delete-then-insert 语义接管
                        .ne(DocumentChunk::getDocId, docId))
                .stream()
                .map(DocumentChunk::getSha256)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        List<ChunkSpec> unique = new ArrayList<>();
        for (Map.Entry<String, ChunkSpec> entry : byHash.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                unique.add(entry.getValue());
            }
        }
        log.info(
                "SHA256 去重: 原始={}, 批内去重后={}, 查库跳过={}, 入库={}",
                specs.size(),
                byHash.size(),
                existing.size(),
                unique.size());
        return unique;
    }

    /**
     * 上传图片字节到 MinIO（uuid 预生成 objectKey，与文档上传同一资源先占策略）
     */
    private String uploadImage(Document doc, ParsedContent.ImageSection image) {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return minioStorageService.uploadFile(
                doc.getKbId(), uuid, new ByteArrayInputStream(image.bytes()), extensionOf(image.mimeType()));
    }

    /** MIME → 文件扩展名（未知类型回退 bin，MinIO objectKey 后缀用） */
    private static String extensionOf(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/bmp" -> "bmp";
            default -> "bin";
        };
    }

    /**
     * 文本分区分片 —— TokenTextSplitter + 过小 chunk 并入前一个
     */
    private List<ChunkSpec> splitTextSection(ParsedContent.TextSection section) {
        TextChunkSplitter splitter = new TextChunkSplitter(
                etlProperties.chunk().size(), etlProperties.chunk().minChunkSizeChars());
        List<String> pieces = new ArrayList<>();
        for (String piece : splitter.splitText(section.text())) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                pieces.add(trimmed);
            }
        }
        mergeSmallPieces(pieces, etlProperties.chunk().minChunkSizeChars());

        List<ChunkSpec> specs = new ArrayList<>(pieces.size());
        String raw = section.text();
        int cursor = 0;
        for (String piece : pieces) {
            // 字符偏移尽力而为：decode 往返通常保留原文子串；未命中时退化为游标位置
            int start = raw.indexOf(piece, cursor);
            if (start < 0) {
                start = cursor;
            }
            specs.add(new ChunkSpec(piece, section.headingPath(), "text", null, null, start, start + piece.length()));
            cursor = start + piece.length();
        }
        return specs;
    }

    /**
     * 过小 chunk（< minChars 字符）并入前一个，避免尾部碎块独立成片（spec §4.1）
     */
    private void mergeSmallPieces(List<String> pieces, int minChars) {
        for (int i = pieces.size() - 1; i > 0; i--) {
            if (pieces.get(i).length() < minChars) {
                pieces.set(i - 1, pieces.get(i - 1) + "\n" + pieces.get(i));
                pieces.remove(i);
            }
        }
    }

    // ========================================================================
    // 阶段 3：向量化 + Milvus 索引
    // ========================================================================

    /**
     * 向量化 + Milvus upsert（delete-then-insert）
     *
     * <p>状态：CHUNKED → EMBEDDING → INDEXED
     */
    public void embedAndIndex(Long docId) {
        // P1-4: process 链内命中文档缓存；独立调用时回源查库
        Document doc = documentCache.computeIfAbsent(docId, documentMapper::selectById);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: docId=" + docId);
        }

        updateDocStatus(docId, "EMBEDDING", null);
        log.info("开始向量化: docId={}", docId);

        // 查询该文档所有分片
        List<DocumentChunk> chunks = chunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                .eq(DocumentChunk::getDocId, docId)
                .orderByAsc(DocumentChunk::getChunkIndex));

        // 先删除 Milvus 中该文档的旧记录
        deleteFromMilvusByDocId(docId);

        // H-3: 批量向量化——按批调用 embedding（一次请求携带多文本，调用次数 = 分片数/批大小）、
        // PG 向量批量回写（单条 CASE WHEN UPDATE）、Milvus 多行插入（InsertReq.data 多行）
        int failedCount = 0;
        int batchSize = etlProperties.embeddingBatchSize();
        for (int start = 0; start < chunks.size(); start += batchSize) {
            List<DocumentChunk> batch = chunks.subList(start, Math.min(start + batchSize, chunks.size()));
            try {
                // 批量 embedding（DashScope 一次请求携带全部文本，序与输入一致）
                List<float[]> vectors = embeddingModel.embed(
                        batch.stream().map(DocumentChunk::getContent).toList());
                List<DocumentChunk> indexed = new ArrayList<>();
                List<float[]> indexedVectors = new ArrayList<>();
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = vectors.get(i);
                    if (vector == null || vector.length == 0) {
                        log.warn("Embedding 返回空向量: chunkId={}", batch.get(i).getId());
                        // 空向量计入失败（与 P2-1「部分失败标 FAILED」语义一致，避免静默跳过误标 INDEXED 导致检索漏召回）
                        failedCount++;
                    } else {
                        indexed.add(batch.get(i));
                        indexedVectors.add(vector);
                    }
                }
                if (indexed.isEmpty()) {
                    continue;
                }

                // PG 向量批量回写（dense_vector BYTEA + milvus_pk）
                List<ChunkVectorUpdate> vectorUpdates = new ArrayList<>(indexed.size());
                for (int i = 0; i < indexed.size(); i++) {
                    vectorUpdates.add(new ChunkVectorUpdate(
                            indexed.get(i).getId(),
                            floatArrayToBytes(indexedVectors.get(i)),
                            String.valueOf(indexed.get(i).getId())));
                }
                chunkMapper.batchUpdateVectors(vectorUpdates);

                // Milvus 多行插入（每批一次 InsertReq，原逐 chunk 单行 insert）
                List<JsonObject> rows = new ArrayList<>(indexed.size());
                for (int i = 0; i < indexed.size(); i++) {
                    rows.add(buildMilvusRow(indexed.get(i), indexedVectors.get(i), doc.getTitle()));
                }
                insertToMilvusBatch(rows);

                log.debug("分片批次已索引: docId={}, 批次起始={}, 数量={}", docId, start, indexed.size());
            } catch (Exception e) {
                // 批次级失败：该批分片全部计入失败（embedding API/PG/Milvus 异常多为全局性，
                // 与单分片失败粒度差异可接受），继续处理其他批次
                log.error("分片批次向量化失败: docId={}, 批次起始={}, size={}", docId, start, batch.size(), e);
                failedCount += batch.size();
            }
        }

        // P2-1: 部分失败标 FAILED（避免误标 INDEXED 导致检索漏召回），全部成功才 INDEXED
        if (failedCount > 0) {
            // P2-6: 清空 Milvus 半成品——旧向量已在开头全删、新向量只插入一部分，
            // 若不清空则 FAILED 文档的残缺内容在学生端持续被检索命中（漏召回）；
            // 清空后重试前该文档 fail-closed（不命中任何内容），重跑时 delete-then-insert 幂等收敛
            try {
                deleteFromMilvusByDocId(docId);
            } catch (Exception e) {
                log.warn("FAILED 文档 Milvus 半成品清理失败（重试/删除时会再次清理）: docId={}", docId, e);
            }
            updateDocStatus(docId, "FAILED", "分片向量化失败: " + failedCount + "/" + chunks.size());
            log.warn("向量化部分失败: docId={}, 失败={}/{}", docId, failedCount, chunks.size());
            return;
        }
        updateDocStatus(docId, "INDEXED", null);
        log.info("向量化完成: docId={}, 分片数={}", docId, chunks.size());
    }

    // ========================================================================
    // 重新向量化（单个 chunk，用于 content 更新）
    // ========================================================================

    /**
     * 重新向量化单个分片（content 变更后调用）
     *
     * <p>流程：embed → 更新 PG dense_vector → Milvus delete-then-insert
     */
    public void reEmbedAndUpsert(Long chunkId) {
        DocumentChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalStateException("分片不存在: chunkId=" + chunkId);
        }

        Document doc = documentMapper.selectById(chunk.getDocId());
        String docTitle = doc != null ? doc.getTitle() : "";

        // 重新 embedding
        float[] vector = embeddingModel.embed(chunk.getContent());
        if (vector == null || vector.length == 0) {
            throw new RuntimeException("Embedding 返回空向量: chunkId=" + chunkId);
        }

        // 更新 PG dense_vector
        byte[] denseVector = floatArrayToBytes(vector);
        updateChunkVector(chunkId, denseVector, String.valueOf(chunkId));

        // Milvus delete-then-insert
        deleteFromMilvusByChunkId(String.valueOf(chunkId));
        insertToMilvusBatch(List.of(buildMilvusRow(chunk, vector, docTitle)));

        log.info("分片重新向量化完成: chunkId={}", chunkId);
    }

    // ========================================================================
    // Milvus 清理方法（供 Service 层级联删除调用）
    // ========================================================================

    /**
     * 按 doc_id 删除 Milvus 中该文档的所有分片。
     *
     * <p>P1-4 Bug 1 修复：直接按 Milvus doc_id 字段过滤一次删除，不再查 PG chunk 表——
     * 规避 MP 逻辑删除过滤（@TableLogic 自动过滤 deleted=0）导致已软删 chunk 的向量漏删
     * （reparse 场景旧向量永久残留）。删除失败上抛，阻断调用方 PG 软删（失败可见可重试）。
     */
    public void deleteFromMilvusByDocId(Long docId) {
        String filter = "doc_id == \"" + docId + "\"";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build();
        milvusClientV2.delete(deleteReq);
        log.info("Milvus 清理完成（按文档）: docId={}", docId);
    }

    /**
     * 按 kb_id 删除 Milvus 中该知识库的所有分片。
     *
     * <p>P1-4 修复同 {@link #deleteFromMilvusByDocId(Long)}：filter 直删，不查 PG。
     */
    public void deleteFromMilvusByKbId(Long kbId) {
        String filter = "kb_id == \"" + kbId + "\"";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build();
        milvusClientV2.delete(deleteReq);
        log.info("Milvus 清理完成（按知识库）: kbId={}", kbId);
    }

    /**
     * 按 course_id 删除 Milvus 中该课程标注的所有分片。
     *
     * <p>P1-4 Bug 2 修复：课程删除需同步清理 Milvus（course_id 为 Milvus 现有 VARCHAR 字段，
     * 过滤值格式与 ICourseService.deleteCourse 软删 chunk 的 courseIdStr 一致）。
     * 删除失败上抛，阻断课程级联软删（失败可见可重试）。
     *
     * @param courseId 课程 ID 字符串
     */
    public void deleteFromMilvusByCourseId(String courseId) {
        String filter = "course_id == \"" + courseId + "\"";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build();
        milvusClientV2.delete(deleteReq);
        log.info("Milvus 清理完成（按课程）: courseId={}", courseId);
    }

    /**
     * 删除 Milvus 中单个分片（v2 API：DeleteReq + filter）
     *
     * <p>P0-8 修复：删除失败上抛（不再吞异常）——调用方（IDocumentChunkService.delete）
     * 先删 Milvus 再软删 PG，失败阻断 PG 软删，可重试收敛；吞异常会导致向量永久残留可检索。
     *
     * @param chunkIdStr 分片 ID 字符串
     */
    public void deleteFromMilvusByChunkId(String chunkIdStr) {
        String filter = "chunk_id == \"" + chunkIdStr + "\"";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build();
        milvusClientV2.delete(deleteReq);
        log.info("Milvus 清理完成（按分片）: chunkId={}", chunkIdStr);
    }

    /**
     * 按 chunk_id 列表批量删除 Milvus 记录（filter IN 一次删除，P0-1 课程删除按 PG 关联清理用）
     *
     * <p>与 {@link #deleteFromMilvusByCourseId} 的差异：Milvus 侧 course_id 标注与 PG 不同步时，
     * 按 course_id 过滤删不到向量；按 PG 查出的 chunk_id 列表 IN 删除可精确清理。
     * 删除失败上抛，阻断调用方 PG 软删（失败可见可重试）。
     *
     * @param chunkIds 分片 ID 列表（不允许为空）
     */
    public void deleteFromMilvusByChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String idList = chunkIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        String filter = "chunk_id in [" + idList + "]";
        DeleteReq deleteReq = DeleteReq.builder()
                .collectionName(COLLECTION_NAME)
                .filter(filter)
                .build();
        milvusClientV2.delete(deleteReq);
        log.info("Milvus 清理完成（按分片批量）: count={}", chunkIds.size());
    }

    /**
     * 将 PG 分片的标量字段（course_id / content_type / image_url / sha256）同步到 Milvus（delete-then-insert）
     *
     * <p>P0-1：D5/D7 标注只改 PG 时 Milvus 侧 course_id 恒为 DEFAULT——课程删除按 course_id
     * 过滤删不到向量、学生端课程维度过滤失效。Milvus v2 SDK 无按 filter 更新的 API，
     * 采用 delete-then-insert 重建行（向量从 PG dense_vector 恢复，不重新调 embedding API）。
     *
     * <p>分片未向量化（dense_vector 为空，PENDING/FAILED 文档）时跳过——无需同步。
     * 删除/插入失败上抛，阻断 PG 标注更新（可重试收敛）。
     *
     * @param chunkId 分片 ID
     */
    public void syncChunkToMilvus(Long chunkId) {
        DocumentChunk chunk = chunkMapper.selectById(chunkId);
        if (chunk == null) {
            throw new IllegalStateException("分片不存在: chunkId=" + chunkId);
        }
        if (chunk.getDenseVector() == null || chunk.getDenseVector().length == 0) {
            log.debug("分片未向量化，跳过 Milvus 同步: chunkId={}", chunkId);
            return;
        }
        Document doc = documentMapper.selectById(chunk.getDocId());
        syncChunkRowToMilvus(chunk, doc != null ? doc.getTitle() : "");
    }

    /**
     * 文档级同步：将该文档全部未删分片的标量字段（course_id / content_type / image_url / sha256）同步到 Milvus
     *
     * <p>用户裁决（2026-08-15）：后台提供文档级同步而非逐 chunk——B 端「把整篇文档标注为
     * 某课程」时一次调用完成（调用次数 = 文档数，而非分片数）。内部仍 delete-then-insert
     * 重建 Milvus 行（向量从 PG dense_vector 恢复，不重新调 embedding API）。
     * 未向量化的分片（dense_vector 为空）跳过。失败上抛，阻断调用方（可重试收敛）。
     *
     * <p>M-4：批量 delete（filter IN 一次）+ 多行 InsertReq（原逐 chunk 两两往返）。
     *
     * @param docId 文档 ID
     */
    public void syncDocToMilvus(Long docId) {
        Document doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw new IllegalStateException("文档不存在: docId=" + docId);
        }
        String docTitle = doc.getTitle();
        // PERF-20（宪法 A.4.4 按需取列）：原查询无投影全列取回（26 列，含 metadata_json/
        // parent_title 等大列传输后即弃）；Milvus 行构建 + 向量过滤仅消费以下 12 列
        // （dense_vector 向量列必须保留——delete-then-insert 重建的向量来源）
        List<DocumentChunk> chunks = chunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                .select(
                        DocumentChunk::getId,
                        DocumentChunk::getDocId,
                        DocumentChunk::getKbId,
                        DocumentChunk::getChunkIndex,
                        DocumentChunk::getContent,
                        DocumentChunk::getHeadingPath,
                        DocumentChunk::getTokenCount,
                        DocumentChunk::getCourseId,
                        DocumentChunk::getContentType,
                        DocumentChunk::getImageUrl,
                        DocumentChunk::getSha256,
                        DocumentChunk::getDenseVector)
                .eq(DocumentChunk::getDocId, docId)
                .orderByAsc(DocumentChunk::getChunkIndex));
        // 仅同步已向量化的分片（未向量化的跳过，无需同步）
        List<DocumentChunk> vectorized = chunks.stream()
                .filter(c -> c.getDenseVector() != null && c.getDenseVector().length > 0)
                .toList();
        if (vectorized.isEmpty()) {
            log.info("文档标注已同步 Milvus（无向量化分片）: docId={}, 同步分片数=0", docId);
            return;
        }
        // M-4: 批量 delete（一次 filter IN）+ 多行 insert（一次 InsertReq）
        deleteFromMilvusByChunkIds(
                vectorized.stream().map(c -> String.valueOf(c.getId())).toList());
        List<JsonObject> rows = new ArrayList<>(vectorized.size());
        for (DocumentChunk chunk : vectorized) {
            rows.add(buildMilvusRow(chunk, bytesToFloatArray(chunk.getDenseVector()), docTitle));
        }
        insertToMilvusBatch(rows);
        log.info("文档标注已同步 Milvus: docId={}, 同步分片数={}", docId, vectorized.size());
    }

    /**
     * 单分片重建 Milvus 行（delete-then-insert，向量从 PG dense_vector 恢复）
     */
    private void syncChunkRowToMilvus(DocumentChunk chunk, String docTitle) {
        float[] vector = bytesToFloatArray(chunk.getDenseVector());
        deleteFromMilvusByChunkId(String.valueOf(chunk.getId()));
        insertToMilvusBatch(List.of(buildMilvusRow(chunk, vector, docTitle)));
        log.debug("分片标量字段已同步 Milvus: chunkId={}, courseId={}", chunk.getId(), chunk.getCourseId());
    }

    // ========================================================================
    // Milvus 插入
    // ========================================================================

    /**
     * 批量插入多行到 Milvus（v2 API：InsertReq + Gson JsonObject 行式插入）
     *
     * <p>H-3/M-4：一次 InsertReq 携带多行（原逐 chunk 单行 insert，N 次网络往返 → N/批大小 次）。
     *
     * <p>B3-1 修复：插入失败上抛（对齐 delete 路径 P0-8 的处理哲学）——原实现仅记 warn 吞异常，
     * 导致 embedAndIndex 的 failedCount 不增而误标 INDEXED（向量永久缺失且无重试路径）、
     * reEmbedAndUpsert/syncDocToMilvus/syncChunkRowToMilvus 同步后向量静默丢失。
     * 上抛后各调用点的失败语义：embedAndIndex 既有批次 catch 计入 failedCount → 标 FAILED +
     * P2-6 半成品清理（PENDING/FAILED 可重跑收敛）；其余调用点异常传播阻断调用方（可重试收敛）。
     * 确保「向量缺失但文档标 INDEXED」不再可能。
     *
     * @param rows Milvus 行列表（每行 13 个字段，不含 sparse_vector —— 服务端 BM25 Function 自动生成）
     */
    private void insertToMilvusBatch(List<JsonObject> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        InsertReq insertReq =
                InsertReq.builder().collectionName(COLLECTION_NAME).data(rows).build();
        try {
            milvusClientV2.insert(insertReq);
        } catch (Exception e) {
            // B3-1: 记录行数与错误摘要后上抛，由调用链按各自失败语义处理（不吞异常）
            log.error("Milvus 批量插入失败（异常上抛）: 行数={}, error={}", rows.size(), e.getMessage());
            throw e;
        }
    }

    /**
     * 构建单条 Milvus 行（13 个字段：chunk_id, doc_id, kb_id, content, heading_path, dense_vector,
     * chunk_index, token_count, course_id, content_type, image_url, sha256, updated_at；不含 sparse_vector）
     *
     * @param chunk      PG 分片实体
     * @param denseVector dense 向量（embedding 模型输出）
     * @param docTitle   文档标题（当前未使用，新 schema 无 source 字段）
     * @return Gson JsonObject 行
     */
    private JsonObject buildMilvusRow(DocumentChunk chunk, float[] denseVector, String docTitle) {
        String chunkIdStr = String.valueOf(chunk.getId());

        JsonObject row = new JsonObject();
        row.addProperty(MilvusCollectionInitializer.FIELD_CHUNK_ID, chunkIdStr);
        row.addProperty(MilvusCollectionInitializer.FIELD_DOC_ID, String.valueOf(chunk.getDocId()));
        row.addProperty(MilvusCollectionInitializer.FIELD_KB_ID, String.valueOf(chunk.getKbId()));
        row.addProperty(MilvusCollectionInitializer.FIELD_CONTENT, truncate(chunk.getContent(), MAX_CONTENT_LENGTH));
        row.addProperty(
                MilvusCollectionInitializer.FIELD_HEADING_PATH,
                chunk.getHeadingPath() != null ? chunk.getHeadingPath() : "");
        // dense_vector：List<Float> → JsonArray
        JsonArray vecArray = new JsonArray();
        for (float f : denseVector) {
            vecArray.add(f);
        }
        row.add(MilvusCollectionInitializer.FIELD_DENSE_VECTOR, vecArray);
        row.addProperty(
                MilvusCollectionInitializer.FIELD_CHUNK_INDEX,
                chunk.getChunkIndex() != null ? chunk.getChunkIndex() : 0);
        row.addProperty(
                MilvusCollectionInitializer.FIELD_TOKEN_COUNT,
                chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
        row.addProperty(
                MilvusCollectionInitializer.FIELD_COURSE_ID,
                chunk.getCourseId() != null ? chunk.getCourseId() : "DEFAULT");
        row.addProperty(
                MilvusCollectionInitializer.FIELD_CONTENT_TYPE,
                chunk.getContentType() != null ? chunk.getContentType() : "text");
        row.addProperty(
                MilvusCollectionInitializer.FIELD_IMAGE_URL, chunk.getImageUrl() != null ? chunk.getImageUrl() : "");
        row.addProperty(MilvusCollectionInitializer.FIELD_SHA256, chunk.getSha256() != null ? chunk.getSha256() : "");
        row.addProperty(MilvusCollectionInitializer.FIELD_UPDATED_AT, System.currentTimeMillis() / 1000);
        // 注意：不插入 sparse_vector —— 服务端 BM25 Function 自动生成
        return row;
    }

    /**
     * 截断字符串到指定长度（Milvus VARCHAR max_length 限制）
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * float[] → byte[]（用于 PG BYTEA 存储）
     */
    private byte[] floatArrayToBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float f : vector) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * byte[] → float[]（从 PG BYTEA 恢复向量，供 Milvus 重建行）
     */
    private float[] bytesToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    /**
     * 更新文档解析状态（所有状态写入的统一入口：PARSING/PARSED/CHUNKING/CHUNKED/EMBEDDING/INDEXED/FAILED）
     *
     * <p>统计失效精确化：仅影响统计口径的状态才失效 Dashboard 统计缓存——
     * CHUNKED（分片落库，pendingChunkCount 变更）与 INDEXED/FAILED（终态兜底）；
     * PARSING/PARSED/CHUNKING/EMBEDDING 中间态不改变任何统计口径，跳过失效。
     * 先写 DB 后失效，一致性铁律。
     */
    private void updateDocStatus(Long docId, String status, String errorMessage) {
        LambdaUpdateWrapper<Document> wrapper = Wrappers.<Document>lambdaUpdate()
                .eq(Document::getId, docId)
                .set(Document::getParseStatus, status)
                .set(Document::getUpdatedAt, LocalDateTime.now());
        if (errorMessage != null) {
            wrapper.set(Document::getErrorMessage, errorMessage);
        }
        documentMapper.update(null, wrapper);
        if (STATS_AFFECTING_STATUSES.contains(status)) {
            dashboardCacheEvictor.evictAll();
        }
    }

    /**
     * 更新文档分片数
     */
    private void updateDocChunkCount(Long docId, int count) {
        documentMapper.update(
                null,
                Wrappers.<Document>lambdaUpdate().eq(Document::getId, docId).set(Document::getChunkCount, count));
    }

    /**
     * 更新分片的 dense_vector 和 milvus_pk
     */
    private void updateChunkVector(Long chunkId, byte[] denseVector, String milvusPk) {
        chunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getId, chunkId)
                        .set(DocumentChunk::getDenseVector, denseVector)
                        .set(DocumentChunk::getMilvusPk, milvusPk));
    }
}
