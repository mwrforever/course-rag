# P2/P3 契约与 ETL 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第三波 P2/P3——ETL 幂等（4 子项）、跨端契约对齐前端文档（4 处）、403 双轨契约统一、P3 死代码清理、A11 RT 旋转原子化（用户已批准）、V6 环境问题三项，全量测试通过 + 真实环境验证。

**Architecture:** 10 个任务按文件域独立。ETL 侧：状态守卫用条件更新原子抢占（CAS 式）+ 部分失败标 FAILED + try-with-resources；契约侧：分片端点/文档列表/dashboard 统计按 frontend-design.md 逐条对齐；错误契约：GlobalExceptionHandler 加 @ResponseStatus/ResponseEntity；安全侧：RT 旋转合并为单条 Lua；环境侧：V6 迁移类型与实体对齐后 drop 重建。

**Tech Stack:** Spring Boot 3.5.8 / MyBatis-Plus 3.5.12 / Flyway / JUnit5 + Mockito / Redis Lua / PG 42.7.8。

## Global Constraints

- 注释/日志/文档全中文；UTF-8 无 BOM；LF 行尾；文件末尾保留换行
- 测试与实现**同一次提交**；因本次改动失效的旧测试**同提交改写或删除**，禁止留过渡
- git add 只加任务明确列出的文件，**禁 `git add -A`**（工作区有历史遗留无关改动）；Windows 用 `mvn.cmd`
- 分层约束：controller → service → mapper；统计查询简单计数走 MP `Wrappers.lambdaQuery()` 链式，分组/聚合统计走 **mapper 接口方法 + XML 映射**（`src/main/resources/mapper/UserFeedbackMapper.xml`，本波新建基建）；**禁止 service 拼接 SQL 字符串**（宪法强制，用户 2026-08-15 定调）
- 死代码零容忍：本次改动产生的废弃方法/测试必须同提交清理
- 代码格式 palantirJavaFormat（spotless:check 绑定 verify，多参 log 每参一行）
- 每个任务完成后跑 `cd backend && mvn.cmd test -Dtest=<XxxTest>`，全部任务完成后跑全量 `mvn.cmd test`（基线 231/231）
- 契约权威源：`docs/plans/2026-07-16-frontend-design.md`（用户已裁决改后端迁就前端文档）

---

### Task 1: EtlPipeline 状态守卫 + 部分失败标 FAILED + 流关闭

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（process :98-109、parseDocument :120-147、embedAndIndex :238-282）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`

**Interfaces:**
- Consumes: `DocumentMapper.update(Wrapper)`（返回 int 行数）、`MinioStorageService.downloadFile(String)`、现有 `updateDocStatus` 私有方法
- Produces: `process(Long)` 语义变更——仅 PENDING/FAILED 状态可执行（原子抢占）；`embedAndIndex` 部分失败标 FAILED；`parseDocument` 流自动关闭

- [ ] **Step 1: 新增测试**（EtlPipelineTest 追加；现有 process 测试需适配——见 Step 4）

```java
@Test
@DisplayName("process 状态守卫 — 抢占失败（非 PENDING/FAILED）直接跳过，不执行解析")
void process_claimFailed_skipsExecution() {
    // Given: document 存在，抢占 update 返回 0（状态为 INDEXED/执行中）
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(10L);
    doc.setSourcePath("10/1.pdf");
    when(documentMapper.selectById(1L)).thenReturn(doc);
    when(documentMapper.update(any(), any())).thenReturn(0);

    etlPipeline.process(1L);

    // Then: 不下载文件（解析未执行）
    verify(minioStorageService, never()).downloadFile(anyString());
}

@Test
@DisplayName("process 状态守卫 — FAILED 状态可重试（抢占成功继续执行）")
void process_claimSuccessFromFailed_continues() throws Exception {
    // Given: 抢占 update 返回 1（PENDING/FAILED → PARSING 成功）
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(10L);
    doc.setSourcePath("10/1.pdf");
    when(documentMapper.selectById(1L)).thenReturn(doc);
    when(documentMapper.update(any(), any())).thenReturn(1);
    lenient().when(minioStorageService.downloadFile("10/1.pdf"))
            .thenReturn(new ByteArrayInputStream("测试内容。\n\n第二段。".getBytes()));
    lenient().when(chunkMapper.insert(any(DocumentChunk.class))).thenReturn(1);
    lenient().when(chunkMapper.selectList(any())).thenReturn(List.of());

    etlPipeline.process(1L);

    // Then: 管道继续执行（下载被调用）
    verify(minioStorageService).downloadFile("10/1.pdf");
}

@Test
@DisplayName("embedAndIndex 部分失败 — 标 FAILED 而非 INDEXED")
void embedAndIndex_partialFailure_setsFailed() throws Exception {
    // Given: 1 个 chunk，embedding 抛异常（部分失败）
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(10L);
    doc.setSourcePath("10/1.pdf");
    when(documentMapper.selectById(1L)).thenReturn(doc);
    DocumentChunk chunk = new DocumentChunk();
    chunk.setId(1L);
    chunk.setDocId(1L);
    chunk.setKbId(10L);
    chunk.setContent("内容");
    when(chunkMapper.selectList(any())).thenReturn(List.of(chunk));
    when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding 服务不可用"));

    etlPipeline.embedAndIndex(1L);

    // Then: 状态 FAILED（非 INDEXED）——捕获 update 调用断言 set 值
    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> wrapperCaptor =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
    verify(documentMapper, atLeastOnce()).update(any(), wrapperCaptor.capture());
    String sql = wrapperCaptor.getAllValues().stream()
            .map(Object::toString)
            .reduce("", (a, b) -> a + b);
    assertTrue(sql.contains("FAILED"), "部分失败应标 FAILED: " + sql);
}

@Test
@DisplayName("parseDocument 解析异常 — 输入流仍被关闭（try-with-resources）")
void parseDocument_parseFailure_streamClosed() throws Exception {
    // Given: 下载成功但 Tika 解析抛异常（损坏文件）
    Document doc = new Document();
    doc.setId(1L);
    doc.setKbId(10L);
    doc.setSourcePath("10/bad.pdf");
    when(documentMapper.selectById(1L)).thenReturn(doc);
    java.io.InputStream mockStream = mock(java.io.InputStream.class);
    when(minioStorageService.downloadFile("10/bad.pdf")).thenReturn(mockStream);

    // When: 解析抛异常（AutoDetectParser 对空流/未知类型可能抛或返回空——用直接抛异常场景）
    assertThrows(Exception.class, () -> etlPipeline.parseDocument(1L));
    // Then: 流已关闭（try-with-resources 保证）
    verify(mockStream).close();
}
```

新增 import：`org.mockito.ArgumentCaptor`（已有）、`java.io.InputStream` 按需。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest`
Expected: FAIL——抢占逻辑不存在（`downloadFile` 仍被调用）、FAILED 断言失败、流未关闭

- [ ] **Step 3: 实现**

`EtlPipeline.java`：

1. process（:98-109）入口加原子抢占（selectById 检查后）：

```java
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
        int claimed = documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
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
    }
}
```

新增 import：`com.baomidou.mybatisplus.core.toolkit.Wrappers`（宪法强制：禁止方法内全限定类名 new wrapper、禁止 new wrapper 对象——统一走 Wrappers 静态工厂链式）。

- [ ] **Step 3.5: EtlPipeline 既有全限定 new wrapper 合规化**（宪法强制，本波触碰文件内一并清理）

EtlPipeline 现有 5 处全限定名 `new com.baomidou.mybatisplus.core.conditions.query/update.Lambda(Query|Update)Wrapper`（:249 查询、:609/:624/:635/:647 更新），全部改为 import + `Wrappers.lambdaQuery()/lambdaUpdate()` 链式：

```java
// 例 :609 updateDocStatus —— 原 `new com.baomidou...LambdaUpdateWrapper<Document>()` 改：
documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
        .eq(Document::getId, docId)
        .set(Document::getParseStatus, status)
        .set(Document::getErrorMsg, errorMessage)
        .set(Document::getUpdatedAt, LocalDateTime.now()));
```

- 逐处保持原条件/原 set 语义不变，仅换构建方式（import `Wrappers`，删除全限定名）
- 改动后 `grep -rn "new com\.baomidou" src/main/java/com/commerce/rag/etl/` 应零输出

**Java 全局合规（宪法强制，同文件一并清理）**：
1. `parsedTextCache` 字段（:70）全路径类名 `java.util.concurrent.ConcurrentHashMap` → import 短类名（`import java.util.concurrent.ConcurrentHashMap;`，字段改 `ConcurrentHashMap<Long, String>`）
2. 手写样板构造器（:73-86）→ Lombok `@RequiredArgsConstructor`（类级注解；6 个 `private final` 字段已具备，注解生成同签名全参构造器——测试 `new EtlPipeline(...)` 无需改动）；若文件其他构造器（如测试用）签名依赖保持不变
3. 类头注释补充：依赖注入方式说明
4. 改动后 `grep -rn "java\.util\.\|java\.time\.\|java\.sql\." src/main/java/com/commerce/rag/etl/` 应零输出（全路径类名零残留）

2. parseDocument（:130-138）改 try-with-resources：

```java
// P2-1: try-with-resources——Tika 解析异常/损坏文件时流必关（防 MinIO 句柄泄漏）
try (InputStream inputStream = minioStorageService.downloadFile(doc.getSourcePath())) {
    BodyContentHandler handler = new BodyContentHandler(TIKA_WRITE_LIMIT);
    Metadata metadata = new Metadata();
    ParseContext context = new ParseContext();
    AutoDetectParser parser = new AutoDetectParser();
    parser.parse(inputStream, handler, metadata, context);
    String text = handler.toString();
    log.info("文档解析完成: docId={}, 字符数={}", docId, text.length());
    parsedTextCache.put(docId, text);
}
```

3. embedAndIndex（:257-282）失败计数：

```java
// 批量向量化
int failedCount = 0;
for (DocumentChunk chunk : chunks) {
    try {
        ...
    } catch (Exception e) {
        log.error("分片向量化失败: chunkId={}", chunk.getId(), e);
        failedCount++;
    }
}

// P2-1: 部分失败标 FAILED（避免误标 INDEXED 导致检索漏召回），全部成功才 INDEXED
if (failedCount > 0) {
    updateDocStatus(docId, "FAILED", "分片向量化失败: " + failedCount + "/" + chunks.size());
    log.warn("向量化部分失败: docId={}, 失败={}/{}", docId, failedCount, chunks.size());
    return;
}
updateDocStatus(docId, "INDEXED", null);
log.info("向量化完成: docId={}, 分片数={}", docId, chunks.size());
```

- [ ] **Step 4: 适配既有测试并确认通过**

现有 `process_fullPipeline`（EtlPipelineTest :108-133）与 `process_docNotFound_setsFailed`（:135-144）需适配抢占逻辑：
- `process_fullPipeline`：现有 stub `when(documentMapper.update(any(), any())).thenReturn(1)` 使抢占成功 ✓（无需改动）；确认 `downloadFile` verify 仍通过
- `process_docNotFound_setsFailed`：selectById 返回 null → process 抛异常 → FAILED ✓（无需改动）

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest`
Expected: PASS（新增 4 + 既有 6 全过）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "fix: P2-1 ETL 状态守卫（原子抢占）+ 部分失败标 FAILED + try-with-resources 关流"
```

---

### Task 2: 文件类型白名单 + maxFileSizeMb 引用

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java`（构造器 :42-46、upload :49-68）
- Test: Create `backend/src/test/java/com/commerce/rag/controller/AdminDocumentControllerTest.java`

**Interfaces:**
- Consumes: `EtlProperties`（record：`maxFileSizeMb()`，构造 `new EtlProperties(100, new EtlProperties.Executor(2,4,20,"etl-"), new EtlProperties.Chunk(768,128))`）、`DocumentService.upload`（现有签名）
- Produces: `AdminDocumentController` 构造器加 `EtlProperties` 参数；upload 校验非法类型/超限抛 `ResponseStatusException(400)`

- [ ] **Step 1: 新增测试**（新建 AdminDocumentControllerTest）

```java
package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.etl.EtlProperties;
import com.commerce.rag.service.DocumentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * AdminDocumentController 单元测试 —— 上传白名单与大小校验（P2-1）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDocumentController 上传校验测试")
class AdminDocumentControllerTest {

    @Mock
    private DocumentService documentService;

    private AdminDocumentController controller;

    @BeforeEach
    void setUp() {
        EtlProperties props = new EtlProperties(100, new EtlProperties.Executor(2, 4, 20, "etl-"), new EtlProperties.Chunk(768, 128));
        controller = new AdminDocumentController(documentService, props);
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("SUPER_ADMIN");
        return req;
    }

    @Test
    @DisplayName("upload 非法文件类型（.exe）→ 400，不触发上传")
    void upload_invalidType_throws400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "virus.exe", "application/octet-stream", new byte[10]);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upload(adminRequest(), 1L, "doc", file));
        assertEquals(400, ex.getStatusCode().value());
        verify(documentService, never()).upload(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upload 超过 maxFileSizeMb → 400，不触发上传")
    void upload_tooLarge_throws400() throws Exception {
        // 100MB 限制，构造 100MB+1 的流（仅 size 校验，不真正读内容）
        MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", new byte[100 * 1024 * 1024 + 1]);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.upload(adminRequest(), 1L, "doc", file));
        assertEquals(400, ex.getStatusCode().value());
        verify(documentService, never()).upload(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upload 合法类型（.pdf）→ 放行，调用 service")
    void upload_validType_succeeds() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", "内容".getBytes());

        assertDoesNotThrow(() -> controller.upload(adminRequest(), 1L, "doc", file));
        verify(documentService).upload(eq(1L), eq("doc"), any(), eq("pdf"), eq(6L), eq(1L), eq(true));
    }
}
```

注意：`AuthInterceptor.ATTR_ROLE` 常量名需确认（AuthController 用 `AuthInterceptor.getCurrentRole`——attribute 名可能在 AuthInterceptor 中定义，测试若编译失败改用 `getCurrentRole` 的 attribute key 常量实际名；可先在 AuthInterceptor.java grep `ATTR_`）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=AdminDocumentControllerTest`
Expected: FAIL——构造器签名不匹配（编译错误）

- [ ] **Step 3: 实现**

`AdminDocumentController.java`：

1. 构造器加 EtlProperties：

```java
private final DocumentService documentService;
private final EtlProperties etlProperties;

/** 文件类型白名单（前端设计文档 2.6.2 限定：PDF/PPTX/DOCX/MD/TXT） */
private static final Set<String> ALLOWED_FILE_TYPES = Set.of("pdf", "docx", "pptx", "md", "txt");
```

（Java 全局宪法：依赖注入统一 `private final` + `@RequiredArgsConstructor`——类级加 Lombok 注解生成全参构造器，删除手写构造器；测试 `new AdminDocumentController(documentService, props)` 签名不变。）

新增 import：`com.commerce.rag.etl.EtlProperties`、`java.util.Set`、`org.springframework.http.HttpStatus`、`org.springframework.web.server.ResponseStatusException`。

2. upload 校验（fileType/fileSize 提取后、service 调用前）：

```java
// P2-1: 文件类型白名单（前端文档限定 PDF/PPTX/DOCX/MD/TXT，防 .exe/.zip 等任意类型堆积 FAILED）
if (!ALLOWED_FILE_TYPES.contains(fileType)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件类型: " + fileType);
}
// P2-1: 大小校验（引用 etl.max-file-size-mb 配置，修复死配置）
if (fileSize > etlProperties.maxFileSizeMb() * 1024 * 1024L) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "文件大小超过限制: " + etlProperties.maxFileSizeMb() + "MB");
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=AdminDocumentControllerTest`
Expected: PASS（3 测试全过）；确认既有 `AdminUserControllerTest` 等未受影响（无其他 AdminDocumentController 实例化点——grep 确认）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java backend/src/test/java/com/commerce/rag/controller/AdminDocumentControllerTest.java
git commit -m "fix: P2-1 上传文件类型白名单 + 大小校验引用 maxFileSizeMb"
```

---

### Task 3: 分片端点契约对齐（PUT /{id}、POST /batch-corrected）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminChunkController.java`（updateContent :76、batchCorrected :122）
- Test: Create `backend/src/test/java/com/commerce/rag/controller/AdminChunkControllerTest.java`

**Interfaces:**
- Consumes: 无新依赖（`DocumentChunkService.updateContent/batchCorrected` 现有签名）
- Produces: `PUT /api/v1/admin/chunks/{id}`（改 content→重新向量化）、`POST /api/v1/admin/chunks/batch-corrected`

- [ ] **Step 1: 新增契约测试**（新建 AdminChunkControllerTest）

```java
package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.controller.dto.BatchCorrectedRequest;
import com.commerce.rag.controller.dto.ChunkContentUpdateRequest;
import com.commerce.rag.service.DocumentChunkService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * AdminChunkController 契约测试 —— 端点与前端设计文档对齐（P2-2）
 *
 * <p>锁定契约：PUT /{id}（分片编辑）、POST /batch-corrected（批量标记已修正）。
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminChunkController 契约测试")
class AdminChunkControllerTest {

    @Mock
    private DocumentChunkService chunkService;

    private AdminChunkController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminChunkController(chunkService);
    }

    private HttpServletRequest adminRequest() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR_USER_ID)).thenReturn(1L);
        when(req.getAttribute(AuthInterceptor.ATTR_ROLE)).thenReturn("SUPER_ADMIN");
        return req;
    }

    @Test
    @DisplayName("契约 — updateContent 映射 PUT /{id}（前端文档 :933）")
    void updateContent_mapsToPutId() throws Exception {
        var method = AdminChunkController.class.getMethod(
                "updateContent", HttpServletRequest.class, Long.class, ChunkContentUpdateRequest.class);
        PutMapping mapping = method.getAnnotation(PutMapping.class);
        assertNotNull(mapping, "必须为 @PutMapping");
        assertArrayEquals(new String[] {"/{id}"}, mapping.value(), "路径应为 /{id}");

        controller.updateContent(adminRequest(), 1L, new ChunkContentUpdateRequest("新内容"));
        verify(chunkService).updateContent(eq(1L), eq("新内容"), eq(1L), eq(true));
    }

    @Test
    @DisplayName("契约 — batchCorrected 映射 POST /batch-corrected（前端文档 :926）")
    void batchCorrected_mapsToPost() throws Exception {
        var method = AdminChunkController.class.getMethod(
                "batchCorrected", HttpServletRequest.class, BatchCorrectedRequest.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(mapping, "必须为 @PostMapping");
        assertArrayEquals(new String[] {"/batch-corrected"}, mapping.value(), "路径应为 /batch-corrected");

        controller.batchCorrected(adminRequest(), new BatchCorrectedRequest(List.of(1L, 2L)));
        verify(chunkService).batchCorrected(eq(List.of(1L, 2L)), eq(1L), eq(true));
    }
}
```

需要确认：`ChunkContentUpdateRequest`/`BatchCorrectedRequest` record 字段名（content()/ids()——从 AdminChunkController 现有调用 `request2.content()`、`request2.ids()` 推断）；`AuthInterceptor.ATTR_ROLE` 常量名（与 Task 2 同样确认）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=AdminChunkControllerTest`
Expected: FAIL——PutMapping 路径为 `/{id}/content`（断言不符）、PatchMapping 无 PostMapping

- [ ] **Step 3: 实现**

`AdminChunkController.java` 两处注解修改 + 构造器注解化：

```java
/** D3: 更新分片内容（重新向量化）—— 契约对齐：前端文档 :933 PUT /api/v1/admin/chunks/{id} */
@PutMapping("/{id}")
public ApiResponse<Void> updateContent(...)
```

```java
/** D8: 批量标记已修正 —— 契约对齐：前端文档 :926 POST /api/v1/admin/chunks/batch-corrected */
@PostMapping("/batch-corrected")
public ApiResponse<Void> batchCorrected(...)
```

（`@PatchMapping` import 若不再使用则删除。）

**Java 全局合规（宪法强制）**：`AdminChunkController` 手写样板构造器（:46-48）→ 类级加 Lombok `@RequiredArgsConstructor`（字段 `private final DocumentChunkService chunkService` 已具备，注解生成同签名构造器，测试 `new AdminChunkController(chunkService)` 不变）；类头注释补充依赖注入方式。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=AdminChunkControllerTest`
Expected: PASS（2 契约测试全过）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/controller/AdminChunkController.java backend/src/test/java/com/commerce/rag/controller/AdminChunkControllerTest.java
git commit -m "fix: P2-2 分片端点契约对齐（PUT /chunks/{id}、POST /batch-corrected）"
```

---

### Task 4: 文档列表筛选参数（status/q/sort）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/DocumentService.java`（findPage :151-162）
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java`（findPage :83-92）
- Test: `backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java`

**Interfaces:**
- Produces: `DocumentService.findPage(Long kbId, String status, String q, String sort, int page, int size, Long userId, String role)`（新增 status/q/sort 参数）；controller findPage 透传
- Consumes: 无新依赖

- [ ] **Step 1: 新增测试**（DocumentServiceTest 追加）

```java
@Test
@DisplayName("findPage 筛选 — status/q/sort 参数生效且 TEACHER 过滤保留")
void findPage_filtersApplied() {
    // Given: selectPage 返回空页（断言调用行为即可）
    when(documentMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

    documentService.findPage(10L, "PENDING", "标题", "updated", 1, 20, 100L, "TEACHER");

    // Then: selectPage 被调用（筛选逻辑经 wrapper 生效）
    verify(documentMapper).selectPage(any(), any());
}

@Test
@DisplayName("findPage 排序 — sort=created 默认 created_at 降序；非法 sort 不抛异常")
void findPage_sortHandling() {
    when(documentMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

    // 默认（null sort）与非法值均不抛异常
    assertDoesNotThrow(() -> documentService.findPage(null, null, null, null, 1, 20, null, null));
    assertDoesNotThrow(() -> documentService.findPage(null, null, null, "invalid", 1, 20, null, null));
}
```

新增 import：`com.baomidou.mybatisplus.extension.plugins.pagination.Page`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=DocumentServiceTest`
Expected: FAIL——findPage 签名不匹配（编译错误）

- [ ] **Step 3: 实现**

`DocumentService.findPage`（:151-162）：

```java
/**
 * 分页查询文档（P2-2 契约对齐：前端文档 :871 支持 status/q/sort 筛选）
 *
 * @param kbId   知识库 ID（可选）
 * @param status 解析状态筛选（可选，parse_status 精确匹配）
 * @param q      标题关键词（可选，title like）
 * @param sort   排序（created=created_at 降序默认；updated=updated_at 降序；非法值按 created）
 * @param page   页码（1-based）
 * @param size   每页条数
 * @param userId 当前用户 ID（TEACHER 数据权限过滤）
 * @param role   当前用户角色（TEACHER 时按 created_by 过滤）
 */
public IPage<Document> findPage(
        Long kbId, String status, String q, String sort, int page, int size, Long userId, String role) {
    Page<Document> pageObj = new Page<>(page, size > 0 ? size : DEFAULT_PAGE_SIZE);
    // 合规：Wrappers 静态工厂 + lambda 链式（宪法「Wrapper 一律 lambda 链式构建，禁止 new」）
    LambdaQueryWrapper<Document> wrapper = Wrappers.<Document>lambdaQuery()
            .eq(kbId != null, Document::getKbId, kbId)
            .eq(status != null && !status.isBlank(), Document::getParseStatus, status)
            .like(q != null && !q.isBlank(), Document::getTitle, q)
            // TEACHER 只能查看自己创建的文档
            .eq("TEACHER".equals(role) && userId != null, Document::getCreatedBy, userId)
            .orderByDesc("updated".equals(sort) ? Document::getUpdatedAt : Document::getCreatedAt);
    return documentMapper.selectPage(pageObj, wrapper);
}
```

`AdminDocumentController.findPage`（:83-92）：

```java
/** C3: 分页查询文档（P2-2：status/q/sort 筛选参数对齐前端文档 :871） */
@GetMapping
public ApiResponse<PageResponse<Document>> findPage(
        HttpServletRequest request,
        @RequestParam(required = false) Long kbId,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size) {
    Long userId = AuthInterceptor.getCurrentUserId(request);
    String role = AuthInterceptor.getCurrentRole(request);
    return ApiResponse.ok(PageResponse.of(
            documentService.findPage(kbId, status, q, sort, page, size, userId, role)));
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=DocumentServiceTest`
Expected: PASS（新增 2 + 既有全过）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/DocumentService.java backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java
git commit -m "fix: P2-2 文档列表 status/q/sort 筛选参数对齐前端文档"
```

---

### Task 5: dashboard 统计三端点（AdminDashboardController + DashboardService）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/service/DashboardService.java`
- Create: `backend/src/main/java/com/commerce/rag/controller/AdminDashboardController.java`
- Modify: `backend/src/main/java/com/commerce/rag/mapper/UserFeedbackMapper.java`（新增 `selectDailyFeedbackCount` 分组统计方法）
- Create: `backend/src/main/resources/mapper/UserFeedbackMapper.xml`（分组聚合 SQL 映射，本波新建 mapper XML 基建）
- Test: Create `backend/src/test/java/com/commerce/rag/service/DashboardServiceTest.java`
- Test: Create `backend/src/test/java/com/commerce/rag/controller/AdminDashboardControllerTest.java`

**Interfaces:**
- Produces: `DashboardService.dashboardStats()` → `Map<String,Object>`（`documentCount`/`pendingChunkCount`）；`DashboardService.feedbackStats(String period)` → `Map<String,Object>`（`sessionCount`/`likeRate`，period ∈ today/week/month 默认 today）；`DashboardService.feedbackTrend(int days)` → `List<Map<String,Object>>`（`date`/`count`，近 N 天升序含 0 补位）
- Consumes: `DocumentMapper`/`DocumentChunkMapper`/`ChatRunMapper`/`UserFeedbackMapper`（依赖注入统一 `private final` + Lombok `@RequiredArgsConstructor`——Java 全局宪法）；新增 `UserFeedbackMapper.selectDailyFeedbackCount(LocalDateTime start)` → `List<Map<String,Object>>`（每行 `{d: 'YYYY-MM-DD', c: 计数}`，日期升序）——分组 SQL 在 UserFeedbackMapper.xml 实现（宪法：禁止业务拼 SQL 字符串）

- [ ] **Step 1: 新增测试**（DashboardServiceTest 新建）

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DashboardService 单元测试 —— 统计口径（P2-2）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService 统计测试")
class DashboardServiceTest {

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper chunkMapper;

    @Mock
    private ChatRunMapper chatRunMapper;

    @Mock
    private UserFeedbackMapper feedbackMapper;

    private DashboardService service;

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor 生成全参构造器，直接构造注入 mock
        service = new DashboardService(documentMapper, chunkMapper, chatRunMapper, feedbackMapper);
    }

    @Test
    @DisplayName("dashboardStats — 文档总数与待修正分片数")
    void dashboardStats_counts() {
        when(documentMapper.selectCount(any())).thenReturn(10L);
        when(chunkMapper.selectCount(any())).thenReturn(3L);

        Map<String, Object> stats = service.dashboardStats();

        assertEquals(10L, stats.get("documentCount"));
        assertEquals(3L, stats.get("pendingChunkCount"));
    }

    @Test
    @DisplayName("feedbackStats — 今日会话数 + 点赞率")
    void feedbackStats_likeRate() {
        when(chatRunMapper.selectCount(any())).thenReturn(5L);
        // 第一次调用=总反馈数，第二次调用=点赞数（Mockito 序列返回值）
        when(feedbackMapper.selectCount(any())).thenReturn(4L, 3L);

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(5L, stats.get("sessionCount"));
        assertEquals(0.75, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackStats — 0 反馈时点赞率为 0（无除零）")
    void feedbackStats_zeroFeedback_likeRateZero() {
        when(chatRunMapper.selectCount(any())).thenReturn(0L);
        when(feedbackMapper.selectCount(any())).thenReturn(0L, 0L);

        Map<String, Object> stats = service.feedbackStats("today");

        assertEquals(0L, stats.get("sessionCount"));
        assertEquals(0.0, stats.get("likeRate"));
    }

    @Test
    @DisplayName("feedbackTrend — 近 N 天每日反馈数，0 补位，升序")
    void feedbackTrend_zeroFillAscending() {
        // mapper 分组统计返回 2 条记录（间隔日期），其余天补 0（SQL 在 UserFeedbackMapper.xml）
        when(feedbackMapper.selectDailyFeedbackCount(any()))
                .thenReturn(List.of(
                        Map.of("d", LocalDate.now().minusDays(1).toString(), "c", 2L),
                        Map.of("d", LocalDate.now().toString(), "c", 3L)));

        List<Map<String, Object>> trend = service.feedbackTrend(7);

        assertEquals(7, trend.size());
        assertEquals(LocalDate.now().minusDays(6).toString(), trend.get(0).get("date"));
        assertEquals(0L, trend.get(0).get("count"));
        assertEquals(2L, trend.get(2).get("count"));
        assertEquals(3L, trend.get(6).get("count"));
    }

    @Test
    @DisplayName("feedbackTrend — days 钳位（1~90），负数按 1")
    void feedbackTrend_clampDays() {
        when(feedbackMapper.selectDailyFeedbackCount(any())).thenReturn(List.of());

        assertEquals(1, service.feedbackTrend(0).size());
        assertEquals(90, service.feedbackTrend(999).size());
    }
}
```

注意：`selectDailyFeedbackCount` 返回多行 `List<Map>`（MyBatis 多行 resultType="map" 即 List，接口签名需为 `List<Map<String,Object>>`——**不能声明 `Map` 单行类型**，否则 MyBatis 只取首行）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=DashboardServiceTest`
Expected: FAIL——类不存在（编译错误）

- [ ] **Step 3: 实现**

`DashboardService.java`（private final + @RequiredArgsConstructor 模式）：

```java
package com.commerce.rag.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.commerce.rag.entity.ChatRun;
import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.DocumentChunk;
import com.commerce.rag.entity.UserFeedback;
import com.commerce.rag.mapper.ChatRunMapper;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.UserFeedbackMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 管理端 Dashboard 统计服务（P2-2 契约对齐：前端设计文档 :783-786 三统计接口）
 *
 * <p>统计口径（spec §2.3 定义，前端文档未定义返回 schema）：
 * <ul>
 *   <li>dashboard/stats：文档总数 + 待修正分片数（correction_status=PENDING）</li>
 *   <li>feedback/stats?period=：周期内会话数 + 点赞率（period ∈ today/week/month）</li>
 *   <li>feedback/trend?days=：近 N 天每日反馈数（0 补位升序）</li>
 * </ul>
 *
 * @author commerce-rag
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    private final DocumentMapper documentMapper;

    private final DocumentChunkMapper chunkMapper;

    private final ChatRunMapper chatRunMapper;

    private final UserFeedbackMapper feedbackMapper;

    /** 文档总数 + 待修正分片数（简单计数走 MP Wrappers 链式） */
    public Map<String, Object> dashboardStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("documentCount", documentMapper.selectCount(Wrappers.<Document>lambdaQuery()));
        stats.put("pendingChunkCount", chunkMapper.selectCount(
                Wrappers.<DocumentChunk>lambdaQuery().eq(DocumentChunk::getCorrectionStatus, "PENDING")));
        return stats;
    }

    /**
     * 周期内会话数 + 点赞率
     *
     * @param period today/week/month（默认 today）
     */
    public Map<String, Object> feedbackStats(String period) {
        LocalDateTime start = periodStart(period);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("sessionCount", chatRunMapper.selectCount(
                Wrappers.<ChatRun>lambdaQuery().ge(ChatRun::getCreatedAt, start)));
        long total = feedbackMapper.selectCount(
                Wrappers.<UserFeedback>lambdaQuery().ge(UserFeedback::getCreatedAt, start));
        long liked = feedbackMapper.selectCount(Wrappers.<UserFeedback>lambdaQuery()
                .ge(UserFeedback::getCreatedAt, start)
                .eq(UserFeedback::getIsLiked, true));
        // 总数 0 时点赞率 0（防除零）
        stats.put("likeRate", total > 0 ? (double) liked / total : 0.0);
        return stats;
    }

    /**
     * 近 N 天每日反馈数（0 补位，日期升序）
     *
     * @param days 天数（1~90 钳位）
     */
    public List<Map<String, Object>> feedbackTrend(int days) {
        int clamped = Math.max(1, Math.min(days, 90));
        LocalDate startDate = LocalDate.now().minusDays(clamped - 1L);
        // 分组聚合 SQL 走 mapper XML 映射（宪法：禁止业务层拼接 SQL 字符串）
        List<Map<String, Object>> rows = feedbackMapper.selectDailyFeedbackCount(startDate.atStartOfDay());

        Map<String, Long> countByDate = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            countByDate.put(String.valueOf(row.get("d")), ((Number) row.get("c")).longValue());
        }
        // 0 补位：近 N 天逐日填充
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 0; i < clamped; i++) {
            String date = startDate.plusDays(i).toString();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date);
            entry.put("count", countByDate.getOrDefault(date, 0L));
            trend.add(entry);
        }
        return trend;
    }

    /** period → 起始时间（today=当天 0 点、week=近 7 天、month=近 30 天，默认 today） */
    private LocalDateTime periodStart(String period) {
        LocalDate today = LocalDate.now();
        return switch (period == null ? "today" : period) {
            case "week" -> today.minusDays(6).atStartOfDay();
            case "month" -> today.minusDays(29).atStartOfDay();
            default -> today.atStartOfDay();
        };
    }
}
```

需要确认：`UserFeedback` 实体字段 `getIsLiked`/`getCreatedAt`、`ChatRun.getCreatedAt`、`DocumentChunk.getCorrectionStatus` 存在（Entity 字段名——从 V6 表结构与既有代码推断，SDD 阶段编译验证）。

`UserFeedbackMapper.java` 新增方法（分组聚合 SQL 走 XML 映射，宪法强制）：

```java
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 按天分组统计反馈数（Dashboard 趋势图，P2-2）
 *
 * <p>分组聚合 SQL（to_char/COUNT/GROUP BY）在 UserFeedbackMapper.xml 实现，
 * 不在 service 拼接 SQL 字符串（宪法：复杂 SQL 必须走 mapper XML 映射）。
 *
 * @param start 统计起始时间（含当天 0 点）
 * @return 每行 {d: 'YYYY-MM-DD', c: 计数} 的列表，日期升序（deleted=0 已过滤）
 */
List<Map<String, Object>> selectDailyFeedbackCount(@Param("start") LocalDateTime start);
```

`src/main/resources/mapper/UserFeedbackMapper.xml`（本波新建 mapper XML 基建，mapper-locations 已配置）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.commerce.rag.mapper.UserFeedbackMapper">

    <!-- 按天分组统计反馈数（Dashboard 趋势图，P2-2）：deleted=0 过滤软删，日期升序 -->
    <select id="selectDailyFeedbackCount" resultType="map">
        SELECT to_char(created_at, 'YYYY-MM-DD') AS d, COUNT(*) AS c
        FROM user_feedback
        WHERE deleted = 0 AND created_at &gt;= #{start}
        GROUP BY to_char(created_at, 'YYYY-MM-DD')
        ORDER BY d
    </select>

</mapper>
```

（列别名 d/c：PG 列名小写，MyBatis map key 即 "d"/"c"，service 层再映射为 date/count 输出。）

`AdminDashboardController.java`：

```java
package com.commerce.rag.controller;

import com.commerce.rag.controller.dto.ApiResponse;
import com.commerce.rag.service.DashboardService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 Dashboard 统计 Controller（P2-2 契约对齐：前端设计文档 :783-786）
 *
 * <p>提供三个统计端点（方法级路径区分，前缀 /api/v1/admin）：
 * <ul>
 *   <li>GET /api/v1/admin/dashboard/stats — 文档总数/待修正数</li>
 *   <li>GET /api/v1/admin/feedback/stats?period=today — 周期会话数/点赞率</li>
 *   <li>GET /api/v1/admin/feedback/trend?days=7 — 近 N 天反馈趋势</li>
 * </ul>
 *
 * @author commerce-rag
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TEACHER')")
@RequiredArgsConstructor
public class AdminDashboardController {

    private static final Logger log = LoggerFactory.getLogger(AdminDashboardController.class);

    private final DashboardService dashboardService;

    /** Dashboard KPI：文档总数 + 待修正分片数（前端文档 :783） */
    @GetMapping("/dashboard/stats")
    public ApiResponse<Map<String, Object>> dashboardStats() {
        return ApiResponse.ok(dashboardService.dashboardStats());
    }

    /** Dashboard KPI：周期内会话数 + 点赞率（前端文档 :784，period 默认 today） */
    @GetMapping("/feedback/stats")
    public ApiResponse<Map<String, Object>> feedbackStats(@RequestParam(defaultValue = "today") String period) {
        return ApiResponse.ok(dashboardService.feedbackStats(period));
    }

    /** Dashboard 趋势图：近 N 天每日反馈数（前端文档 :786，days 默认 7） */
    @GetMapping("/feedback/trend")
    public ApiResponse<List<Map<String, Object>>> feedbackTrend(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(dashboardService.feedbackTrend(days));
    }
}
```

- [ ] **Step 4: 新增契约测试 + 运行全部确认通过**

`AdminDashboardControllerTest`（契约：三端点路径 + 参数默认值）：

```java
package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.service.DashboardService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * AdminDashboardController 契约测试 —— 三统计端点与前端文档对齐（P2-2）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminDashboardController 契约测试")
class AdminDashboardControllerTest {

    @Mock
    private DashboardService dashboardService;

    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminDashboardController(dashboardService);
    }

    @Test
    @DisplayName("契约 — dashboardStats 映射 GET /dashboard/stats（:783）")
    void dashboardStats_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("dashboardStats");
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/dashboard/stats"}, mapping.value());
        when(dashboardService.dashboardStats()).thenReturn(Map.of("documentCount", 1L));
        assertEquals(1L, controller.dashboardStats().data().get("documentCount"));
    }

    @Test
    @DisplayName("契约 — feedbackStats 映射 GET /feedback/stats 且 period 默认 today（:784）")
    void feedbackStats_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("feedbackStats", String.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/feedback/stats"}, mapping.value());
        when(dashboardService.feedbackStats("today")).thenReturn(Map.of("sessionCount", 5L));
        assertEquals(5L, controller.feedbackStats("today").data().get("sessionCount"));
    }

    @Test
    @DisplayName("契约 — feedbackTrend 映射 GET /feedback/trend 且 days 默认 7（:786）")
    void feedbackTrend_mapsToPath() throws Exception {
        var method = AdminDashboardController.class.getMethod("feedbackTrend", int.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[] {"/feedback/trend"}, mapping.value());
        when(dashboardService.feedbackTrend(7)).thenReturn(List.of());
        assertNotNull(controller.feedbackTrend(7).data());
    }
}
```

Run: `cd backend && mvn.cmd test -Dtest=DashboardServiceTest,AdminDashboardControllerTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/DashboardService.java backend/src/main/java/com/commerce/rag/controller/AdminDashboardController.java backend/src/main/java/com/commerce/rag/mapper/UserFeedbackMapper.java backend/src/main/resources/mapper/UserFeedbackMapper.xml backend/src/test/java/com/commerce/rag/service/DashboardServiceTest.java backend/src/test/java/com/commerce/rag/controller/AdminDashboardControllerTest.java
git commit -m "feat: P2-2 dashboard 统计三端点（dashboard/stats、feedback/stats、feedback/trend，分组统计走 mapper XML）"
```

---

### Task 6: GlobalExceptionHandler 403 双轨契约统一

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/GlobalExceptionHandler.java`（4 个 handler）
- Test: `backend/src/test/java/com/commerce/rag/controller/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `handleResponseStatusException` 返回 `ResponseEntity<ApiResponse<Void>>`；其余 3 个 handler 加 `@ResponseStatus`；行为不变（body code/message 保持）

- [ ] **Step 1: 改写测试**（GlobalExceptionHandlerTest 替换 5 个测试）

```java
package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.commerce.rag.controller.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * GlobalExceptionHandler 单元测试 —— 5 个异常处理器逐一验证返回的业务码、消息与真实 HTTP 状态
 *
 * <p>P2-3 契约统一：所有 handler 的 HTTP 状态码与 body code 一致（原实现 HTTP 恒 200 的双轨问题）。
 *
 * @author commerce-rag
 */
@DisplayName("GlobalExceptionHandler 异常处理测试")
class GlobalExceptionHandlerTest {

    /** 被测试的异常处理器（无状态，直接实例化） */
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** 断言方法上的 @ResponseStatus 注解契约（HTTP 状态 = 指定值） */
    private void assertResponseStatus(Class<?>[] paramTypes, HttpStatus expected) throws Exception {
        var method = GlobalExceptionHandler.class.getMethod("handle" + paramTypes[0].getSimpleName(), paramTypes);
        ResponseStatus responseStatus = method.getAnnotation(ResponseStatus.class);
        assertNotNull(responseStatus, "handler 必须标注 @ResponseStatus");
        assertEquals(expected, responseStatus.value(), "@ResponseStatus 应为 " + expected);
    }

    @Test
    @DisplayName("handleResponseStatusException → ResponseEntity 404 + body code 404")
    void handleResponseStatusException_returns404WithReason() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleResponseStatusException(new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(), "HTTP 状态应为 404");
        assertNotNull(response.getBody());
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getBody().code(), "业务码应为 404");
        assertTrue(response.getBody().message().contains("不存在"), "message 应包含异常原因");
    }

    @Test
    @DisplayName("handleIllegalArgumentException → @ResponseStatus(400) + body code 400")
    void handleIllegalArgumentException_returns400WithMessage() throws Exception {
        assertResponseStatus(new Class<?>[] {IllegalArgumentException.class}, HttpStatus.BAD_REQUEST);
        ApiResponse<Void> result = handler.handleIllegalArgumentException(new IllegalArgumentException("参数错误"));
        assertEquals(HttpStatus.BAD_REQUEST.value(), result.code(), "业务码应为 400");
        assertEquals("参数错误", result.message(), "message 应透传异常消息");
    }

    @Test
    @DisplayName("handleSecurityException → @ResponseStatus(403) + body code 403")
    void handleSecurityException_returns403() throws Exception {
        assertResponseStatus(new Class<?>[] {SecurityException.class}, HttpStatus.FORBIDDEN);
        ApiResponse<Void> result = handler.handleSecurityException(new SecurityException("越权访问"));
        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());
    }

    @Test
    @DisplayName("handleAccessDeniedException → @ResponseStatus(403) + body code 403")
    void handleAccessDeniedException_returns403() throws Exception {
        assertResponseStatus(new Class<?>[] {AccessDeniedException.class}, HttpStatus.FORBIDDEN);
        ApiResponse<Void> result = handler.handleAccessDeniedException(new AccessDeniedException("Access Denied"));
        assertEquals(HttpStatus.FORBIDDEN.value(), result.code(), "业务码应为 403");
        assertEquals("无权操作", result.message());
    }

    @Test
    @DisplayName("handleException → @ResponseStatus(500) + body code 500")
    void handleException_returns500() throws Exception {
        assertResponseStatus(new Class<?>[] {Exception.class}, HttpStatus.INTERNAL_SERVER_ERROR);
        ApiResponse<Void> result = handler.handleException(new RuntimeException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR.value(), result.code(), "业务码应为 500");
        assertEquals("服务器内部错误", result.message());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL——ResponseEntity 类型不匹配（编译错误）+ @ResponseStatus 断言失败

- [ ] **Step 3: 实现**

`GlobalExceptionHandler.java` 四处修改：

1. handleResponseStatusException（:35-40）改 ResponseEntity：

```java
/**
 * 处理 ResponseStatusException —— 使用异常中指定的 HTTP 状态码
 * （P2-3：真实 HTTP 状态码，原实现 HTTP 恒 200 的双轨问题）
 */
@ExceptionHandler(ResponseStatusException.class)
public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException e) {
    int code = e.getStatusCode().value();
    log.warn("业务异常: status={}, reason={}", code, e.getReason());
    return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.fail(code, e.getReason()));
}
```

2. handleIllegalArgumentException（:45-49）加注解：

```java
/** 处理 IllegalArgumentException —— 参数校验失败，返回 400（P2-3 真实 HTTP 状态码） */
@ResponseStatus(HttpStatus.BAD_REQUEST)
@ExceptionHandler(IllegalArgumentException.class)
```

3. handleSecurityException（:54-58）加注解：

```java
/** 处理 SecurityException —— 权限不足，返回 403（P2-3 真实 HTTP 状态码） */
@ResponseStatus(HttpStatus.FORBIDDEN)
@ExceptionHandler(SecurityException.class)
```

4. handleException（:73-77）加注解：

```java
/** 处理其他未捕获异常 —— 返回 500（P2-3 真实 HTTP 状态码） */
@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
@ExceptionHandler(Exception.class)
```

新增 import：`org.springframework.http.ResponseEntity`（HttpStatus/ResponseStatus 已有）。

- [ ] **Step 4: 运行测试确认通过 + 全量回归确认无依赖 HTTP 200 的测试**

Run: `cd backend && mvn.cmd test -Dtest=GlobalExceptionHandlerTest`
Expected: PASS（5 测试全过）

随后跑全量 `cd backend && mvn.cmd test`——若有测试因 HTTP 状态变化失败（如 WebMvcTest 断言 200 + body code），按"真实状态更正确"原则更新断言（同提交）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/controller/GlobalExceptionHandler.java backend/src/test/java/com/commerce/rag/controller/GlobalExceptionHandlerTest.java
git commit -m "fix: P2-3 全局异常处理器真实 HTTP 状态码（消除 200+body code 双轨）"
```

---

### Task 7: P3 死代码清理（4 项）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（:92 字段、:356 set、:433 remove）
- Modify: `backend/src/main/java/com/commerce/rag/stream/SseEvent.java`（:27-29 toSseText）
- Modify: `backend/src/main/java/com/commerce/rag/service/ChatRunService.java`（:96-115 findActiveRun/cancelRun）
- Modify: `backend/src/main/java/com/commerce/rag/config/AuthConfig.java`（:28 排除路径）
- Test: 无测试改动（grep 零残留验证）

**Interfaces:**
- Consumes: 无
- Produces: 四项死代码删除（RunSnapshot 类保留——captureSnapshot 仍返回）

- [ ] **Step 1: 修改实现**（4 处删除，均为删除操作）

1. `ChatRequestWorker.java`：删除 `private final ThreadLocal<RunSnapshot> runSnapshot = new ThreadLocal<>();`（:92）、`runSnapshot.set(snapshot);`（:356）、`runSnapshot.remove();`（:433）；检查 `ThreadLocal` import 是否仍被其他字段使用（runSnapshot 是唯一 ThreadLocal 字段 → import 删除）
2. `SseEvent.java`：删除 `toSseText()` 方法（:27-29，含 javadoc）
3. `ChatRunService.java`：删除 `findActiveRun`（:96-106）与 `cancelRun`（:108-115）两方法（含 javadoc）
4. `AuthConfig.java:28`：`excludePathPatterns("/api/v1/auth/**", "/api/v1/public/**")` → `excludePathPatterns("/api/v1/auth/**")`

- [ ] **Step 2: grep 验证零残留 + 编译**

Run:
```bash
cd backend
grep -rn "runSnapshot\|toSseText\|findActiveRun\|cancelRun" src/main/java --include="*.java" | grep -v "cancelFlags\|取消"
grep -rn "public/\*\*" src/main/java --include="*.java"
mvn.cmd test -Dtest=ChatRequestWorkerTest,SseEventTransformerTest,AuthControllerTest
```
Expected: 零残留（cancelFlags 为取消标记非死代码）；相关测试全过

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/main/java/com/commerce/rag/stream/SseEvent.java backend/src/main/java/com/commerce/rag/service/ChatRunService.java backend/src/main/java/com/commerce/rag/config/AuthConfig.java
git commit -m "chore: P3 死代码清理（runSnapshot/toSseText/findActiveRun+cancelRun/public 排除）"
```

---

### Task 8: A11 RT 旋转原子化（Lua）

**Files:**
- Create: `backend/src/main/resources/lua/mark_rt_used.lua`
- Modify: `backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java`（字段区、新增方法、删除 isRefreshTokenUsed/markRefreshTokenUsed :182-214）
- Modify: `backend/src/main/java/com/commerce/rag/controller/AuthController.java`（refresh 步骤 3+5 合并 :169-195 区域）
- Test: `backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java`
- Test: `backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java`

**Interfaces:**
- Produces: `DeviceKickService.markRefreshTokenUsedAtomic(String jtiRt)` → `boolean`（true=首次使用抢占成功；false=已被使用）；删除 `isRefreshTokenUsed`/`markRefreshTokenUsed`
- Consumes: `redisTemplate.execute(DefaultRedisScript<Long>, List<K>, Object... args)`、`AuthProperties.refreshTokenExpiry()`

- [ ] **Step 1: 新建 Lua 脚本**

`src/main/resources/lua/mark_rt_used.lua`：

```lua
-- P3 A11: RT 一次性旋转原子标记（检查+置位单条脚本，消除 TOCTOU）
-- KEYS[1] = auth:rt:used:{jtiRt}，ARGV[1] = TTL 秒
-- 返回 1 = 首次使用（本次抢占成功）；0 = 已被使用
if redis.call('GET', KEYS[1]) == '1' then
    return 0
end
redis.call('SET', KEYS[1], '1', 'EX', ARGV[1])
return 1
```

- [ ] **Step 2: 新增/改写测试**（DeviceKickServiceTest 追加 + AuthControllerTest 适配）

DeviceKickServiceTest 追加：

```java
@Test
@DisplayName("markRefreshTokenUsedAtomic — Lua 返回 1 → 首次使用（true）")
void markRefreshTokenUsedAtomic_firstUse_returnsTrue() {
    when(redisTemplate.execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
            anyList(), any(Object[].class))).thenReturn(1L);

    boolean result = deviceKickService.markRefreshTokenUsedAtomic("jti-1");

    assertTrue(result);
}

@Test
@DisplayName("markRefreshTokenUsedAtomic — Lua 返回 0 → 已被使用（false）")
void markRefreshTokenUsedAtomic_reuse_returnsFalse() {
    when(redisTemplate.execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
            anyList(), any(Object[].class))).thenReturn(0L);

    assertFalse(deviceKickService.markRefreshTokenUsedAtomic("jti-1"));
}

@Test
@DisplayName("markRefreshTokenUsedAtomic — Redis 异常降级放行并写 PG 黑名单")
void markRefreshTokenUsedAtomic_redisFail_fallbackOpen() {
    when(redisTemplate.execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
            anyList(), any(Object[].class))).thenThrow(new RuntimeException("redis down"));

    // 降级放行（与原 isRefreshTokenUsed PG 降级宽松语义一致）+ PG 黑名单兜底写入
    assertTrue(deviceKickService.markRefreshTokenUsedAtomic("jti-1"));
    // addToBlacklistPg 被调用（PG 黑名单有记录可查）
    verify(deviceKickService, atLeastOnce()).addToBlacklistPg(eq("jti-1"), eq("REFRESH"), any(), any(), eq("TOKEN_REUSE"));
}
```

注意：`addToBlacklistPg` 为 private——若测试无法 verify private 方法，改为断言其公共行为（PG 黑名单表查询 mock 或直接验证无异常+true；SDD 阶段按 DeviceKickServiceTest 既有 mock 基建调整）。`deviceKickService` 为 @InjectMocks 实例（现有测试结构需确认——DeviceKickServiceTest 用 @InjectMocks 还是构造器？SDD 阶段适配）。

AuthControllerTest：既有 refresh 相关测试中 `verify(deviceKickService).isRefreshTokenUsed(...)`/`markRefreshTokenUsed(...)` 调用改写为 `markRefreshTokenUsedAtomic` 断言；新增复用路径测试（markRefreshTokenUsedAtomic 返回 false → 401 + disableUser 被调）。具体改写以 AuthControllerTest 既有 refresh 测试为准（SDD 阶段执行）。

- [ ] **Step 3: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=DeviceKickServiceTest,AuthControllerTest`
Expected: FAIL——`markRefreshTokenUsedAtomic` 不存在（编译错误）

- [ ] **Step 4: 实现**

`DeviceKickService.java`：

1. 字段区（:50 附近）加脚本字段与构造器初始化（参照 kickAndLoginScript :69-72 模式）：

```java
/** RT 一次性旋转原子标记脚本（P3 A11：检查+置位单条 Lua，消除 TOCTOU） */
private final DefaultRedisScript<Long> markRtUsedScript;

// 构造器内：
this.markRtUsedScript = new DefaultRedisScript<>();
this.markRtUsedScript.setLocation(new org.springframework.core.io.ClassPathResource("lua/mark_rt_used.lua"));
this.markRtUsedScript.setResultType(Long.class);
```

2. 替换 :182-214 两个方法为：

```java
/**
 * 原子检查并标记 RT 为已使用（一次性旋转，P3 A11 Lua 化消除 TOCTOU）
 *
 * <p>单条 Lua 完成「检查是否已标记 + 置位」，并发 refresh 仅一个能抢占成功；
 * Redis 异常降级放行并写 PG 黑名单兜底（与原宽松降级语义一致）。
 *
 * @param jtiRt RT 的 JWT ID
 * @return true=首次使用（本次抢占成功）；false=已被使用（应拒绝）
 */
public boolean markRefreshTokenUsedAtomic(String jtiRt) {
    if (jtiRt == null || jtiRt.isEmpty()) {
        return false;
    }
    try {
        Long result = redisTemplate.execute(
                markRtUsedScript,
                List.of(RT_USED_KEY_PREFIX + jtiRt),
                String.valueOf(authProperties.refreshTokenExpiry()));
        return result != null && result == 1L;
    } catch (Exception e) {
        log.warn("Redis RT 原子标记失败，降级放行并写 PG 黑名单: jtiRt={}", jtiRt, e);
        addToBlacklistPg(jtiRt, "REFRESH", null, null, "TOKEN_REUSE");
        return true;
    }
}
```

（删除 isRefreshTokenUsed/markRefreshTokenUsed 及不再使用的辅助——`isBlacklisted` 仍被 AuthController.refresh 步骤 4 使用，保留。）

`AuthController.java` refresh 步骤 3+5 合并（替换 :169-175 检查块与 :183-185 标记块）：

```java
// 3. 原子检查并标记 RT 已使用（P3 A11：Lua 单条脚本消除检查/置位 TOCTOU——并发 refresh 仅一个成功）
if (!deviceKickService.markRefreshTokenUsedAtomic(oldJtiRt)) {
    // RT 复用 → 全量作废该用户所有 Token
    log.warn("RT 复用检测: userId={}, jtiRt={}", userId, oldJtiRt);
    deviceKickService.disableUser(userId, userId);
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被使用，请重新登录");
}

// 4. 检查 RT 是否在黑名单中（保留）
if (deviceKickService.isBlacklisted(oldJtiRt)) {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh Token 已被吊销");
}

// 5.（原 markRefreshTokenUsed 已合并进步骤 3 的原子脚本）
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=DeviceKickServiceTest,AuthControllerTest`
Expected: PASS（新增 3 + 既有全过，refresh 测试已适配）

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/resources/lua/mark_rt_used.lua backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java backend/src/main/java/com/commerce/rag/controller/AuthController.java backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java
git commit -m "fix: P3 A11 RT 一次性旋转原子化（Lua 单条脚本消除 TOCTOU）"
```

---

### Task 9: V6 迁移环境修复（TIMESTAMPTZ/JSONB/种子 hash）

**Files:**
- Modify: `backend/src/main/resources/db/migration/V6__full_schema_v5.sql`

**Interfaces:**
- Consumes: 无
- Produces: 全表 TIMESTAMPTZ→TIMESTAMP（29 处）、JSONB→TEXT（5 处 :41/:67/:166/:269/:288）、删 gin 索引（:181）、种子 admin hash 修正（:330/:333）

- [ ] **Step 1: 修改迁移文件**

```bash
cd backend
# 1. TIMESTAMPTZ → TIMESTAMP（全部 29 处）
sed -i 's/TIMESTAMPTZ/TIMESTAMP/g' src/main/resources/db/migration/V6__full_schema_v5.sql
# 2. JSONB → TEXT（全部 5 处）
sed -i 's/JSONB/TEXT/g' src/main/resources/db/migration/V6__full_schema_v5.sql
# 3. 删除 course_info.tags 的 gin 索引（TEXT 上 gin 不适用）
sed -i '/idx_course_info_tags/d' src/main/resources/db/migration/V6__full_schema_v5.sql
# 4. 种子 admin hash 替换（V6:330 注释行与 :333 INSERT 行的旧 hash → 真 admin123 hash）
sed -i 's|\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy|\$2a\$10\$4Tr8GR4XD98OTopP6/vK5eYsK8yRsRPOjdYzBgK9eahMJDo6KpL8.|g' src/main/resources/db/migration/V6__full_schema_v5.sql
```

人工核对：
- `grep -c "TIMESTAMPTZ"` → 0；`grep -c "JSONB"` → 0；`grep "idx_course_info_tags"` → 无输出；`grep "4Tr8GR4"` → 2 行（注释 + INSERT）
- `grep -n "TIMESTAMP" V6__full_schema_v5.sql | head` 抽查类型定义完整（`TIMESTAMP DEFAULT now()`）

- [ ] **Step 2: 确认 V7 无 TIMESTAMPTZ**

Run: `grep -c "TIMESTAMPTZ" src/main/resources/db/migration/V7__checkpoint_tables.sql`
Expected: 0（已核验）

- [ ] **Step 3: 提交**

```bash
git add backend/src/main/resources/db/migration/V6__full_schema_v5.sql
git commit -m "fix: 环境问题 V6 迁移类型对齐（TIMESTAMP/JSONB→TEXT/种子 admin hash 修正）"
```

---

### Task 10: 全量回归 + drop 重建真实环境验证 + 收尾

**Files:**
- 无代码改动；验证 + 进度文档更新

- [ ] **Step 1: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全过（基线 231 + 本波新增/改写）

- [ ] **Step 2: drop 重建 + 真实环境验证**

```bash
# 1. 起基础设施（镜像均在本地）
docker compose -f docker-compose.dev.yml up -d
# 2. drop 重建开发库（V6 已改，旧库迁移校验必失败——按 DB 约定重建）
docker exec rag-postgres psql -U postgres -c "DROP DATABASE IF EXISTS commerce_rag;" -c "CREATE DATABASE commerce_rag;"
# 3. 启动后端（后台，日志 /tmp/backend-run.log）
cd backend && nohup mvn.cmd spring-boot:run > /tmp/backend-run.log 2>&1 &
# 4. 等待 8080 就绪 + Flyway 重跑 V6/V7 成功
```

验证项（2026-08-15 P1 波已验证的链路重跑 + 本波新增）：
- 登录 `admin/admin123` 成功（种子 hash 修正生效）
- 上传文档（.pdf 合法类型）→ ETL → INDEXED（白名单放行 + 状态守卫链路）
- 上传 .exe → 400（白名单拒绝）
- 契约端点：`PUT /api/v1/admin/chunks/{id}` 与 `POST /batch-corrected` 返回 200/404 语义正确（不存在 id → 404 而非 405）
- `GET /api/v1/admin/dashboard/stats`、`GET /api/v1/admin/feedback/stats?period=today`、`GET /api/v1/admin/feedback/trend?days=7` 返回 200 + 正确口径
- `GET /api/v1/admin/documents?status=PENDING&q=&sort=updated` 筛选生效
- 错误契约：非法参数请求 → HTTP 400（真实状态码）

- [ ] **Step 3: 更新进度文档**

修改 `docs/progress/2026-08-14-P0修复与后续波次.md` §2.2：标记 P2/P3 完成（含全量测试数、真实环境验证结果），推进到 S1 主任务（§2.3）。

- [ ] **Step 4: 提交（如有验证期间发现的小修复，另行提交）**

```bash
git add <修复涉及文件>
git commit -m "fix: 真实环境验证发现的问题"
```
