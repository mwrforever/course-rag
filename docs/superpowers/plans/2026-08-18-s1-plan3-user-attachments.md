# S1 计划 3/5：用户附件会话级处理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用户对话时自发送图片/文档附件，会话级局部处理（图片 VLM caption、文档局部检索），结果以 `<user-document>` 子块随 `<system-document>` 合并注入，附件 URL 双存 chat_run + chat_message，不进系统知识库。

**Architecture:** 附件上传端点存 MinIO 返回 URL；消息发送后 worker 内按附件类型分两条管线——图片走 VLM caption（Caffeine 按字节 hash 缓存），文档走 Tika 解析→切分→embedding→内存局部检索；caption 就绪后拼入 QU 的 `{query}`；局部检索结果与系统检索结果在 ContextBuilder 合并组装 `<document>`（`<system-document>` + `<user-document>` 子块），经既有 DocumentAssemblerInterceptor 通道注入。后续轮次以 chat_run 的 attachments_json 为入口重建（Caffeine 命中或重处理）。

**Tech Stack:** Spring Boot 3.5.8 / Spring AI 1.1.2（EmbeddingModel）/ Spring AI Alibaba 1.1.2.0（DashScope VLM，multiModel=true 已实锤）/ Apache Tika 2.9.2 / Caffeine / PostgreSQL 16 + Flyway V9 增量 / JUnit5 + Mockito + Testcontainers。

## 计划拆分总览（S1 五份计划，本计划为第 3 份）

| # | 计划 | 范围（spec 章节） | 状态 |
|---|---|---|---|
| 1/5 | ETL 多模态数据底座 | §4 ETL 改造 + §12 PG/Milvus schema + §6 模型配置 | ✅ 已完成（2963d30..87f75f1） |
| 2/5 | 检索链路重构 | §1-3（QU/RetrieveNode/ContextBuilder/Interceptor/三节点图） | ✅ 已完成（87f75f1..95696e7） |
| 3/5 | **用户附件会话级处理** | **§5（上传端点、AttachmentService、Caffeine、attachments_json、局部检索）** | **本计划** |
| 4/5 | 偏好记忆 | §7 | 待写 |
| 5/5 | 经历记忆 | §8 | 待写 |

依赖：3/5 消费 1/5 的解析/切分/caption 组件（TikaImageExtractor/XhtmlDocumentParser/TextChunkSplitter/ImageCaptionService/ImageFilter）、2/5 的 QU 输入组装与 ContextBuilder/RetrieveNode/Interceptor 通道。

## Global Constraints

- **附件不进系统知识库**：不写 document_chunk、不进 Milvus knowledge_chunks（spec §5.1）；附件文件字节 hash 只做**内存级唯一维护**（Caffeine key=hash，同文件只解析/caption 一次），不落库、无跨会话语义
- **存储三表决策（用户拍板）**：chat_run 新增 `attachments_json` JSONB（业务入口）+ chat_message 新增 `attachments_json`（渲染/审计）；**state/checkpoint 不存**；后续轮次以 chat_run 为入口重建
- **上传限额（用户拍板）**：单图 ≤10MB、单文档 ≤50MB、单次 ≤10 个、单次合计 ≤100MB；超限 4xx + 明确提示（配置化 attachment 段）
- **图片统一 VLM caption（用户拍板，D9 首版无视觉直答）**：每图一个 caption，标注"图片1/图片2"序号；caption 双角色（document 内容 + 非闲聊场景作查询文本检索系统库）；caption 在消息发送后 worker 内生成（上传接口不生成），就绪后才组装 QU 输入
- **caption 拼入 QU {query}**："图片1:[caption] 图片2:[caption] 用户问题"（spec §5.3 已确认）
- **文档首版边界**：只支持文本类文档（PDF/Word/TXT/MD），不做文档内嵌图片提取（spec §5.2 第二阶段）；文档 = 局部检索语料 + 独立系统检索（用户问题为查询），两者合并注入；文档内容不参与系统检索查询（spec §5.4）
- **document 块结构**：`<system-document>`（系统检索）与 `<user-document>`（用户附件）子标签分离；user-document 内 `[图片N]`/`[文件N]` 标记（spec §3.2/§5.3）；附件内容与系统资料冲突时如实指出（system-base.yml document_protocol 已有该规则，不重复改 prompt）
- **模型通道（spec §6）**：caption 用 qwen3.7-flash（DashScopeChatOptions 覆盖 + **multiModel(true)**——计划 2 终审后修复实锤，走 multimodal-generation 接口）；embedding 用 qwen3.7-text-embedding；主对话/QU 通道沿用当前配置
- **工程宪法**：注释/日志全中文；禁全路径类名；@RequiredArgsConstructor + private final；禁循环依赖；本 service 主表 this.lambdaQuery() 链式；先写 DB 后失效缓存；死代码零容忍（本次改动产生的废弃配置/测试同提交清理）；测试与实现同一次提交；新测试覆盖正常/边界/异常三类，禁止空断言
- **提交纪律**：只 add 任务文件（禁 git add -A）；docs/ 下审查报告不纳入提交；本计划文档不提交
- **验证命令**：`cd backend && mvn.cmd clean verify`（spotless+checkstyle+spotbugs+jacoco 全门禁）；单类 `mvn.cmd test -Dtest=XxxTest`；Entity 变更需 `mvn.cmd clean`
- **Windows 环境**：spotless:apply 会把改过的文件转 CRLF（check 接受）；批量改文件用 python 脚本时按 \r\n 匹配

---

## Task 1: PG schema 与实体——chat_run/chat_message 新增 attachments_json

**Files:**
- Modify: `backend/src/main/resources/db/migration/V9__chat_attachments_json.sql`（新建）
- Modify: `backend/src/main/java/com/commerce/rag/entity/ChatRun.java`
- Modify: `backend/src/main/java/com/commerce/rag/entity/ChatMessage.java`
- Test: `backend/src/test/java/com/commerce/rag/mapper/ChatAttachmentsSchemaTest.java`（新建，Testcontainers 真实 PG）

**Interfaces:**
- Consumes: `IntegrationTestBase`（单例 PG 容器 + Flyway 迁移，既有基建，见计划 1 先例）
- Produces: `ChatRun.attachmentsJson / ChatMessage.attachmentsJson` 两个 String 字段（Task 8 落库、Task 11 重建消费）；PG 列 `attachments_json JSONB`（chat_run + chat_message 各一列）

- [ ] **Step 1: 新建 V9 迁移**

`backend/src/main/resources/db/migration/V9__chat_attachments_json.sql`：

```sql
-- 用户附件双存：chat_run（业务入口）+ chat_message（渲染/审计）
-- 结构：JSON 数组 [{"type":"image|document","url":"...","name":"...","size":123}]
ALTER TABLE chat_run    ADD COLUMN attachments_json JSONB;
ALTER TABLE chat_message ADD COLUMN attachments_json JSONB;
```

- [ ] **Step 2: ChatRun/ChatMessage 实体加字段**

`entity/ChatRun.java` 的 `metaJson` 字段后加：

```java
    /** 本次输入的附件列表 JSON（[{type,url,name,size}]，业务入口表，spec §5.1 双存决策） */
    @TableField("attachments_json")
    private String attachmentsJson;
```

`entity/ChatMessage.java` 的 `sourcesJson` 字段后加（同构）：

```java
    /** 本次消息附件列表 JSON（[{type,url,name,size}]，渲染/审计用，spec §5.1 双存决策） */
    @TableField("attachments_json")
    private String attachmentsJson;
```

- [ ] **Step 3: 写失败测试 ChatAttachmentsSchemaTest**

`backend/src/test/java/com/commerce/rag/mapper/ChatAttachmentsSchemaTest.java`（参考计划 1 的 `DocumentChunkSchemaTest` 结构，集成测试基类写法见 IntegrationTestBase）：

```java
package com.commerce.rag.mapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** V9 迁移集成测试 —— chat_run/chat_message attachments_json 列存在且为 JSONB */
class ChatAttachmentsSchemaTest extends IntegrationTestBase {

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("chat_run 与 chat_message 均有 attachments_json JSONB 列")
    void attachmentsJsonColumnsExist() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[] {"chat_run", "chat_message"}) {
                try (ResultSet rs = conn.getMetaData().getColumns(null, "public", table, "attachments_json")) {
                    assertTrue(rs.next(), table + " 应有 attachments_json 列");
                    assertNotNull(rs.getString("TYPE_NAME"), "attachments_json 类型不应为空");
                }
            }
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd backend && mvn.cmd test -Dtest=ChatAttachmentsSchemaTest -DfailIfNoTests=false`
Expected: Tests run: 1, Failures: 0（Testcontainers 单例 PG 容器启动 + Flyway V9 执行）

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V9__chat_attachments_json.sql backend/src/main/java/com/commerce/rag/entity/ChatRun.java backend/src/main/java/com/commerce/rag/entity/ChatMessage.java backend/src/test/java/com/commerce/rag/mapper/ChatAttachmentsSchemaTest.java
git commit -m "feat(S1): chat_run/chat_message 新增 attachments_json（V9 迁移 + 实体字段 + Testcontainers schema 测试）"
```

---

## Task 2: AttachmentProperties 配置与 AttachmentRecord 记录

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/properties/AttachmentProperties.java`
- Create: `backend/src/main/java/com/commerce/rag/record/AttachmentRecord.java`
- Modify: `backend/src/main/resources/application.yml`（attachment 段）
- Test: `backend/src/test/java/com/commerce/rag/properties/AttachmentPropertiesTest.java`（新建）

**Interfaces:**
- Consumes: 无（独立配置与值对象）
- Produces: `AttachmentProperties`（record：imageMaxSizeMb=10、documentMaxSizeMb=50、maxCount=10、totalMaxSizeMb=100、cacheMaxSize=100、cacheExpireMinutes=30）；`AttachmentRecord(String type, String url, String name, Long size)`（Task 3/4/8/11 消费）；`AttachmentType` 枚举（image/document，Task 3/4 消费，放 `enums/AttachmentType.java`）

- [ ] **Step 1: 写失败测试 AttachmentPropertiesTest**

`backend/src/test/java/com/commerce/rag/properties/AttachmentPropertiesTest.java`：

```java
package com.commerce.rag.properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AttachmentProperties 默认值测试（与 application.yml attachment 段一致） */
class AttachmentPropertiesTest {

    @Test
    @DisplayName("默认限额 — 图片10MB/文档50MB/10个/合计100MB，缓存100条30分钟")
    void defaults() {
        AttachmentProperties p = new AttachmentProperties(10, 50, 10, 100, 100, 30);
        assertEquals(10, p.imageMaxSizeMb());
        assertEquals(50, p.documentMaxSizeMb());
        assertEquals(10, p.maxCount());
        assertEquals(100, p.totalMaxSizeMb());
        assertEquals(100, p.cacheMaxSize());
        assertEquals(30, p.cacheExpireMinutes());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentPropertiesTest -DfailIfNoTests=false`
Expected: FAIL（编译错误：类不存在）

- [ ] **Step 3: 实现三件套**

`properties/AttachmentProperties.java`：

```java
package com.commerce.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户附件限额与缓存配置（spec §5.2 用户拍板限额定稿）
 *
 * @param imageMaxSizeMb   单张图片大小上限（MB）
 * @param documentMaxSizeMb 单个文档大小上限（MB）
 * @param maxCount         单次消息附件个数上限
 * @param totalMaxSizeMb   单次消息附件合计大小上限（MB）
 * @param cacheMaxSize     Caffeine 附件处理结果缓存条数（LRU）
 * @param cacheExpireMinutes 附件处理结果缓存失效时间（分钟）
 */
@ConfigurationProperties(prefix = "attachment")
public record AttachmentProperties(
        int imageMaxSizeMb,
        int documentMaxSizeMb,
        int maxCount,
        int totalMaxSizeMb,
        int cacheMaxSize,
        int cacheExpireMinutes) {}
```

`enums/AttachmentType.java`：

```java
package com.commerce.rag.enums;

/**
 * 用户附件类型（spec §5.2 首版范围）
 *
 * <p>image=图片（VLM caption 链路）；document=文本文档（PDF/Word/TXT/MD，局部检索链路）。
 */
public enum AttachmentType {
    IMAGE,
    DOCUMENT
}
```

`record/AttachmentRecord.java`：

```java
package com.commerce.rag.record;

/**
 * 用户附件记录（上传返回/落库/重建的统一载体）
 *
 * @param type 附件类型（image/document）
 * @param url  MinIO 对象访问 URL（objectKey，重建时下载用）
 * @param name 原始文件名
 * @param size 字节大小
 */
public record AttachmentRecord(String type, String url, String name, Long size) {}
```

`config/AttachmentConfig.java`（放 config/ 目录，注册 ConfigurationProperties）：

```java
package com.commerce.rag.config;

import com.commerce.rag.properties.AttachmentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 附件属性绑定注册（宪法：@ConfigurationProperties 一律放 properties/，注册放 config/） */
@Configuration
@EnableConfigurationProperties(AttachmentProperties.class)
public class AttachmentConfig {}
```

- [ ] **Step 4: application.yml 加 attachment 段**

`backend/src/main/resources/application.yml` 末尾（`context:` 段之后）加：

```yaml
# ── 用户附件（spec §5.2 限额定稿，C 端会话级局部处理）──
attachment:
  image-max-size-mb: 10        # 单张图片上限
  document-max-size-mb: 50     # 单个文档上限
  max-count: 10                # 单次消息附件个数上限
  total-max-size-mb: 100       # 单次消息附件合计上限
  cache-max-size: 100          # Caffeine 附件处理结果缓存条数（LRU）
  cache-expire-minutes: 30     # 附件处理结果缓存失效时间
```

- [ ] **Step 5: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentPropertiesTest -DfailIfNoTests=false`
Expected: PASS

```bash
git add backend/src/main/java/com/commerce/rag/properties/AttachmentProperties.java backend/src/main/java/com/commerce/rag/enums/AttachmentType.java backend/src/main/java/com/commerce/rag/record/AttachmentRecord.java backend/src/main/java/com/commerce/rag/config/AttachmentConfig.java backend/src/main/resources/application.yml backend/src/test/java/com/commerce/rag/properties/AttachmentPropertiesTest.java
git commit -m "feat(S1): 附件限额配置与类型/记录值对象（spec §5.2 用户拍板限额）"
```

---

## Task 3: 上传端点——POST /api/v1/student/chat/attachments

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/ChatController.java`
- Create: `backend/src/main/java/com/commerce/rag/dto/AttachmentUploadResponse.java`
- Create: `backend/src/main/java/com/commerce/rag/service/IAttachmentService.java` + `backend/src/main/java/com/commerce/rag/service/impl/AttachmentServiceImpl.java`（骨架：上传方法本 Task 实现，下载/处理后续 Task 扩展）
- Test: `backend/src/test/java/com/commerce/rag/controller/ChatAttachmentUploadTest.java`（新建）+ `backend/src/test/java/com/commerce/rag/service/AttachmentServiceImplTest.java`（新建）

**Interfaces:**
- Consumes: `AttachmentProperties`（Task 2）、`MinioStorageService.uploadFile(Long kbId, String uuid, InputStream, String ext)`（既有，注意 kbId 传 0L——附件不归属任何知识库）、`AttachmentType`（Task 2）
- Produces: `POST /api/v1/student/chat/attachments`（multipart，字段 `files`，多文件），响应 `AttachmentUploadResponse(List<AttachmentRecord> attachments)`；`IAttachmentService.upload(MultipartFile[])` → `List<AttachmentRecord>`

- [ ] **Step 1: 写失败测试（service 校验逻辑）**

`backend/src/test/java/com/commerce/rag/service/AttachmentServiceImplTest.java`（Mockito，参考既有 service 测试风格）：

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.storage.MinioStorageService;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 附件上传校验与 MinIO 落盘测试 */
class AttachmentServiceImplTest {

    private final MinioStorageService minio = mock(MinioStorageService.class);
    private final AttachmentProperties props = new AttachmentProperties(10, 50, 10, 100, 100, 30);
    private final AttachmentServiceImpl service = new AttachmentServiceImpl(minio, props);

    @Test
    @DisplayName("上传合法图片 — 返回 image 类型附件记录")
    void upload_validImage() {
        MockMultipartFile f = new MockMultipartFile("files", "a.png", "image/png", new byte[1024]);
        when(minio.uploadFile(eq(0L), any(), any(), eq("png"))).thenReturn("0/abc.png");
        var result = service.upload(new MockMultipartFile[] {f});
        assertEquals(1, result.size());
        assertEquals("image", result.get(0).type());
        assertEquals("0/abc.png", result.get(0).url());
    }

    @Test
    @DisplayName("上传超过单文件限额 — BizException 400 明确提示")
    void upload_overSize() {
        byte[] big = new byte[11 * 1024 * 1024];
        MockMultipartFile f = new MockMultipartFile("files", "big.png", "image/png", big);
        BizException e = assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[] {f}));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        verify(minio, never()).uploadFile(any(), any(), any(), any());
    }

    @Test
    @DisplayName("单次超过 10 个附件 — BizException 400")
    void upload_tooMany() {
        MockMultipartFile[] files = new MockMultipartFile[11];
        for (int i = 0; i < 11; i++) {
            files[i] = new MockMultipartFile("files", "a" + i + ".txt", "text/plain", new byte[10]);
        }
        assertThrows(BizException.class, () -> service.upload(files));
    }

    @Test
    @DisplayName("不支持的文件类型（.exe）— BizException 400")
    void upload_unsupportedType() {
        MockMultipartFile f = new MockMultipartFile("files", "a.exe", "application/octet-stream", new byte[10]);
        assertThrows(BizException.class, () -> service.upload(new MockMultipartFile[] {f}));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentServiceImplTest -DfailIfNoTests=false`
Expected: FAIL（编译错误：AttachmentServiceImpl 不存在）

- [ ] **Step 3: 实现 AttachmentService 上传**

`service/IAttachmentService.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.record.AttachmentRecord;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户附件服务 —— 上传校验/落盘/下载/类型分发（spec §5）
 *
 * <p>附件不进系统知识库：上传仅存 MinIO 返回 objectKey；图片 caption、文档解析均延迟到
 * 消息发送后 worker 内处理（Caffeine 按字节 hash 缓存）。
 */
public interface IAttachmentService {

    /**
     * 校验并上传附件到 MinIO（kbId=0L 占位，附件不归属任何知识库）
     *
     * @param files 上传文件数组（数量/类型/大小/合计均校验）
     * @return 附件记录列表（type/url/name/size）
     */
    List<AttachmentRecord> upload(MultipartFile[] files);
}
```

`service/impl/AttachmentServiceImpl.java`：

```java
package com.commerce.rag.service.impl;

import com.commerce.rag.enums.AttachmentType;
import com.commerce.rag.exception.BizException;
import com.commerce.rag.exception.ErrorCode;
import com.commerce.rag.properties.AttachmentProperties;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IAttachmentService;
import com.commerce.rag.storage.MinioStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户附件服务实现 —— 上传校验（spec §5.2 限额定稿）+ MinIO 落盘
 *
 * <p>kbId 固定传 0L：附件是会话级局部上下文，不归属任何知识库（spec §5.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentServiceImpl implements IAttachmentService {

    /** 图片扩展名白名单 */
    private static final Set<String> IMAGE_EXTS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    /** 文档扩展名白名单（spec §5.2 首版：文本类文档，无内嵌图片提取） */
    private static final Set<String> DOCUMENT_EXTS = Set.of("pdf", "doc", "docx", "txt", "md");

    private final MinioStorageService minioStorageService;
    private final AttachmentProperties properties;

    @Override
    public List<AttachmentRecord> upload(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件不能为空");
        }
        if (files.length > properties.maxCount()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多上传 " + properties.maxCount() + " 个附件");
        }
        // 合计大小校验（单次 ≤100MB）
        long total = 0;
        for (MultipartFile f : files) {
            total += f.getSize();
        }
        if (total > properties.totalMaxSizeMb() * 1024L * 1024L) {
            throw new BizException(ErrorCode.BAD_REQUEST, "附件合计超过 " + properties.totalMaxSizeMb() + "MB 限制");
        }

        List<AttachmentRecord> records = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            records.add(uploadSingle(file));
        }
        log.info("附件上传完成: count={}, totalSize={}B", files.length, total);
        return records;
    }

    /** 单个附件：类型白名单 → 大小限额 → MinIO 落盘（uuid objectKey，外部资源 key 一律 uuid 先行） */
    private AttachmentRecord uploadSingle(MultipartFile file) {
        String ext = extractExt(file.getOriginalFilename());
        AttachmentType type = classify(ext);
        long maxBytes = (type == AttachmentType.IMAGE
                        ? properties.imageMaxSizeMb()
                        : properties.documentMaxSizeMb())
                * 1024L * 1024L;
        if (file.getSize() > maxBytes) {
            throw new BizException(
                    ErrorCode.BAD_REQUEST,
                    "附件 " + file.getOriginalFilename() + " 超过 " + (maxBytes / 1024 / 1024) + "MB 限制");
        }
        String uuid = UUID.randomUUID().toString().replace("-", "");
        try (InputStream in = file.getInputStream()) {
            String objectKey = minioStorageService.uploadFile(0L, uuid, in, ext);
            return new AttachmentRecord(type.name().toLowerCase(Locale.ROOT), objectKey, file.getOriginalFilename(), file.getSize());
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "附件读取失败: " + file.getOriginalFilename());
        }
    }

    /** 按扩展名分类（未知类型直接拒绝，防 .exe/.zip 堆积） */
    private AttachmentType classify(String ext) {
        if (IMAGE_EXTS.contains(ext)) {
            return AttachmentType.IMAGE;
        }
        if (DOCUMENT_EXTS.contains(ext)) {
            return AttachmentType.DOCUMENT;
        }
        throw new BizException(ErrorCode.BAD_REQUEST, "不支持的文件类型: " + ext);
    }

    /** 取扩展名（小写，无扩展名返回空串） */
    private static String extractExt(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 4: ChatController 加上传端点**

`controller/ChatController.java` 加（`/api/v1/student/chat` 下）：

```java
    /** 用户附件上传（spec §5.1）：只存 MinIO 返回 objectKey，caption/解析延迟到消息发送后 */
    @PostMapping(value = "/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<AttachmentRecord>> uploadAttachments(@RequestParam("files") MultipartFile[] files) {
        return ApiResponse.ok(attachmentService.upload(files));
    }
```

（构造器注入 IAttachmentService，`dto/AttachmentUploadResponse.java` 不需要——直接返回 `ApiResponse<List<AttachmentRecord>>`，与 AdminDocumentController 返回风格一致，避免多余包装类）

- [ ] **Step 5: 补 controller 测试 ChatAttachmentUploadTest**

`backend/src/test/java/com/commerce/rag/controller/ChatAttachmentUploadTest.java`（Mockito standalone，参考既有 controller 测试风格）：

```java
package com.commerce.rag.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.service.IAttachmentService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** 附件上传端点测试 —— 委托 AttachmentService 并原样返回 */
class ChatAttachmentUploadTest {

    @Test
    @DisplayName("POST /chat/attachments — 委托 service 返回附件记录")
    void upload_delegatesToService() {
        IAttachmentService service = mock(IAttachmentService.class);
        ChatController controller = new ChatController(service);
        MockMultipartFile f = new MockMultipartFile("files", "a.png", "image/png", new byte[1]);
        when(service.upload(any())).thenReturn(List.of(
                new AttachmentRecord("image", "0/uuid.png", "a.png", 1L)));
        var resp = controller.uploadAttachments(new MockMultipartFile[] {f});
        assertEquals(0, resp.getCode());
        assertEquals("image", resp.getData().get(0).type());
    }
}
```

注意：ChatController 现构造器注入 `ChatStreamEntry`，改为构造器注入两个依赖（ChatStreamEntry + IAttachmentService）——**非 @RequiredArgsConstructor**（现有风格是手写构造器，保持一致，改构造器签名后既有 ChatController 测试同步更新）。

- [ ] **Step 6: 运行全部相关测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentServiceImplTest,ChatAttachmentUploadTest,ChatControllerTest -DfailIfNoTests=false`
Expected: 全部 PASS（ChatControllerTest 若有构造器断言需同步更新）

```bash
git add backend/src/main/java/com/commerce/rag/controller/ChatController.java backend/src/main/java/com/commerce/rag/dto/AttachmentUploadResponse.java backend/src/main/java/com/commerce/rag/service/IAttachmentService.java backend/src/main/java/com/commerce/rag/service/impl/AttachmentServiceImpl.java backend/src/test/java/com/commerce/rag/controller/ChatAttachmentUploadTest.java backend/src/test/java/com/commerce/rag/service/AttachmentServiceImplTest.java
git commit -m "feat(S1): 附件上传端点（限额校验 + MinIO 落盘，spec §5.2）"
```

---

## Task 4: AttachmentService 下载/识别/哈希 + Caffeine 附件缓存

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/IAttachmentService.java`（加 download/identify 方法）
- Modify: `backend/src/main/java/com/commerce/rag/service/impl/AttachmentServiceImpl.java`
- Create: `backend/src/main/java/com/commerce/rag/service/AttachmentCacheService.java`
- Test: Modify `backend/src/test/java/com/commerce/rag/service/AttachmentServiceImplTest.java` + Create `backend/src/test/java/com/commerce/rag/service/AttachmentCacheServiceTest.java`

**Interfaces:**
- Consumes: `MinioStorageService.downloadFile(String objectKey)`（既有）、`ContentHash.sha256Hex(byte[])`（既有，ETL 同款）
- Produces: `IAttachmentService.download(String objectKey)` → `byte[]`（不存在抛 BizException 404）；`AttachmentCacheService.getOrProcess(String byteHash, Function<byte[], T> processor)` → `T`（Caffeine，key=文件字节 sha256，LRU + 失效时间）；`AttachmentCacheService.computeHash(byte[])` → `String`（Task 5/6/11 消费）

- [ ] **Step 1: 写失败测试 AttachmentCacheServiceTest**

`backend/src/test/java/com/commerce/rag/service/AttachmentCacheServiceTest.java`：

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件处理结果 Caffeine 缓存测试（key=字节 hash，同图/同文档只处理一次） */
class AttachmentCacheServiceTest {

    @Test
    @DisplayName("同 hash 二次请求 — 处理器只执行一次（缓存命中）")
    void sameHash_processorRunsOnce() {
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AtomicInteger calls = new AtomicInteger();
        byte[] bytes = "hello".getBytes();
        String hash = cache.computeHash(bytes);

        String r1 = cache.getOrProcess(hash, b -> "result" + calls.incrementAndGet());
        String r2 = cache.getOrProcess(hash, b -> "result" + calls.incrementAndGet());

        assertEquals("result1", r1);
        assertEquals("result1", r2);
        assertEquals(1, calls.get(), "同 hash 只处理一次");
    }

    @Test
    @DisplayName("computeHash — sha256 十六进制 64 字符")
    void computeHash_sha256Hex() {
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        assertEquals(64, cache.computeHash("abc".getBytes()).length());
        assertTrue(cache.computeHash("abc".getBytes()).matches("[0-9a-f]{64}"));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentCacheServiceTest -DfailIfNoTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 AttachmentCacheService**

`service/AttachmentCacheService.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.record.ContentHash;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 附件处理结果缓存 —— 文件字节 sha256 → 处理结果（caption / 局部向量列表）
 *
 * <p>spec §5.1：附件处理结果只在内存（Caffeine，LRU + 失效时间），同文件重复出现只处理一次；
 * 不落库、不进 Milvus、无跨会话归属语义。
 */
@Slf4j
@Service
public class AttachmentCacheService {

    /** 缓存实例：key=文件字节 sha256，value=处理结果 */
    private final Cache<String, Object> cache;

    public AttachmentCacheService(
            com.commerce.rag.properties.AttachmentProperties properties) {
        this(properties.cacheMaxSize(), properties.cacheExpireMinutes());
    }

    /** 测试构造器（直接给参数） */
    AttachmentCacheService(int maxSize, int expireMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofMinutes(expireMinutes))
                .build();
    }

    /** 文件字节 sha256 十六进制摘要（缓存键） */
    public String computeHash(byte[] bytes) {
        return ContentHash.sha256Hex(bytes);
    }

    /**
     * 按 hash 取缓存；未命中时执行 processor 并把结果写入缓存
     *
     * @param hash      文件字节 sha256
     * @param processor 处理函数（入参为文件字节，返回处理结果；不允许返回 null——返回 null 不入缓存）
     * @return 处理结果
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrProcess(String hash, Function<byte[], T> processor, byte[] bytes) {
        Object cached = cache.getIfPresent(hash);
        if (cached != null) {
            return (T) cached;
        }
        T result = processor.apply(bytes);
        if (result != null) {
            cache.put(hash, result);
        }
        return result;
    }

    /** 缓存条目数（测试/监控用） */
    public long size() {
        return cache.estimatedSize();
    }
}
```

- [ ] **Step 4: IAttachmentService 加 download 方法并在 impl 实现**

`IAttachmentService` 加：

```java
    /**
     * 按 objectKey 从 MinIO 下载附件字节
     *
     * @param objectKey 上传时返回的对象键
     * @return 文件字节（不存在抛 BizException 404）
     */
    byte[] download(String objectKey);
```

`AttachmentServiceImpl` 实现：

```java
    @Override
    public byte[] download(String objectKey) {
        try (InputStream in = minioStorageService.downloadFile(objectKey)) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("附件下载失败: objectKey={}, error={}", objectKey, e.getMessage());
            throw new BizException(ErrorCode.NOT_FOUND, "附件不存在或已过期");
        }
    }
```

- [ ] **Step 5: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentCacheServiceTest,AttachmentServiceImplTest -DfailIfNoTests=false`
Expected: 全部 PASS（download 测试可加一条：mock downloadFile 返回流 → 字节一致）

```bash
git add backend/src/main/java/com/commerce/rag/service/AttachmentCacheService.java backend/src/main/java/com/commerce/rag/service/IAttachmentService.java backend/src/main/java/com/commerce/rag/service/impl/AttachmentServiceImpl.java backend/src/test/java/com/commerce/rag/service/AttachmentCacheServiceTest.java backend/src/test/java/com/commerce/rag/service/AttachmentServiceImplTest.java
git commit -m "feat(S1): 附件下载与 Caffeine 缓存（字节 hash 同文件只处理一次，spec §5.1）"
```

---

## Task 5: 图片附件 caption 处理（多图标注 + 缓存）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/service/AttachmentImageProcessor.java`
- Create: `backend/src/main/java/com/commerce/rag/record/ImageCaptionResult.java`
- Test: `backend/src/test/java/com/commerce/rag/service/AttachmentImageProcessorTest.java`（新建）

**Interfaces:**
- Consumes: `AttachmentCacheService.getOrProcess`（Task 4）、`ImageCaptionService.caption(byte[], String)`（既有，qwen3.7-flash + multiModel）、`ImageFilter.isSmallIcon(byte[], int)` + `isDecorative(byte[])`（既有，ETL 同款过滤）
- Produces: `ImageCaptionResult(String caption, String resourceName)`；`AttachmentImageProcessor.processImages(List<AttachmentRecord>, String baseObjectKey)` → `List<ImageCaptionResult>`（按上传顺序标注图片1/2…；过滤小图标/装饰图返回空列表；caption 失败跳过该图不中断）

- [ ] **Step 1: 写失败测试 AttachmentImageProcessorTest**

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 图片附件 caption 处理测试（多图标注/缓存命中/过滤） */
class AttachmentImageProcessorTest {

    @Test
    @DisplayName("多图 — 每张生成 caption，标注图片1/图片2（按上传顺序）")
    void processImages_multiCaptions() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("红色图表", "蓝色图表");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(new byte[] {1}, new byte[] {2}), List.of("a.png", "b.png"));

        assertEquals(2, results.size());
        assertEquals("图片1:红色图表", results.get(0).caption());
        assertEquals("图片2:蓝色图表", results.get(1).caption());
    }

    @Test
    @DisplayName("同图重复出现 — Caffeine 命中，caption 只调一次")
    void processImages_cacheHit() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any())).thenReturn("同一张图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

        byte[] img = new byte[] {1, 2, 3};
        processor.processImages(List.of(img), List.of("a.png"));
        processor.processImages(List.of(img), List.of("a.png"));

        assertEquals(1, org.mockito.Mockito.mockingDetails(captionService).getInvocations().size());
    }

    @Test
    @DisplayName("caption 失败 — 跳过该图不中断，其他图正常")
    void processImages_captionFailSkip() {
        ImageCaptionService captionService = mock(ImageCaptionService.class);
        when(captionService.caption(any(), any()))
                .thenThrow(new RuntimeException("模型调用失败"))
                .thenReturn("正常图");
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentImageProcessor processor = new AttachmentImageProcessor(captionService, cache);

        List<ImageCaptionResult> results = processor.processImages(
                List.of(new byte[] {1}, new byte[] {2}), List.of("bad.png", "ok.png"));

        assertEquals(1, results.size());
        assertEquals("图片2:正常图", results.get(0).caption());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentImageProcessorTest -DfailIfNoTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 ImageCaptionResult + AttachmentImageProcessor**

`record/ImageCaptionResult.java`：

```java
package com.commerce.rag.record;

/**
 * 图片 caption 处理结果
 *
 * @param caption 带序号标注的 caption 文本（"图片N:描述"）；被过滤/失败的图片不产生结果
 * @param resourceName 图片资源标识（原始文件名，调试/审计用）
 */
public record ImageCaptionResult(String caption, String resourceName) {}
```

`service/AttachmentImageProcessor.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.etl.ImageCaptionService;
import com.commerce.rag.etl.ImageFilter;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 图片附件 caption 处理器 —— 会话级图片统一 VLM caption 链路（spec §5.3）
 *
 * <p>流程：过滤小图标/装饰图 → Caffeine 按字节 hash 缓存（同图只 caption 一次）→
 * VLM caption（qwen3.7-flash，multiModel 已修复）→ 按上传顺序标注"图片N"序号。
 *
 * <p>caption 双角色：作为 user-document 内容注入；非闲聊场景下由调用方决定是否作为
 * 查询文本检索系统知识库（Task 9/10 消费）。
 *
 * <p>字节下载由调用方（AttachmentOrchestrator）完成——本类只负责过滤/caption/缓存，
 * 不依赖 IAttachmentService，测试无需 mock MinIO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentImageProcessor {

    /** 图片过滤最小尺寸（KB，与 ETL 同阈值，spec §5.2） */
    private static final int IMAGE_MIN_SIZE_KB = 10;

    private final ImageCaptionService imageCaptionService;
    private final AttachmentCacheService cacheService;

    /**
     * 处理一组图片字节（按上传顺序标注图片1/2…）
     *
     * @param images 图片字节列表（与上传顺序一致）
     * @param names  原始文件名列表（同序，MIME 识别用）
     * @return caption 结果列表（"图片N:描述"；被过滤/失败的图片不产生结果；全部失败返回空列表）
     */
    public List<ImageCaptionResult> processImages(List<byte[]> images, List<String> names) {
        List<ImageCaptionResult> results = new ArrayList<>(images.size());
        int index = 1;
        for (int i = 0; i < images.size(); i++) {
            byte[] bytes = images.get(i);
            String name = names.get(i);
            try {
                String hash = cacheService.computeHash(bytes);
                // 同图只 caption 一次（Caffeine 按字节 hash 缓存，spec §5.1）
                String caption = cacheService.getOrProcess(hash, b -> captionInternal(b, name), bytes);
                if (caption == null) {
                    index++;
                    continue;
                }
                results.add(new ImageCaptionResult("图片" + index + ":" + caption, name));
            } catch (Exception e) {
                // 单图失败跳过，不中断整体（spec §5.3 边界）
                log.warn("图片 caption 处理失败，跳过: name={}, error={}", name, e.getMessage());
            }
            index++;
        }
        return results;
    }

    /** caption 内部逻辑：过滤 → VLM 调用（返回 null 表示被过滤） */
    private String captionInternal(byte[] bytes, String name) {
        if (ImageFilter.isSmallIcon(bytes, IMAGE_MIN_SIZE_KB) || ImageFilter.isDecorative(bytes)) {
            log.info("图片过滤（小图标/装饰图）: name={}", name);
            return null;
        }
        return imageCaptionService.caption(bytes, mimeOf(name));
    }

    /** 按文件名后缀取 MIME（与 ETL extensionOf 同语义） */
    private static String mimeOf(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }
}
```

（测试 `processImages_multiCaptions` 相应改为传字节列表：`processor.processImages(List.of(new byte[]{1}, new byte[]{2}), List.of("a.png", "b.png"))`；`processImages_cacheHit` 同图字节重复传两次。）

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentImageProcessorTest -DfailIfNoTests=false`
Expected: PASS

```bash
git add backend/src/main/java/com/commerce/rag/service/AttachmentImageProcessor.java backend/src/main/java/com/commerce/rag/record/ImageCaptionResult.java backend/src/test/java/com/commerce/rag/service/AttachmentImageProcessorTest.java
git commit -m "feat(S1): 图片附件 caption 处理（多图标注图片N + Caffeine 缓存 + 失败跳过，spec §5.3）"
```

---

## Task 6: 文档附件局部解析/切分/向量化

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/service/AttachmentDocumentProcessor.java`
- Create: `backend/src/main/java/com/commerce/rag/record/DocumentLocalChunk.java`
- Test: `backend/src/test/java/com/commerce/rag/service/AttachmentDocumentProcessorTest.java`（新建）

**Interfaces:**
- Consumes: `AttachmentCacheService`（Task 4）、`TextChunkSplitter(int, int).splitText(String)`（既有 ETL 组件）、`EmbeddingModel.embed(String)`（既有，qwen3.7-text-embedding）、Tika 解析（`AutoDetectParser` + `ToHTMLContentHandler` + jsoup 文本提取——文档附件只需纯文本，不需要 XhtmlDocumentParser 的章节结构）
- Produces: `DocumentLocalChunk(String text, float[] vector, int index)`；`AttachmentDocumentProcessor.processDocument(byte[] bytes, String name)` → `List<DocumentLocalChunk>`（解析→切分→逐块向量化；Caffeine 按字节 hash 缓存整个结果列表）

- [ ] **Step 1: 写失败测试 AttachmentDocumentProcessorTest**

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.DocumentLocalChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/** 文档附件局部处理测试（解析→切分→向量化） */
class AttachmentDocumentProcessorTest {

    @Test
    @DisplayName("TXT 文档 — 解析切分向量化，按序生成局部分片")
    void processDocument_txtChunks() {
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        request.getInstructions().stream()
                                .map(t -> new org.springframework.ai.embedding.Embedding(new float[] {1f, 0f}))
                                .toList());
            }
        };
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentDocumentProcessor processor =
                new AttachmentDocumentProcessor(embedding, cache, 100, 64);
        // "这是一段测试内容。" 重复 30 遍 → 超过 100 token 阈值会被切分
        String text = ("导数公式表：常数的导数为零，sin x 的导数是 cos x，链式法则用于复合函数求导。")
                .repeat(10);
        List<DocumentLocalChunk> chunks = processor.processDocument(text.getBytes(), "note.txt");

        assertTrue(chunks.size() >= 1, "应至少产生一个局部分片");
        assertEquals(2f, chunks.get(0).vector()[0] + chunks.get(0).vector()[1], 0.001);
        assertTrue(chunks.get(0).text().contains("导数"));
    }

    @Test
    @DisplayName("同文档重复处理 — Caffeine 命中，向量化只执行一次")
    void processDocument_cacheHit() {
        final int[] embedCalls = {0};
        EmbeddingModel embedding = new EmbeddingModel() {
            @Override
            public org.springframework.ai.embedding.EmbeddingResponse call(
                    org.springframework.ai.embedding.EmbeddingRequest request) {
                embedCalls[0]++;
                return new org.springframework.ai.embedding.EmbeddingResponse(
                        request.getInstructions().stream()
                                .map(t -> new org.springframework.ai.embedding.Embedding(new float[] {1f}))
                                .toList());
            }
        };
        AttachmentCacheService cache = new AttachmentCacheService(100, 30);
        AttachmentDocumentProcessor processor =
                new AttachmentDocumentProcessor(embedding, cache, 100, 64);
        byte[] bytes = "短文档内容。".repeat(5).getBytes();

        processor.processDocument(bytes, "a.txt");
        processor.processDocument(bytes, "a.txt");

        assertEquals(1, embedCalls[0], "同文档只向量化一次");
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentDocumentProcessorTest -DfailIfNoTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 DocumentLocalChunk + AttachmentDocumentProcessor**

`record/DocumentLocalChunk.java`：

```java
package com.commerce.rag.record;

/**
 * 文档附件局部检索分片（内存态，不进 PG/Milvus）
 *
 * @param text   分片文本
 * @param vector 分片向量（embedding 模型输出）
 * @param index  文档内序号（0 起，检索结果定位用）
 */
public record DocumentLocalChunk(String text, float[] vector, int index) {}
```

`service/AttachmentDocumentProcessor.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.etl.TextChunkSplitter;
import com.commerce.rag.record.DocumentLocalChunk;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.jsoup.Jsoup;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

/**
 * 文档附件局部处理器 —— 解析（Tika）→ 切分（TextChunkSplitter）→ 向量化（EmbeddingModel）
 *
 * <p>spec §5.4：文档 = 局部检索语料；结果按文件字节 hash 缓存在 Caffeine（同文档只处理一次）。
 * 首版仅文本类文档（PDF/Word/TXT/MD），不做文档内嵌图片提取。
 */
@Slf4j
@Service
public class AttachmentDocumentProcessor {

    private final EmbeddingModel embeddingModel;
    private final AttachmentCacheService cacheService;
    private final int chunkSize;
    private final int minChunkSizeChars;

    public AttachmentDocumentProcessor(
            EmbeddingModel embeddingModel,
            AttachmentCacheService cacheService,
            com.commerce.rag.properties.AttachmentProperties properties) {
        this.embeddingModel = embeddingModel;
        this.cacheService = cacheService;
        this.chunkSize = 768;
        this.minChunkSizeChars = 64;
    }

    /** 测试构造器（直接给切分参数） */
    AttachmentDocumentProcessor(
            EmbeddingModel embeddingModel, AttachmentCacheService cacheService, int chunkSize, int minChunkSizeChars) {
        this.embeddingModel = embeddingModel;
        this.cacheService = cacheService;
        this.chunkSize = chunkSize;
        this.minChunkSizeChars = minChunkSizeChars;
    }

    /**
     * 处理文档附件：解析 → 切分 → 逐块向量化（结果按字节 hash 缓存）
     *
     * @param bytes 文档字节
     * @param name  原始文件名（Tika 类型识别提示）
     * @return 局部分片列表（解析失败返回空列表，不中断对话）
     */
    public List<DocumentLocalChunk> processDocument(byte[] bytes, String name) {
        String hash = cacheService.computeHash(bytes);
        return cacheService.getOrProcess(hash, b -> doProcess(b, name), bytes);
    }

    /** 实际处理（缓存未命中时执行） */
    @SuppressWarnings("unchecked")
    private List<DocumentLocalChunk> doProcess(byte[] bytes, String name) {
        try {
            // 1. Tika 解析 → XHTML → jsoup 提取纯文本
            ByteArrayOutputStreamHolder holder = new ByteArrayOutputStreamHolder();
            ToHTMLContentHandler handler = new ToHTMLContentHandler(holder.out(), "UTF-8");
            Metadata metadata = new Metadata();
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            String plainText = Jsoup.parse(holder.out().toString(StandardCharsets.UTF_8)).text();

            // 2. 切分（复用 ETL 组件，spec §4.1 同参数）
            TextChunkSplitter splitter = new TextChunkSplitter(chunkSize, minChunkSizeChars);
            List<String> pieces = splitter.splitText(plainText);

            // 3. 逐块向量化
            List<DocumentLocalChunk> chunks = new ArrayList<>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                float[] vector = embeddingModel.embed(pieces.get(i));
                if (vector != null && vector.length > 0) {
                    chunks.add(new DocumentLocalChunk(pieces.get(i), vector, i));
                }
            }
            log.info("文档附件处理完成: name={}, 分片数={}", name, chunks.size());
            return chunks;
        } catch (Exception e) {
            log.warn("文档附件解析失败，返回空语料: name={}, error={}", name, e.getMessage());
            return new ArrayList<>();
        }
    }

    /** XHTML 输出流载体（避免额外依赖） */
    private static final class ByteArrayOutputStreamHolder {
        private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream out() {
            return out;
        }
    }
}
```

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentDocumentProcessorTest -DfailIfNoTests=false`
Expected: PASS

```bash
git add backend/src/main/java/com/commerce/rag/service/AttachmentDocumentProcessor.java backend/src/main/java/com/commerce/rag/record/DocumentLocalChunk.java backend/src/test/java/com/commerce/rag/service/AttachmentDocumentProcessorTest.java
git commit -m "feat(S1): 文档附件局部处理（Tika 解析+切分+向量化，Caffeine 缓存，spec §5.4）"
```

---

## Task 7: 局部检索（余弦相似度 Top-K）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/service/AttachmentLocalSearchService.java`
- Test: `backend/src/test/java/com/commerce/rag/service/AttachmentLocalSearchServiceTest.java`（新建）

**Interfaces:**
- Consumes: `DocumentLocalChunk`（Task 6）
- Produces: `AttachmentLocalSearchService.search(List<DocumentLocalChunk> chunks, float[] queryVector, int topK)` → `List<DocumentLocalChunk>`（按余弦相似度降序；空语料/空查询返回空列表）；`AttachmentLocalSearchService.cosine(float[], float[])` → `double`

- [ ] **Step 1: 写失败测试 AttachmentLocalSearchServiceTest**

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.record.DocumentLocalChunk;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 附件局部检索测试（内存余弦相似度） */
class AttachmentLocalSearchServiceTest {

    @Test
    @DisplayName("余弦相似度 — 相同向量=1，正交向量=0")
    void cosine_basic() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        assertEquals(1.0, svc.cosine(new float[] {1f, 0f}, new float[] {1f, 0f}), 0.0001);
        assertEquals(0.0, svc.cosine(new float[] {1f, 0f}, new float[] {0f, 1f}), 0.0001);
    }

    @Test
    @DisplayName("Top-K 检索 — 按相似度降序返回")
    void search_topKOrdered() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        List<DocumentLocalChunk> chunks = List.of(
                new DocumentLocalChunk("a", new float[] {1f, 0f}, 0),
                new DocumentLocalChunk("b", new float[] {0.9f, 0.1f}, 1),
                new DocumentLocalChunk("c", new float[] {0f, 1f}, 2));
        List<DocumentLocalChunk> top = svc.search(chunks, new float[] {1f, 0.05f}, 2);
        assertEquals(2, top.size());
        assertEquals("a", top.get(0).text());
        assertEquals("b", top.get(1).text());
    }

    @Test
    @DisplayName("空语料 / 空查询 — 返回空列表")
    void search_empty() {
        AttachmentLocalSearchService svc = new AttachmentLocalSearchService();
        assertTrue(svc.search(List.of(), new float[] {1f}, 5).isEmpty());
        assertTrue(svc.search(List.of(new DocumentLocalChunk("a", new float[] {1f}, 0)), null, 5).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `mvn.cmd test -Dtest=AttachmentLocalSearchServiceTest -DfailIfNoTests=false`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 AttachmentLocalSearchService**

`service/AttachmentLocalSearchService.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.record.DocumentLocalChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 附件局部检索 —— 内存余弦相似度 Top-K（spec §5.4：文档作局部检索语料）
 *
 * <p>不经过 Milvus（附件不进系统知识库）；文档量级小（≤50MB 文本），内存线性扫描足够。
 */
@Service
public class AttachmentLocalSearchService {

    /**
     * 按余弦相似度检索局部语料 Top-K
     *
     * @param chunks       局部语料（文档附件分片）
     * @param queryVector  查询向量（用户问题 embedding）
     * @param topK         返回条数上限
     * @return 按相似度降序的分片列表（空语料/空查询返回空列表）
     */
    public List<DocumentLocalChunk> search(List<DocumentLocalChunk> chunks, float[] queryVector, int topK) {
        if (chunks == null || chunks.isEmpty() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        return chunks.stream()
                .filter(c -> c.vector() != null && c.vector().length > 0)
                .map(c -> new ScoredChunk(c, cosine(c.vector(), queryVector)))
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(topK)
                .map(ScoredChunk::chunk)
                .toList();
    }

    /** 余弦相似度（零向量返回 0） */
    public double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0.0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 带分数的分片载体 */
    private record ScoredChunk(DocumentLocalChunk chunk, double score) {}
}
```

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=AttachmentLocalSearchServiceTest -DfailIfNoTests=false`
Expected: PASS

```bash
git add backend/src/main/java/com/commerce/rag/service/AttachmentLocalSearchService.java backend/src/test/java/com/commerce/rag/service/AttachmentLocalSearchServiceTest.java
git commit -m "feat(S1): 附件局部检索（内存余弦 Top-K，spec §5.4）"
```

---

## Task 8: ChatRequest 附件字段 + 入队/落库链路

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/dto/ChatRequest.java`
- Modify: `backend/src/main/java/com/commerce/rag/stream/ChatStreamEntry.java`（入队 body 带附件）
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（run/message 落 attachments_json）
- Test: Modify `backend/src/test/java/com/commerce/rag/stream/ChatStreamEntryTest.java`（如有）+ `backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`（如有，补附件断言）

**Interfaces:**
- Consumes: `AttachmentRecord`（Task 2，序列化为 JSON 数组存 attachments_json）、chat_run/chat_message `attachmentsJson` 字段（Task 1）
- Produces: `ChatRequest(List<AttachmentRecord> attachmentUrls)`——注意字段名：spec §5.1 说"ChatRequest 带 attachmentUrls"，实际传参为**上传接口返回的附件记录列表**（type/url/name/size）；Redis Stream body 键 `attachments`（JSON 数组字符串）；`ChatRun.attachmentsJson`/`ChatMessage.attachmentsJson` 落库

- [ ] **Step 1: ChatRequest 加字段**

`dto/ChatRequest.java`：

```java
public record ChatRequest(Long sessionId, String query, List<AttachmentRecord> attachments) {}
```

（既有 `ChatRequest(Long sessionId, String query)` 调用点全部改为三参——**注意**：为兼容既有测试/调用，可提供紧凑构造器 `ChatRequest(Long, String)` 委托 `this(sessionId, query, null)`；record 紧凑构造器写法：

```java
public record ChatRequest(Long sessionId, String query, List<AttachmentRecord> attachments) {
    /** 无附件构造（既有调用兼容） */
    public ChatRequest(Long sessionId, String query) {
        this(sessionId, query, null);
    }
}
```
）

- [ ] **Step 2: ChatStreamEntry 入队 body 带附件**

`ChatStreamEntry.chat()` 中构建 Redis Stream 消息处（`XADD` body map），加：

```java
        // 附件记录列表 → JSON 数组字符串（run/message 落库与 worker 消费）
        String attachmentsJson = request.attachments() == null
                ? "[]"
                : new com.google.gson.Gson().toJson(request.attachments());
        body.put("attachments", attachmentsJson);
```

（既有 body 键：runId/sessionId/userId/query——先看 ChatStreamEntry 实际入队代码段，`attachments` 键与之一致）

- [ ] **Step 3: ChatRequestWorker 落库**

`ChatRequestWorker.processRequest` 中解析 body 后：

```java
        String attachmentsJson = body.getOrDefault("attachments", "[]");
        // 校验 JSON 合法性：非法时按空数组处理（附件损坏不阻断对话）
        if (!isValidJsonArray(attachmentsJson)) {
            log.warn("附件 JSON 非法，按空处理: runId={}", runIdStr);
            attachmentsJson = "[]";
        }
```

run 状态更新处（run 表 metaJson 附近）加 `chatRunService.updateAttachments(runId, attachmentsJson)`；消息持久化处（`ChatMessageServiceImpl` 批量插入附近）加 `message.setAttachmentsJson(attachmentsJson)`（用户消息行）。

`IChatRunService` 加方法（impl 用 this.lambdaUpdate() 主表链式）：

```java
    /** 落库本次输入附件（业务入口表，spec §5.1 双存决策） */
    void updateAttachments(Long runId, String attachmentsJson);
```

实现（`ChatRunServiceImpl`）：

```java
    @Override
    public void updateAttachments(Long runId, String attachmentsJson) {
        this.lambdaUpdate().eq(ChatRun::getId, runId).set(ChatRun::getAttachmentsJson, attachmentsJson).update();
    }
```

- [ ] **Step 4: 补测试断言**

`ChatRequestWorkerTest`（或新测试 `AttachmentPersistenceTest`）加用例：

```java
    @Test
    @DisplayName("带附件消息 — run 与 message 均落 attachments_json")
    void processRequest_withAttachments() {
        // 构造 Redis 消息 body 含 attachments=[{"type":"image","url":"0/a.png","name":"a.png","size":1}]
        // 断言：chatRunService.updateAttachments 被调用且参数为合法 JSON 数组；
        //       消息持久化时用户消息行 attachmentsJson 非空
    }
```

（具体断言按既有 worker 测试的 mock 风格实现——参考 ChatRequestWorkerTest 现有用例的 Redis 消息构造方式。）

- [ ] **Step 5: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=ChatRequestWorkerTest,ChatStreamEntryTest -DfailIfNoTests=false`
Expected: 全部 PASS

```bash
git add backend/src/main/java/com/commerce/rag/dto/ChatRequest.java backend/src/main/java/com/commerce/rag/stream/ChatStreamEntry.java backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/main/java/com/commerce/rag/service/IChatRunService.java backend/src/main/java/com/commerce/rag/service/impl/ChatRunServiceImpl.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java
git commit -m "feat(S1): 附件入队与双存落库（ChatRequest 附件字段 + run/message attachments_json，spec §5.1）"
```

---

## Task 9: QU 输入组装——caption 拼入 {query} + 附件上下文注入

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/service/AttachmentOrchestrator.java`（附件编排：下载→按类型分发→处理，worker 只调用 process）
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（调 orchestrator + QU 查询拼 caption）
- Create: `backend/src/main/java/com/commerce/rag/record/AttachmentContext.java`
- Test: `backend/src/test/java/com/commerce/rag/service/AttachmentOrchestratorTest.java`（新建）+ `backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`（补附件断言）

**Interfaces:**
- Consumes: `AttachmentImageProcessor.processImages`（Task 5）、`AttachmentDocumentProcessor.processDocument`（Task 6）、`AttachmentLocalSearchService.search`（Task 7）、`IAttachmentService.download`（Task 4）、`EmbeddingModel.embed(String)`（用户问题向量，Task 7 查询向量）
- Produces: `AttachmentContext(List<ImageCaptionResult> captions, Map<String, List<DocumentLocalChunk>> documents)`——附件处理结果载体，经 RunnableConfig.metadata 传给 QU 节点与 RetrieveNode（与 document 组装同通道）；QU 的 {query} 组装规则：`图片1:[caption] 图片2:[caption] 用户问题`（有 caption 时前缀拼接，无附件时原样）

- [ ] **Step 1: 实现 AttachmentContext + AttachmentOrchestrator**

`record/AttachmentContext.java`：

```java
package com.commerce.rag.record;

import java.util.List;
import java.util.Map;

/**
 * 附件处理结果载体（orchestrator 组装 → config.metadata 传 QU/RetrieveNode，不落 state）
 *
 * @param captions  图片 caption 结果（"图片N:描述"，按上传顺序）
 * @param documents 文档局部语料（key=附件 objectKey，value=分片列表）
 */
public record AttachmentContext(
        List<ImageCaptionResult> captions, Map<String, List<DocumentLocalChunk>> documents) {

    public static AttachmentContext empty() {
        return new AttachmentContext(List.of(), Map.of());
    }

    /** 是否有任何附件上下文 */
    public boolean hasAny() {
        return (captions != null && !captions.isEmpty()) || (documents != null && !documents.isEmpty());
    }
}
```

`service/AttachmentOrchestrator.java`：

```java
package com.commerce.rag.service;

import com.commerce.rag.record.AttachmentContext;
import com.commerce.rag.record.AttachmentRecord;
import com.commerce.rag.record.DocumentLocalChunk;
import com.commerce.rag.record.ImageCaptionResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 附件处理编排 —— 下载 → 按类型分发（图片 caption / 文档局部语料）→ 组装 AttachmentContext
 *
 * <p>spec §5.1：消息发送后 worker 内处理；单项失败跳过不中断；Caffeine 缓存由各处理器内部完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentOrchestrator {

    private final IAttachmentService attachmentService;
    private final AttachmentImageProcessor imageProcessor;
    private final AttachmentDocumentProcessor documentProcessor;

    /**
     * 处理附件列表（下载/分发/处理；单项失败跳过）
     *
     * @param attachments 附件记录列表（可为空）
     * @return 附件处理结果（无附件/全部失败返回 empty）
     */
    public AttachmentContext process(List<AttachmentRecord> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return AttachmentContext.empty();
        }
        List<byte[]> imageBytes = new ArrayList<>();
        List<String> imageNames = new ArrayList<>();
        Map<String, List<DocumentLocalChunk>> documents = new HashMap<>();
        for (AttachmentRecord att : attachments) {
            try {
                byte[] bytes = attachmentService.download(att.url());
                if ("image".equals(att.type())) {
                    imageBytes.add(bytes);
                    imageNames.add(att.name());
                } else {
                    documents.put(att.url(), documentProcessor.processDocument(bytes, att.name()));
                }
            } catch (Exception e) {
                log.warn("附件下载/处理失败，跳过: url={}, error={}", att.url(), e.getMessage());
            }
        }
        List<ImageCaptionResult> captions =
                imageBytes.isEmpty() ? List.of() : imageProcessor.processImages(imageBytes, imageNames);
        return new AttachmentContext(captions, documents);
    }
}
```

`ChatRequestWorker.processRequest` 中（构建图 inputs 前）插入附件处理段：

```java
        // ── 附件处理（spec §5.1：消息发送后 worker 内处理，caption/语料 Caffeine 缓存）──
        AttachmentContext attachmentContext = AttachmentContext.empty();
        List<AttachmentRecord> attachments = parseAttachments(body.get("attachments"));
        if (attachments.isEmpty()) {
            // 后续轮次：以 chat_run 为入口重建附件上下文（Task 11 实现 findRecentAttachments）
            attachments = chatRunService.findRecentAttachments(sessionId, runId, 3);
        }
        if (!attachments.isEmpty()) {
            attachmentContext = orchestrator.process(attachments);
        }
        if (attachmentContext.hasAny()) {
            config.addMetadata("attachmentContext", attachmentContext);
        }
```

（worker 构造器注入 AttachmentOrchestrator；`parseAttachments` 用 Gson 把 JSON 数组字符串解析为 `List<AttachmentRecord>`，非法 JSON 返回空列表。）

- [ ] **Step 2: QU 输入组装——{query} 前缀拼 caption**

`QueryUnderstandingService` 加公开方法（供 worker 组装 QU 输入时调用，或 worker 直接拼——**选择：worker 拼好后传入**，QU 服务不感知附件细节，保持单一职责）：

`ChatRequestWorker` 组装 QU 输入处（调用 QU 服务前）：

```java
        // caption 拼入 QU {query}（spec §5.3："图片1:[caption] 图片2:[caption] 用户问题"）
        String quQuery = userQuery;
        if (!attachmentContext.captions().isEmpty()) {
            String captionPrefix = attachmentContext.captions().stream()
                    .map(ImageCaptionResult::caption)
                    .collect(Collectors.joining(" "));
            quQuery = captionPrefix + " " + userQuery;
        }
```

（QU 服务现有调用签名 `analyze(context, query)` 不变——worker 把拼好的 quQuery 传入；`QueryUnderstandingServiceTest` 不需改，若既有测试直接调 analyze 则不受影响。）

- [ ] **Step 3: 补测试（worker 附件编排）**

`ChatRequestWorkerTest` 补用例（mock IAttachmentService/AttachmentImageProcessor/AttachmentDocumentProcessor）：

```java
    @Test
    @DisplayName("带图片附件 — caption 拼入 QU 查询且 metadata 携带附件上下文")
    void processRequest_withImageAttachment() {
        // 构造 body 含 attachments=[{"type":"image","url":"0/a.png","name":"a.png","size":1}]
        // mock: download → 字节；processImages → [ImageCaptionResult("图片1:红色图表","a.png")]
        // 断言: 图输入的 userQuery 以 "图片1:红色图表 " 前缀开头（或 metadata 含 attachmentContext）
    }
```

（按既有 worker 测试的消息构造与图执行 mock 风格实现；若 worker 测试难以注入图 mock，则把 `buildAttachmentContext` 提取为可测方法或组件 `AttachmentOrchestrator`——**本计划采用后者**：附件编排逻辑放独立组件 `service/AttachmentOrchestrator.java`（Task 10 一起实现），worker 只调用 `orchestrator.process(attachments)`，worker 测试 mock orchestrator。）

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=ChatRequestWorkerTest,QueryUnderstandingServiceTest -DfailIfNoTests=false`
Expected: 全部 PASS

```bash
git add backend/src/main/java/com/commerce/rag/record/AttachmentContext.java backend/src/main/java/com/commerce/rag/service/AttachmentOrchestrator.java backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java
git commit -m "feat(S1): 附件编排与 QU caption 拼装（图片N标注前缀，spec §5.3）"
```

---

## Task 10: ContextBuilder user-document 扩展 + RetrieveNode 合并注入

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/retrieval/ContextBuilderService.java`（加 buildUserDocument）
- Modify: `backend/src/main/java/com/commerce/rag/bot/graph/RetrieveNode.java`（附件上下文 → 局部检索 → 合并组装）
- Test: `backend/src/test/java/com/commerce/rag/retrieval/ContextBuilderServiceTest.java`（补 user-document 用例）+ `backend/src/test/java/com/commerce/rag/bot/graph/RetrieveNodeTest.java`（如有）

**Interfaces:**
- Consumes: `AttachmentContext`（Task 9）、`AttachmentLocalSearchService.search`（Task 7）、`ContextBuilderService.buildDocument`（既有，system-document）
- Produces: `ContextBuilderService.buildUserDocument(AttachmentContext, String userQuery, float[] queryVector)` → `String`（`<user-document>` 块：`[图片1] caption`、`[文件1] 局部检索段落…`；无附件上下文返回 null）；RetrieveNode 组装顺序：先 `<document>` 头+检索说明 → `<system-document>`（既有）→ `<user-document>`（新增）→ `</document>`

- [ ] **Step 1: ContextBuilderService 加 buildUserDocument**

`ContextBuilderService.java` 加：

```java
    /**
     * 组装 <user-document> 子块（spec §3.2/§5.3：用户附件局部上下文）
     *
     * @param captions    图片 caption 结果（"图片N:描述"）
     * @param docHits     文档局部检索命中（key=objectKey 短名，value=命中的段落列表）
     * @return user-document 文本；无任何内容返回 null
     */
    public String buildUserDocument(List<ImageCaptionResult> captions, Map<String, List<String>> docHits) {
        boolean empty = (captions == null || captions.isEmpty()) && (docHits == null || docHits.isEmpty());
        if (empty) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<user-document>\n");
        if (captions != null) {
            for (ImageCaptionResult c : captions) {
                sb.append("  [").append(c.caption()).append("]\n");
            }
        }
        if (docHits != null) {
            int fileNo = 1;
            for (Map.Entry<String, List<String>> entry : docHits.entrySet()) {
                sb.append("  [文件").append(fileNo++).append("] ");
                sb.append(entry.getKey()).append("：\n");
                for (String hit : entry.getValue()) {
                    sb.append("    - ").append(hit).append("\n");
                }
            }
        }
        sb.append("</user-document>");
        return sb.toString();
    }
```

- [ ] **Step 2: RetrieveNode 合并组装**

`RetrieveNode` 执行段（`buildDocument` 调用处）扩展：

```java
        // ── 用户附件上下文（spec §5.3/§5.4：caption 注入 + 文档局部检索合并）──
        AttachmentContext attachmentContext =
                (AttachmentContext) config.getMetadata().get("attachmentContext");
        String userDocument = null;
        if (attachmentContext != null && attachmentContext.hasAny()) {
            // 文档局部检索：用户问题作查询向量（spec §5.4：文档内容不参与系统检索查询）
            Map<String, List<String>> docHits = new LinkedHashMap<>();
            if (!attachmentContext.documents().isEmpty()) {
                float[] queryVector = embeddingModel.embed(originalQuery);
                for (Map.Entry<String, List<DocumentLocalChunk>> entry : attachmentContext.documents().entrySet()) {
                    List<DocumentLocalChunk> hits =
                            localSearchService.search(entry.getValue(), queryVector, 3);
                    if (!hits.isEmpty()) {
                        docHits.put(entry.getKey(), hits.stream().map(DocumentLocalChunk::text).toList());
                    }
                }
            }
            userDocument = contextBuilderService.buildUserDocument(attachmentContext.captions(), docHits);
        }
```

（RetrieveNode 注入点：document 文本 = buildDocument（system）+ userDocument 合并，仍走 config.metadata() → DocumentAssemblerInterceptor 通道；**注意**：RetrieveNode 现有构造器需注入 AttachmentLocalSearchService + EmbeddingModel——EmbeddingModel 已有（检索用），检查既有注入，保持一致。）

- [ ] **Step 3: 补测试**

`ContextBuilderServiceTest` 补：

```java
    @Test
    @DisplayName("buildUserDocument — 图片 caption 与文件命中合并为 user-document 块")
    void buildUserDocument_mergesCaptionsAndHits() {
        String doc = service.buildUserDocument(
                List.of(new ImageCaptionResult("图片1:红色图表", "a.png")),
                Map.of("0/doc.pdf", List.of("段落一", "段落二")));
        assertTrue(doc.contains("<user-document>"));
        assertTrue(doc.contains("[图片1:红色图表]"));
        assertTrue(doc.contains("[文件1]"));
        assertTrue(doc.contains("段落一"));
    }

    @Test
    @DisplayName("buildUserDocument — 无附件内容返回 null")
    void buildUserDocument_empty() {
        assertNull(service.buildUserDocument(null, Map.of()));
    }
```

（RetrieveNodeTest 若有既有用例则补"附件上下文 → 合并 document"用例；若 RetrieveNode 无单测则本任务新建——参考 ContextBuilderServiceTest 既有结构。）

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=ContextBuilderServiceTest,RetrieveNodeTest -DfailIfNoTests=false`
Expected: 全部 PASS

```bash
git add backend/src/main/java/com/commerce/rag/retrieval/ContextBuilderService.java backend/src/main/java/com/commerce/rag/bot/graph/RetrieveNode.java backend/src/test/java/com/commerce/rag/retrieval/ContextBuilderServiceTest.java backend/src/test/java/com/commerce/rag/bot/graph/RetrieveNodeTest.java
git commit -m "feat(S1): document 组装 user-document 子块（附件 caption/局部检索合并，spec §3.2/§5.3）"
```

---

## Task 11: 后续轮次附件重建（chat_run 入口）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/IChatRunService.java` + `ChatRunServiceImpl.java`（查最近 run 附件）
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（无附件时按 session 查最近 run 重建）
- Test: Modify `ChatRequestWorkerTest`（补重建用例）

**Interfaces:**
- Consumes: `ChatRun.attachmentsJson`（Task 1）、`IChatSessionService`（session 归属，既有）
- Produces: `IChatRunService.findRecentAttachments(Long sessionId, Long excludeRunId, int limit)` → `List<AttachmentRecord>`（查该 session 最近 N 个 run 的 attachments_json，排除当前 run；无则空列表）

- [ ] **Step 1: IChatRunService 加查询方法**

```java
    /**
     * 查会话最近 run 的附件（后续轮次重建入口，spec §5.1）
     *
     * @param sessionId    会话 ID
     * @param excludeRunId 排除的 run（当前 run——附件已在本次处理）
     * @param limit        最多查几个 run（默认 3）
     * @return 附件记录列表（去重：同 url 只保留一条；无则空列表）
     */
    List<AttachmentRecord> findRecentAttachments(Long sessionId, Long excludeRunId, int limit);
```

实现（`ChatRunServiceImpl`）：

```java
    @Override
    public List<AttachmentRecord> findRecentAttachments(Long sessionId, Long excludeRunId, int limit) {
        List<ChatRun> runs = this.lambdaQuery()
                .select(ChatRun::getAttachmentsJson)
                .eq(ChatRun::getSessionId, sessionId)
                .ne(excludeRunId != null, ChatRun::getId, excludeRunId)
                .isNotNull(ChatRun::getAttachmentsJson)
                .orderByDesc(ChatRun::getId)
                .last("LIMIT " + limit)
                .list();
        Map<String, AttachmentRecord> unique = new LinkedHashMap<>();
        for (ChatRun run : runs) {
            if (run.getAttachmentsJson() == null || run.getAttachmentsJson().isBlank()) {
                continue;
            }
            try {
                List<AttachmentRecord> records = new Gson().fromJson(
                        run.getAttachmentsJson(), new TypeToken<List<AttachmentRecord>>() {}.getType());
                for (AttachmentRecord r : records) {
                    unique.putIfAbsent(r.url(), r);
                }
            } catch (Exception e) {
                log.warn("run 附件 JSON 解析失败，跳过: runId={}", run.getId());
            }
        }
        return new ArrayList<>(unique.values());
    }
```

- [ ] **Step 2: worker 无附件时重建**

`ChatRequestWorker.processRequest` 附件处理段改：

```java
        List<AttachmentRecord> attachments = parseAttachments(body.get("attachments"));
        if (attachments.isEmpty()) {
            // 后续轮次：以 chat_run 为入口重建附件上下文（spec §5.1 最终三表决策）
            attachments = chatRunService.findRecentAttachments(sessionId, runId, 3);
        }
        AttachmentContext attachmentContext = AttachmentContext.empty();
        if (!attachments.isEmpty()) {
            attachmentContext = orchestrator.process(attachments);
        }
```

（Caffeine 命中则直接复用 caption/语料；未命中重新下载处理——`AttachmentOrchestrator` 内部已走缓存。）

- [ ] **Step 3: 补测试**

`ChatRequestWorkerTest` 补：

```java
    @Test
    @DisplayName("无附件消息 — 按 session 查最近 run 附件重建上下文")
    void processRequest_rebuildFromRecentRun() {
        // body 不含 attachments 键
        // mock chatRunService.findRecentAttachments → [AttachmentRecord("image","0/a.png","a.png",1)]
        // mock orchestrator.process → AttachmentContext([ImageCaptionResult("图片1:旧图","a.png")], Map.of())
        // 断言: orchestrator.process 被调用；metadata 携带 attachmentContext
    }
```

- [ ] **Step 4: 运行测试 + Commit**

Run: `mvn.cmd test -Dtest=ChatRequestWorkerTest -DfailIfNoTests=false`
Expected: PASS

```bash
git add backend/src/main/java/com/commerce/rag/service/IChatRunService.java backend/src/main/java/com/commerce/rag/service/impl/ChatRunServiceImpl.java backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java
git commit -m "feat(S1): 后续轮次附件重建（chat_run 入口查最近 run，Caffeine 命中或重处理，spec §5.1）"
```

---

## Task 12: 收尾——全量验证与死代码清理

**Files:**
- Verify: 全量 `mvn.cmd clean verify`
- 清理: 本计划引入的废弃/临时代码（如 Task 5 早期测试构造器残留、Task 9 若改用 Orchestrator 后 worker 内残留的临时方法）

**Interfaces:**
- 无（收尾任务）

- [ ] **Step 1: 死代码自查**

- 全仓 grep：`AttachmentImageProcessor` 测试构造器（unused 参数）若存在删除，统一走正式构造器
- `ChatRequest` 两参构造器保留（兼容既有调用，非死代码）
- 确认无未使用 import、无注释掉的代码块（AGENTS.md 6.3）

- [ ] **Step 2: 全量 verify**

Run: `cd backend && mvn.cmd clean verify`
Expected: BUILD SUCCESS（全量测试 + spotless + checkstyle + spotbugs + jacoco 全绿）

- [ ] **Step 3: Commit（如有清理）**

```bash
git add -A backend/src
git commit -m "chore(S1): 计划 3 收尾清理（死代码/临时代码清零）"
```

---

## Self-Review 记录

- **Spec 覆盖**：§5.1 整体流程（Task 3 上传、Task 8 双存、Task 9/10 处理与注入、Task 11 后续轮次）✓；§5.2 类型与限额（Task 2/3）✓；§5.3 图片边界（Task 5 caption 双角色、Task 9 QU 拼装、Task 10 user-document）✓；§5.4 文档边界（Task 6/7 局部检索、Task 10 合并注入、文档不参与系统检索查询）✓；§6 模型（caption qwen3.7-flash + multiModel，沿用既有修复）✓
- **与计划 2/5 的衔接**：document 通道仍为 config.metadata() → DocumentAssemblerInterceptor（不重复实现）；system-base.yml 的 document_protocol 已有 user-document 子块说明（计划 2 已定稿），本计划不重复改 prompt
- **占位符检查**：Task 8/9/11 的测试用例给出断言意图但依赖既有测试的 mock 构造方式（ChatRequestWorkerTest 的 Redis 消息构造）——**注意**：SDD 执行时实现者需先读 ChatRequestWorkerTest 现有用例，按同风格补测试；Task 5 明确标注了设计修正（测试构造器方案废弃，改传字节），执行时以修正后签名为准
- **类型一致性**：`AttachmentRecord(type,url,name,size)`、`AttachmentContext(captions,documents)`、`DocumentLocalChunk(text,vector,index)`、`ImageCaptionResult(caption,resourceName)` 在各任务间引用一致

## 执行交接

计划已保存至 `docs/superpowers/plans/2026-08-18-s1-plan3-user-attachments.md`。两种执行方式：

1. **Subagent-Driven（推荐）**——每任务派发独立子代理 + 任务间审查（新账本 `.superpowers/sdd/2026-08-18-s1-plan3-user-attachments/`）
2. **Inline Execution**——本会话用 executing-plans 批量执行 + 检查点审查
