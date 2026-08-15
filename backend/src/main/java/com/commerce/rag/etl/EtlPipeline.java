package com.commerce.rag.etl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.config.MilvusCollectionInitializer;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.storage.MinioStorageService;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * ETL 异步管道 —— 文档解析 → 递归分片 → 向量化 + Milvus 索引
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
 * <p>依赖注入：Lombok @RequiredArgsConstructor 构造器注入（7 个 private final 依赖：
 * DocumentMapper / DocumentChunkMapper / MinioStorageService / EmbeddingModel /
 * MilvusClientV2 / EtlProperties / dashboardStatsCache）。
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

    /** Tika 解析最大字符数（-1 = 无限制） */
    private static final int TIKA_WRITE_LIMIT = -1;

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

    /** 解析文本内存缓存（docId → text），仅在同一线程内有效 */
    private final ConcurrentHashMap<Long, String> parsedTextCache = new ConcurrentHashMap<>();

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
            // perf P3-3: 任何路径（含异常）都清理解析文本缓存——parse 成功但 chunk/embed
            // 失败时缓存残留会随 docId 递增持续增长（反复 reparse 失败即内存泄漏）
            parsedTextCache.remove(docId);
        }
    }

    // ========================================================================
    // 阶段 1：文档解析（Tika）
    // ========================================================================

    /**
     * Tika 解析文档 → 提取纯文本
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
            // Tika 解析
            BodyContentHandler handler = new BodyContentHandler(TIKA_WRITE_LIMIT);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(inputStream, handler, metadata, context);

            String text = handler.toString();
            log.info("文档解析完成: docId={}, 字符数={}", docId, text.length());

            // 将解析文本暂存到内存缓存（供 chunkDocument 阶段使用）
            parsedTextCache.put(docId, text);
        }

        updateDocStatus(docId, "PARSED", null);
    }

    // ========================================================================
    // 阶段 2：递归分片
    // ========================================================================

    /**
     * 递归分片 —— chunk-size=768, overlap=128, 父子关联
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

        String text = parsedTextCache.get(docId);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("解析文本为空或未找到: docId=" + docId);
        }

        int chunkSize = etlProperties.chunk().size();
        int overlap = etlProperties.chunk().overlap();

        // P2-7: delete-then-insert 幂等化——先软删该文档旧 chunk（含上次 FAILED 遗留的半成品），
        // 再插入新分片。当前 upload/reparse 入口虽已在外层软删，但 FAILED 重跑等预留路径
        // （process 抢占 PENDING/FAILED 直接重跑）无此步骤时每次重跑会重复堆积一套分片
        chunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getDocId, docId)
                        .set(DocumentChunk::getDeleted, System.currentTimeMillis()));

        // 递归分片
        List<ChunkInfo> chunks = recursiveSplit(text, chunkSize, overlap);
        log.info("分片完成: docId={}, 分片数={}", docId, chunks.size());

        // 保存到 PG + 建立父子关联
        Long prevChunkId = null;
        Long currentGroupFirstId = null; // 当前段落组的首 chunk ID（用于 parent_chunk_id）

        for (int i = 0; i < chunks.size(); i++) {
            ChunkInfo info = chunks.get(i);

            DocumentChunk chunk = new DocumentChunk();
            chunk.setDocId(docId);
            chunk.setKbId(doc.getKbId());
            chunk.setChunkIndex(i);
            chunk.setContent(info.text);
            chunk.setHeadingPath(info.headingPath);
            chunk.setTokenCount(estimateTokens(info.text));
            chunk.setCollectionType("TECHNICAL_QA");
            // 课程归属：优先取文档级 course_id（上传时前端可指定，用户裁决），空则 DEFAULT=通用资料库
            chunk.setCourseId(
                    doc.getCourseId() != null && !doc.getCourseId().isBlank() ? doc.getCourseId() : "DEFAULT");
            chunk.setCharOffsetStart(info.start);
            chunk.setCharOffsetEnd(info.end);
            chunk.setCorrectionStatus("PENDING");
            chunk.setPrevChunkId(prevChunkId);

            // 父子关联：非首 chunk 的子分片指向同组首 chunk
            if (info.isSubChunk && currentGroupFirstId != null) {
                chunk.setParentChunkId(currentGroupFirstId);
            }

            chunkMapper.insert(chunk);

            // 设置前一个 chunk 的 next_chunk_id
            if (prevChunkId != null) {
                updateChunkNextId(prevChunkId, chunk.getId());
            }

            // 更新当前段落组的首 chunk ID
            if (!info.isSubChunk) {
                currentGroupFirstId = chunk.getId(); // 新段落组的首 chunk
            }

            prevChunkId = chunk.getId();
        }

        // 更新文档分片数
        updateDocChunkCount(docId, chunks.size());

        // 清理缓存
        parsedTextCache.remove(docId);

        updateDocStatus(docId, "CHUNKED", null);
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

        // 批量向量化
        int failedCount = 0;
        for (DocumentChunk chunk : chunks) {
            try {
                // 调用 Embedding API
                float[] vector = embeddingModel.embed(chunk.getContent());
                if (vector == null || vector.length == 0) {
                    log.warn("Embedding 返回空向量: chunkId={}", chunk.getId());
                    // 空向量计入失败（与 P2-1「部分失败标 FAILED」语义一致，避免静默跳过误标 INDEXED 导致检索漏召回）
                    failedCount++;
                    continue;
                }

                // 存储到 PG（BYTEA）
                byte[] denseVector = floatArrayToBytes(vector);
                updateChunkVector(chunk.getId(), denseVector, String.valueOf(chunk.getId()));

                // 插入 Milvus
                insertToMilvus(chunk, vector, doc.getTitle());

                log.debug("分片已索引: chunkId={}, index={}", chunk.getId(), chunk.getChunkIndex());
            } catch (Exception e) {
                log.error("分片向量化失败: chunkId={}", chunk.getId(), e);
                // 继续处理其他分片，不中断
                failedCount++;
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
        insertToMilvus(chunk, vector, docTitle);

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
     * 过滤值格式与 CourseService.deleteCourse 软删 chunk 的 courseIdStr 一致）。
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
     * <p>P0-8 修复：删除失败上抛（不再吞异常）——调用方（DocumentChunkService.delete）
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
     * 将 PG 分片的标量字段（course_id/collection_type）同步到 Milvus（delete-then-insert）
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
     * 文档级同步：将该文档全部未删分片的标量字段（course_id/collection_type）同步到 Milvus
     *
     * <p>用户裁决（2026-08-15）：后台提供文档级同步而非逐 chunk——B 端「把整篇文档标注为
     * 某课程」时一次调用完成（调用次数 = 文档数，而非分片数）。内部仍逐 chunk
     * delete-then-insert 重建 Milvus 行（向量从 PG dense_vector 恢复，不重新调 embedding API）。
     * 未向量化的分片（dense_vector 为空）跳过。失败上抛，阻断调用方（可重试收敛）。
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
        int synced = 0;
        for (DocumentChunk chunk : chunks) {
            if (chunk.getDenseVector() == null || chunk.getDenseVector().length == 0) {
                continue;
            }
            syncChunkRowToMilvus(chunk, docTitle);
            synced++;
        }
        log.info("文档标注已同步 Milvus: docId={}, 同步分片数={}", docId, synced);
    }

    /**
     * 单分片重建 Milvus 行（delete-then-insert，向量从 PG dense_vector 恢复）
     */
    private void syncChunkRowToMilvus(DocumentChunk chunk, String docTitle) {
        float[] vector = bytesToFloatArray(chunk.getDenseVector());
        deleteFromMilvusByChunkId(String.valueOf(chunk.getId()));
        insertToMilvus(chunk, vector, docTitle);
        log.debug("分片标量字段已同步 Milvus: chunkId={}, courseId={}", chunk.getId(), chunk.getCourseId());
    }

    // ========================================================================
    // Milvus 插入
    // ========================================================================

    /**
     * 插入单条记录到 Milvus（v2 API：InsertReq + Gson JsonObject 行式插入）
     *
     * <p>插入 11 个字段（不含 sparse_vector —— 服务端 BM25 Function 自动生成）：
     * chunk_id, doc_id, kb_id, content, heading_path, dense_vector,
     * chunk_index, token_count, collection_type, course_id, updated_at
     *
     * @param chunk      PG 分片实体
     * @param denseVector dense 向量（embedding 模型输出）
     * @param docTitle   文档标题（当前未使用，新 schema 无 source 字段）
     */
    private void insertToMilvus(DocumentChunk chunk, float[] denseVector, String docTitle) {
        String chunkIdStr = String.valueOf(chunk.getId());

        // 构建 Gson JsonObject 行（v2 行式插入）
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
                MilvusCollectionInitializer.FIELD_COLLECTION_TYPE,
                chunk.getCollectionType() != null ? chunk.getCollectionType() : "TECHNICAL_QA");
        row.addProperty(
                MilvusCollectionInitializer.FIELD_COURSE_ID,
                chunk.getCourseId() != null ? chunk.getCourseId() : "DEFAULT");
        row.addProperty(MilvusCollectionInitializer.FIELD_UPDATED_AT, System.currentTimeMillis() / 1000);
        // 注意：不插入 sparse_vector —— 服务端 BM25 Function 自动生成

        InsertReq insertReq = InsertReq.builder()
                .collectionName(COLLECTION_NAME)
                .data(List.of(row))
                .build();

        try {
            milvusClientV2.insert(insertReq);
        } catch (Exception e) {
            log.warn("Milvus 插入失败: chunkId={}, error={}", chunkIdStr, e.getMessage());
        }
    }

    /**
     * 截断字符串到指定长度（Milvus VARCHAR max_length 限制）
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    // ========================================================================
    // 递归分片算法
    // ========================================================================

    /**
     * 递归分片 —— 按段落 → 句子 → 字符递归拆分，带 overlap
     *
     * @param text       原始文本
     * @param chunkSize  目标分片大小（字符数）
     * @param overlap    相邻分片重叠字符数
     * @return 分片列表
     */
    private List<ChunkInfo> recursiveSplit(String text, int chunkSize, int overlap) {
        List<ChunkInfo> result = new ArrayList<>();

        // 按段落分割（双换行）
        String[] paragraphs = text.split("\n\n+");
        int globalOffset = 0;

        for (String para : paragraphs) {
            // 计算段落在原文中的偏移
            int paraStart = text.indexOf(para, globalOffset);
            if (paraStart < 0) paraStart = globalOffset;
            globalOffset = paraStart + para.length();

            if (para.length() <= chunkSize) {
                // 段落不超过 chunk_size，直接作为一个 chunk
                result.add(new ChunkInfo(para.trim(), paraStart, paraStart + para.length(), "", false));
            } else {
                // 段落超过 chunk_size，按句子拆分
                List<ChunkInfo> subChunks = splitLargeParagraph(para, paraStart, chunkSize, overlap);
                // 第一个子 chunk 不是 sub-chunk（它是组的 parent），其余是
                for (int i = 1; i < subChunks.size(); i++) {
                    subChunks.get(i).isSubChunk = true;
                }
                result.addAll(subChunks);
            }
        }

        // 合并过小的 chunk（可选，保持简单暂不合并）
        // 应用 overlap
        applyOverlap(result, text, overlap);

        return result;
    }

    /**
     * 拆分大段落 —— 按句子 → 字符递归
     */
    private List<ChunkInfo> splitLargeParagraph(String para, int paraStart, int chunkSize, int overlap) {
        List<ChunkInfo> chunks = new ArrayList<>();

        // 按句子分割（中文句号、英文句号、问号、感叹号）
        String[] sentences = para.split("(?<=[。.!?！？\\n])");

        StringBuilder current = new StringBuilder();
        int currentStart = 0;

        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;

            int sentStart = para.indexOf(sentence, currentStart);
            if (sentStart < 0) sentStart = currentStart;
            currentStart = sentStart + sentence.length();

            if (current.length() + sentence.length() > chunkSize && current.length() > 0) {
                // 当前 chunk 已满，保存并开始新 chunk
                String content = current.toString().trim();
                chunks.add(new ChunkInfo(
                        content, paraStart + sentStart - current.length(), paraStart + sentStart, "", false));
                // overlap：保留上一 chunk 的末尾
                if (overlap > 0 && content.length() > overlap) {
                    current = new StringBuilder(content.substring(content.length() - overlap));
                } else {
                    current = new StringBuilder();
                }
            }

            if (sentence.length() > chunkSize) {
                // 单个句子超过 chunk_size，按字符强制拆分
                for (int i = 0; i < sentence.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, sentence.length());
                    String sub = sentence.substring(i, end).trim();
                    if (!sub.isEmpty()) {
                        chunks.add(new ChunkInfo(sub, paraStart + i, paraStart + end, "", false));
                    }
                }
                current = new StringBuilder();
            } else {
                current.append(sentence);
            }
        }

        // 保存最后一个 chunk
        if (current.length() > 0) {
            String content = current.toString().trim();
            if (!content.isEmpty()) {
                chunks.add(new ChunkInfo(content, paraStart, paraStart + para.length(), "", false));
            }
        }

        return chunks;
    }

    /**
     * 应用 overlap（在相邻 chunk 之间添加重叠内容）
     */
    private void applyOverlap(List<ChunkInfo> chunks, String originalText, int overlap) {
        // 分片时已在 splitLargeParagraph 中处理了 overlap
        // 此处无需额外操作
    }

    // ========================================================================
    // 辅助方法
    // ========================================================================

    /**
     * 估算 token 数（粗略：中文 1 字 ≈ 1 token，英文 4 字符 ≈ 1 token）
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cnCount = 0;
        int enCount = 0;
        for (char c : text.toCharArray()) {
            if (c > 127) {
                cnCount++;
            } else {
                enCount++;
            }
        }
        return cnCount + enCount / 4;
    }

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

    /**
     * 更新分片的 next_chunk_id
     */
    private void updateChunkNextId(Long chunkId, Long nextChunkId) {
        chunkMapper.update(
                null,
                Wrappers.<DocumentChunk>lambdaUpdate()
                        .eq(DocumentChunk::getId, chunkId)
                        .set(DocumentChunk::getNextChunkId, nextChunkId));
    }

    // ========================================================================
    // 分片信息内部数据结构
    // ========================================================================

    /**
     * 分片信息（分片过程中的临时数据结构）
     */
    private static class ChunkInfo {
        String text;
        int start;
        int end;
        String headingPath;
        boolean isSubChunk; // 是否为大段落拆分出的子分片

        ChunkInfo(String text, int start, int end, String headingPath, boolean isSubChunk) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.headingPath = headingPath;
            this.isSubChunk = isSubChunk;
        }
    }
}
