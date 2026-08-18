package com.commerce.rag.etl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.github.benmanes.caffeine.cache.Cache;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

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
 * （由 EtlConfig.etlPool 提供，调用方通过 execute() 提交）。
 *
 * <p>Milvus upsert 策略：delete-then-insert。
 * PG 冗余 dense_vector（BYTEA）避免回查 Milvus。
 *
 * <p>依赖注入：Lombok @RequiredArgsConstructor 构造器注入（10 个 private final 依赖：
 * DocumentMapper / DocumentChunkMapper / MinioStorageService / EmbeddingModel /
 * MilvusClientV2 / EtlProperties / dashboardStatsCache / XhtmlDocumentParser / TableChunker /
 * ImageCaptionService）。
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

    /** Dashboard 统计缓存（TTL 60 秒；ETL 状态写入后失效，覆盖分片数/终态变更，先写 DB 后失效——一致性铁律） */
    @Qualifier("dashboardStatsCache")
    private final Cache<String, Object> dashboardStatsCache;

    /** XHTML 结构解析器（纯函数，Tika 解析 → 结构化分区） */
    private final XhtmlDocumentParser xhtmlDocumentParser;

    /** 表格分片器（HTML 表格 → Markdown，大表按行分组/表头重复/overlap 行，Task 6 接入） */
    private final TableChunker tableChunker;

    /** 图片描述（caption）服务（VLM 生成中文图片描述，Task 7 接入图片分片） */
    private final ImageCaptionService imageCaptionService;

    /** 解析内容内存缓存（docId → 结构化分区），仅在同一线程内有效 */
    private final ConcurrentHashMap<Long, ParsedContent> parsedContentCache = new ConcurrentHashMap<>();

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
            parseDocument(docId);
            chunkDocument(docId);
            embedAndIndex(docId);
            log.info("ETL 管道完成: docId={}", docId);
        } catch (Exception e) {
            log.error("ETL 管道失败: docId={}", docId, e);
            updateDocStatus(docId, "FAILED", e.getMessage());
        } finally {
            // perf P3-3: 任何路径（含异常）都清理解析内容缓存——parse 成功但 chunk/embed
            // 失败时缓存残留会随 docId 递增持续增长（反复 reparse 失败即内存泄漏）
            parsedContentCache.remove(docId);
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
        Document doc = documentMapper.selectById(docId);
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
     * <p>状态：PARSED → CHUNKING → CHUNKED
     */
    public void chunkDocument(Long docId) {
        Document doc = documentMapper.selectById(docId);
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

        // P2-7: delete-then-insert 幂等化——先软删该文档旧 chunk，再插入新分片
        chunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getDocId, docId)
                        .set(DocumentChunk::getDeleted, System.currentTimeMillis()));

        // 落库（M-1：next_chunk_id 回填收集后单条批量 UPDATE）
        Long prevChunkId = null;
        List<ChunkLinkPair> linkPairs = new ArrayList<>();
        int inserted = 0;
        for (ChunkSpec spec : specs) {
            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(docId);
            chunk.setKbId(doc.getKbId());
            chunk.setChunkIndex(inserted);
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
            chunk.setPrevChunkId(prevChunkId);

            chunkMapper.insert(chunk);

            // 收集 next_chunk_id 回填对（前驱 → 当前），落库后统一批量 UPDATE
            if (prevChunkId != null) {
                linkPairs.add(new ChunkLinkPair(prevChunkId, chunk.getId()));
            }
            prevChunkId = chunk.getId();
            inserted++;
        }

        // M-1: next_chunk_id 批量回填（单条 CASE WHEN UPDATE）
        if (!linkPairs.isEmpty()) {
            chunkMapper.batchUpdateNextChunkIds(linkPairs);
        }

        // 更新文档分片数（实际入库数）
        updateDocChunkCount(docId, inserted);

        // 清理缓存
        parsedContentCache.remove(docId);

        updateDocStatus(docId, "CHUNKED", null);
    }

    /**
     * 组装待落库分片 —— 按文档顺序遍历结构分区，按类型分片
     * （文本走 TokenTextSplitter；表格走 TableChunker；图片分区于 Task 7 接入）
     *
     * @param doc    文档实体（图片分片 MinIO 上传需要 kbId）
     * @param parsed 解析后的结构化分区
     */
    private List<ChunkSpec> buildChunkSpecs(Document doc, ParsedContent parsed) {
        List<ChunkSpec> specs = new ArrayList<>();
        // 图片字节级去重表（sha256 → MinIO objectKey），仅本文件内有效（同图只处理一次）
        Map<String, String> processedImages = new HashMap<>();
        for (ParsedContent.ParsedSection section : parsed.sections()) {
            if (section instanceof ParsedContent.TextSection text) {
                specs.addAll(splitTextSection(text));
            } else if (section instanceof ParsedContent.TableSection table) {
                specs.addAll(tableChunker.chunk(table.html(), table.headingPath()));
            } else if (section instanceof ParsedContent.ImageSection image) {
                // 图片：过滤 → 字节去重 → MinIO 上传 → caption → image chunk
                // 单图失败仅跳过该图（记 warn），文档 ETL 继续（spec §4.2）
                try {
                    ChunkSpec spec = buildImageChunk(doc, image, processedImages);
                    if (spec != null) {
                        specs.add(spec);
                    }
                } catch (Exception e) {
                    log.warn(
                            "图片处理失败，跳过该图（文档 ETL 继续）: docId={}, resource={}, error={}",
                            doc.getId(),
                            image.resourceName(),
                            e.getMessage());
                }
            }
        }
        return specs;
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
     * 图片分片：过滤小图标/装饰图 → 字节级去重（同图只处理一次）→ MinIO 上传 → VLM caption
     *
     * @return 图片分片规格；被过滤/跳过时返回 null
     */
    private ChunkSpec buildImageChunk(
            Document doc, ParsedContent.ImageSection image, Map<String, String> processedImages) {
        if (ImageFilter.isSmallIcon(image.bytes(), etlProperties.imageMinSizeKb())) {
            log.info(
                    "图片过滤（小于 {}KB）: docId={}, resource={}",
                    etlProperties.imageMinSizeKb(),
                    doc.getId(),
                    image.resourceName());
            return null;
        }
        if (ImageFilter.isDecorative(image.bytes())) {
            log.info("图片过滤（装饰图）: docId={}, resource={}", doc.getId(), image.resourceName());
            return null;
        }
        // 图片字节 sha256 去重：同图只 caption + 上传一次（内存级，spec §4.2）
        String byteHash = ContentHash.sha256Hex(image.bytes());
        if (processedImages.containsKey(byteHash)) {
            log.info("图片字节去重（同图只处理一次）: docId={}, resource={}", doc.getId(), image.resourceName());
            return null;
        }
        String objectKey = uploadImage(doc, image);
        processedImages.put(byteHash, objectKey);

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
        Document doc = documentMapper.selectById(docId);
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
        List<DocumentChunk> chunks = chunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
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
     * 插入失败仅记 warn（与单行插入既有语义一致，不阻断文档终态判定）。
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
            log.warn("Milvus 批量插入失败: 行数={}, error={}", rows.size(), e.getMessage());
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
            dashboardStatsCache.invalidateAll();
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
