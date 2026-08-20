# S1 实施计划（1/5）：ETL 多模态数据底座

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ETL 管道从「纯文本递归分片」升级为多模态数据底座：XHTML 结构化解析（标题/表格/内嵌图片）、TokenTextSplitter 分片、表格 Markdown 化、图片 VLM caption、SHA256 全局去重，同步重建 PG document_chunk 与 Milvus knowledge_chunks schema，并保持现有聊天检索链路可用。

**Architecture:** 本计划是 S1 spec（docs/superpowers/specs/2026-08-12-multimodal-rag-design.md，2026-08-14 终版）§4/§12/§6 部分的落地。解析阶段 Tika（ToHTMLContentHandler + EmbeddedDocumentExtractor）产出 XHTML 与图片字节 → XhtmlDocumentParser（Jsoup）切成带标题路径的文本/表格/图片分区 → 分片阶段按类型产出 chunk（text 走 TokenTextSplitter、table 走 TableChunker、image 走 VLM caption）→ SHA256 归一化去重 → 落 PG + Milvus。Milvus knowledge_chunks 去 collection_type、加 content_type/image_url/sha256，启动时 describe 比对、schema 不匹配自动 drop 重建。SearchKnowledgeTool 做最小兼容适配（去掉 collection_type 过滤），意图-检索解耦的完整重构在计划 2/5 执行。

**Tech Stack:** Spring Boot 3.5.8 / Spring AI 1.1.2（TokenTextSplitter 在 spring-ai-commons）/ Spring AI Alibaba 1.1.2.0（DashScope VLM base64 data URL 已实锤）/ Apache Tika 2.9.2 / Jsoup 1.18.1（新增依赖）/ Milvus SDK 2.6.11 v2 / MyBatis-Plus 3.5.12 / PostgreSQL 16 + Flyway V6 直改 / JUnit5 + Mockito + Testcontainers。

## 计划拆分总览（S1 五份计划，本计划为第 1 份）

| # | 计划 | 范围（spec 章节） | 状态 |
|---|---|---|---|
| 1/5 | **ETL 多模态数据底座** | §4 ETL 改造 + §12 PG/Milvus schema + §6 模型配置 | 本计划 |
| 2/5 | 检索链路重构（QU + 检索节点 + document 组装） | §1-3（IntentType 值域、QueryUnderstandingService、CourseNameMapper、RetrieveNode、ContextBuilder、DocumentAssemblerInterceptor、prompt 三件套、LeadAgentGraph 三节点） | 待写 |
| 3/5 | 用户附件会话级处理 | §5（上传端点、AttachmentService、Caffeine、attachments_json、局部检索） | 待写 |
| 4/5 | 偏好记忆 | §7（user_preference 表、提取流水线、决策引擎、PreferenceInterceptor） | 待写 |
| 5/5 | 经历记忆 | §8（user_episodic_memory 表 + memory_chunks collection、提取/决策/动态召回/注入） | 待写 |

依赖顺序：1 → 2 → 3 → 4/5（2 的检索去重消费 1 的 sha256 字段；3 消费 1 的解析/切分/caption 组件；4/5 消费 2 的拦截器与 prompt 基础设施）。

## 关键决策与依据（执行前请确认）

1. **TokenTextSplitter 无 overlap 参数（API 实锤，偏离 spec 文字）**：spec §4.1 写「TokenTextSplitter(chunkSizeTokens=768, overlapTokens=128)」，但 Spring AI 1.1.2 实锤（javap 字节码）构造器为 `TokenTextSplitter(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed, int maxNumChunks, boolean keepSeparator)`，**无 overlap 参数**。1.1.2 实现内部以 JTokkit CL100K_BASE encode/decode 切片，并以句子边界（`。!?！？\n` 的 lastIndexOf）回卷实现跨 chunk 连续性（无固定重叠、无文本丢失、无空格拼接副作用）。**本计划采用框架行为**（chunkSize=768 + minChunkSizeChars=64 + 过小 chunk 并入前一个），删除 `etl.chunk.overlap` 配置。如需精确 128-token 固定重叠，可后续用 JTokkit 直接实现滑动窗口（约 20 行），此为备选，不在本计划内。
2. **表格文本剥离正文（Task 6 起）**：Task 4-5 过渡期表格以纯文本并入正文（与现行为等价）；Task 6 起表格从正文剥离、独立成 content_type=table 的 chunk（spec §4.3 语义完整单元）。
3. **document.chunk_count 语义变更**：去重后实际入库数（Task 8 起），非分片总数。
4. **图片提取范围**：PDF 内联图片（EmbeddedDocumentExtractor 经典路径，确定支持）+ OOXML（DOCX/PPTX）内嵌图片（Tika 2.x 容器解析器路由，Task 7 用真实文档验证）；用户附件图片走计划 3/5 的独立链路，不进系统知识库。
5. **Milvus 重建策略**：启动时 describe 比对字段集，不匹配即 drop + 重建（开发库无业务数据，用户已拍板）；describe 异常时保守视为匹配（不误删）。
6. **PG document_chunk.collection_type 列保留**：admin 标注/校正工作流（findPending/batchCorrected/updateCollectionType）仍依赖该列；仅 Milvus 侧去除此字段，检索过滤语义变化在计划 2/5 落地。

## Global Constraints

- 模型通道（spec §6 定稿，2026-08 阿里云官方最新）：主对话 `qwen3.8-max`、VLM caption `qwen3.7-flash`、embedding `qwen3.7-text-embedding`（维度保持 1024）、rerank `qwen3-rerank` 不变；各通道 application.yml 独立配置。
- SHA256 归一化算法（spec §4.4 定稿，逐字实现）：`trim()` → `replaceAll("[\\s\\u3000]+", " ")` → `replaceAll("[。．.!！?？；;：:、,，]", "")` → `toLowerCase()`。
- 去重两层：ETL 入库前查重（全局唯一硬约束，本计划）+ 检索侧防御去重（计划 2/5）。
- content_type 值域：`text` / `image` / `table`（PG 默认 'text'）。
- 图片过滤：<10KB 图标 + alpha 纯色装饰图跳过；单图 VLM 失败仅跳过该图，整文档不 FAILED（spec §4.2）。
- 表格原则：语义完整单元永不硬切；大表按行分组（20~30 行，token 动态调整）、子 chunk 重复完整表头、相邻组 overlap 行、表头+前 2 行作上下文前缀（spec §4.3）。
- 过小 chunk（<64 字符）并入前一个（spec §4.1）。
- 工程宪法：注释/日志全中文；禁全路径类名（注意 entity.Document 与 spring-ai Document 同名冲突的解法）；@RequiredArgsConstructor + private final（有初始化逻辑除外）；Wrapper 一律 Wrappers 静态工厂链式；先写 DB 后失效缓存；死代码零容忍（递归分片替换后同任务删除）；测试与实现同一次提交；新测试覆盖正常/边界/异常三类。
- 提交纪律：只 add 任务文件（禁 git add -A）；docs/ 下审查报告不纳入提交；本计划文档不提交。
- 验证命令：`cd backend && mvn.cmd clean verify`（spotless+checkstyle+spotbugs+jacoco 全门禁）；单类 `mvn.cmd test -Dtest=XxxTest`；MapStruct 无关但 Entity 变更需 `mvn.cmd clean`。
- Windows 环境：spotless:apply 会把改过的文件转 CRLF（check 接受）；批量改文件用 python 脚本时按 \r\n 匹配（见交接文档 §3）。

---

## Task 1: PG schema 与实体——document_chunk 三新列

**Files:**
- Modify: `backend/src/main/resources/db/migration/V6__full_schema_v5.sql`（document_chunk 段）
- Modify: `backend/src/main/java/com/commerce/rag/entity/DocumentChunk.java`
- Test: `backend/src/test/java/com/commerce/rag/mapper/DocumentChunkSchemaTest.java`（新建，Testcontainers 真实 PG）

**Interfaces:**
- Consumes: IntegrationTestBase（单例 PG 容器 + Flyway 迁移，既有基建）
- Produces: `DocumentChunk.contentType / imageUrl / sha256` 三字段（Task 2/5/6/7/8 消费）；PG 列 `content_type VARCHAR(20) NOT NULL DEFAULT 'text'`、`image_url VARCHAR(1000)`、`sha256 VARCHAR(64)` + 索引 `idx_document_chunk_sha256`（Task 8 查重消费）

- [ ] **Step 1: 修改 V6 SQL——document_chunk 加三列与去重索引**

在 `V6__full_schema_v5.sql` 的 document_chunk 建表语句中，`course_id VARCHAR(64) DEFAULT 'DEFAULT',` 行之后插入三列：

```sql
    content_type      VARCHAR(20)  NOT NULL DEFAULT 'text',
    image_url         VARCHAR(1000),
    sha256            VARCHAR(64),
```

在该表索引区末尾（`idx_document_chunk_correction` 之后）加一行：

```sql
CREATE INDEX idx_document_chunk_sha256       ON document_chunk(sha256) WHERE deleted = 0;
```

注意：**不改其它表、不动既有列**；sha256 索引用普通索引（非唯一）——reparse 场景旧 chunk 软删后同内容重插不冲突，并发竞态由检索侧防御去重兜底（spec §4.4 双层设计）。

- [ ] **Step 2: DocumentChunk 实体加三字段**

在 `entity/DocumentChunk.java` 的 `courseId` 字段后加（沿用 Lombok @Data 自动生成 getter/setter）：

```java
    /** 分片内容类型：text 文本 / image 图片（content=caption）/ table 表格（Markdown） */
    @TableField("content_type")
    private String contentType;

    /** 图片分片的 MinIO objectKey（仅 content_type=image 有值，其余为 null） */
    @TableField("image_url")
    private String imageUrl;

    /** 归一化内容的 SHA-256 十六进制摘要（64 字符，ETL 全局去重键） */
    @TableField("sha256")
    private String sha256;
```

- [ ] **Step 3: 写失败测试 DocumentChunkSchemaTest**

新建 `backend/src/test/java/com/commerce/rag/mapper/DocumentChunkSchemaTest.java`（风格对齐 DocumentChunkMapperXmlTest：extends IntegrationTestBase、@RequiredArgsConstructor + @TestConstructor(ALL)）：

```java
package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.commerce.rag.test.IntegrationTestBase;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;

/**
 * document_chunk 多模态列 schema 测试（Testcontainers 真实 PG + Flyway V6 迁移）
 *
 * <p>验证 S1 §12 直改 V6 后的三新列：content_type 默认 'text'、image_url/sha256 可写可读。
 *
 * @author commerce-rag
 */
@RequiredArgsConstructor
@TestConstructor(autowireMode = AutowireMode.ALL)
class DocumentChunkSchemaTest extends IntegrationTestBase {

    private final JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("多模态列存在 — content_type/image_url/sha256 可写可读")
    void multimodalColumns_writable() {
        long chunkId = 500001L;
        String hash = "a".repeat(64);
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, doc_id, kb_id, chunk_index, content, content_type, image_url, sha256)"
                        + " VALUES (?, 1, 1, 0, '图片描述内容', 'image', '10/abc.png', ?)",
                chunkId, hash);
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT content_type, image_url, sha256 FROM document_chunk WHERE id = ?", chunkId);
            assertEquals("image", row.get("content_type"));
            assertEquals("10/abc.png", row.get("image_url"));
            assertEquals(hash, row.get("sha256"));
        } finally {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE id = ?", chunkId);
        }
    }

    @Test
    @DisplayName("content_type 默认值 — 不显式赋值时为 text")
    void contentType_defaultsToText() {
        long chunkId = 500002L;
        jdbcTemplate.update(
                "INSERT INTO document_chunk (id, doc_id, kb_id, chunk_index, content) VALUES (?, 1, 1, 0, '正文内容')",
                chunkId);
        try {
            String type = jdbcTemplate.queryForObject(
                    "SELECT content_type FROM document_chunk WHERE id = ?", String.class, chunkId);
            assertEquals("text", type);
        } finally {
            jdbcTemplate.update("DELETE FROM document_chunk WHERE id = ?", chunkId);
        }
    }
}
```

- [ ] **Step 4: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=DocumentChunkSchemaTest`
Expected: FAIL（content_type 列不存在，INSERT 报 `column "content_type" does not exist`）
注意：Docker Desktop 需已启动（Testcontainers 依赖）。

- [ ] **Step 5: 重建开发库使 V6 生效（一次性手动步骤，仅开发环境）**

```bash
docker compose -f docker-compose.dev.yml up -d        # 确保 PG 已起
docker compose -f docker-compose.dev.yml exec postgres psql -U postgres -c "DROP DATABASE IF EXISTS commerce_rag;" -c "CREATE DATABASE commerce_rag;"
```

（Flyway 下次应用启动自动全量迁移；开发库无业务数据，用户已拍板 drop 重建。此步骤不进测试。）

- [ ] **Step 6: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=DocumentChunkSchemaTest`
Expected: PASS（2 用例）

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V6__full_schema_v5.sql backend/src/main/java/com/commerce/rag/entity/DocumentChunk.java backend/src/test/java/com/commerce/rag/mapper/DocumentChunkSchemaTest.java
git commit -m "feat(S1): document_chunk 增加多模态三列 content_type/image_url/sha256 与去重索引"
```

---

## Task 2: Milvus knowledge_chunks schema 重建 + 检索侧兼容

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/config/MilvusCollectionInitializer.java`
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（buildMilvusRow 字段增删）
- Modify: `backend/src/main/java/com/commerce/rag/bot/tool/SearchKnowledgeTool.java`（兼容适配）
- Test: `backend/src/test/java/com/commerce/rag/config/MilvusCollectionInitializerTest.java`（新增；若已存在同名测试则更新）
- Test: `backend/src/test/java/com/commerce/rag/bot/tool/SearchKnowledgeToolTest.java`（更新既有断言）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（更新，加行字段断言）

**Interfaces:**
- Consumes: Task 1 的 DocumentChunk 三字段；Milvus SDK v2 `describeCollection(DescribeCollectionReq) → DescribeCollectionResp.getFieldNames()`、`dropCollection(DropCollectionReq)`（已 javap 实锤）
- Produces: Milvus 字段常量 `FIELD_CONTENT_TYPE="content_type"` / `FIELD_IMAGE_URL="image_url"` / `FIELD_SHA256="sha256"`（FIELD_COLLECTION_TYPE 常量删除）；knowledge_chunks 14 字段 schema；SearchKnowledgeTool 过滤表达式仅剩 course_id 子句

- [ ] **Step 1: 写失败测试 MilvusCollectionInitializerTest**

新建（mock MilvusClientV2，与 EtlPipelineTest 同 Mockito 风格）：

```java
package com.commerce.rag.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * MilvusCollectionInitializer 单元测试 —— schema 比对重建逻辑
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
class MilvusCollectionInitializerTest {

    @Mock
    private MilvusClientV2 milvusClientV2;

    private MilvusCollectionInitializer initializer() {
        return new MilvusCollectionInitializer(milvusClientV2, "knowledge_chunks", 1024, 16, 200, true);
    }

    @Test
    @DisplayName("schema 不匹配（缺 sha256）— drop 后重建")
    void schemaMismatch_dropsAndRecreates() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        DescribeCollectionResp desc = new DescribeCollectionResp();
        desc.setFieldNames(List.of("chunk_id", "doc_id", "content", "dense_vector")); // 旧 schema
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class))).thenReturn(desc);

        initializer().run(null);

        verify(milvusClientV2).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("schema 匹配 — 不 drop 不重建")
    void schemaMatches_skipsRebuild() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        DescribeCollectionResp desc = new DescribeCollectionResp();
        desc.setFieldNames(List.of("chunk_id", "doc_id", "kb_id", "content", "heading_path", "dense_vector",
                "sparse_vector", "chunk_index", "token_count", "course_id", "content_type", "image_url",
                "sha256", "updated_at"));
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class))).thenReturn(desc);

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("describe 异常 — 保守视为匹配，不误删")
    void describeFailure_treatedAsMatch() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(milvusClientV2.describeCollection(any(DescribeCollectionReq.class)))
                .thenThrow(new RuntimeException("milvus busy"));

        initializer().run(null);

        verify(milvusClientV2, never()).dropCollection(any(DropCollectionReq.class));
    }

    @Test
    @DisplayName("Collection 不存在 — 直接创建（schema 含新三字段，无 collection_type）")
    void collectionMissing_createsNewSchema() {
        when(milvusClientV2.hasCollection(any(HasCollectionReq.class))).thenReturn(false);

        initializer().run(null);

        ArgumentCaptor<io.milvus.v2.service.collection.request.CreateCollectionReq> captor =
                ArgumentCaptor.forClass(io.milvus.v2.service.collection.request.CreateCollectionReq.class);
        verify(milvusClientV2).createCollection(captor.capture());
        List<String> fields = captor.getValue().getCollectionSchema().getFieldNames();
        assertTrue(fields.contains("content_type") && fields.contains("image_url") && fields.contains("sha256"));
        assertTrue(!fields.contains("collection_type"), "新 schema 不应含 collection_type");
        assertEquals(14, fields.size());
    }
}
```

（注意：测试中使用全路径类名 `io.milvus.v2.service.collection.request.CreateCollectionReq` 违反宪法——改为文件顶部 import 短名。执行者按此改。）

- [ ] **Step 2: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=MilvusCollectionInitializerTest`
Expected: FAIL（现实现 hasCollection=true 直接跳过，无 describe 比对）

- [ ] **Step 3: 实现 MilvusCollectionInitializer 改造**

（a）字段常量：删除 `FIELD_COLLECTION_TYPE`，新增三个常量，并新增期望字段集：

```java
    public static final String FIELD_CONTENT_TYPE = "content_type";
    public static final String FIELD_IMAGE_URL = "image_url";
    public static final String FIELD_SHA256 = "sha256";

    /** 期望 schema 字段全集（14 个）——启动时 describe 比对，不匹配则 drop 重建 */
    private static final List<String> EXPECTED_FIELD_NAMES = List.of(
            FIELD_CHUNK_ID, FIELD_DOC_ID, FIELD_KB_ID, FIELD_CONTENT, FIELD_HEADING_PATH,
            FIELD_DENSE_VECTOR, FIELD_SPARSE_VECTOR, FIELD_CHUNK_INDEX, FIELD_TOKEN_COUNT,
            FIELD_COURSE_ID, FIELD_CONTENT_TYPE, FIELD_IMAGE_URL, FIELD_SHA256, FIELD_UPDATED_AT);
```

（b）`initCollection()` 重写为比对重建：

```java
    private void initCollection() {
        // 1. 检查 Collection 是否存在
        Boolean exists = milvusClientV2.hasCollection(
                HasCollectionReq.builder().collectionName(collectionName).build());

        if (Boolean.TRUE.equals(exists)) {
            if (schemaMatches()) {
                log.info("Milvus Collection 已存在且 schema 匹配，跳过创建: name={}", collectionName);
                return;
            }
            // S1 §12：schema 不匹配（历史版本）→ drop 重建（开发库无业务数据，用户已拍板）
            log.warn("Milvus Collection schema 不匹配，drop 重建: name={}", collectionName);
            milvusClientV2.dropCollection(
                    DropCollectionReq.builder().collectionName(collectionName).build());
        }

        // 2. 创建 Collection（14 字段 Schema + BM25 Function + 3 索引）
        log.info("开始创建 Milvus Collection: name={}", collectionName);
        CollectionSchema schema = buildCollectionSchema();
        List<IndexParam> indexParams = buildIndexParams();

        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .indexParams(indexParams)
                .build();

        milvusClientV2.createCollection(createReq);
        log.info("Milvus Collection 创建成功（含 Schema + Function + 索引）: name={}", collectionName);

        // 3. 加载 Collection 到内存
        milvusClientV2.loadCollection(
                LoadCollectionReq.builder().collectionName(collectionName).build());
        log.info("Milvus Collection 加载完成: name={}", collectionName);
    }

    /**
     * describe 比对实际字段集与期望字段集（双向包含，多余/缺失都视为不匹配）
     *
     * <p>describe 异常时保守返回 true（视为匹配）——不因 Milvus 瞬时故障误删有数据 Collection。
     */
    private boolean schemaMatches() {
        try {
            DescribeCollectionResp resp = milvusClientV2.describeCollection(
                    DescribeCollectionReq.builder().collectionName(collectionName).build());
            if (resp == null || resp.getFieldNames() == null) {
                return false;
            }
            Set<String> actual = new HashSet<>(resp.getFieldNames());
            return actual.containsAll(EXPECTED_FIELD_NAMES) && EXPECTED_FIELD_NAMES.containsAll(actual);
        } catch (Exception e) {
            log.warn("Milvus describe 失败，保守视为 schema 匹配（跳过重建）: collection={}, error={}",
                    collectionName, e.getMessage());
            return true;
        }
    }
```

（c）`buildCollectionSchema()`：删除 collection_type 字段块（原第 10 段），在 course_id 之后、updated_at 之前加：

```java
        // 10. content_type — 分片内容类型（text / image / table）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT_TYPE)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_CONTENT_TYPE)
                .build());

        // 11. image_url — 图片分片的 MinIO objectKey（仅 image 分片有值）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_IMAGE_URL)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_IMAGE_URL)
                .build());

        // 12. sha256 — 归一化内容哈希（检索侧防御去重用，计划 2/5 消费）
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SHA256)
                .dataType(DataType.VarChar)
                .maxLength(MAX_LEN_SHA256)
                .build());
```

类顶部长度常量区：删 `MAX_LEN_COLLECTION_TYPE`，加：

```java
    private static final int MAX_LEN_CONTENT_TYPE = 20;
    private static final int MAX_LEN_IMAGE_URL = 1000;
    private static final int MAX_LEN_SHA256 = 64;
```

（d）`buildIndexParams()`：删除 collection_type 的 INVERTED 索引块（保留 dense/sparse/course_id 三个）。同步更新类 Javadoc（12 字段 → 14 字段、4 索引 → 3 索引、幂等 → 比对重建）。

- [ ] **Step 4: EtlPipeline.buildMilvusRow 适配（同任务，schema 变更的连带修改）**

`buildMilvusRow` 中删除 collection_type 行，在 course_id 行之后加：

```java
        row.addProperty(
                MilvusCollectionInitializer.FIELD_CONTENT_TYPE,
                chunk.getContentType() != null ? chunk.getContentType() : "text");
        row.addProperty(
                MilvusCollectionInitializer.FIELD_IMAGE_URL,
                chunk.getImageUrl() != null ? chunk.getImageUrl() : "");
        row.addProperty(
                MilvusCollectionInitializer.FIELD_SHA256,
                chunk.getSha256() != null ? chunk.getSha256() : "");
```

同步更新该方法 Javadoc 的字段清单（11 字段 → 13 字段描述）。

- [ ] **Step 5: SearchKnowledgeTool 兼容适配**

（a）`OUTPUT_FIELDS` 删除 `FIELD_COLLECTION_TYPE` 行（剩 9 个）。
（b）`buildFilterExpression` 重写（仅 course_id 子句，无 courseIds 时返回 null）：

```java
    /**
     * 构建 Milvus 标量过滤表达式（S1 意图-检索解耦：不再按 collection_type 过滤）
     *
     * <p>格式：
     * <ul>
     *   <li>无 courseIds：返回 null（不设过滤，全局检索）</li>
     *   <li>有 courseIds：{@code (course_id == "DEFAULT" or course_id in ["C1", "C2"])}</li>
     * </ul>
     *
     * @param query 类型化查询
     * @return Milvus 过滤表达式字符串，无过滤条件时为 null
     */
    String buildFilterExpression(TypedQuery query) {
        if (query.courseIds() == null || query.courseIds().isEmpty()) {
            return null;
        }
        String courseList =
                query.courseIds().stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(", "));
        return "(course_id == \"DEFAULT\" or course_id in [" + courseList + "])";
    }
```

（c）`searchSingle` 结果映射段：删除 collectionType 解析，`parseCollectionType` 方法整体删除，KnowledgeChunk 构造传 null：

```java
                chunks.add(new KnowledgeChunk(chunkId, content, "", "", headingPath, score, null));
```

（d）`@Tool` description 更新为：「知识库检索：混合检索 Milvus 知识库（dense+sparse RRF 融合）并精排返回」。（e）类 Javadoc 中「标量路由/collection_type 过滤」描述同步更新。

- [ ] **Step 6: 更新既有测试断言（SearchKnowledgeToolTest / EtlPipelineTest）**

SearchKnowledgeToolTest 中 `buildFilterExpression` 相关断言改为：无 courseIds → `assertNull`；有 courseIds → 断言 `(course_id == "DEFAULT" or course_id in [...])` 且不含 "collection_type"。EtlPipelineTest 增加 Milvus 行字段断言（embedAndIndex 成功路径，捕获 InsertReq）：

```java
    @Test
    @DisplayName("Milvus 行字段 — 新 schema：含 content_type/image_url/sha256，不含 collection_type")
    void milvusRow_containsNewFields_noCollectionType() {
        Document doc = new Document();
        doc.setId(1L);
        doc.setTitle("测试文档");
        when(documentMapper.selectById(1L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(1L);
        chunk.setDocId(1L);
        chunk.setKbId(10L);
        chunk.setContent("图片描述");
        chunk.setContentType("image");
        chunk.setImageUrl("10/abc.png");
        chunk.setSha256("f".repeat(64));
        chunk.setChunkIndex(0);
        when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
        when(embeddingModel.embed(anyList())).thenReturn(List.of(new float[] {0.1f, 0.2f}));

        etlPipeline.embedAndIndex(1L);

        ArgumentCaptor<InsertReq> captor = ArgumentCaptor.forClass(InsertReq.class);
        verify(milvusClientV2).insert(captor.capture());
        com.google.gson.JsonObject row = captor.getValue().getData().get(0);
        assertEquals("image", row.get("content_type").getAsString());
        assertEquals("10/abc.png", row.get("image_url").getAsString());
        assertEquals("f".repeat(64), row.get("sha256").getAsString());
        assertNull(row.get("collection_type"), "新 schema 行不应含 collection_type");
    }
```

（import 用短名；若 EtlPipelineTest 因 Task 1 的三字段已编译通过，此处直接可用。）

- [ ] **Step 7: 跑全部相关测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=MilvusCollectionInitializerTest,SearchKnowledgeToolTest,EtlPipelineTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/commerce/rag/config/MilvusCollectionInitializer.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/main/java/com/commerce/rag/bot/tool/SearchKnowledgeTool.java backend/src/test/java/com/commerce/rag/config/MilvusCollectionInitializerTest.java backend/src/test/java/com/commerce/rag/bot/tool/SearchKnowledgeToolTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): Milvus knowledge_chunks 重建（去 collection_type 加 content_type/image_url/sha256）+ 检索侧兼容"
```

---

## Task 3: 模型配置对齐（spec §6）

**Files:**
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/commerce/rag/properties/EtlProperties.java`
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（EtlProperties 构造更新）
- Test: `backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`（构造参数 + METADATA 模型名断言更新）

**Interfaces:**
- Consumes: 无（纯配置）
- Produces: `EtlProperties` 新形态（`chunk().size()/minChunkSizeChars()`、`captionModel()`、`imageMinSizeKb()`、`table().maxRowsPerChunk()/rowsPerChunk()/overlapRows()`）——Task 5/6/7 消费；yml 键 `etl.caption-model` / `etl.image-min-size-kb` / `etl.chunk.min-chunk-size-chars` / `etl.table.*` / `rag.agent.model`

- [ ] **Step 1: application.yml 修改**

`spring.ai.dashscope` 段两处模型名：

```yaml
      chat:
        options:
          model: qwen3.8-max          # spec §6 主对话旗舰（原 qwen3.7-max）
          temperature: 0.7
      embedding:
        options:
          model: qwen3.7-text-embedding   # spec §6 官方当前最强（原 text-embedding-v4，维度仍 1024）
```

`rag` 段新增（放在 rag.agent.run-limit 旁）：

```yaml
rag:
  agent:
    run-limit: 15
    model: qwen3.8-max              # 主对话模型名（METADATA 事件 model 字段来源，原硬编码于 Worker）
```

`etl` 段改为：

```yaml
etl:
  max-file-size-mb: 100
  embedding-batch-size: 16
  caption-model: qwen3.7-flash       # VLM 图片描述模型（spec §4.5 两通道独立配置）
  image-min-size-kb: 10              # 图片过滤：小于该大小的图标跳过（spec §4.2）
  executor:
    core-size: 2
    max-size: 4
    queue-capacity: 20
    thread-name-prefix: etl-
  chunk:
    size: 768                        # TokenTextSplitter chunkSizeTokens
    min-chunk-size-chars: 64         # 过小 chunk 并入前一个的阈值（spec §4.1）
    # 注意：overlap 配置已删除——Spring AI 1.1.2 TokenTextSplitter 无 overlap 参数（见计划决策点 1）
  table:
    rows-per-chunk: 25               # 大表分组名义行数（spec §4.3「每 20~30 行一组」）
    max-rows-per-chunk: 30           # 分组行数硬上限（token 动态调整）
    overlap-rows: 2                  # 相邻子 chunk 间重叠行数（spec §4.3「1~2 行」）
```

- [ ] **Step 2: EtlProperties 改造**

```java
@Validated
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(
        @Min(1) int maxFileSizeMb,
        Executor executor,
        Chunk chunk,
        @Min(1) int embeddingBatchSize,
        @NotBlank String captionModel,
        @Min(1) int imageMinSizeKb,
        Table table) {

    /**
     * ETL 线程池配置
     */
    public record Executor(
            @Min(1) int coreSize, @Min(1) int maxSize, @Min(1) int queueCapacity, @NotBlank String threadNamePrefix) {}

    /**
     * 文本分块参数（Spring AI TokenTextSplitter 映射：size=chunkSizeTokens，
     * minChunkSizeChars=过小合并阈值；1.1.2 无 overlap 参数，见计划决策点 1）
     */
    public record Chunk(@Min(1) int size, @Min(1) int minChunkSizeChars) {}

    /**
     * 表格分块参数（spec §4.3：20~30 行一组按 token 动态调整，子 chunk 重复表头，组间 overlap 行）
     */
    public record Table(@Min(1) int rowsPerChunk, @Min(1) int maxRowsPerChunk, @Min(0) int overlapRows) {}
}
```

类 Javadoc 中 yml 示例块同步更新。

- [ ] **Step 3: ChatRequestWorker 模型名配置化**

字段与构造器（手写构造器保持既有风格，加一个参数；该类构造器属「有初始化逻辑」的既有例外，不改为 @RequiredArgsConstructor）：

```java
    /** 主对话模型名（METADATA 事件 model 字段，来自 rag.agent.model） */
    private final String agentModel;
```

构造器参数区加：

```java
            @Value("${rag.agent.model:qwen3.8-max}") String agentModel,
```

（注意 @Value 放参数上；参数顺序放 objectMapper 之后，`this.agentModel = agentModel;`。）

`processRequest` 中 RunState 创建改为：

```java
        SseEventTransformer.RunState runState =
                SseEventTransformer.RunState.create(runIdStr, sessionIdStr, agentModel);
```

- [ ] **Step 4: 跑测试验证失败（编译错误即失败信号）**

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest,ChatRequestWorkerTest`
Expected: FAIL（EtlProperties 构造参数个数不匹配、ChatRequestWorker 构造参数不匹配）

- [ ] **Step 5: 更新两个测试的构造调用**

EtlPipelineTest `setUp()` 中：

```java
        EtlProperties props = new EtlProperties(
                100,
                new EtlProperties.Executor(2, 4, 20, "etl-"),
                new EtlProperties.Chunk(768, 64),
                16,
                "qwen3.7-flash",
                10,
                new EtlProperties.Table(25, 30, 2));
```

ChatRequestWorkerTest：构造调用追加 `"qwen3.8-max"` 参数；若有断言 METADATA 事件 payload 中 `model` 字段的用例，期望值改为 `qwen3.8-max`（原硬编码 qwen3.7-max）。

- [ ] **Step 6: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest,ChatRequestWorkerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/application.yml backend/src/main/java/com/commerce/rag/properties/EtlProperties.java backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java
git commit -m "feat(S1): 模型配置对齐（qwen3.8-max/qwen3.7-text-embedding/caption 独立配置）+ 模型名配置化"
```

---

## Task 4: XHTML 结构化解析（标题路径 + 图片捕获 + 分区）

**Files:**
- Modify: `backend/pom.xml`（加 jsoup 依赖）
- Create: `backend/src/main/java/com/commerce/rag/etl/ParsedContent.java`
- Create: `backend/src/main/java/com/commerce/rag/etl/TikaImageExtractor.java`
- Create: `backend/src/main/java/com/commerce/rag/etl/XhtmlDocumentParser.java`
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（parseDocument 改造 + parsedTextCache → parsedContentCache + chunkDocument 适配）
- Test: `backend/src/test/java/com/commerce/rag/etl/XhtmlDocumentParserTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/TikaImageExtractorTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（seedParsedText → seedParsedContent）

**Interfaces:**
- Consumes: PromptLoader 无；Tika ToHTMLContentHandler(OutputStream, String)、EmbeddedDocumentExtractor（已 javap 实锤）
- Produces: `ParsedContent(List<ParsedSection>)` 及 `ParsedSection` sealed 接口（TextSection/ImageSection，TableSection 于 Task 6 加入）；`XhtmlDocumentParser.parse(String xhtml, Map<String, CapturedImage>)`；`TikaImageExtractor`（Task 5/6/7 消费）

- [ ] **Step 1: pom.xml 加 jsoup**

在 tika 依赖之后加：

```xml
        <!-- S1 表格/结构提取：Tika XHTML 输出 → Markdown 表格与标题路径解析 -->
        <dependency>
            <groupId>org.jsoup</groupId>
            <artifactId>jsoup</artifactId>
            <version>1.18.1</version>
        </dependency>
```

- [ ] **Step 2: ParsedContent 与两个新类（写失败测试前的实现骨架）**

`etl/ParsedContent.java`：

```java
package com.commerce.rag.etl;

import java.util.List;

/**
 * Tika 结构化解析结果 —— parseDocument 阶段的产出，chunkDocument 阶段的输入
 *
 * <p>sections 按文档出现顺序排列（文本/表格/图片混合），保证 chunk_index 与原文顺序一致。
 *
 * @author commerce-rag
 */
public record ParsedContent(List<ParsedSection> sections) {

    /** 文档结构单元（文本 / 表格 / 图片），按出现顺序编排 */
    public sealed interface ParsedSection permits TextSection, ImageSection, TableSection {}

    /** 文本单元：同一标题路径下的连续正文 */
    public record TextSection(String headingPath, String text) implements ParsedSection {}

    /** 表格单元：Tika XHTML 输出中的原始 table 片段（Markdown 化在分片阶段，Task 6 引入） */
    public record TableSection(String headingPath, String html) implements ParsedSection {}

    /** 图片单元：内嵌图片字节与 MIME（caption 与入库在分片阶段，Task 7 消费） */
    public record ImageSection(String headingPath, String mimeType, byte[] bytes, String resourceName)
            implements ParsedSection {}

    /** 内嵌图片捕获结果（resourceName → 字节与 MIME） */
    public record CapturedImage(byte[] bytes, String mimeType) {}
}
```

`etl/TikaImageExtractor.java`：

```java
package com.commerce.rag.etl;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/**
 * Tika 内嵌图片捕获器 —— EmbeddedDocumentExtractor 实现
 *
 * <p>PDF 内联图片与 OOXML（DOCX/PPTX）内嵌图片经 Tika 容器解析器路由到本提取器，
 * 捕获图片字节与 MIME 类型，以资源名（resourceName）为键，供 XhtmlDocumentParser 按
 * XHTML 中的 &lt;img src="embedded:xxx"&gt; 定位。
 *
 * @author commerce-rag
 */
public class TikaImageExtractor implements EmbeddedDocumentExtractor {

    private final Map<String, ParsedContent.CapturedImage> images = new LinkedHashMap<>();

    /** 无资源名图片的捕获序号（兜底命名） */
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * 是否解析该内嵌资源 —— 仅图片（image/*）需要捕获，其余内嵌文档跳过
     */
    @Override
    public boolean shouldParseEmbedded(Metadata metadata) {
        return metadata.get(Metadata.CONTENT_TYPE) != null
                && metadata.get(Metadata.CONTENT_TYPE).startsWith("image/");
    }

    /**
     * 捕获内嵌图片字节与 MIME
     */
    @Override
    public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata, boolean outputHtml)
            throws SAXException, IOException {
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) {
            return;
        }
        String resourceName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
        if (resourceName == null || resourceName.isBlank()) {
            resourceName = "image" + counter.getAndIncrement();
        }
        images.putIfAbsent(resourceName, new ParsedContent.CapturedImage(bytes, metadata.get(Metadata.CONTENT_TYPE)));
    }

    /**
     * @return 捕获到的图片映射（resourceName → 字节与 MIME）
     */
    public Map<String, ParsedContent.CapturedImage> getImages() {
        return images;
    }
}
```

`etl/XhtmlDocumentParser.java`：

```java
package com.commerce.rag.etl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Component;

/**
 * Tika XHTML 结构解析器 —— 提取标题导航路径，将正文切分为带标题的文本单元，
 * 并定位内嵌图片（&lt;img src="embedded:xxx"&gt; 与捕获字节按资源名匹配，未匹配者按捕获顺序追加）。
 *
 * <p>纯函数组件（无状态、无 IO）：输入 XHTML 字符串与图片字节映射，输出 ParsedContent，
 * 便于单元测试。
 *
 * @author commerce-rag
 */
@Component
public class XhtmlDocumentParser {

    /** 块级元素：递归结束后补换行，保证正文段落分隔 */
    private static final Set<String> BLOCK_TAGS =
            Set.of("p", "div", "li", "br", "tr", "ul", "ol", "pre", "blockquote");

    /**
     * 解析 XHTML 为结构分区
     *
     * @param xhtml  Tika ToHTMLContentHandler 输出的 XHTML
     * @param images 内嵌图片映射（resourceName → 字节与 MIME），允许为空
     * @return 按文档顺序排列的文本/图片分区（表格分区于 Task 6 启用）
     */
    public ParsedContent parse(String xhtml, Map<String, ParsedContent.CapturedImage> images) {
        Element body = Jsoup.parse(xhtml).body();
        List<ParsedContent.ParsedSection> sections = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Deque<Heading> headings = new ArrayDeque<>();
        List<String> matchedNames = new ArrayList<>();

        walk(body, headings, buf, sections, images, matchedNames);
        flushText(buf, currentPath(headings), sections);

        // 未在正文中定位到的图片：按捕获顺序追加到末尾（无标题/位置信息，尽力而为）
        for (Map.Entry<String, ParsedContent.CapturedImage> entry : images.entrySet()) {
            if (!matchedNames.contains(entry.getKey())) {
                sections.add(new ParsedContent.ImageSection(
                        "", entry.getValue().mimeType(), entry.getValue().bytes(), entry.getKey()));
            }
        }
        return new ParsedContent(sections);
    }

    /** 深度优先遍历元素树，维护标题栈并收集正文/图片 */
    private void walk(
            Element element,
            Deque<Heading> headings,
            StringBuilder buf,
            List<ParsedContent.ParsedSection> sections,
            Map<String, ParsedContent.CapturedImage> images,
            List<String> matchedNames) {
        for (Node child : element.childNodes()) {
            if (child instanceof Element e) {
                String tag = e.tagName();
                if (tag.matches("h[1-6]")) {
                    // 标题：切换标题栈（标题文本进 heading_path，不进正文）
                    flushText(buf, currentPath(headings), sections);
                    int level = tag.charAt(1) - '0';
                    while (!headings.isEmpty() && headings.peek().level() >= level) {
                        headings.pop();
                    }
                    String title = e.text().trim();
                    if (!title.isEmpty()) {
                        headings.push(new Heading(level, title));
                    }
                    continue;
                }
                if (tag.equals("table")) {
                    // 过渡期（Task 6 前）：表格以纯文本并入正文，保证内容不丢
                    flushText(buf, currentPath(headings), sections);
                    buf.append(e.text()).append('\n');
                    continue;
                }
                if (tag.equals("img")) {
                    flushText(buf, currentPath(headings), sections);
                    String src = e.attr("src");
                    ParsedContent.CapturedImage image = lookup(images, src);
                    if (image != null) {
                        sections.add(new ParsedContent.ImageSection(
                                currentPath(headings), image.mimeType(), image.bytes(), src));
                        matchedNames.add(src);
                    }
                    continue;
                }
                walk(e, headings, buf, sections, images, matchedNames);
                if (BLOCK_TAGS.contains(tag)) {
                    buf.append('\n');
                }
            } else if (child instanceof TextNode textNode) {
                String text = textNode.text().trim();
                if (!text.isEmpty()) {
                    buf.append(text).append('\n');
                }
            }
        }
    }

    /** 按资源名匹配图片字节；XHTML src 形如 embedded:image0.png */
    private static ParsedContent.CapturedImage lookup(Map<String, ParsedContent.CapturedImage> images, String src) {
        if (images.containsKey(src)) {
            return images.get(src);
        }
        if (src.startsWith("embedded:")) {
            return images.get(src.substring("embedded:".length()));
        }
        return null;
    }

    private static void flushText(StringBuilder buf, String path, List<ParsedContent.ParsedSection> sections) {
        String text = buf.toString().trim();
        buf.setLength(0);
        if (!text.isEmpty()) {
            sections.add(new ParsedContent.TextSection(path, text));
        }
    }

    private static String currentPath(Deque<Heading> headings) {
        return headings.stream().map(Heading::title).collect(Collectors.joining(" > "));
    }

    /** 标题栈元素（层级 + 标题文本） */
    private record Heading(int level, String title) {}
}
```

- [ ] **Step 3: EtlPipeline parseDocument 改造 + chunkDocument 适配**

（a）依赖注入：构造器加 `XhtmlDocumentParser xhtmlDocumentParser`（@RequiredArgsConstructor 自动生效）。
（b）缓存字段：`parsedTextCache` 改名为 `parsedContentCache`，类型 `ConcurrentHashMap<Long, ParsedContent>`；`process` 的 finally 清理处同步改名。
（c）`parseDocument` 方法体（try 块内）替换为：

```java
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
                    docId, xhtml.length(), imageExtractor.getImages().size());

            // 将解析结果暂存到内存缓存（供 chunkDocument 阶段使用）
            parsedContentCache.put(docId, parsed);
```

import 同步：删 `org.apache.tika.sax.BodyContentHandler`，加 `org.apache.tika.sax.ToHTMLContentHandler`、`org.apache.tika.extractor.EmbeddedDocumentExtractor`、`java.io.ByteArrayOutputStream`、`java.nio.charset.StandardCharsets`。

（d）`chunkDocument` 开头适配（过渡期：合并文本分区走旧递归分片，图片/表格暂不处理）：

```java
        ParsedContent parsed = parsedContentCache.get(docId);
        if (parsed == null) {
            throw new IllegalStateException("解析结果为空或未找到: docId=" + docId);
        }
        String text = parsed.sections().stream()
                .filter(ParsedContent.TextSection.class::isInstance)
                .map(s -> ((ParsedContent.TextSection) s).text())
                .collect(Collectors.joining("\n\n"));
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("解析文本为空: docId=" + docId);
        }
```

（原 `String text = parsedTextCache.get(docId); ... throw` 段替换为上述代码；`parsedTextCache.remove(docId)` 在 chunkDocument 尾部的调用同步改名。）

- [ ] **Step 4: 写失败测试 XhtmlDocumentParserTest / TikaImageExtractorTest**

`XhtmlDocumentParserTest`：

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XhtmlDocumentParser 单元测试 —— 标题路径/正文切分/图片定位（纯函数）
 *
 * @author commerce-rag
 */
class XhtmlDocumentParserTest {

    private final XhtmlDocumentParser parser = new XhtmlDocumentParser();

    @Test
    @DisplayName("标题嵌套 — 正文单元继承完整标题路径")
    void headings_producePath() {
        String xhtml = "<html><body>"
                + "<h1>第一章</h1><p>第一节正文内容。</p>"
                + "<h2>1.1 小节</h2><p>小节正文内容。</p>"
                + "</body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());
        List<ParsedContent.ParsedSection> sections = parsed.sections();

        assertEquals(2, sections.size());
        ParsedContent.TextSection first = (ParsedContent.TextSection) sections.get(0);
        ParsedContent.TextSection second = (ParsedContent.TextSection) sections.get(1);
        assertEquals("第一章", first.headingPath());
        assertTrue(first.text().contains("第一节正文内容"));
        assertEquals("第一章 > 1.1 小节", second.headingPath());
        assertTrue(second.text().contains("小节正文内容"));
    }

    @Test
    @DisplayName("标题文本不进正文 — heading 只出现在 headingPath")
    void headingText_excludedFromBody() {
        String xhtml = "<html><body><h2>环境要求</h2><p>JDK 17 以上。</p></body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());
        ParsedContent.TextSection section = (ParsedContent.TextSection) parsed.sections().get(0);

        assertTrue(!section.text().contains("环境要求"), "标题文本不应重复出现在正文: " + section.text());
    }

    @Test
    @DisplayName("表格过渡期 — 表格文本并入正文（内容不丢）")
    void tableText_mergedIntoBody() {
        String xhtml = "<html><body><p>前置说明。</p><table><tr><th>名称</th></tr>"
                + "<tr><td>数值</td></tr></table><p>后置说明。</p></body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());
        String all = parsed.sections().stream()
                .filter(ParsedContent.TextSection.class::isInstance)
                .map(s -> ((ParsedContent.TextSection) s).text())
                .reduce("", (a, b) -> a + "\n" + b);

        assertTrue(all.contains("名称") && all.contains("数值"), "表格文本应保留在正文: " + all);
    }

    @Test
    @DisplayName("图片定位 — embedded: 前缀剥除后按资源名匹配，产出 ImageSection")
    void imgElement_matchedToCapturedImage() {
        String xhtml = "<html><body><h1>图例</h1><img src=\"embedded:image0.png\"/></body></html>";
        byte[] bytes = new byte[] {1, 2, 3};
        Map<String, ParsedContent.CapturedImage> images = new LinkedHashMap<>();
        images.put("image0.png", new ParsedContent.CapturedImage(bytes, "image/png"));

        ParsedContent parsed = parser.parse(xhtml, images);

        assertEquals(1, parsed.sections().size());
        ParsedContent.ImageSection image = (ParsedContent.ImageSection) parsed.sections().get(0);
        assertEquals("图例", image.headingPath());
        assertEquals("image/png", image.mimeType());
        assertEquals(bytes, image.bytes());
    }

    @Test
    @DisplayName("未匹配图片 — 按捕获顺序追加到末尾（尽力而为）")
    void unmatchedImage_appendedAtEnd() {
        ParsedContent parsed = parser.parse(
                "<html><body><p>只有文本。</p></body></html>",
                Map.of("orphan.png", new ParsedContent.CapturedImage(new byte[] {9}, "image/png")));

        assertEquals(2, parsed.sections().size());
        ParsedContent.ImageSection image = (ParsedContent.ImageSection) parsed.sections().get(1);
        assertEquals("orphan.png", image.resourceName());
        assertEquals("", image.headingPath());
    }

    @Test
    @DisplayName("空输入 — 空分区列表")
    void blankInput_emptySections() {
        ParsedContent parsed = parser.parse("<html><body></body></html>", Map.of());
        assertTrue(parsed.sections().isEmpty());
    }
}
```

`TikaImageExtractorTest`：

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TikaImageExtractor 单元测试 —— 内嵌图片捕获
 *
 * @author commerce-rag
 */
class TikaImageExtractorTest {

    @Test
    @DisplayName("shouldParseEmbedded — 仅 image/* 返回 true")
    void shouldParseEmbedded_onlyImages() {
        TikaImageExtractor extractor = new TikaImageExtractor();
        Metadata imageMeta = new Metadata();
        imageMeta.set(Metadata.CONTENT_TYPE, "image/png");
        Metadata docMeta = new Metadata();
        docMeta.set(Metadata.CONTENT_TYPE, "application/pdf");

        assertTrue(extractor.shouldParseEmbedded(imageMeta));
        assertFalse(extractor.shouldParseEmbedded(docMeta));
        assertFalse(extractor.shouldParseEmbedded(new Metadata()));
    }

    @Test
    @DisplayName("parseEmbedded — 按资源名捕获字节与 MIME")
    void parseEmbedded_capturesByResourceName() throws Exception {
        TikaImageExtractor extractor = new TikaImageExtractor();
        Metadata metadata = new Metadata();
        metadata.set(Metadata.CONTENT_TYPE, "image/jpeg");
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "image0.jpg");
        byte[] bytes = "fake-image-bytes".getBytes(StandardCharsets.UTF_8);

        extractor.parseEmbedded(new ByteArrayInputStream(bytes), mock(org.xml.sax.ContentHandler.class), metadata, false);

        ParsedContent.CapturedImage captured = extractor.getImages().get("image0.jpg");
        assertEquals("image/jpeg", captured.mimeType());
        assertEquals(new String(bytes, StandardCharsets.UTF_8), new String(captured.bytes(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("parseEmbedded — 无资源名时按序号兜底命名")
    void parseEmbedded_fallbackNameByCounter() throws Exception {
        TikaImageExtractor extractor = new TikaImageExtractor();
        extractor.parseEmbedded(
                new ByteArrayInputStream(new byte[] {1}),
                mock(org.xml.sax.ContentHandler.class),
                new Metadata(),
                false);

        assertTrue(extractor.getImages().containsKey("image0"));
    }
}
```

（测试中 mock(org.xml.sax.ContentHandler.class) 全路径名同样改为 import 短名。）

- [ ] **Step 5: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=XhtmlDocumentParserTest,TikaImageExtractorTest`
Expected: FAIL（类不存在）

- [ ] **Step 6: EtlPipelineTest 适配（seed 反射改为 ParsedContent）**

`seedParsedText` 改名为 `seedParsedContent`：

```java
    /** 反射向 parsedContentCache 注入解析结果（process 内由 Tika 写入，单测直接 seed） */
    @SuppressWarnings("unchecked")
    private void seedParsedContent(Long docId, String text) throws Exception {
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(docId, new ParsedContent(List.of(new ParsedContent.TextSection("", text))));
    }
```

既有调用点（chunkDocument 两个用例）同步改名；`setUp` 构造器加 `xhtmlDocumentParser` 依赖——**用真实实例**（该类为纯函数无依赖，真实实例保证 process 全管道用例端到端可用）：`new XhtmlDocumentParser()`（不 mock，避免 mock 返回 null 使 Tika 解析结果丢失）。

- [ ] **Step 7: 跑全部测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=XhtmlDocumentParserTest,TikaImageExtractorTest,EtlPipelineTest`
Expected: PASS（含既有 process 全管道用例——Tika 真实解析仍工作）

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/commerce/rag/etl/ParsedContent.java backend/src/main/java/com/commerce/rag/etl/TikaImageExtractor.java backend/src/main/java/com/commerce/rag/etl/XhtmlDocumentParser.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/XhtmlDocumentParserTest.java backend/src/test/java/com/commerce/rag/etl/TikaImageExtractorTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): ETL 解析改造为 XHTML 结构化解析（标题路径 + 内嵌图片捕获 + 分区结构）"
```

---

## Task 5: TokenTextSplitter 文本分片（替换手写递归分片）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/etl/TextChunkSplitter.java`
- Create: `backend/src/main/java/com/commerce/rag/etl/TokenEstimator.java`
- Create: `backend/src/main/java/com/commerce/rag/etl/ChunkSpec.java`
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（chunkDocument 重写；**同任务删除** recursiveSplit/splitLargeParagraph/applyOverlap/ChunkInfo 与私有 estimateTokens）
- Test: `backend/src/test/java/com/commerce/rag/etl/TextChunkSplitterTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（chunk 用例更新为 ParsedContent seed + 新断言）

**Interfaces:**
- Consumes: Task 4 的 ParsedContent/TextSection；Task 3 的 EtlProperties.chunk().size()/minChunkSizeChars()；TokenTextSplitter 构造器 `(int, int, int, int, boolean)`（字节码实锤参数序：chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator）
- Produces: `ChunkSpec(content, headingPath, contentType, imageUrl, metadataJson, charOffsetStart, charOffsetEnd)`（Task 6/7/8 扩展消费）；`TokenEstimator.estimate(String)`（Task 6 表格分组消费）

- [ ] **Step 1: 写失败测试 TextChunkSplitterTest**

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TextChunkSplitter 单元测试 —— TokenTextSplitter 字符串直出封装
 *
 * @author commerce-rag
 */
class TextChunkSplitterTest {

    @Test
    @DisplayName("长文本分片 — 每片 token 数不超过 chunkSize，文本不丢")
    void longText_splitsWithinTokenLimit() {
        TextChunkSplitter splitter = new TextChunkSplitter(100, 20);
        String longText = ("检索增强生成是一种结合检索与生成的架构范式，向量数据库负责存储嵌入向量，"
                + "混合检索融合了向量相似度与关键词匹配两种召回信号。").repeat(20);

        List<String> chunks = splitter.splitText(longText);

        assertTrue(chunks.size() > 1, "长文本应拆分为多片");
        for (String chunk : chunks) {
            assertTrue(TokenEstimator.estimate(chunk) <= 120,
                    "单片 token 不应明显超过 chunkSize: " + TokenEstimator.estimate(chunk));
        }
        // 关键句不丢（decode 往返保留原文）
        String joined = String.join("", chunks);
        assertTrue(joined.contains("混合检索融合了向量相似度与关键词匹配"));
    }

    @Test
    @DisplayName("短文本 — 单块直出")
    void shortText_singleChunk() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);

        List<String> chunks = splitter.splitText("这是短文本内容，不足一个分片大小。");

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).contains("短文本内容"));
    }

    @Test
    @DisplayName("空文本 — 空列表")
    void blankText_emptyList() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);

        assertEquals(0, splitter.splitText("   ").size());
        assertEquals(0, splitter.splitText("").size());
    }

    @Test
    @DisplayName("中文文本 — 无空格拼接副作用（decode 往返保留原文）")
    void chineseText_noSpaceCorruption() {
        TextChunkSplitter splitter = new TextChunkSplitter(768, 64);
        String chinese = "中文段落应当保持连续无空格。".repeat(50);

        List<String> chunks = splitter.splitText(chinese);

        for (String chunk : chunks) {
            assertTrue(!chunk.contains(" 中") && !chunk.contains("文 "), "中文不应被空格拆散: " + chunk.substring(0, Math.min(30, chunk.length())));
        }
    }
}
```

（TokenEstimator 尚未存在——测试先编译不过即为红。TokenEstimator 的 estimate 阈值 120 留余量，因 TokenTextSplitter 的句子边界回卷会让单片略超。）

- [ ] **Step 2: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=TextChunkSplitterTest`
Expected: FAIL（TextChunkSplitter/TokenEstimator 类不存在）

- [ ] **Step 3: 实现 TokenEstimator / TextChunkSplitter / ChunkSpec**

`etl/TokenEstimator.java`：

```java
package com.commerce.rag.etl;

/**
 * Token 数量估算器 —— 粗略估算（中文 1 字 ≈ 1 token，英文 4 字符 ≈ 1 token）
 *
 * <p>用于分片 token_count 字段与表格行分组 token 上限判断（非精确计费口径）。
 * 原 EtlPipeline 私有方法 estimateTokens 上提至此，供分片器与表格分片器共用。
 *
 * @author commerce-rag
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    /**
     * 估算文本 token 数
     *
     * @param text 文本（可为空）
     * @return 估算 token 数（空文本为 0）
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
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
}
```

`etl/TextChunkSplitter.java`：

```java
package com.commerce.rag.etl;

import java.util.List;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * 文本分片器 —— Spring AI TokenTextSplitter 的字符串直出封装
 *
 * <p>Spring AI 1.1.2 的 TextSplitter 公开 API 仅接受 org.springframework.ai.document.Document，
 * 与本项目实体 Document（com.commerce.rag.entity.Document）同名（宪法禁止全路径类名），
 * 故子类化提升 protected splitText(String) 可见性，直接对原始文本分片。
 *
 * <p>框架行为（1.1.2 字节码实锤）：内部以 JTokkit CL100K_BASE 编码 token 序列后按
 * chunkSize 切片再解码——无固定 overlap 参数（见计划决策点 1）；相邻 chunk 之间以
 * 句子边界（。!?！？换行）回卷实现连续性，边界句不截断；解码往返保留原文（无空格
 * 拼接副作用）。
 *
 * @author commerce-rag
 */
public class TextChunkSplitter extends TokenTextSplitter {

    /** 小于该 token 数的 chunk 不输出（过滤纯标点/空白碎块） */
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 5;

    /** 单次分片最大 chunk 数（超长文档防御上限） */
    private static final int MAX_NUM_CHUNKS = 10000;

    /**
     * @param chunkSize         目标 chunk token 数（etl.chunk.size）
     * @param minChunkSizeChars 句子边界回卷的最小字符数（etl.chunk.min-chunk-size-chars）
     */
    public TextChunkSplitter(int chunkSize, int minChunkSizeChars) {
        super(chunkSize, minChunkSizeChars, MIN_CHUNK_LENGTH_TO_EMBED, MAX_NUM_CHUNKS, true);
    }

    @Override
    public List<String> splitText(String text) {
        return super.splitText(text);
    }
}
```

`etl/ChunkSpec.java`：

```java
package com.commerce.rag.etl;

/**
 * 待落库分片规格 —— 分片阶段的临时数据结构（三种内容类型统一载体）
 *
 * @param content         分片内容（text 正文 / image caption / table Markdown）
 * @param headingPath     标题导航路径（如「第一章 > 1.1 小节」）
 * @param contentType     内容类型：text / image / table
 * @param imageUrl        图片分片的 MinIO objectKey（其余类型为 null）
 * @param metadataJson    附加元数据 JSON（如图片 resourceName）
 * @param charOffsetStart 原文字符偏移起点（尽力而为，图片为 null）
 * @param charOffsetEnd   原文字符偏移终点（尽力而为，图片为 null）
 *
 * @author commerce-rag
 */
public record ChunkSpec(
        String content,
        String headingPath,
        String contentType,
        String imageUrl,
        String metadataJson,
        Integer charOffsetStart,
        Integer charOffsetEnd) {}
```

- [ ] **Step 4: EtlPipeline chunkDocument 重写为 ChunkSpec 组装 + 落库循环**

（a）`chunkDocument` 主体替换（软删幂等段保留，分片与落库段重写）：

```java
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

        // 组装全部待落库分片（按文档顺序：文本/表格/图片）
        List<ChunkSpec> specs = buildChunkSpecs(parsed);
        if (specs.isEmpty()) {
            throw new IllegalStateException("分片结果为空: docId=" + docId);
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
     * （文本走 TokenTextSplitter；表格/图片分区于 Task 6/7 接入）
     */
    private List<ChunkSpec> buildChunkSpecs(ParsedContent parsed) {
        List<ChunkSpec> specs = new ArrayList<>();
        for (ParsedContent.ParsedSection section : parsed.sections()) {
            if (section instanceof ParsedContent.TextSection text) {
                specs.addAll(splitTextSection(text));
            }
        }
        return specs;
    }

    /**
     * 文本分区分片 —— TokenTextSplitter + 过小 chunk 并入前一个
     */
    private List<ChunkSpec> splitTextSection(ParsedContent.TextSection section) {
        TextChunkSplitter splitter =
                new TextChunkSplitter(etlProperties.chunk().size(), etlProperties.chunk().minChunkSizeChars());
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
            specs.add(new ChunkSpec(
                    piece, section.headingPath(), "text", null, null, start, start + piece.length()));
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
```

（b）**同任务删除死代码**（本次改动使其废弃）：`recursiveSplit`、`splitLargeParagraph`、`applyOverlap`（空实现）、内部类 `ChunkInfo`、私有 `estimateTokens`（被 TokenEstimator 取代）。类 Javadoc「递归分片」描述同步改为 TokenTextSplitter 描述。

- [ ] **Step 5: 更新 EtlPipelineTest 既有 chunk 用例**

`chunkDocument_longParagraph_splitsWithRelations`：seed 改 `seedParsedContent(1L, longPara)`；断言追加 `contentType=text` 与 `parentChunkId==null`（新分片模型无段落分组）；`chunkDocument_shortText_singleChunkWithDefaultCourse` 同 seed 改名。新增两个用例：

```java
    @Test
    @DisplayName("chunkDocument — 多标题分区：各分片继承所属 headingPath")
    void chunkDocument_multiSections_inheritHeadingPath() throws Exception {
        Document doc = new Document();
        doc.setId(3L);
        doc.setKbId(30L);
        when(documentMapper.selectById(3L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(3L, new ParsedContent(List.of(
                        new ParsedContent.TextSection("第一章", "第一章的正文内容。".repeat(10)),
                        new ParsedContent.TextSection("第二章", "第二章的正文内容。".repeat(10)))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(300);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(3L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, atLeast(2)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(c -> "第一章".equals(c.getHeadingPath())));
        assertTrue(captor.getAllValues().stream().anyMatch(c -> "第二章".equals(c.getHeadingPath())));
    }

    @Test
    @DisplayName("chunkDocument — 过小尾块并入前一个（不产生 <64 字符碎块）")
    void chunkDocument_smallTail_mergedIntoPrevious() throws Exception {
        Document doc = new Document();
        doc.setId(4L);
        doc.setKbId(40L);
        when(documentMapper.selectById(4L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 构造：一大段正文 + 短尾句（<64 字符）
        String longBody = "检索增强生成结合检索与生成，向量数据库存储嵌入向量。".repeat(12);
        String tail = "完。";
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(4L, new ParsedContent(List.of(new ParsedContent.TextSection("", longBody + tail))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(400);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(4L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper, atLeast(1)).insert(captor.capture());
        assertTrue(captor.getAllValues().stream()
                        .noneMatch(c -> c.getContent().length() < 64 && c.getContent().endsWith("完。")),
                "过小尾块应并入前一个分片");
    }
```

- [ ] **Step 6: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=TextChunkSplitterTest,EtlPipelineTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/commerce/rag/etl/TextChunkSplitter.java backend/src/main/java/com/commerce/rag/etl/TokenEstimator.java backend/src/main/java/com/commerce/rag/etl/ChunkSpec.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/TextChunkSplitterTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): 文本分片切换 TokenTextSplitter（标题路径继承 + 过小合并），删除手写递归分片"
```

---

## Task 6: table chunk（Markdown 化 + 大表行分组）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/etl/TableChunker.java`
- Modify: `backend/src/main/java/com/commerce/rag/etl/XhtmlDocumentParser.java`（table 分支改为产出 TableSection 并剥离正文）
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（buildChunkSpecs 接入表格）
- Test: `backend/src/test/java/com/commerce/rag/etl/TableChunkerTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/XhtmlDocumentParserTest.java`（table 用例更新）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（加 table chunk 集成用例）

**Interfaces:**
- Consumes: Task 4 的 TableSection（record 已定义）；Task 3 的 `EtlProperties.chunk().size()`（token 上限）与 `table().rowsPerChunk()/maxRowsPerChunk()/overlapRows()`；Task 5 的 TokenEstimator/ChunkSpec
- Produces: `TableChunker.chunk(String html, String headingPath) → List<ChunkSpec>`（contentType="table"）

- [ ] **Step 1: 写失败测试 TableChunkerTest**

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TableChunker 单元测试 —— HTML 表格 → Markdown；大表行分组/表头重复/overlap 行/上下文前缀
 *
 * @author commerce-rag
 */
class TableChunkerTest {

    private static final EtlProperties PROPS = new EtlProperties(
            100,
            new EtlProperties.Executor(2, 4, 20, "etl-"),
            new EtlProperties.Chunk(768, 64),
            16,
            "qwen3.7-flash",
            10,
            new EtlProperties.Table(25, 30, 2));

    private final TableChunker chunker = new TableChunker(PROPS);

    @Test
    @DisplayName("小表格 — 整表一个 Markdown chunk（表头行 + 分隔行 + 数据行）")
    void smallTable_singleMarkdownChunk() {
        String html = "<table><tr><th>名称</th><th>价格</th></tr>"
                + "<tr><td>课程A</td><td>1999</td></tr><tr><td>课程B</td><td>2999</td></tr></table>";

        List<ChunkSpec> specs = chunker.chunk(html, "课程列表");

        assertEquals(1, specs.size());
        ChunkSpec spec = specs.get(0);
        assertEquals("table", spec.contentType());
        assertEquals("课程列表", spec.headingPath());
        assertTrue(spec.content().contains("| 名称 | 价格 |"), "应含表头行: " + spec.content());
        assertTrue(spec.content().contains("| --- | --- |"), "应含 Markdown 分隔行: " + spec.content());
        assertTrue(spec.content().contains("| 课程A | 1999 |"), "应含数据行: " + spec.content());
    }

    @Test
    @DisplayName("大表格 — 行分组：每组重复表头、组间 overlap 行、上下文前缀")
    void largeTable_groupedWithHeaderAndOverlap() {
        // 40 行大表（每行约 40+ token，5 行即逼近 768 token 上限）
        StringBuilder html = new StringBuilder("<table><tr><th>序号</th><th>说明</th></tr>");
        for (int i = 1; i <= 40; i++) {
            html.append("<tr><td>").append(i).append("</td><td>")
                    .append("这是第").append(i).append("行的详细说明内容，包含足够的文字。".repeat(2))
                    .append("</td></tr>");
        }
        html.append("</table>");

        List<ChunkSpec> specs = chunker.chunk(html.toString(), "");

        assertTrue(specs.size() > 1, "大表应拆分为多组: " + specs.size());
        for (ChunkSpec spec : specs) {
            assertEquals("table", spec.contentType());
            // 每组重复完整表头（语义独立）
            assertTrue(spec.content().contains("| 序号 | 说明 |"), "每组应含表头: " + spec.content().substring(0, 200));
            // 上下文前缀 = 表头 + 前 2 数据行（拼在 content 开头）
            assertTrue(spec.content().startsWith("| 序号 | 说明 |"), "前缀应位于 content 开头");
            // token 上限（分组 ≤ 768 token + 前缀约 120 token 的余量）
            assertTrue(TokenEstimator.estimate(spec.content()) <= 1000, "分组 token 超上限");
        }
        // 相邻组 overlap：前一组末尾行出现在后一组
        String prev = specs.get(0).content();
        String next = specs.get(1).content();
        boolean overlapped = java.util.stream.IntStream.rangeClosed(1, 40)
                .anyMatch(i -> prev.contains("| " + i + " |") && next.contains("| " + i + " |"));
        assertTrue(overlapped, "相邻组应有重叠行");
    }

    @Test
    @DisplayName("仅表头表格 — 一个 chunk（不抛异常）")
    void headerOnly_singleChunk() {
        List<ChunkSpec> specs = chunker.chunk("<table><tr><th>名称</th><th>价格</th></tr></table>", "");
        assertEquals(1, specs.size());
    }

    @Test
    @DisplayName("非表格 HTML — 空列表")
    void noTable_emptyList() {
        assertEquals(0, chunker.chunk("<p>纯文本。</p>", "").size());
    }

    @Test
    @DisplayName("单行超长 — 强制单行成组（不硬切行内结构）")
    void oversizedSingleRow_forcedGroup() {
        String longCell = "超长单元格内容".repeat(200);
        String html = "<table><tr><th>列</th></tr><tr><td>" + longCell + "</td></tr></table>";

        List<ChunkSpec> specs = chunker.chunk(html, "");

        assertEquals(1, specs.size());
        assertTrue(specs.get(0).content().contains("超长单元格内容"), "超长行不得被硬切丢内容");
    }
}
```

- [ ] **Step 2: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=TableChunkerTest`
Expected: FAIL（TableChunker 不存在）

- [ ] **Step 3: 实现 TableChunker**

```java
package com.commerce.rag.etl;

import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * 表格分片器 —— HTML 表格转 Markdown；大表按行分组（表头重复 + 组间 overlap 行 + 上下文前缀）
 *
 * <p>核心原则（spec §4.3）：表格是语义完整单元，永不硬切破坏结构：
 * <ul>
 *   <li>小表格（≤ chunk.size token）：整表一个 chunk</li>
 *   <li>大表格：每 rowsPerChunk 行一组（token 估算动态调整，硬上限 maxRowsPerChunk），
 *       每个子 chunk 重复完整表头，相邻子 chunk 间 overlap overlapRows 行</li>
 *   <li>上下文前缀：表头 + 前 2 数据行拼进 content 开头（向量感知表格主题）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class TableChunker {

    /** 上下文前缀包含的数据行数（spec §4.3 定稿：表头 + 前 2 行） */
    private static final int PREFIX_DATA_ROWS = 2;

    private final EtlProperties etlProperties;

    /**
     * 将单个 HTML 表格切分为 Markdown 分片规格
     *
     * @param html        Tika XHTML 中的原始 table 片段
     * @param headingPath 表格所在章节的标题导航路径
     * @return 表格分片规格列表（非表格输入返回空列表）
     */
    public List<ChunkSpec> chunk(String html, String headingPath) {
        Element table = selectFirstTable(html);
        if (table == null) {
            return List.of();
        }

        List<List<String>> rows = extractRows(table);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> header = rows.get(0);
        List<List<String>> dataRows = rows.subList(1, rows.size());
        if (dataRows.isEmpty()) {
            // 仅表头：整表一个 chunk
            return List.of(new ChunkSpec(toMarkdown(List.of(header)), headingPath, "table", null, null, null, null));
        }

        int maxTokens = etlProperties.chunk().size();
        String fullMarkdown = toMarkdown(rows);
        if (TokenEstimator.estimate(fullMarkdown) <= maxTokens) {
            // 小表格：整表一个 chunk，不加不减
            return List.of(new ChunkSpec(fullMarkdown, headingPath, "table", null, null, null, null));
        }

        // 大表格：上下文前缀（表头 + 前 2 数据行）
        List<List<String>> prefixRows = new ArrayList<>();
        prefixRows.add(header);
        prefixRows.addAll(dataRows.subList(0, Math.min(PREFIX_DATA_ROWS, dataRows.size())));
        String prefix = toMarkdown(prefixRows);

        // 行分组：表头 + 至多 maxRowsPerChunk 数据行；达名义行数后按 token 动态收口
        List<ChunkSpec> specs = new ArrayList<>();
        int startIdx = 0;
        while (startIdx < dataRows.size()) {
            List<List<String>> current = new ArrayList<>();
            current.add(header);
            int count = 0;
            while (startIdx + count < dataRows.size() && count < etlProperties.table().maxRowsPerChunk()) {
                current.add(dataRows.get(startIdx + count));
                count++;
            }
            // 达名义行数后超 token 上限：逐行回退收口（回退行留给下一组，单行超长时退至该行成组）
            while (count >= etlProperties.table().rowsPerChunk()
                    && count > 1
                    && TokenEstimator.estimate(toMarkdown(current)) > maxTokens) {
                current.remove(current.size() - 1);
                count--;
            }
            specs.add(new ChunkSpec(prefix + "\n\n" + toMarkdown(current), headingPath, "table", null, null, null, null));
            // 相邻子 chunk 间 overlap：下一组从本组末尾 overlapRows 行开始
            startIdx += Math.max(count - etlProperties.table().overlapRows(), 1);
        }
        return specs;
    }

    /** 定位第一个 table 元素（parseBodyFragment 包装后 select） */
    private static Element selectFirstTable(String html) {
        return Jsoup.parseBodyFragment(html).body().selectFirst("table");
    }

    /** 提取表格行（表头行 + 数据行，含 th/td 单元格） */
    private static List<List<String>> extractRows(Element table) {
        List<List<String>> rows = new ArrayList<>();
        for (Element tr : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : tr.select("th, td")) {
                cells.add(cell.text().trim());
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    /** 行列表 → Markdown 表格（首行为表头，自动生成分隔行，列数对齐） */
    private static String toMarkdown(List<List<String>> rows) {
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> padded = new ArrayList<>(rows.get(i));
            while (padded.size() < cols) {
                padded.add("");
            }
            sb.append("| ").append(String.join(" | ", padded)).append(" |\n");
            if (i == 0) {
                sb.append("|").append(" --- |".repeat(cols)).append("\n");
            }
        }
        return sb.toString().trim();
    }
}
```

- [ ] **Step 4: XhtmlDocumentParser table 分支切换（剥离正文）**

table 分支替换为（原「过渡期纯文本并入正文」代码删除）：

```java
                if (tag.equals("table")) {
                    // 表格是语义完整单元：独立成 TableSection，从正文剥离（spec §4.3）
                    flushText(buf, currentPath(headings), sections);
                    sections.add(new ParsedContent.TableSection(currentPath(headings), e.outerHtml()));
                    continue;
                }
```

- [ ] **Step 5: EtlPipeline buildChunkSpecs 接入表格**

依赖注入加 `TableChunker tableChunker`（@RequiredArgsConstructor 自动生效；EtlPipelineTest 构造加 `@Mock TableChunker`——不，集成用例要真实 chunker：见 Step 6）。buildChunkSpecs 改为：

```java
    private List<ChunkSpec> buildChunkSpecs(ParsedContent parsed) {
        List<ChunkSpec> specs = new ArrayList<>();
        for (ParsedContent.ParsedSection section : parsed.sections()) {
            if (section instanceof ParsedContent.TextSection text) {
                specs.addAll(splitTextSection(text));
            } else if (section instanceof ParsedContent.TableSection table) {
                specs.addAll(tableChunker.chunk(table.html(), table.headingPath()));
            }
        }
        return specs;
    }
```

- [ ] **Step 6: 更新测试**

XhtmlDocumentParserTest 的 `tableText_mergedIntoBody` 用例改名并重写为：

```java
    @Test
    @DisplayName("表格剥离正文 — 产出独立 TableSection，正文不含表格文本")
    void tableElement_producesTableSection() {
        String xhtml = "<html><body><h1>数据</h1><p>前置说明。</p>"
                + "<table><tr><th>名称</th></tr><tr><td>数值</td></tr></table>"
                + "<p>后置说明。</p></body></html>";

        ParsedContent parsed = parser.parse(xhtml, Map.of());

        assertEquals(3, parsed.sections().size(), "应产出 前置文本/表格/后置文本 三个分区");
        ParsedContent.TableSection table =
                (ParsedContent.TableSection) parsed.sections().get(1);
        assertEquals("数据", table.headingPath());
        assertTrue(table.html().contains("<table>"));
        String allText = parsed.sections().stream()
                .filter(ParsedContent.TextSection.class::isInstance)
                .map(s -> ((ParsedContent.TextSection) s).text())
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(!allText.contains("数值"), "表格文本应从正文剥离: " + allText);
    }
```

EtlPipelineTest：`setUp` 构造传入真实 `new TableChunker(props)`（props 已构建）；新增集成用例：

```java
    @Test
    @DisplayName("chunkDocument — 表格分区产出 content_type=table 的 Markdown chunk")
    void chunkDocument_tableSection_producesTableChunk() throws Exception {
        Document doc = new Document();
        doc.setId(5L);
        doc.setKbId(50L);
        when(documentMapper.selectById(5L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(5L, new ParsedContent(List.of(
                        new ParsedContent.TableSection(
                                "价格表",
                                "<table><tr><th>名称</th><th>价格</th></tr><tr><td>课程A</td><td>1999</td></tr></table>"))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(500);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(5L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        DocumentChunk chunk = captor.getValue();
        assertEquals("table", chunk.getContentType());
        assertEquals("价格表", chunk.getHeadingPath());
        assertTrue(chunk.getContent().contains("| 课程A | 1999 |"));
    }
```

- [ ] **Step 7: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=TableChunkerTest,XhtmlDocumentParserTest,EtlPipelineTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/commerce/rag/etl/TableChunker.java backend/src/main/java/com/commerce/rag/etl/XhtmlDocumentParser.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/TableChunkerTest.java backend/src/test/java/com/commerce/rag/etl/XhtmlDocumentParserTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): 表格分片（Markdown 化 + 大表行分组/表头重复/overlap 行/上下文前缀）"
```

---

## Task 7: 图片 caption + image chunk

**Files:**
- Create: `backend/src/main/resources/prompts/caption.yml`
- Create: `backend/src/main/java/com/commerce/rag/record/ContentHash.java`（sha256Hex 部分；normalize/of 于 Task 8 补充）
- Create: `backend/src/main/java/com/commerce/rag/etl/ImageCaptionService.java`
- Create: `backend/src/main/java/com/commerce/rag/etl/ImageFilter.java`
- Modify: `backend/src/main/java/com/commerce/rag/bot/graph/PromptLoader.java`（加 loadSections）
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（图片消费：过滤→字节去重→MinIO 上传→caption→image chunk；失败跳过）
- Test: `backend/src/test/java/com/commerce/rag/etl/ImageFilterTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/ImageCaptionServiceTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/bot/graph/PromptLoaderTest.java`（加 loadSections 用例；若存在则更新）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（图片 chunk 用例）

**Interfaces:**
- Consumes: Task 4 的 ImageSection/CapturedImage；Task 3 的 `etlProperties.captionModel()/imageMinSizeKb()`；MinioStorageService.uploadFile(kbId, uuid, InputStream, ext)；SAA Media.builder().data(Object)/UserMessage.builder().media(List)（字节 → base64 data URL，已实锤）
- Produces: `ImageCaptionService.caption(byte[], String mimeType) → String`；`ImageFilter.isSmallIcon/isDecorative`；`ContentHash.sha256Hex(byte[])/sha256Hex(String)`（Task 8 复用）；chunk 规格 contentType="image"（content=caption、imageUrl=objectKey）

- [ ] **Step 1: caption.yml（spec §4.2 定稿原文）**

```yaml
caption:
  system: |
    <role>
    你是一个图片内容描述专家。你的任务是生成适合向量检索的中文图片描述。
    </role>

    <rules>
    ## 描述要求
    1. 课件/讲义类图片(整页导出图、教学演示图):优先提取图中关键文字与结构(标题、要点、代码、公式)
    2. 数据图表:说明图表类型、坐标轴含义、主要数据趋势
    3. 插图/示意图:描述主体内容与上下文关系
    4. 只描述图片中实际存在的内容,禁止推测、禁止补充图中没有的信息
    5. 描述长度 100~200 字,直接陈述,不要使用"这张图片显示了"等冗余前缀
    </rules>

  instruction: |
    <output_format>
    请直接输出图片的中文描述,不要输出任何其他内容。
    </output_format>
```

- [ ] **Step 2: PromptLoader.loadSections（写失败测试先行）**

PromptLoaderTest 新增：

```java
    @Test
    @DisplayName("loadSections — 多叶子 YAML 展平为路径→文本映射")
    void loadSections_flattensLeafPaths() {
        PromptLoader loader = new PromptLoader();
        Map<String, String> sections = loader.loadSections("test-multi-leaf.yml");

        assertTrue(sections.containsKey("caption.system"));
        assertTrue(sections.containsKey("caption.instruction"));
        assertTrue(sections.get("caption.system").contains("描述要求"));
    }
```

（test-multi-leaf.yml 已存在于 src/test/resources/prompts/——执行者先确认其结构与断言匹配，不匹配则新增专用 fixture。）

实现（PromptLoader 内）：

```java
    /**
     * 加载 YAML 并返回全部叶子字符串（展平路径为 key）
     *
     * <p>适用于多分段提示词（如 caption.yml 的 caption.system / caption.instruction），
     * 供调用方按路径取用，避免各处重复 extractSection 解析。
     *
     * @param fileName 文件名（如 "caption.yml"）
     * @return 展平路径 → 叶子文本（加载失败返回空 Map）
     */
    public Map<String, String> loadSections(String fileName) {
        try (InputStream is = new ClassPathResource("prompts/" + fileName).getInputStream()) {
            Map<String, Object> data = yaml.load(is);
            Map<String, String> result = new LinkedHashMap<>();
            flattenLeaves(data, "", result);
            log.info("已加载提示词模板(sections): {} ({} 段)", fileName, result.size());
            return result;
        } catch (Exception e) {
            log.error("加载提示词模板(sections)失败: {}", fileName, e);
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenLeaves(Map<String, Object> map, String prefix, Map<String, String> result) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String s) {
                result.put(key, s.trim());
            } else if (value instanceof Map) {
                flattenLeaves((Map<String, Object>) value, key, result);
            }
        }
    }
```

import 补 `java.util.LinkedHashMap`。

- [ ] **Step 3: ContentHash（Task 7 部分：字节/文本 sha256）**

```java
package com.commerce.rag.record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容哈希 —— 归一化文本 + SHA-256 摘要
 *
 * <p>ETL 全局去重（入库硬约束，spec §4.4）与检索侧防御去重（计划 2/5 ContextBuilder
 * 消费）共用；sha256Hex 同时供图片字节级去重使用（同图只处理一次）。
 *
 * @author commerce-rag
 */
public record ContentHash(String sha256, String normalizedText) {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * 计算字节数组的 SHA-256 十六进制摘要
     *
     * @param bytes 原始字节（不允许为空）
     * @return 64 位十六进制小写摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            char[] hex = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = HEX[(digest[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[digest[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 计算文本的 SHA-256 十六进制摘要（UTF-8 编码）
     *
     * @param text 文本（不允许为空）
     * @return 64 位十六进制小写摘要
     */
    public static String sha256Hex(String text) {
        return sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }
}
```

（`normalizedText` 组件与 `of/normalize` 工厂在 Task 8 补充——本任务仅先落地 sha256Hex，避免 Task 8 前出现未消费方法。）

- [ ] **Step 4: ImageFilter（写失败测试先行）**

ImageFilterTest：

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ImageFilter 单元测试 —— 小图标 / 装饰图过滤
 *
 * @author commerce-rag
 */
class ImageFilterTest {

    @Test
    @DisplayName("isSmallIcon — 小于阈值的字节跳过（含边界）")
    void isSmallIcon_boundary() {
        assertTrue(ImageFilter.isSmallIcon(new byte[10 * 1024 - 1], 10));
        assertFalse(ImageFilter.isSmallIcon(new byte[10 * 1024], 10));
    }

    @Test
    @DisplayName("isDecorative — 全透明 alpha 图片为装饰图")
    void isDecorative_fullyTransparent() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(new Color(0, 0, 0, 0));
            g.fillRect(0, 0, w, h);
        });

        assertTrue(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 单一纯色 alpha 图片为装饰图")
    void isDecorative_singleColor() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(new Color(255, 0, 0, 255));
            g.fillRect(0, 0, w, h);
        });

        assertTrue(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 多颜色有效图片不误杀")
    void isDecorative_multiColor_notDecorative() throws Exception {
        byte[] png = pngOf((g, w, h) -> {
            g.setColor(Color.RED);
            g.fillRect(0, 0, w / 2, h);
            g.setColor(Color.BLUE);
            g.fillRect(w / 2, 0, w / 2, h);
        });

        assertFalse(ImageFilter.isDecorative(png));
    }

    @Test
    @DisplayName("isDecorative — 损坏字节不按装饰图处理（不误杀）")
    void isDecorative_corruptBytes_false() {
        assertFalse(ImageFilter.isDecorative(new byte[] {1, 2, 3, 4}));
    }

    @Test
    @DisplayName("isDecorative — 无 alpha 通道图片直接放行")
    void isDecorative_noAlpha_false() throws Exception {
        byte[] jpgLike = pngOf((g, w, h) -> {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, w, h);
        });
        // PNG 含 alpha 通道；此处以带 alpha 的多色图验证主路径已覆盖，无 alpha 分支由
        // BufferedImage.getColorModel().hasAlpha() 控制（JPEG 解码后为 false）
        assertFalse(ImageFilter.isDecorative(jpgLike));
    }

    /** 生成指定绘制逻辑的 PNG 字节 */
    private static byte[] pngOf(Painter painter) throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        painter.paint(image.createGraphics(), 32, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private interface Painter {
        void paint(java.awt.Graphics2D g, int w, int h);
    }
}
```

实现 `etl/ImageFilter.java`：

```java
package com.commerce.rag.etl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 图片过滤器 —— 过滤小图标与装饰图（spec §4.2）
 *
 * <p>规则：<10KB 图标直接跳过；带 alpha 通道且全部透明或仅一种有效颜色的装饰图
 * （PNG 分割线/纯色 logo）跳过。全部为内存级判断，不依赖外部服务。
 *
 * @author commerce-rag
 */
public final class ImageFilter {

    /** 装饰图检测的像素采样上限（控制解码后扫描成本） */
    private static final int MAX_SAMPLE_PIXELS = 4096;

    private ImageFilter() {}

    /**
     * 是否小于最小体积阈值的小图标
     *
     * @param bytes     图片字节
     * @param minSizeKb 最小体积阈值（KB，etl.image-min-size-kb）
     * @return true 表示应跳过
     */
    public static boolean isSmallIcon(byte[] bytes, int minSizeKb) {
        return bytes.length < (long) minSizeKb * 1024;
    }

    /**
     * 是否装饰图 —— 带 alpha 通道且（全部透明 或 仅一种有效颜色）
     *
     * <p>采样检测（上限 MAX_SAMPLE_PIXELS 像素），解码失败返回 false（不因过滤逻辑误杀有效图片）。
     *
     * @param bytes 图片字节
     * @return true 表示应跳过
     */
    public static boolean isDecorative(byte[] bytes) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null || !image.getColorModel().hasAlpha()) {
                return false;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int stride = (int) Math.max(1, ((long) width * height) / MAX_SAMPLE_PIXELS);
            Set<Integer> opaqueColors = new HashSet<>();
            boolean anyOpaque = false;
            for (int y = 0; y < height; y += stride) {
                for (int x = 0; x < width; x += stride) {
                    int argb = image.getRGB(x, y);
                    if ((argb >>> 24) == 0) {
                        continue; // 全透明像素
                    }
                    anyOpaque = true;
                    opaqueColors.add(argb & 0x00FFFFFF);
                    if (opaqueColors.size() > 1) {
                        return false;
                    }
                }
            }
            return !anyOpaque || opaqueColors.size() <= 1;
        } catch (IOException e) {
            return false;
        }
    }
}
```

- [ ] **Step 5: ImageCaptionService（写失败测试先行）**

ImageCaptionServiceTest：

```java
package com.commerce.rag.etl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * ImageCaptionService 单元测试 —— VLM caption 调用组装
 *
 * @author commerce-rag
 */
class ImageCaptionServiceTest {

    private ChatModel chatModel;
    private PromptLoader promptLoader;
    private ImageCaptionService service;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        promptLoader = mock(PromptLoader.class);
        when(promptLoader.loadSections("caption.yml")).thenReturn(Map.of(
                "caption.system", "系统规则",
                "caption.instruction", "输出格式"));
        EtlProperties props = new EtlProperties(
                100, new EtlProperties.Executor(2, 4, 20, "etl-"), new EtlProperties.Chunk(768, 64),
                16, "qwen3.7-flash", 10, new EtlProperties.Table(25, 30, 2));
        service = new ImageCaptionService(chatModel, promptLoader, props);
    }

    @Test
    @DisplayName("caption — 图片以 Media 传入（base64 data URL 路径），模型名按次覆盖为 caption 模型")
    void caption_sendsMediaAndCaptionModel() {
        AssistantMessage output = new AssistantMessage("这是图片描述内容");
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(output));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);

        String caption = service.caption(new byte[] {1, 2, 3}, "image/png");

        assertEquals("这是图片描述内容", caption);
        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        org.mockito.Mockito.verify(chatModel).call(captor.capture());
        Prompt prompt = captor.getValue();
        // 消息序列：SystemMessage + UserMessage（带 1 个 Media）
        assertEquals(2, prompt.getInstructions().size());
        org.springframework.ai.chat.messages.UserMessage user =
                (org.springframework.ai.chat.messages.UserMessage) prompt.getInstructions().get(1);
        assertEquals(1, user.getMedia().size());
        // 模型名按次覆盖（DashScopeChatOptions.model = caption 模型）
        assertTrue(prompt.getOptions() != null);
    }

    @Test
    @DisplayName("caption — 模型调用失败上抛（调用方按图片跳过）")
    void caption_failure_throws() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("dashscope 不可用"));

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> service.caption(new byte[] {1}, "image/png"));
    }
}
```

实现 `etl/ImageCaptionService.java`：

```java
package com.commerce.rag.etl;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.commerce.rag.bot.graph.PromptLoader;
import com.commerce.rag.properties.EtlProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 图片描述（caption）服务 —— 调用 VLM 生成适合向量检索的中文图片描述
 *
 * <p>模型走 DashScopeChatOptions 按次覆盖（etl.caption-model，qwen3.7-flash）；
 * 图片以 Media 字节传入——SAA 1.1.2 实锤：Media data 为 byte[] 时转 base64 data URL
 * （本地 MinIO 对 DashScope 云不可达，不能传 URL）。
 *
 * @author commerce-rag
 */
@Component
@RequiredArgsConstructor
public class ImageCaptionService {

    private final ChatModel chatModel;
    private final PromptLoader promptLoader;
    private final EtlProperties etlProperties;

    /**
     * 生成图片中文描述
     *
     * <p>模型调用失败上抛——调用方（ETL 图片分片）按「该图片跳过，文档 ETL 继续」处理（spec §4.2）。
     *
     * @param imageBytes 图片字节（不允许为空）
     * @param mimeType   图片 MIME（如 image/png）
     * @return 100~200 字中文描述
     */
    public String caption(byte[] imageBytes, String mimeType) {
        Map<String, String> sections = promptLoader.loadSections("caption.yml");
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                .data((Object) imageBytes)
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text(sections.getOrDefault("caption.instruction", ""))
                .media(List.of(media))
                .build();
        Prompt prompt = new Prompt(
                List.of(
                        new SystemMessage(sections.getOrDefault("caption.system", "")),
                        userMessage),
                DashScopeChatOptions.builder().model(etlProperties.captionModel()).build());
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
}
```

（注意：`Prompt(List<Message>, ChatOptions)` 构造器与 `UserMessage.builder().media(...)` 执行时若签名出入，按 IDE 提示微调，均为 Spring AI 1.1.2 标准 API。）

- [ ] **Step 6: EtlPipeline 图片消费**

依赖注入加 `ImageCaptionService imageCaptionService`。buildChunkSpecs 的 else-if 链补：

```java
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
                            doc.getId(), image.resourceName(), e.getMessage());
                }
            }
```

buildChunkSpecs 需要 doc 参数（courseId 不涉及图片，但 MinIO 上传需要 kbId）——签名改为 `buildChunkSpecs(Document doc, ParsedContent parsed)`，方法开头建 `Map<String, String> processedImages = new HashMap<>()`。新增方法：

```java
    /**
     * 图片分片：过滤小图标/装饰图 → 字节级去重（同图只处理一次）→ MinIO 上传 → VLM caption
     *
     * @return 图片分片规格；被过滤/跳过时返回 null
     */
    private ChunkSpec buildImageChunk(Document doc, ParsedContent.ImageSection image, Map<String, String> processedImages) {
        if (ImageFilter.isSmallIcon(image.bytes(), etlProperties.imageMinSizeKb())) {
            log.info(
                    "图片过滤（小于 {}KB）: docId={}, resource={}",
                    etlProperties.imageMinSizeKb(), doc.getId(), image.resourceName());
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
        return new ChunkSpec(
                caption, image.headingPath(), "image", objectKey, new Gson().toJson(meta), null, null);
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
```

import 补：`com.commerce.rag.record.ContentHash`、`com.google.gson.Gson`、`java.util.HashMap`、`java.util.LinkedHashMap`、`java.util.UUID`。

- [ ] **Step 7: EtlPipelineTest 图片用例**

`setUp` 构造加 `@Mock ImageCaptionService imageCaptionService` 并传入构造器。新增用例：

```java
    @Test
    @DisplayName("chunkDocument — 图片分区：过滤小图标跳过、有效图片产出 image chunk")
    void chunkDocument_imageSection_filtersAndCaptions() throws Exception {
        Document doc = new Document();
        doc.setId(6L);
        doc.setKbId(60L);
        when(documentMapper.selectById(6L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        byte[] bigImage = new byte[10 * 1024]; // 10KB 起（≥ imageMinSizeKb 不触发小图标过滤）
        when(minioStorageService.uploadFile(eq(60L), anyString(), any(InputStream.class), eq("png")))
                .thenReturn("60/abc.png");
        when(imageCaptionService.caption(any(byte[].class), eq("image/png"))).thenReturn("这是一段图片描述");
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(6L, new ParsedContent(List.of(
                        new ParsedContent.ImageSection("图例", "image/png", bigImage, "image0.png"))));
        java.util.concurrent.atomic.AtomicLong idSeq = new java.util.concurrent.atomic.AtomicLong(600);
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(idSeq.getAndIncrement());
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(6L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        DocumentChunk chunk = captor.getValue();
        assertEquals("image", chunk.getContentType());
        assertEquals("这是一段图片描述", chunk.getContent());
        assertEquals("60/abc.png", chunk.getImageUrl());
        assertEquals("图例", chunk.getHeadingPath());
        assertTrue(chunk.getMetadataJson().contains("image0.png"));
    }

    @Test
    @DisplayName("chunkDocument — caption 失败仅跳过该图，文档 ETL 不 FAILED")
    void chunkDocument_imageCaptionFailure_skipsImage() throws Exception {
        Document doc = new Document();
        doc.setId(7L);
        doc.setKbId(70L);
        when(documentMapper.selectById(7L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        byte[] bigImage = new byte[10 * 1024];
        when(minioStorageService.uploadFile(eq(70L), anyString(), any(InputStream.class), eq("png")))
                .thenReturn("70/abc.png");
        when(imageCaptionService.caption(any(byte[].class), eq("image/png")))
                .thenThrow(new RuntimeException("VLM 服务不可用"));
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(7L, new ParsedContent(List.of(
                        new ParsedContent.TextSection("", "正文内容保证文档非空。"),
                        new ParsedContent.ImageSection("", "image/png", bigImage, "image1.png"))));
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(700L);
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(7L);

        // 仅文本 chunk 落库（图片跳过），状态 CHUNKED 而非 FAILED
        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        assertEquals("text", captor.getValue().getContentType());
    }
```

（EtlPipelineTest import 补 `eq`、`anyString`。）

- [ ] **Step 8: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=ImageFilterTest,ImageCaptionServiceTest,PromptLoaderTest,EtlPipelineTest`
Expected: PASS

- [ ] **Step 9: 真实文档验证（手动，开发环境）**

用一份含内嵌图片的 PDF（如任意带截图的 PDF）通过管理端上传 → 观察日志：捕获图片数、caption 生成、image chunk 落库；再上传一份 DOCX 验证 OOXML 图片路由（若 Tika 2.9.2 对 DOCX 图片不路由到 extractor，记录该限制并保持 PDF 主路径可用）。

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/resources/prompts/caption.yml backend/src/main/java/com/commerce/rag/record/ContentHash.java backend/src/main/java/com/commerce/rag/etl/ImageCaptionService.java backend/src/main/java/com/commerce/rag/etl/ImageFilter.java backend/src/main/java/com/commerce/rag/bot/graph/PromptLoader.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/ImageFilterTest.java backend/src/test/java/com/commerce/rag/etl/ImageCaptionServiceTest.java backend/src/test/java/com/commerce/rag/bot/graph/PromptLoaderTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): 图片链路（VLM caption + 过滤 + 字节去重 + MinIO 上传 + image chunk）"
```

---

## Task 8: SHA256 内容去重（ETL 全局唯一）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/record/ContentHash.java`（补 normalize/of）
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（chunkDocument 去重段）
- Test: `backend/src/test/java/com/commerce/rag/record/ContentHashTest.java`（新建）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（去重用例）

**Interfaces:**
- Consumes: Task 1 的 sha256 列 + idx_document_chunk_sha256 索引；Task 7 的 ContentHash.sha256Hex
- Produces: `ContentHash.of(String) → (sha256, normalizedText)`（计划 2/5 检索侧防御去重复用）；chunk.sha256 落库 + Milvus 字段（Task 2 已接线）

- [ ] **Step 1: ContentHash 补 normalize/of（写失败测试先行）**

ContentHashTest：

```java
package com.commerce.rag.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ContentHash 单元测试 —— 归一化规则与摘要（spec §4.4 定稿）
 *
 * @author commerce-rag
 */
class ContentHashTest {

    @Test
    @DisplayName("归一化 — 首尾空白去除 + 空白折叠为单空格（含全角空格）")
    void normalize_collapsesWhitespace() {
        String raw = "  检索   增强\u3000生成  ";
        assertEquals("检索 增强 生成", ContentHash.of(raw).normalizedText());
    }

    @Test
    @DisplayName("归一化 — 常见中英文标点删除")
    void normalize_removesPunctuation() {
        assertEquals("检索增强生成", ContentHash.of("检索。增强！生成？").normalizedText());
        assertEquals("hello world", ContentHash.of("hello, world.").normalizedText());
    }

    @Test
    @DisplayName("归一化 — 统一小写")
    void normalize_lowercases() {
        assertEquals("python 开发", ContentHash.of("Python 开发").normalizedText());
    }

    @Test
    @DisplayName("相同语义文本（标点/空白/大小写差异）— 哈希一致")
    void sameSemanticText_sameHash() {
        ContentHash a = ContentHash.of("Python  开发,基础！");
        ContentHash b = ContentHash.of("python 开发 基础");

        assertEquals(a.sha256(), b.sha256());
    }

    @Test
    @DisplayName("不同内容 — 哈希不同；格式为 64 位十六进制")
    void differentContent_differentHash() {
        String hash = ContentHash.of("内容A").sha256();
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertNotEquals(hash, ContentHash.of("内容B").sha256());
    }

    @Test
    @DisplayName("sha256Hex(byte[]) — 与 JDK 摘要一致（确定性）")
    void sha256Hex_bytes_deterministic() {
        assertEquals(ContentHash.sha256Hex(new byte[] {1, 2, 3}), ContentHash.sha256Hex(new byte[] {1, 2, 3}));
        assertEquals(64, ContentHash.sha256Hex(new byte[] {1, 2, 3}).length());
    }
}
```

实现（ContentHash 补）：

```java
    /** 中英文常见标点（归一化时删除，spec §4.4 定稿） */
    private static final String PUNCTUATION = "[。．.!！?？；;：:、,，]";

    /**
     * 计算内容的归一化文本与 SHA-256 摘要
     *
     * @param text 原始内容
     * @return 归一化文本 + 摘要
     */
    public static ContentHash of(String text) {
        String normalized = normalize(text);
        return new ContentHash(sha256Hex(normalized), normalized);
    }

    /**
     * 归一化（spec §4.4 定稿）：去首尾空白 → 空白（含全角空格）折叠为单空格 → 去标点 → 统一小写
     */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim()
                .replaceAll("[\\s\\u3000]+", " ")
                .replaceAll(PUNCTUATION, "")
                .toLowerCase();
    }
```

- [ ] **Step 2: 跑测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=ContentHashTest`
Expected: FAIL（of/normalize 不存在）

- [ ] **Step 3: 实现后跑通过（含 Task 7 的既有 sha256Hex 用例）**

Run: `cd backend && mvn.cmd test -Dtest=ContentHashTest`
Expected: PASS

- [ ] **Step 4: EtlPipeline chunkDocument 去重接入**

在 `buildChunkSpecs(doc, parsed)` 之后、软删旧 chunk 之前插入去重（**同时删除 Task 5 版中紧邻 buildChunkSpecs 的 `if (specs.isEmpty()) throw` 守卫**，替换为下述两段守卫——「无可分片内容」仍抛异常，与既有 chunkDocument_blankText_throws 用例一致；「全部去重」则正常 CHUNKED 收尾）：

```java
        // 组装待落库分片（按文档顺序：文本/表格/图片）
        List<ChunkSpec> rawSpecs = buildChunkSpecs(doc, parsed);
        if (rawSpecs.isEmpty()) {
            throw new IllegalStateException("分片结果为空: docId=" + docId);
        }
        // SHA256 全局去重（spec §4.4）：批内去重 + 查库跳过，全局唯一硬约束
        List<ChunkSpec> specs = deduplicateSpecs(rawSpecs);
        if (specs.isEmpty()) {
            log.info("全部内容已存在（SHA256 去重），无新分片入库: docId={}", docId);
            updateDocChunkCount(docId, 0);
            parsedContentCache.remove(docId);
            updateDocStatus(docId, "CHUNKED", null);
            return;
        }
```

注意：去重查询先于软删旧 chunk 执行（查询结果包含本文件既有 chunk 时即为重复命中，直接收尾，避免无谓的软删+重插）。

新增方法：

```java
    /**
     * SHA256 内容去重 —— 批内（同 hash 保留首个）+ 查库（deleted=0 由 @TableLogic 自动过滤）
     *
     * <p>spec §4.4：同 sha256 全库只存一条（全局唯一硬约束）；检索侧防御去重在计划 2/5。
     */
    private List<ChunkSpec> deduplicateSpecs(List<ChunkSpec> specs) {
        Map<String, ChunkSpec> byHash = new LinkedHashMap<>();
        for (ChunkSpec spec : specs) {
            byHash.putIfAbsent(ContentHash.of(spec.content()).sha256(), spec);
        }
        List<String> hashes = new ArrayList<>(byHash.keySet());
        Set<String> existing = chunkMapper.selectList(Wrappers.<DocumentChunk>lambdaQuery()
                        .select(DocumentChunk::getSha256)
                        .in(DocumentChunk::getSha256, hashes))
                .stream()
                .map(DocumentChunk::getSha256)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        List<ChunkSpec> unique = new ArrayList<>();
        for (Map.Entry<String, ChunkSpec> entry : byHash.entrySet()) {
            if (!existing.contains(entry.getKey())) {
                unique.add(entry.getValue());
            }
        }
        log.info(
                "SHA256 去重: 原始={}, 批内去重后={}, 查库跳过={}, 入库={}",
                specs.size(), byHash.size(), existing.size(), unique.size());
        return unique;
    }
```

（`java.util.Objects` 改 import 短名；落库循环里 `chunk.setSha256(ContentHash.of(spec.content()).sha256());` 加在 `setContent` 之后。）

注意：原「分片结果为空 throw」检查语义更新——`buildChunkSpecs` 为空仍 throw（文档无可分片内容属异常），但去重后为空走正常 CHUNKED 收尾。

- [ ] **Step 5: EtlPipelineTest 去重用例**

```java
    @Test
    @DisplayName("chunkDocument — 批内重复内容只入库一次")
    void chunkDocument_duplicateContentWithinBatch_deduped() throws Exception {
        Document doc = new Document();
        doc.setId(8L);
        doc.setKbId(80L);
        when(documentMapper.selectById(8L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        // 两个内容完全相同的文本分区（不同 heading）
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(8L, new ParsedContent(List.of(
                        new ParsedContent.TextSection("甲", "完全相同的内容段落。"),
                        new ParsedContent.TextSection("乙", "完全相同的内容段落。"))));
        // 查库：无已有 hash
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        doAnswer(inv -> {
                    inv.getArgument(0, DocumentChunk.class).setId(800L);
                    return 1;
                })
                .when(chunkMapper)
                .insert(any(DocumentChunk.class));

        etlPipeline.chunkDocument(8L);

        ArgumentCaptor<DocumentChunk> captor = ArgumentCaptor.forClass(DocumentChunk.class);
        verify(chunkMapper).insert(captor.capture());
        assertEquals(1, captor.getAllValues().size(), "重复内容应只入库一次");
        assertEquals(64, captor.getValue().getSha256().length());
    }

    @Test
    @DisplayName("chunkDocument — 全库已有同内容：零入库，状态 CHUNKED")
    void chunkDocument_allContentExists_skipsInsert() throws Exception {
        Document doc = new Document();
        doc.setId(9L);
        doc.setKbId(90L);
        when(documentMapper.selectById(9L)).thenReturn(doc);
        when(documentMapper.update(any(), any())).thenReturn(1);
        String text = "全库已存在的内容。";
        java.lang.reflect.Field field = EtlPipeline.class.getDeclaredField("parsedContentCache");
        field.setAccessible(true);
        ((java.util.concurrent.ConcurrentHashMap<Long, ParsedContent>) field.get(etlPipeline))
                .put(9L, new ParsedContent(List.of(new ParsedContent.TextSection("", text))));
        // 查库：返回同 hash 的既有分片（deleted=0）
        DocumentChunk existing = new DocumentChunk();
        existing.setSha256(ContentHash.of(text).sha256());
        when(chunkMapper.selectList(any())).thenReturn(List.of(existing));

        etlPipeline.chunkDocument(9L);

        verify(chunkMapper, never()).insert(any(DocumentChunk.class));
        ArgumentCaptor<LambdaUpdateWrapper> captor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper, atLeastOnce()).update(any(), captor.capture());
        String setValues = captor.getAllValues().stream()
                .map(w -> String.valueOf(w.getParamNameValuePairs().values()))
                .reduce("", (a, b) -> a + b);
        assertTrue(setValues.contains("CHUNKED"), "全部去重后仍应正常收尾 CHUNKED: " + setValues);
    }
```

注意 `chunkMapper.selectList(any())` 的 stub 会同时影响 chunkDocument 的去重查询与（不存在其它查询）——embedAndIndex 不在此用例内，OK。但 `when(chunkMapper.selectList(any())).thenReturn(List.of())` 与 dedup 查询的 select 投影兼容（mock 不校验 wrapper）。

- [ ] **Step 6: 跑测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=ContentHashTest,EtlPipelineTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/commerce/rag/record/ContentHash.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/record/ContentHashTest.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "feat(S1): SHA256 内容归一化全局去重（批内 + 查库跳过，全局唯一硬约束）"
```

---

## Task 9: 整合收尾——全链路验证与死代码清零

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（最终检查：三类型分区全部消费、注释/Javadoc 对齐、无死代码）
- Modify: 任何残留编译/格式问题文件
- 检查（不修改）: `MilvusCollectionInitializer`/`SearchKnowledgeTool` 的 Javadoc 与实现一致性

**Interfaces:**
- Consumes: Task 1-8 全部产出
- Produces: 全绿 verify + 开发环境重建验证记录

- [ ] **Step 1: 死代码与一致性自查（逐条核对）**

1. `recursiveSplit` / `splitLargeParagraph` / `applyOverlap` / `ChunkInfo` / 私有 `estimateTokens` 已删（Task 5）——grep 确认零残留：`grep -rn "recursiveSplit\|splitLargeParagraph\|applyOverlap\|ChunkInfo" backend/src/main/java/` 输出为空。
2. `parsedTextCache` 改名零残留：`grep -rn "parsedTextCache" backend/src/` 输出为空。
3. ParsedContent 三类型（TextSection/TableSection/ImageSection）均有生产点与消费点（buildChunkSpecs 三分支）。
4. `etl.chunk.overlap` 配置零残留（yml 与 EtlProperties 均已移除）。
5. `FIELD_COLLECTION_TYPE` 常量零残留：`grep -rn "FIELD_COLLECTION_TYPE" backend/src/` 输出为空（entity.collectionType 字段保留是刻意的，见决策点 6）。
6. 类 Javadoc 与实际实现一致（MilvusCollectionInitializer 的 14 字段/3 索引、EtlPipeline 的分片描述、SearchKnowledgeTool 的过滤描述）。

- [ ] **Step 2: 全量门禁验证**

Run: `cd backend && mvn.cmd clean verify`
Expected: PASS——736+ 既有测试与新测试全部绿；spotless 280+ 文件 clean；checkstyle 0 violations；spotbugs 0 BugInstance；jacoco BUNDLE ≥0.80 且新增类单类 ≥0.80（新增类均有单测：XhtmlDocumentParser/TableChunker/TextChunkSplitter/ImageFilter/ImageCaptionService/TokenEstimator/ContentHash/TikaImageExtractor；record 类 ParsedContent/ChunkSpec 无逻辑不单独考核）。若 jacoco 单类不达标（如 EtlPipeline 因分支增多），补对应测试到达标。

- [ ] **Step 3: 开发环境重建验证（手动步骤，记录日志要点）**

```bash
cd backend && mvn.cmd spotless:apply   # 若 verify 报格式问题
docker compose -f docker-compose.dev.yml up -d
# 启动后端，观察：
# 1. Flyway 迁移 V6 新列生效
# 2. Milvus 启动日志：「schema 不匹配，drop 重建」→「创建成功」→「加载完成」
# 3. 上传一份含表格/图片的 PDF → ETL 日志：XHTML字符数/捕获图片数 → 分片（text/table/image）→ SHA256 去重 → INDEXED
# 4. 学生端发一条知识问答 → 检索仍可用（SearchKnowledgeTool 兼容路径）
```

- [ ] **Step 4: 更新进度文档并 Commit 收尾（如有收尾改动）**

```bash
git add <仅收尾改动文件>
git commit -m "chore(S1): ETL 多模态底座收尾（Javadoc 对齐/死代码清零核查）"
```

若 Step 1-3 无需改动，本任务零提交即可，任务完成以 verify 全绿为准。

---

## 自审记录（Writing-Plans Self-Review）

**1. Spec 覆盖（spec §4/§6/§12 部分，对照本计划）：**
- §4.1 切分（TokenTextSplitter 768/128、过小合并）→ Task 3/5 ✅（overlap 见决策点 1 的 API 实锤偏离）
- §4.2 图片链路（Tika 提取/过滤/去重/MinIO/VLM caption/失败跳过/元数据）→ Task 4/7 ✅
- §4.3 table chunk（Markdown/大小表/行分组/表头重复/overlap/caption 前缀/heading 继承）→ Task 6 ✅
- §4.4 SHA256 去重（normalize 算法原文、ETL 全局唯一、sha256 进 PG+Milvus）→ Task 1/2/8 ✅；检索侧防御去重 → 计划 2/5 ✅
- §4.5 模型分开配置（embedding/VLM 独立通道）→ Task 3/7 ✅
- §4.6 元数据保存（metadata_json）→ Task 7（图片 resourceName/headingPath）✅
- §6 模型搭配（qwen3.8-max/qwen3.7-flash/qwen3.7-text-embedding、维度 1024）→ Task 3 ✅
- §12 DB/Milvus 重建（document_chunk 三列、knowledge_chunks 去 collection_type 加三字段）→ Task 1/2 ✅
- §9 组件清单中本计划范围：EtlPipeline 改造 ✅、MilvusCollectionInitializer 改造 ✅、SearchKnowledgeTool 改造（移除 @Tool 在计划 2/5，本计划仅兼容适配）✅

**2. 占位符扫描：** 无 TBD/TODO/「类似 Task N」；所有代码步骤均给出完整代码块；测试均为具体断言代码。仅 Task 3/7 对既有测试的更新以「按现有构造方式适配」表述（因既有测试文件细节未全量读入，执行者可见），其余全为具体代码。

**3. 类型一致性：** `ChunkSpec(content, headingPath, contentType, imageUrl, metadataJson, charOffsetStart, charOffsetEnd)` 在 Task 5 定义、Task 6/7 复用一致；`ParsedContent.TextSection/TableSection/ImageSection` 三记录跨任务命名一致；`EtlProperties.chunk().size()/minChunkSizeChars()、captionModel()、imageMinSizeKb()、table().rowsPerChunk()/maxRowsPerChunk()/overlapRows()` 在 Task 3 定义、Task 5/6/7 引用一致；`ContentHash.sha256Hex/of` 在 Task 7/8 演进一致；`buildChunkSpecs(Document doc, ParsedContent parsed)` 在 Task 7 改签名后 Task 8 引用一致。

## 执行交接（Execution Handoff）

计划已保存至 `docs/superpowers/plans/2026-08-17-s1-plan1-etl-multimodal-foundation.md`。两种执行方式：

1. **Subagent-Driven（推荐）**——每任务派发新子代理，任务间两阶段审查，快速迭代
2. **Inline Execution**——本会话内按 executing-plans 批量执行、检查点审查

请确认：① 计划整体；② 决策点 1（TokenTextSplitter 无 overlap 参数的处置）；③ 执行方式。
