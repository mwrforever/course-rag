package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.record.ChunkLinkPair;
import com.commerce.rag.record.ChunkVectorUpdate;
import com.commerce.rag.test.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * DocumentChunkMapper.xml 执行级测试（真实 PG 执行 selectPageFilteredByTeacher）
 *
 * <p>验证教师数据权限子查询 SQL 的真实执行结果：
 * <ul>
 *   <li>数据权限：doc_id IN 子查询按 document.created_by 收窄到本人文档</li>
 *   <li>软删过滤：deleted=0（自定义 SQL 不经过 @TableLogic，SQL 内显式过滤）</li>
 *   <li>可选条件：docId / kbId / pendingOnly 三个动态 if 分支各断言一次</li>
 * </ul>
 *
 * <p>数据准备：本类 @BeforeEach 清空 document_chunk / document（表依赖顺序：先子表后主表），
 * 预置 2 个文档（不同 created_by）+ 4 条分片（含 1 条 deleted=1）。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class DocumentChunkMapperXmlTest extends IntegrationTestBase {

    /** 教师 A（文档 docA 的创建者，分片预置 3 条含 1 条软删） */
    private static final long TEACHER_A = 2001L;
    /** 教师 B（文档 docB 的创建者，仅 1 条分片） */
    private static final long TEACHER_B = 2002L;
    /** 教师 A 的文档 ID */
    private static final long DOC_A = 101L;
    /** 教师 B 的文档 ID */
    private static final long DOC_B = 102L;
    /** 教师 A 文档所属知识库 ID */
    private static final long KB_A = 1L;

    private final DocumentChunkMapper documentChunkMapper;

    @BeforeEach
    void setUpChunkData() {
        // 清空分片与文档表（先删子表 document_chunk，再删主表 document，避免依赖顺序问题）
        jdbcTemplate.update("DELETE FROM document_chunk");
        jdbcTemplate.update("DELETE FROM document");
        // 预置文档：docA 属教师 A（kb 1），docB 属教师 B（kb 2）
        jdbcTemplate.update(
                "INSERT INTO document (id, kb_id, title, parse_status, course_id, created_by, deleted)"
                        + " VALUES (?, 1, '教师A文档', 'INDEXED', 'DEFAULT', ?, 0)",
                DOC_A,
                TEACHER_A);
        jdbcTemplate.update(
                "INSERT INTO document (id, kb_id, title, parse_status, course_id, created_by, deleted)"
                        + " VALUES (?, 2, '教师B文档', 'INDEXED', 'DEFAULT', ?, 0)",
                DOC_B,
                TEACHER_B);
        // 预置分片：docA 两条未删除（PENDING + CORRECTED）+ 一条已删除；docB 一条未删除
        insertChunk(1001L, DOC_A, KB_A, 1, "教师A分片一", "PENDING", 0L);
        insertChunk(1002L, DOC_A, KB_A, 2, "教师A分片二", "CORRECTED", 0L);
        insertChunk(1003L, DOC_A, KB_A, 3, "教师A已删除分片", "PENDING", 1L);
        insertChunk(1004L, DOC_B, 2L, 1, "教师B分片一", "PENDING", 0L);
    }

    /**
     * 预置单条分片记录（JdbcTemplate 直插，等价于 ETL 落库后的数据形态）。
     *
     * @param id               分片主键
     * @param docId            所属文档 ID
     * @param kbId             所属知识库 ID
     * @param chunkIndex       分片序号
     * @param content          分片文本
     * @param correctionStatus 旁路修正状态（PENDING / CORRECTED）
     * @param deleted          逻辑删除标记（0 = 未删除，1 = 已删除）
     */
    private void insertChunk(
            Long id, Long docId, Long kbId, int chunkIndex, String content, String correctionStatus, Long deleted) {
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, doc_id, kb_id, chunk_index, content, collection_type, course_id,"
                        + " correction_status, deleted, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, 'TECHNICAL_QA', 'DEFAULT', ?, ?, now(), now())",
                id,
                docId,
                kbId,
                chunkIndex,
                content,
                correctionStatus,
                deleted);
    }

    /**
     * 数据权限 + 软删过滤：教师 A 只能看到本人文档（docA）的未删除分片，共 2 条。
     *
     * <p>断言链：
     * <ol>
     *   <li>total=2：教师 B 的分片（1004）被子查询 created_by 过滤掉</li>
     *   <li>软删分片（1003，deleted=1）被 SQL 显式过滤</li>
     *   <li>结果按 chunk_index 升序（1 → 2）</li>
     * </ol>
     */
    @Test
    void selectPageFilteredByTeacher仅返回本人未删除分片() {
        IPage<DocumentChunk> page =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), null, null, false, TEACHER_A);
        List<DocumentChunk> records = page.getRecords();
        assertEquals(2, page.getTotal(), "教师 A 应只有 2 条可见分片（教师 B 的与软删的均被过滤）");
        assertEquals(2, records.size(), "本页记录数应与 total 一致");
        Set<Long> docIds = records.stream().map(DocumentChunk::getDocId).collect(Collectors.toSet());
        assertEquals(Set.of(DOC_A), docIds, "可见分片应全部属于本人文档 docA");
        assertTrue(records.stream().noneMatch(r -> r.getDeleted() != null && r.getDeleted() == 1L), "不应包含软删分片");
        assertEquals(1, records.get(0).getChunkIndex(), "首条应按 chunk_index 升序");
        assertEquals(2, records.get(1).getChunkIndex(), "次条应按 chunk_index 升序");
    }

    /**
     * docId 可选条件：限定单文档后仅返回该文档分片；教师 B 查 docA 无数据（数据权限叠加生效）。
     */
    @Test
    void selectPageFilteredByTeacher按docId过滤() {
        IPage<DocumentChunk> page =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), DOC_A, null, false, TEACHER_A);
        assertEquals(2, page.getTotal(), "docId=docA 时教师 A 应返回 2 条");
        assertTrue(page.getRecords().stream().allMatch(r -> DOC_A == r.getDocId()), "全部记录应属于 docA");

        // 数据权限叠加：教师 B 无 docA 的分片（即使指定 docId 也不可见）
        IPage<DocumentChunk> empty =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), DOC_A, null, false, TEACHER_B);
        assertEquals(0, empty.getTotal(), "教师 B 不应看到教师 A 文档的分片");
    }

    /**
     * kbId 可选条件：限定知识库后仅返回该库分片；不存在的 kbId 返回空。
     */
    @Test
    void selectPageFilteredByTeacher按kbId过滤() {
        IPage<DocumentChunk> page =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), null, KB_A, false, TEACHER_A);
        assertEquals(2, page.getTotal(), "kbId=KB_A 时教师 A 应返回 2 条");
        assertTrue(page.getRecords().stream().allMatch(r -> KB_A == r.getKbId()), "全部记录应属于该知识库");

        IPage<DocumentChunk> empty =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), null, 99L, false, TEACHER_A);
        assertEquals(0, empty.getTotal(), "不存在的 kbId 应返回空");
    }

    /**
     * pendingOnly 可选条件：true 仅返回 correction_status=PENDING（findPending 场景），false 返回全部。
     */
    @Test
    void selectPageFilteredByTeacher按pendingOnly过滤() {
        IPage<DocumentChunk> pending =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), null, null, true, TEACHER_A);
        assertEquals(1, pending.getTotal(), "pendingOnly=true 时仅剩 PENDING 分片（CORRECTED 与软删均排除）");
        assertEquals("PENDING", pending.getRecords().get(0).getCorrectionStatus(), "剩余分片修正状态应为 PENDING");

        IPage<DocumentChunk> all =
                documentChunkMapper.selectPageFilteredByTeacher(new Page<>(1, 10), null, null, false, TEACHER_A);
        assertEquals(2, all.getTotal(), "pendingOnly=false 应返回全部未删除分片");
    }

    /**
     * M-1 + P1-4: batchUpdateChunkLinks 执行级验证——单条批量 UPDATE 正确回填 prev/next 双向链指针。
     *
     * <p>预置分片 1001 → 1002 → 1004 的线性链，批量回填后按 id 校验：
     * 前驱行的 next_chunk_id 与后继行的 prev_chunk_id 同时回填（批插后链组装的双写语义）。
     */
    @Test
    void batchUpdateChunkLinks回填链路双向指针() {
        int rows = documentChunkMapper.batchUpdateChunkLinks(
                List.of(new ChunkLinkPair(1001L, 1002L), new ChunkLinkPair(1002L, 1004L)));

        assertEquals(3, rows, "批量回填应命中 3 行（1001 的 next + 1002 的 next/prev + 1004 的 prev）");
        Long nextOf1001 =
                jdbcTemplate.queryForObject("SELECT next_chunk_id FROM document_chunk WHERE id = 1001", Long.class);
        Long nextOf1002 =
                jdbcTemplate.queryForObject("SELECT next_chunk_id FROM document_chunk WHERE id = 1002", Long.class);
        Long nextOf1004 =
                jdbcTemplate.queryForObject("SELECT next_chunk_id FROM document_chunk WHERE id = 1004", Long.class);
        Long prevOf1002 =
                jdbcTemplate.queryForObject("SELECT prev_chunk_id FROM document_chunk WHERE id = 1002", Long.class);
        Long prevOf1004 =
                jdbcTemplate.queryForObject("SELECT prev_chunk_id FROM document_chunk WHERE id = 1004", Long.class);
        Long prevOf1001 =
                jdbcTemplate.queryForObject("SELECT prev_chunk_id FROM document_chunk WHERE id = 1001", Long.class);
        assertEquals(1002L, nextOf1001, "1001 的 next_chunk_id 应回填为 1002");
        assertEquals(1004L, nextOf1002, "1002 的 next_chunk_id 应回填为 1004");
        assertEquals(1001L, prevOf1002, "1002 的 prev_chunk_id 应回填为 1001（双向指针）");
        assertEquals(1002L, prevOf1004, "1004 的 prev_chunk_id 应回填为 1002（双向指针）");
        assertTrue(nextOf1004 == null, "链尾分片的 next_chunk_id 应保持 NULL");
        assertTrue(prevOf1001 == null, "链首分片的 prev_chunk_id 应保持 NULL");
    }

    /**
     * P1-4: batchInsert 执行级验证——foreach multi-values 批插 + MP 自动填充 ASSIGN_ID 雪花 ID。
     *
     * <p>设计前提实测（当前 MP 3.5.12）：自定义 mapper 方法 List 参数经 MybatisParameterHandler
     * 自动填充 @TableId(ASSIGN_ID)，无需显式预生成 ID；插入后实体 ID 非空、互不相同且与库中行一致。
     * 未携带列（created_at/updated_at/deleted）由列默认值接管，与逐条 MP insert 语义一致。
     */
    @Test
    void batchInsert批量插入并自动填充雪花ID() {
        // 实体形态对齐 EtlPipeline.chunkDocument 的组装产物（collectionType/contentType 等
        // NOT NULL 列恒有值；created_at/updated_at/deleted 不携带走列默认值）
        DocumentChunk c1 = new DocumentChunk();
        c1.setDocId(DOC_A);
        c1.setKbId(KB_A);
        c1.setChunkIndex(10);
        c1.setContent("批插分片一");
        c1.setContentType("text");
        c1.setCollectionType("TECHNICAL_QA");
        c1.setSha256("a".repeat(64));
        c1.setCorrectionStatus("PENDING");
        DocumentChunk c2 = new DocumentChunk();
        c2.setDocId(DOC_A);
        c2.setKbId(KB_A);
        c2.setChunkIndex(11);
        c2.setContent("批插分片二");
        c2.setContentType("text");
        c2.setCollectionType("TECHNICAL_QA");
        c2.setSha256("b".repeat(64));
        c2.setCorrectionStatus("PENDING");

        int rows = documentChunkMapper.batchInsert(List.of(c1, c2));

        assertEquals(2, rows, "批量插入应写入 2 行");
        // MP 参数处理器自动填充雪花 ID（设计前提的核心断言）
        assertNotNull(c1.getId(), "批插后实体 ID 应由 MP 自动填充（ASSIGN_ID）");
        assertNotNull(c2.getId(), "批插后实体 ID 应由 MP 自动填充（ASSIGN_ID）");
        assertNotEquals(c1.getId(), c2.getId(), "各实体 ID 应互不相同");
        // 库中行与实体 ID 一致、业务字段正确落库
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunk WHERE id IN (?, ?) AND doc_id = ? AND content IN (?, ?)",
                Long.class,
                c1.getId(),
                c2.getId(),
                DOC_A,
                "批插分片一",
                "批插分片二");
        assertEquals(2L, cnt, "库中应存在与实体 ID 一致的 2 行");
        // 未携带列走列默认值（与逐条 MP insert 的 NOT_NULL 字段策略一致）
        Long deleted =
                jdbcTemplate.queryForObject("SELECT deleted FROM document_chunk WHERE id = ?", Long.class, c1.getId());
        assertEquals(0L, deleted, "deleted 应由列默认值接管为 0");
    }

    /**
     * H-3: batchUpdateVectors 执行级验证——单条批量 UPDATE 正确回写 dense_vector + milvus_pk。
     */
    @Test
    void batchUpdateVectors批量回写向量() {
        byte[] vec1 = new byte[] {1, 2, 3, 4};
        byte[] vec2 = new byte[] {5, 6, 7, 8};
        int rows = documentChunkMapper.batchUpdateVectors(
                List.of(new ChunkVectorUpdate(1001L, vec1, "1001"), new ChunkVectorUpdate(1002L, vec2, "1002")));

        assertEquals(2, rows, "批量回写应命中 2 行");
        byte[] stored1 =
                jdbcTemplate.queryForObject("SELECT dense_vector FROM document_chunk WHERE id = 1001", byte[].class);
        String pk1 = jdbcTemplate.queryForObject("SELECT milvus_pk FROM document_chunk WHERE id = 1001", String.class);
        assertTrue(java.util.Arrays.equals(vec1, stored1), "dense_vector 应回写为传入字节");
        assertEquals("1001", pk1, "milvus_pk 应回写为分片 ID 字符串");
        // 未回写的分片保持原样（NULL）
        byte[] stored4 =
                jdbcTemplate.queryForObject("SELECT dense_vector FROM document_chunk WHERE id = 1004", byte[].class);
        assertTrue(stored4 == null, "未回写分片的 dense_vector 应保持 NULL");
    }
}
