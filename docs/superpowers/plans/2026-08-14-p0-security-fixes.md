# P0 安全修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复总清单第一波 P0 四项（权限桥接、教师越权 8 子项、对话 IDOR、消息持久化三缺陷），使权限体系真正生效且无越权面。

**Architecture:** 7 个独立任务，顺序 = 权限桥接 → 越权修复（文档/用户/课程/反馈）→ 对话 IDOR → 消息持久化。每任务 TDD：先写失败测试 → 最小实现 → 单类测试通过 → 全量测试 → 提交。权限桥接与越权修复同波闭环（桥接生效后 @PreAuthorize 立即启用，越权校验同波补齐）。

**Tech Stack:** Java 17 / Spring Boot 3.5.8 / Spring Security 6（SecurityContextHolder + @PreAuthorize）/ MyBatis-Plus 3.5.12 / Mockito + JUnit 5 / @WebMvcTest 切片测试 / Maven（Windows 用 `mvn.cmd`）

## Global Constraints

- 规格源：`docs/superpowers/specs/2026-08-14-p0-security-fixes.md`（用户已批准），实现不得偏离其锁定方案
- 注释/日志/文档全中文；UTF-8 无 BOM；文件末尾一个换行符
- 测试与实现同一次提交；因改动失效的旧测试直接删除，禁止保留过渡
- Entity 不出数据层（本计划不扩大既有契约改动面，仅按 spec 加字段）
- DB 变更直接改 `backend/src/main/resources/db/migration/V6__full_schema_v5.sql`（开发阶段 drop 重建约定，不加新迁移文件）
- 工作区有大量与本计划无关的未提交改动，**git add 只加本任务明确列出的文件路径**，禁止 `git add -A` 或 `git add .`
- 测试命令：`cd backend && mvn.cmd test -Dtest=<测试类名>`；全量：`cd backend && mvn.cmd test`
- 提交信息格式：`fix: <改动摘要>`（中文）
- 现有测试类（AdminUserControllerTest/SysUserServiceTest/UserFeedbackServiceTest/ChatControllerTest/ChatRequestWorkerTest/AuthInterceptorTest）已存在：修改前**先 Read 该测试类**，按其现有 mock 风格（@ExtendWith(MockitoExtension.class)、@Mock 字段、DisplayName 中文）适配本计划给出的用例代码；若现有用例因本任务改动失效则直接删除并替换为本计划新用例

## File Structure

| 文件 | 任务 | 职责 |
|---|---|---|
| `auth/AuthInterceptor.java` | T1 修改 | SecurityContext 写入 + afterCompletion 清理 |
| `controller/GlobalExceptionHandler.java` | T1 修改 | AccessDeniedException → 403 |
| `test/auth/AuthInterceptorTest.java` | T1 修改 | SecurityContext 断言 |
| `test/controller/AdminUserControllerSecurityTest.java` | T1 新建 | @WebMvcTest 桥接集成测试 |
| `service/DocumentService.java` | T2 修改 | update/download/upload 归属校验 |
| `controller/AdminDocumentController.java` | T2 修改 | 三端点传 isAdmin/userId |
| `test/service/DocumentServiceTest.java` | T2 新建 | 文档权限三场景测试 |
| `resources/db/migration/V6__full_schema_v5.sql` | T3/T5 修改 | sys_user.created_by / user_feedback.user_id |
| `entity/SysUser.java` | T3 修改 | createdBy 字段 |
| `service/SysUserService.java` | T3 修改 | create/findById/findPage/checkTeacherPermission |
| `controller/AdminUserController.java` | T3 修改 | list/get/create 传参 |
| `controller/dto/CreateUserRequest.java` | T3 修改 | role @Pattern |
| `test/service/SysUserServiceTest.java` | T3 修改 | 权限用例 |
| `test/controller/AdminUserControllerTest.java` | T3 修改 | 传参同步 |
| `service/CourseService.java` | T4 修改 | findById 过滤重载 |
| `controller/AdminCourseController.java` | T4 修改 | detail 归属 |
| `test/service/CourseServiceTest.java` | T4 新建 | findById 过滤测试 |
| `entity/UserFeedback.java` | T5 修改 | userId 字段 |
| `service/UserFeedbackService.java` | T5 修改 | create 加 userId |
| `controller/FeedbackController.java` | T5 修改 | 取当前登录用户 |
| `test/service/UserFeedbackServiceTest.java` | T5 修改 | userId 用例 |
| `controller/ChatController.java` | T6 修改（归属）、T7 修改（XADD catch） | 对话 IDOR + XADD 回滚 |
| `worker/RunSnapshot.java` | T7 修改 | historyMessageCount 字段 |
| `worker/ChatRequestWorker.java` | T7 修改 | 游标 + catch 补齐 |
| `test/controller/ChatControllerTest.java` | T6/T7 修改 | 归属 + XADD 用例 |
| `test/worker/ChatRequestWorkerTest.java` | T7 修改 | 游标去重用例 |

---

### Task 1: P0-1 权限桥接（JWT → SecurityContext）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java`
- Modify: `backend/src/main/java/com/commerce/rag/controller/GlobalExceptionHandler.java`
- Test: `backend/src/test/java/com/commerce/rag/auth/AuthInterceptorTest.java`
- Test: `backend/src/test/java/com/commerce/rag/controller/AdminUserControllerSecurityTest.java`（新建）

**Interfaces:**
- Consumes: `TokenService.validateToken(String)`、`DeviceKickService.isBlacklisted(String)`（现有）
- Produces: preHandle 校验通过后 `SecurityContextHolder.getContext()` 的 Authentication 为 `UsernamePasswordAuthenticationToken(userId, null, [ROLE_{role}])`；afterCompletion 后 SecurityContext 为空——任务 2-6 的 @PreAuthorize 行为依赖此桥接

- [ ] **Step 1: 写失败测试 — 新建 AdminUserControllerSecurityTest（@WebMvcTest 集成）**

新建 `backend/src/test/java/com/commerce/rag/controller/AdminUserControllerSecurityTest.java`：

```java
package com.commerce.rag.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.commerce.rag.auth.AuthInterceptor;
import com.commerce.rag.auth.DeviceKickService;
import com.commerce.rag.auth.SecurityConfig;
import com.commerce.rag.auth.TokenService;
import com.commerce.rag.entity.SysUser;
import com.commerce.rag.service.SysUserService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 权限桥接集成测试 —— 验证 AuthInterceptor 写入 SecurityContext 后 @PreAuthorize 真正生效
 *
 * <p>@WebMvcTest 切片：加载 AdminUserController + SecurityConfig（方法级鉴权）+ AuthConfig/AuthInterceptor（MVC 拦截器）。
 * 场景：TEACHER 合法 token → 200；STUDENT → 403；无 token → 401。
 *
 * @author commerce-rag
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import({SecurityConfig.class, AuthInterceptor.class})
@DisplayName("权限桥接 SecurityContext 集成测试")
class AdminUserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TokenService tokenService;

    @MockBean
    private DeviceKickService deviceKickService;

    @MockBean
    private SysUserService sysUserService;

    private Claims stubToken(String role) throws Exception {
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken("test-token")).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractRole(claims)).thenReturn(role);
        when(tokenService.extractJti(claims)).thenReturn("jti-1");
        when(deviceKickService.isBlacklisted("jti-1")).thenReturn(false);
        return claims;
    }

    @Test
    @DisplayName("TEACHER 携带合法 token 访问 admin 端点 → 200")
    void teacherWithValidToken_returns200() throws Exception {
        stubToken("TEACHER");
        // 注意：本任务执行时 SysUserService.findPage 仍为 5 参旧签名（Task 3 才改 6 参），
        // 按当前签名 stub；Task 3 会同步此 stub 为 6 参（该文件列入 Task 3 提交清单）
        when(sysUserService.findPage(anyInt(), anyInt(), isNull(), isNull()))
                .thenReturn(new Page<>());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("STUDENT 携带合法 token 访问 admin 端点 → 403")
    void studentWithValidToken_returns403() throws Exception {
        stubToken("STUDENT");

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer test-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("无 token 访问 admin 端点 → 401")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users")).andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=AdminUserControllerSecurityTest`
Expected: 失败——TEACHER 用例 403（SecurityContext 为空，@PreAuthorize 拒绝）而非 200；STUDENT 用例也可能 403（同样原因，但断言 403 可能意外通过——以 TEACHER 用例失败为准）

- [ ] **Step 3: 实现 AuthInterceptor 桥接**

`AuthInterceptor.java` 三处修改：

(1) import 增加：
```java
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
```

(2) preHandle 第 5 步（`request.setAttribute(ATTR_JTI, jti);` 之后）追加：
```java
        // 6. 权限桥接：将 JWT 鉴权结果写入 Spring Security 上下文，
        //    供 @PreAuthorize 方法级鉴权读取（hasAnyRole 自动补 ROLE_ 前缀）
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
```

(3) 新增 afterCompletion 清理（防 Tomcat 线程池复用导致上下文串用户）：
```java
    @Override
    public void afterCompletion(
            HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // 清理 SecurityContext：请求结束后必须清空，防止线程池复用串上下文
        SecurityContextHolder.clearContext();
    }
```

`GlobalExceptionHandler.java` 增加 AccessDeniedException 处理器（import `org.springframework.security.access.AccessDeniedException`），放在 SecurityException handler 之后：

```java
    /**
     * 处理 AccessDeniedException —— @PreAuthorize 鉴权拒绝，返回 403
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ApiResponse<Void> handleAccessDeniedException(AccessDeniedException e) {
        log.warn("鉴权拒绝: {}", e.getMessage());
        return ApiResponse.fail(HttpStatus.FORBIDDEN.value(), "无权操作");
    }
```

- [ ] **Step 4: 补充 AuthInterceptorTest 用例**

在 `AuthInterceptorTest.java` 追加（复用其现有 setUp 与 mock 风格，注意该类当前构造器为 3 参含 AuthProperties——按现状适配）：

```java
    @Test
    @DisplayName("preHandle 校验通过后 SecurityContext 写入 ROLE_ 前缀 authority")
    void preHandle_writesSecurityContext() throws Exception {
        String token = "valid.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        Claims claims = mock(Claims.class);
        when(tokenService.validateToken(token)).thenReturn(claims);
        when(tokenService.extractTokenType(claims)).thenReturn("ACCESS");
        when(deviceKickService.isBlacklisted(anyString())).thenReturn(false);
        when(tokenService.extractUserId(claims)).thenReturn(123L);
        when(tokenService.extractRole(claims)).thenReturn("TEACHER");
        when(tokenService.extractJti(claims)).thenReturn("jti-abc");

        boolean result = authInterceptor.preHandle(request, response, null);

        assertTrue(result);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(authentication, "校验通过后应写入 SecurityContext");
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_TEACHER".equals(a.getAuthority())));
        // 清理（afterCompletion 的行为单独断言）
        authInterceptor.afterCompletion(request, response, null, null);
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "afterCompletion 应清空 SecurityContext");
    }
```

（import 增加 `org.springframework.security.core.context.SecurityContextHolder`）

- [ ] **Step 5: 运行相关测试**

Run: `cd backend && mvn.cmd test -Dtest=AdminUserControllerSecurityTest,AuthInterceptorTest`
Expected: 全部 PASS

- [ ] **Step 6: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS（注意：桥接生效后，若其他测试类间接依赖 @PreAuthorize 恒 403 的旧行为则可能失败——如有，在报告中说明并确认修复方式）

- [ ] **Step 7: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java backend/src/main/java/com/commerce/rag/controller/GlobalExceptionHandler.java backend/src/test/java/com/commerce/rag/auth/AuthInterceptorTest.java backend/src/test/java/com/commerce/rag/controller/AdminUserControllerSecurityTest.java
git commit -m "fix: P0-1 JWT 鉴权结果桥接 SecurityContext（@PreAuthorize 生效）+ AccessDenied 403 处理"
```

---

### Task 2: P0-2 文档权限（改名/下载/上传归属校验）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/DocumentService.java`（update :159-171、upload :74-105、download :259-268）
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java`（upload :49-66、update :93-98、download :119-130）
- Test: `backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java`（新建）

**Interfaces:**
- Consumes: `DocumentService.checkOwnership(Document, Long, boolean)`（现有私有）、`KnowledgeBase.getCreatedBy()`（现有）
- Produces: `DocumentService.update(Long id, String title, Long operatorId, boolean isAdmin)`、`download(Long id, Long operatorId, boolean isAdmin)`、`upload(Long kbId, String title, InputStream, String fileType, Long fileSize, Long createdBy, boolean isAdmin)`——controller 侧同步

- [ ] **Step 1: 写失败测试 — 新建 DocumentServiceTest**

新建 `backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java`：

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.Document;
import com.commerce.rag.entity.KnowledgeBase;
import com.commerce.rag.etl.EtlPipeline;
import com.commerce.rag.mapper.DocumentChunkMapper;
import com.commerce.rag.mapper.DocumentMapper;
import com.commerce.rag.mapper.KnowledgeBaseMapper;
import com.commerce.rag.storage.MinioStorageService;
import java.io.ByteArrayInputStream;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/**
 * DocumentService 权限校验单元测试 —— 改名/下载/上传的归属校验（P0-2a/b/c）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentService 文档权限测试")
class DocumentServiceTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentChunkMapper chunkMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private EtlPipeline etlPipeline;
    @Mock
    private ThreadPoolExecutor etlPool;

    private DocumentService documentService;

    @BeforeEach
    void setUp() throws Exception {
        documentService = new DocumentService();
        // 字段为 @Autowired 私有字段，通过反射注入 mock
        for (java.lang.reflect.Field f : DocumentService.class.getDeclaredFields()) {
            f.setAccessible(true);
            Object value = switch (f.getName()) {
                case "documentMapper" -> documentMapper;
                case "chunkMapper" -> chunkMapper;
                case "knowledgeBaseMapper" -> knowledgeBaseMapper;
                case "minioStorageService" -> minioStorageService;
                case "etlPipeline" -> etlPipeline;
                case "etlPool" -> etlPool;
                default -> null;
            };
            if (value != null) {
                f.set(documentService, value);
            }
        }
    }

    private Document mockDoc(Long id, Long createdBy) {
        Document doc = new Document();
        doc.setId(id);
        doc.setCreatedBy(createdBy);
        doc.setSourcePath("kb/1/doc.pdf");
        return doc;
    }

    @Test
    @DisplayName("update → 非创建者改名抛出 403（超管旁路）")
    void update_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        // 操作者 200 不是创建者 100 → 403
        assertThrows(ResponseStatusException.class, () -> documentService.update(1L, "新标题", 200L, false));
        // 超管旁路：不抛异常
        assertDoesNotThrow(() -> documentService.update(1L, "新标题", 200L, true));
    }

    @Test
    @DisplayName("download → 非创建者下载抛出 403")
    void download_notOwner_throws403() {
        when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

        assertThrows(ResponseStatusException.class, () -> documentService.download(1L, 200L, false));
    }

    @Test
    @DisplayName("upload → 非超管向他人知识库上传抛出 403")
    void upload_notKbOwner_throws403() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        // 用户 200 向 createdBy=100 的知识库上传 → 403
        assertThrows(
                ResponseStatusException.class,
                () -> documentService.upload(
                        1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 200L, false));
    }

    @Test
    @DisplayName("upload → 知识库创建者可正常上传")
    void upload_kbOwner_succeeds() {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(1L);
        kb.setCreatedBy(100L);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

        assertDoesNotThrow(() ->
                documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
        verify(documentMapper).insert(any(Document.class));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=DocumentServiceTest`
Expected: 编译失败（update/download/upload 新签名不存在）

- [ ] **Step 3: 实现 DocumentService 三方法**

`update` 签名与校验（:159-163 处）：

```java
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

        LambdaUpdateWrapper<Document> wrapper = new LambdaUpdateWrapper<Document>()
                .eq(Document::getId, id)
                .set(Document::getTitle, title)
                .set(Document::getUpdatedAt, LocalDateTime.now());
        documentMapper.update(null, wrapper);
        log.info("更新文档: docId={}, title={}", id, title);
    }
```

`upload` 签名加 `boolean isAdmin`，kb 存在校验后加归属校验（:78-80 处）：

```java
    public Document upload(
            Long kbId,
            String title,
            InputStream inputStream,
            String fileType,
            Long fileSize,
            Long createdBy,
            boolean isAdmin) {
        // 校验知识库存在
        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            throw new IllegalArgumentException("知识库不存在: kbId=" + kbId);
        }

        // 归属校验：非超管只能上传到自己创建的知识库（P0-2c 跨库上传越权修复）
        if (!isAdmin && (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(createdBy))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权向此知识库上传文档");
        }
        // ... 其余逻辑不变
```

`download` 签名与校验（:259-268 处）：

```java
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
```

`AdminDocumentController` 三端点同步：
- upload（:63）：`String role = AuthInterceptor.getCurrentRole(request);` + 调用加 `"SUPER_ADMIN".equals(role)` 作 isAdmin 末参；
- update（:97）：同款取 isAdmin 传入 `documentService.update(id, request2.title(), userId, isAdmin)`；
- download（:119-130）：加 `HttpServletRequest request` 参数，取 userId/isAdmin 传入 `documentService.download(id, userId, isAdmin)`。

- [ ] **Step 4: 运行 DocumentServiceTest**

Run: `cd backend && mvn.cmd test -Dtest=DocumentServiceTest`
Expected: 4 用例 PASS

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS（若其他测试调用旧签名则同步修改——grep 确认 upload/update/download 调用点仅 AdminDocumentController）

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/service/DocumentService.java backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java
git commit -m "fix: P0-2a/b/c 文档改名/下载/上传补归属校验（checkOwnership + kb.createdBy）"
```

---

### Task 3: P0-2 用户管理（created_by 列 + 教师操作归属 + 角色限制 + 列表过滤）

**Files:**
- Modify: `backend/src/main/resources/db/migration/V6__full_schema_v5.sql`（sys_user 表）
- Modify: `backend/src/main/java/com/commerce/rag/entity/SysUser.java`
- Modify: `backend/src/main/java/com/commerce/rag/service/SysUserService.java`
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminUserController.java`
- Modify: `backend/src/main/java/com/commerce/rag/controller/dto/CreateUserRequest.java`
- Test: `backend/src/test/java/com/commerce/rag/service/SysUserServiceTest.java`
- Test: `backend/src/test/java/com/commerce/rag/controller/AdminUserControllerTest.java`
- Test: `backend/src/test/java/com/commerce/rag/controller/AdminUserControllerSecurityTest.java`（Task 1 新建，本任务同步 findPage stub 为 6 参新签名）

**Interfaces:**
- Consumes: `UserRole` 枚举（现有）
- Produces: `SysUserService.create(CreateUserRequest, Long createdBy, String operatorRole)`、`findById(Long id, Long currentUserId, String operatorRole)`、`findPage(int, int, String role, String status, Long currentUserId, String operatorRole)`、`SysUser.getCreatedBy()/setCreatedBy(Long)`——任务 4/5 不依赖，仅 controller 同步

- [ ] **Step 1: 修改 V6 迁移 — sys_user 加 created_by**

`V6__full_schema_v5.sql` 的 sys_user 建表（:96-106）改为：

```sql
CREATE TABLE sys_user (
    id            BIGINT       PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL,
    password_hash VARCHAR(200) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  DEFAULT 'ACTIVE',
    created_by    BIGINT,
    deleted       BIGINT       DEFAULT 0,
    created_at    TIMESTAMPTZ  DEFAULT now(),
    updated_at    TIMESTAMPTZ  DEFAULT now()
);
```

（注释：`created_by` 为创建者用户 ID，超管/种子用户为 NULL——在表上方加一行中文注释）

- [ ] **Step 2: 写失败测试 — SysUserServiceTest 追加用例**

先 Read 现有 `SysUserServiceTest.java` 按其 mock 风格追加（如构造器为 4 参注入，已有 userMapper/passwordEncoder/deviceKickService/courseTeacherMapper mock）：

```java
    // ==================== P0-2 教师越权修复用例 ====================

    @Test
    @DisplayName("create → 教师创建 TEACHER 账号抛出 403，创建 STUDENT 成功")
    void create_teacherCreatesTeacherRole_throws403() {
        CreateUserRequest req = new CreateUserRequest("stu1", "pass123", "学生一", "TEACHER");

        assertThrows(ResponseStatusException.class, () -> sysUserService.create(req, 100L, "TEACHER"));

        CreateUserRequest stuReq = new CreateUserRequest("stu1", "pass123", "学生一", "STUDENT");
        assertDoesNotThrow(() -> sysUserService.create(stuReq, 100L, "TEACHER"));
        // 落库用户 created_by = 创建者
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getCreatedBy());
    }

    @Test
    @DisplayName("checkTeacherPermission → 教师操作非自己创建的学生抛出 403")
    void teacherOperatesStudentNotCreatedBySelf_throws403() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        target.setCreatedBy(999L); // 由他人创建
        SysUser operator = new SysUser();
        operator.setId(100L);
        operator.setRole("TEACHER");
        when(userMapper.selectById(100L)).thenReturn(operator);
        when(userMapper.selectById(2L)).thenReturn(target);

        assertThrows(
                ResponseStatusException.class,
                () -> sysUserService.updateStatus(2L, "DISABLED", 100L));
    }

    @Test
    @DisplayName("findPage → 教师仅能查到创建者为自己的用户")
    void findPage_teacherFiltersByCreatedBy() {
        sysUserService.findPage(1, 20, null, null, 100L, "TEACHER");
        // 校验查询条件带 created_by = 100
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(userMapper).selectPage(any(), captor.capture());
        // wrapper 内部 SQL 片段含 created_by 条件
        assertTrue(captor.getValue().getCustomSqlSegment().contains("created_by"));
    }

    @Test
    @DisplayName("findById → 教师查看非自己创建的学生返回 null")
    void findById_teacherNotOwner_returnsNull() {
        SysUser target = new SysUser();
        target.setId(2L);
        target.setRole("STUDENT");
        target.setCreatedBy(999L);
        when(userMapper.selectById(2L)).thenReturn(target);

        assertNull(sysUserService.findById(2L, 100L, "TEACHER"));
    }
```

- [ ] **Step 3: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=SysUserServiceTest`
Expected: 编译失败（新签名不存在）或既有用例因签名变化失败

- [ ] **Step 4: 实现 SysUser 实体 + SysUserService + DTO + Controller**

`SysUser.java` 加字段（updatedAt 之后）：

```java
    /** 创建者用户 ID（超管/种子用户为 NULL） */
    @TableField("created_by")
    private Long createdBy;
```

`CreateUserRequest.java` role 校验：

```java
public record CreateUserRequest(
        @NotBlank String username,
        @NotBlank String password,
        @NotBlank String displayName,
        @NotBlank @Pattern(regexp = "SUPER_ADMIN|TEACHER|STUDENT", message = "角色取值非法") String role) {}
```

（import 加 `jakarta.validation.constraints.Pattern`）

`SysUserService.java`：
- `create(CreateUserRequest request, Long createdBy, String operatorRole)`——在超管唯一性校验之前加：
```java
        // 教师只能创建学生账号（P0-2e：防止教师创建 TEACHER 扩权）
        if (UserRole.TEACHER.name().equals(operatorRole) && !UserRole.STUDENT.name().equals(request.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能创建学生账号");
        }
```
落库加 `user.setCreatedBy(createdBy);`
- `findById(Long id, Long currentUserId, String operatorRole)`——查询后：
```java
        // 教师只能查看自己创建的学生（P0-2f）
        if (UserRole.TEACHER.name().equals(operatorRole)
                && (user.getCreatedBy() == null || !user.getCreatedBy().equals(currentUserId))) {
            return null;
        }
```
- `findPage(int page, int size, String role, String status, Long currentUserId, String operatorRole)`——wrapper 条件后加：
```java
        // 教师只能查看自己创建的用户（P0-2f）
        if (UserRole.TEACHER.name().equals(operatorRole)) {
            wrapper.eq(SysUser::getCreatedBy, currentUserId);
        }
```
- `checkTeacherPermission` 教师分支补（"教师只能操作学生"检查之后）：
```java
            // 教师只能操作自己创建的学生（P0-2d：created_by 归属校验）
            if (targetUser.getCreatedBy() == null || !targetUser.getCreatedBy().equals(currentUserId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能操作自己创建的学生");
            }
```
- 类注释中"教师只能操作自己创建的学生"语义维持

`AdminUserController.java`：
- `list` 加 `HttpServletRequest request` 参数，取 userId/role 传入 findPage；
- `get` 加 `HttpServletRequest request` 参数，取 userId/role 传入 findById，null 返回 `ApiResponse.error(404, "用户不存在")`；
- `create` 传 `AuthInterceptor.getCurrentRole(request)` 为第三参。

- [ ] **Step 5: 运行 SysUserServiceTest 与 AdminUserControllerTest**

Run: `cd backend && mvn.cmd test -Dtest=SysUserServiceTest,AdminUserControllerTest,AdminUserControllerSecurityTest`
Expected: 全 PASS——AdminUserControllerTest 中 list/get/create 调用同步（先 Read 该类，旧签名调用更新为新签名，失效断言删除）；AdminUserControllerSecurityTest 中 findPage stub 同步为 6 参（`when(sysUserService.findPage(anyInt(), anyInt(), isNull(), isNull(), any(), any()))`）

- [ ] **Step 6: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS（grep 确认 create/findById/findPage 调用点全部同步）

- [ ] **Step 7: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/resources/db/migration/V6__full_schema_v5.sql backend/src/main/java/com/commerce/rag/entity/SysUser.java backend/src/main/java/com/commerce/rag/service/SysUserService.java backend/src/main/java/com/commerce/rag/controller/AdminUserController.java backend/src/main/java/com/commerce/rag/controller/dto/CreateUserRequest.java backend/src/test/java/com/commerce/rag/service/SysUserServiceTest.java backend/src/test/java/com/commerce/rag/controller/AdminUserControllerTest.java backend/src/test/java/com/commerce/rag/controller/AdminUserControllerSecurityTest.java
git commit -m "fix: P0-2d/e/f sys_user 加 created_by + 教师操作归属校验 + 角色枚举限制 + 列表过滤"
```

---

### Task 4: P0-2g 课程详情归属校验

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/CourseService.java`（findById :120-122）
- Modify: `backend/src/main/java/com/commerce/rag/controller/AdminCourseController.java`（detail :82-89）
- Test: `backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java`（新建）

**Interfaces:**
- Consumes: `CourseInfo.getCreatedBy()`（现有）；`CourseService.findById(Long)`（现有，CourseApiTool 等调用方保持不变）
- Produces: `CourseService.findById(Long courseId, Long createdByFilter)`（filter=null 不过滤；非 null 且 created_by 不匹配返回 null）

- [ ] **Step 1: 写失败测试 — 新建 CourseServiceTest**

新建 `backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java`：

```java
package com.commerce.rag.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.mapper.CourseInfoMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * CourseService 权限单元测试 —— 课程详情归属校验（P0-2g）
 *
 * @author commerce-rag
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CourseService 课程归属测试")
class CourseServiceTest {

    @Mock
    private CourseInfoMapper courseInfoMapper;

    private CourseService courseService;

    @BeforeEach
    void setUp() throws Exception {
        courseService = new CourseService();
        java.lang.reflect.Field f = CourseService.class.getDeclaredField("courseInfoMapper");
        f.setAccessible(true);
        f.set(courseInfoMapper, courseService);
    }

    @Test
    @DisplayName("findById 过滤重载 → 教师查看非自己创建的课程返回 null")
    void findById_teacherNotOwner_returnsNull() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        // 教师 200 查 createdBy=100 的课程 → null（controller 层 404）
        assertNull(courseService.findById(1L, 200L));
    }

    @Test
    @DisplayName("findById 过滤重载 → 创建者可查看 + 超管（filter=null）可查看任意课程")
    void findById_ownerAndAdmin_canView() {
        CourseInfo course = new CourseInfo();
        course.setId(1L);
        course.setCreatedBy(100L);
        when(courseInfoMapper.selectById(1L)).thenReturn(course);

        assertNotNull(courseService.findById(1L, 100L));
        assertNotNull(courseService.findById(1L, null));
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=CourseServiceTest`
Expected: 编译失败（findById(Long, Long) 不存在）

- [ ] **Step 3: 实现 CourseService 重载 + AdminCourseController.detail**

`CourseService.java` 在 `findById(Long courseId)` 之后加重载：

```java
    /**
     * 根据 ID 查询课程（含创建者过滤）
     *
     * <p>P0-2g：教师只能查看自己创建的课程（created_by 归属），超管不过滤。
     *
     * @param courseId        课程 ID
     * @param createdByFilter 创建者过滤（null=不过滤；非 null 且不匹配返回 null）
     * @return 课程实体，不存在或无权访问返回 null
     */
    public CourseInfo findById(Long courseId, Long createdByFilter) {
        CourseInfo course = courseInfoMapper.selectById(courseId);
        if (course == null) {
            return null;
        }
        // 归属校验：不匹配返回 null（controller 层 404，不泄露存在性）
        if (createdByFilter != null
                && (course.getCreatedBy() == null || !course.getCreatedBy().equals(createdByFilter))) {
            return null;
        }
        return course;
    }
```

`AdminCourseController.java` detail 改为：

```java
    /**
     * E3: 查看课程（含内容 + 排期 + 老师）
     *
     * <p>归属校验：教师只能查看自己创建的课程（P0-2g）。
     */
    @GetMapping("/{id}")
    public ApiResponse<CourseDTO> detail(HttpServletRequest request, @PathVariable Long id) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        String role = AuthInterceptor.getCurrentRole(request);
        // 教师按 created_by 过滤，超管不过滤
        Long createdByFilter = "SUPER_ADMIN".equals(role) ? null : userId;
        CourseInfo course = courseService.findById(id, createdByFilter);
        if (course == null) {
            return ApiResponse.error(404, "课程不存在");
        }
        return ApiResponse.ok(courseService.toDTO(course, true));
    }
```

- [ ] **Step 4: 运行 CourseServiceTest**

Run: `cd backend && mvn.cmd test -Dtest=CourseServiceTest`
Expected: 2 用例 PASS

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS（CourseApiTool 仍用单参 findById，不受影响）

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/service/CourseService.java backend/src/main/java/com/commerce/rag/controller/AdminCourseController.java backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java
git commit -m "fix: P0-2g 课程详情补归属校验（教师仅可查看自己创建的课程）"
```

---

### Task 5: P0-2h 反馈归属（user_feedback 加 user_id）

**Files:**
- Modify: `backend/src/main/resources/db/migration/V6__full_schema_v5.sql`（:306-317）
- Modify: `backend/src/main/java/com/commerce/rag/entity/UserFeedback.java`
- Modify: `backend/src/main/java/com/commerce/rag/service/UserFeedbackService.java`
- Modify: `backend/src/main/java/com/commerce/rag/controller/FeedbackController.java`
- Test: `backend/src/test/java/com/commerce/rag/service/UserFeedbackServiceTest.java`

**Interfaces:**
- Consumes: `AuthInterceptor.getCurrentUserId(HttpServletRequest)`（现有静态）
- Produces: `UserFeedbackService.create(Long userId, Long sessionId, Long messageId, Boolean isLiked, String intentType)`；`UserFeedback.getUserId()/setUserId(Long)`

- [ ] **Step 1: 修改 V6 迁移 — user_feedback 加 user_id**

`V6__full_schema_v5.sql` :306-317 改为：

```sql
-- 15. user_feedback ── 用户反馈
CREATE TABLE user_feedback (
    id         BIGINT       PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    session_id BIGINT       NOT NULL,
    message_id BIGINT       NOT NULL,
    is_liked   BOOLEAN,
    intent_type VARCHAR(20),
    deleted    BIGINT       DEFAULT 0,
    created_at TIMESTAMPTZ  DEFAULT now()
);

-- 同一用户对同一消息只允许一条反馈（P0-2h：加 user_id 归属）
CREATE UNIQUE INDEX uniq_feedback_message      ON user_feedback(user_id, message_id) WHERE deleted = 0;
CREATE INDEX        idx_user_feedback_intent_liked ON user_feedback(intent_type, is_liked) WHERE deleted = 0;
CREATE INDEX        idx_user_feedback_session      ON user_feedback(session_id) WHERE deleted = 0;
```

- [ ] **Step 2: 写失败测试 — UserFeedbackServiceTest 追加用例**

先 Read 现有 `UserFeedbackServiceTest.java` 按其 mock 风格追加：

```java
    @Test
    @DisplayName("create → 携带 userId 落库（归属字段）")
    void create_withUserId_persistsOwnership() {
        // 无已有反馈时新建
        when(feedbackMapper.selectOne(any())).thenReturn(null);

        UserFeedback result = userFeedbackService.create(200L, 1L, 10L, true, "knowledge_question");

        ArgumentCaptor<UserFeedback> captor = ArgumentCaptor.forClass(UserFeedback.class);
        verify(feedbackMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getUserId());
        assertEquals(10L, captor.getValue().getMessageId());
    }

    @Test
    @DisplayName("create → 更新已有反馈时查询条件含 user_id（防止跨用户改他人反馈）")
    void create_updateExisting_queriesWithUserId() {
        UserFeedback existing = new UserFeedback();
        existing.setId(5L);
        existing.setUserId(200L);
        when(feedbackMapper.selectOne(any())).thenReturn(existing);

        userFeedbackService.create(200L, 1L, 10L, false, null);

        // 查询 wrapper 条件应含 user_id=200 与 message_id=10（跨用户反馈不会命中）
        ArgumentCaptor<LambdaQueryWrapper<UserFeedback>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(feedbackMapper).selectOne(captor.capture());
        String sqlSegment = captor.getValue().getCustomSqlSegment();
        assertTrue(sqlSegment.contains("user_id"));
        assertTrue(sqlSegment.contains("message_id"));
        // 更新走 update 而非 insert
        verify(feedbackMapper).update(any(), any());
        verify(feedbackMapper, never()).insert(any());
    }
```

- [ ] **Step 3: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=UserFeedbackServiceTest`
Expected: 编译失败（create 新签名不存在）

- [ ] **Step 4: 实现实体 + Service + Controller**

`UserFeedback.java` 加字段（messageId 之后）：

```java
    /** 反馈用户 ID（P0-2h：反馈归属，防止跨用户伪造） */
    @TableField("user_id")
    private Long userId;
```

`UserFeedbackService.java` create 签名与逻辑：

```java
    /**
     * 创建反馈（或更新已有反馈）
     *
     * <p>UNIQUE(user_id, message_id) 约束：同一用户同一消息只允许一条反馈。
     * user_id 取自当前登录用户（P0-2h：防止跨用户伪造赞踩）。
     *
     * @param userId     反馈用户 ID（当前登录用户）
     * @param sessionId  会话 ID
     * @param messageId  消息 ID
     * @param isLiked    是否点赞（NULL/TRUE/FALSE）
     * @param intentType 意图类型
     * @return 已持久化的反馈实体
     */
    public UserFeedback create(Long userId, Long sessionId, Long messageId, Boolean isLiked, String intentType) {
        // 查询是否已有该用户的反馈（按 user_id + message_id 唯一定位）
        LambdaQueryWrapper<UserFeedback> wrapper = new LambdaQueryWrapper<UserFeedback>()
                .eq(UserFeedback::getUserId, userId)
                .eq(UserFeedback::getMessageId, messageId);
        UserFeedback existing = feedbackMapper.selectOne(wrapper);

        if (existing != null) {
            // 更新已有反馈
            LambdaUpdateWrapper<UserFeedback> updateWrapper = new LambdaUpdateWrapper<UserFeedback>()
                    .eq(UserFeedback::getId, existing.getId())
                    .set(UserFeedback::getIsLiked, isLiked)
                    .set(UserFeedback::getIntentType, intentType);
            feedbackMapper.update(null, updateWrapper);
            existing.setIsLiked(isLiked);
            existing.setIntentType(intentType);
            log.info("更新反馈: feedbackId={}, isLiked={}", existing.getId(), isLiked);
            return existing;
        }

        // 创建新反馈
        UserFeedback feedback = new UserFeedback();
        feedback.setUserId(userId);
        feedback.setSessionId(sessionId);
        feedback.setMessageId(messageId);
        feedback.setIsLiked(isLiked);
        feedback.setIntentType(intentType);
        feedbackMapper.insert(feedback);
        log.info(
                "创建反馈: feedbackId={}, userId={}, messageId={}, isLiked={}",
                feedback.getId(), userId, messageId, isLiked);
        return feedback;
    }
```

（类注释中 UNIQUE(session_id, message_id) 描述同步改为 UNIQUE(user_id, message_id)）

`FeedbackController.java` create：

```java
    /** J5: 提交反馈（user_id 取当前登录用户，防止跨用户伪造） */
    @PostMapping
    public ApiResponse<UserFeedback> create(HttpServletRequest request, @RequestBody FeedbackRequest feedbackRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(request);
        UserFeedback feedback = feedbackService.create(
                userId,
                feedbackRequest.sessionId(),
                feedbackRequest.messageId(),
                feedbackRequest.isLiked(),
                feedbackRequest.intentType());
        return ApiResponse.ok(feedback);
    }
```

（import 加 `com.commerce.rag.auth.AuthInterceptor`）

- [ ] **Step 5: 运行 UserFeedbackServiceTest**

Run: `cd backend && mvn.cmd test -Dtest=UserFeedbackServiceTest`
Expected: 全 PASS（旧用例 create 调用同步为新签名，失效断言删除）

- [ ] **Step 6: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 7: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/resources/db/migration/V6__full_schema_v5.sql backend/src/main/java/com/commerce/rag/entity/UserFeedback.java backend/src/main/java/com/commerce/rag/service/UserFeedbackService.java backend/src/main/java/com/commerce/rag/controller/FeedbackController.java backend/src/test/java/com/commerce/rag/service/UserFeedbackServiceTest.java
git commit -m "fix: P0-2h user_feedback 加 user_id 归属（唯一索引改 user_id+message_id，反馈取当前登录用户）"
```

---

### Task 6: P0-3 对话端点水平越权（IDOR）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/ChatController.java`（chat :150-158、cancel :197-202、reconnect :221-226）
- Test: `backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java`

**Interfaces:**
- Consumes: `ChatSessionService.findById(Long)`（现有，返回 ChatSession 或 null）、`ChatRunService.findById(Long)`（现有）、`ChatSession.getUserId()`、`ChatRun.getUserId()`（现有）
- Produces: 无新增对外接口；三端点新增归属校验行为（不匹配 → 403/404）

- [ ] **Step 1: 写失败测试 — ChatControllerTest 追加用例**

先 Read 现有 `ChatControllerTest.java` 按其 mock 风格追加（注意 chat 端点返回 SseEmitter，测试需 mock bridge/redis 等——沿用现有用例的 mock 构造）：

```java
    @Test
    @DisplayName("chat → 传入他人 sessionId 抛出 403")
    void chat_withOthersSession_throws403() {
        // 现有 mock：AuthInterceptor attribute userId=1
        ChatSession othersSession = new ChatSession();
        othersSession.setId(99L);
        othersSession.setUserId(2L); // 属于用户 2
        when(chatSessionService.findById(99L)).thenReturn(othersSession);

        ChatRequest request = new ChatRequest(99L, "你好");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> chatController.chat(httpRequest, request));
        assertEquals(403, ex.getStatusCode().value());
        verify(chatRunService, never()).createRun(any(), any());
    }

    @Test
    @DisplayName("cancel → 他人 runId 返回 404（不泄露存在性）")
    void cancel_withOthersRun_returns404() {
        ChatRun othersRun = new ChatRun();
        othersRun.setId(1L);
        othersRun.setUserId(2L); // 属于用户 2
        when(chatRunService.findById(1L)).thenReturn(othersRun);

        // Step 3 实现为 checkRunOwnership 抛 ResponseStatusException(404)
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> chatController.cancel("1", httpRequest));
        assertEquals(404, ex.getStatusCode().value());
        verify(worker, never()).cancel(anyString());
    }

    @Test
    @DisplayName("reconnect → 他人 runId 返回 404")
    void reconnect_withOthersRun_returns404() {
        ChatRun othersRun = new ChatRun();
        othersRun.setId(1L);
        othersRun.setUserId(2L);
        when(chatRunService.findById(1L)).thenReturn(othersRun);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> chatController.reconnect("1", 0, httpRequest));
        assertEquals(404, ex.getStatusCode().value());
    }
```

（注：cancel 端点修改后签名与返回行为按 Step 3 实现为准——cancel 改为抛 `ResponseStatusException(404)` 而非返回 ResponseEntity，测试对应改 `assertThrows`；若 Step 3 保留 ResponseEntity 返回 404 亦可，测试按实现形态适配。**以 Step 3 代码为准，二选一保持一致。**）

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=ChatControllerTest`
Expected: 新用例失败（无归属校验 → 不抛 403/404）

- [ ] **Step 3: 实现 ChatController 三端点归属校验**

`chat`（:150-158 会话处理处）：

```java
        // 1. 会话处理（含归属校验：sessionId 非空时必须是当前用户的会话）
        Long sessionId = request.sessionId();
        if (sessionId == null) {
            ChatSession session = chatSessionService.createSession(userId, truncateTitle(request.query()));
            sessionId = session.getId();
        } else {
            // P0-3: sessionId 归属校验——传入他人会话 ID 直接拒绝
            ChatSession session = chatSessionService.findById(sessionId);
            if (session == null || !session.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作此会话");
            }
        }
```

`cancel`（:197-202 整体替换）：

```java
    /**
     * 取消正在执行的 run。
     *
     * <p>归属校验：run 必须属于当前用户（P0-3，不匹配 404 不泄露存在性）。
     */
    @PostMapping("/{runId}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable String runId, HttpServletRequest httpRequest) {
        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);
        worker.cancel(runId);
        log.info("取消请求已发送: runId={}", runId);
        return ResponseEntity.ok().build();
    }
```

`reconnect`（:221-226 方法开头加校验）：

```java
    @GetMapping("/{runId}/reconnect")
    public SseEmitter reconnect(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") long lastEventId,
            HttpServletRequest httpRequest) {

        Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
        checkRunOwnership(runId, userId);

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        // ... 其余逻辑不变
```

新增私有辅助方法（escapeJson 之前）：

```java
    /**
     * Run 归属校验 —— runId 必须属于当前用户（P0-3 对话端点 IDOR 修复）
     *
     * <p>runId 非法或不存在或不属于当前用户 → 404（不泄露存在性）。
     *
     * @param runId  Run 唯一标识（字符串）
     * @param userId 当前登录用户 ID
     */
    private void checkRunOwnership(String runId, Long userId) {
        Long runIdLong;
        try {
            runIdLong = Long.parseLong(runId);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在");
        }
        ChatRun run = chatRunService.findById(runIdLong);
        if (run == null || !run.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run 不存在");
        }
    }
```

- [ ] **Step 4: 运行 ChatControllerTest**

Run: `cd backend && mvn.cmd test -Dtest=ChatControllerTest`
Expected: 全 PASS（既有用例的 cancel/reconnect/chat 调用同步新签名，失效断言删除）

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/controller/ChatController.java backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java
git commit -m "fix: P0-3 对话端点归属校验（chat/cancel/reconnect 拒绝他人 session/run，404 不泄露存在性）"
```

---

### Task 7: P0-4 消息持久化（游标去重 + 异常终态补齐 + XADD 回滚）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/worker/RunSnapshot.java`
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（captureSnapshot :452-464、persistMessages :518-559、调用点 :379/:395、catch :401-408）
- Modify: `backend/src/main/java/com/commerce/rag/controller/ChatController.java`（XADD :169-175）
- Test: `backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`
- Test: `backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java`

**Interfaces:**
- Consumes: `handleError(String runIdStr, Long runId, RunState, Throwable)`（现有私有）、`bridge.removeRing(String)`（现有）、`chatRunService.updateStatus(Long, String)`（现有）
- Produces: `RunSnapshot.historyMessageCount()`（int，pre-run checkpoint 中 messages 数，无 checkpoint 为 0）；`persistMessages(Long runId, Long sessionId, String userQuery, int historyCursor, NodeOutput lastOutput)`（仅持久化 rawList 中 index >= historyCursor 的消息）

- [ ] **Step 1: 写失败测试 — ChatRequestWorkerTest 追加游标去重用例**

先 Read 现有 `ChatRequestWorkerTest.java`（其现有 persist 相关用例用 `state()→null` 规避路径——按现有 mock 风格追加）：

```java
    @Test
    @DisplayName("persistMessages → 游标跳过历史消息，仅持久化本轮新增（P0-4a）")
    void persistMessages_withHistoryCursor_skipsHistory() {
        // Given: rawList 含 2 条历史（index 0/1）+ 1 条本轮新增（index 2）
        UserMessage historyUser = new UserMessage("历史问题");
        AssistantMessage historyAssistant = new AssistantMessage("历史回答");
        AssistantMessage newAssistant = new AssistantMessage("本轮回答");

        NodeOutput lastOutput = mock(NodeOutput.class);
        Map<String, Object> state = new HashMap<>();
        state.put("messages", List.of(historyUser, historyAssistant, newAssistant));
        when(lastOutput.state()).thenReturn(state);

        // When: 游标=2（历史 2 条）
        chatRequestWorker.persistMessages(1L, 1L, "本轮问题", 2, lastOutput);

        // Then: 仅持久化本轮（USER 用户消息 + 本轮新增 assistant）
        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMessageService).batchInsert(captor.capture());
        List<ChatMessage> inserted = captor.getValue();
        assertEquals(2, inserted.size()); // USER + 本轮 assistant
        assertEquals("USER", inserted.get(0).getRole());
        assertEquals("本轮回答", inserted.get(1).getContent());
    }

    @Test
    @DisplayName("XADD 失败 → run 状态回滚 ERROR + removeRing + 503（P0-4c）")
    void chat_xaddFailure_rollsBackRun() {
        // Given: XADD 抛异常（复用 ChatControllerTest 的 mock 构造）
        doThrow(new RuntimeException("Redis 不可用"))
                .when(redisTemplate.opsForStream())
                .add(anyString(), anyMap());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> chatController.chat(httpRequest, new ChatRequest(null, "你好")));

        assertEquals(503, ex.getStatusCode().value());
        verify(chatRunService).updateStatus(anyLong(), eq("ERROR"));
        verify(bridge).removeRing(anyString());
    }
```

（XADD 用例放 ChatControllerTest；persist 用例放 ChatRequestWorkerTest。若 persistMessages 为私有方法，测试经反射调用或改为包级可见——**以现测可达方式为准**：现有测试若已通过反射/直接调用私有方法则沿用；否则将 persistMessages 可见性保持现状并用反射，测试类中写反射辅助）

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn.cmd test -Dtest=ChatRequestWorkerTest,ChatControllerTest`
Expected: 编译失败（persistMessages 新签名/XADD 无回滚）

- [ ] **Step 3: 实现 RunSnapshot 游标字段 + Worker 修改**

`RunSnapshot.java` 加字段（capturedAt 之前）与注释：

```java
 * @param historyMessageCount pre-run checkpoint 中 messages 列表长度（持久化游标：本轮只落此数之后的新增消息；无 checkpoint 为 0）
public record RunSnapshot(
        String runId,
        String checkpointId,
        String nodeId,
        String nextNodeId,
        Map<String, Object> state,
        int historyMessageCount,
        long capturedAt) {}
```

`ChatRequestWorker.java`：
(1) `captureSnapshot` 构造快照处（:462）计算游标：

```java
            // 计算持久化游标：pre-run checkpoint 中 messages 列表长度（P0-4a 去重）
            int historyCount = 0;
            Object messagesObj = stateCopy.get("messages");
            if (messagesObj instanceof List<?> messageList) {
                historyCount = messageList.size();
            }
            return new RunSnapshot(
                    runId, cp.getId(), cp.getNodeId(), cp.getNextNodeId(), stateCopy, historyCount,
                    System.currentTimeMillis());
```

(2) `persistMessages` 签名加 `int historyCursor`，遍历改为：

```java
    private void persistMessages(
            Long runId, Long sessionId, String userQuery, int historyCursor, NodeOutput lastOutput) {
        List<ChatMessage> messages = new ArrayList<>();
        int seq = 0;

        // 1. 用户消息
        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRunId(runId);
        userMsg.setRole("USER");
        userMsg.setContent(userQuery);
        userMsg.setSeq(seq++);
        userMsg.setSourcesJson("[]");
        messages.add(userMsg);

        // 2. 从最终状态提取 messages 列表，仅转换本轮新增（index >= 游标）——P0-4a 修复：
        //    state 跨 run 累积（AppendStrategy），游标 = pre-run checkpoint 消息数，
        //    否则历史 assistant/thinking/tool 消息每轮全量重插
        if (lastOutput != null && lastOutput.state() != null) {
            Optional<Object> messagesOpt = lastOutput.state().value("messages");
            if (messagesOpt.isPresent() && messagesOpt.get() instanceof List<?> rawList) {
                int start = Math.max(0, Math.min(historyCursor, rawList.size()));
                for (int i = start; i < rawList.size(); i++) {
                    Object item = rawList.get(i);
                    if (item instanceof Message msg) {
                        // F2-12: 跳过 UserMessage —— 用户消息已在步骤1单独插入，不重复
                        if (msg instanceof UserMessage) {
                            continue;
                        }
                        List<ChatMessage> converted = toChatMessages(msg, runId, sessionId);
                        for (ChatMessage cm : converted) {
                            cm.setSeq(seq++);
                            messages.add(cm);
                        }
                    }
                }
            }
        }
        // 3. 批量插入（不变）
```

(3) 三处调用点传游标 `snapshot != null ? snapshot.historyMessageCount() : 0`：
- onErrorResume 分支（:379）与 doOnComplete 分支（:395）：`persistMessages(runId, sessionId, userQuery, snapshot != null ? snapshot.historyMessageCount() : 0, lastOutput.get());`
- catch 分支（:401-408）整体替换为：

```java
        } catch (Exception e) {
            log.error("processRequest 致命错误 runId={}", runId, e);
            errored.set(true);
            // P0-4b 修复：补齐终态——推送 ERROR 事件 + 持久化已收集消息（与 onErrorResume 分支对齐）
            handleError(runIdStr, runId, runState, e);
            persistMessages(
                    runId, sessionId, userQuery, snapshot != null ? snapshot.historyMessageCount() : 0, lastOutput.get());
            cacheFinalResult(runId, lastOutput.get());
        } finally {
```

（`snapshot` 变量声明在 try 之外 ✅ 可访问；`handleError` 已存在；注意 catch 内 `bridge` ring 在 finally removeRing 前仍存在，push 有效）

(4) `ChatController.java` XADD（:169-175）替换为：

```java
        // 5. XADD 入队（subscribe 之后再入队，确保 Worker 推送的事件能到达 emitter）
        Map<String, String> message = Map.of(
                "runId", runId,
                "sessionId", sessionId.toString(),
                "userId", userId.toString(),
                "query", request.query());
        try {
            redisTemplate.opsForStream().add(streamProperties.requestStream(), message);
        } catch (Exception e) {
            // P0-4c 修复：入队失败回滚 run 状态（解除 uniq_active_run_per_session 唯一索引锁死）
            // + 清理 ring，避免 QUEUED 残留与 ring map 泄漏
            log.error("XADD 入队失败，回滚 run: runId={}", runId, e);
            chatRunService.updateStatus(run.getId(), "ERROR");
            bridge.removeRing(runId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "消息队列暂不可用，请稍后重试");
        }
```

- [ ] **Step 4: 运行相关测试**

Run: `cd backend && mvn.cmd test -Dtest=ChatRequestWorkerTest,ChatControllerTest`
Expected: 全 PASS（现有用例的 persistMessages 调用与 RunSnapshot 构造同步新签名）

- [ ] **Step 5: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全量 PASS

- [ ] **Step 6: Commit**

```bash
cd D:/code/project/commerce-customer/commerce-customer
git add backend/src/main/java/com/commerce/rag/worker/RunSnapshot.java backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/main/java/com/commerce/rag/controller/ChatController.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java
git commit -m "fix: P0-4 消息持久化游标去重 + blockLast 异常补 ERROR 终态/落库 + XADD 失败回滚解除锁死"
```

---

## 收尾（七任务全部完成后）

- [ ] **全量回归**：`cd backend && mvn.cmd test` 全过；DB drop 重建后 Flyway 迁移成功（sys_user.created_by / user_feedback.user_id 两列存在）
- [ ] **更新总清单**：`docs/bugs/2026-08-14-待修复bug总清单.md` 标注第一波 P0 已修复（含提交号），P1/P2/P3 待后续波次
- [ ] **更新进度文档**：`docs/progress/2026-08-14-多模态rag重构spec定稿.md` §2.2 追加 P0 波次完成记录
- [ ] 向用户汇报：P0 清零；后续波次（P1/P2/P3）与 P2-2 契约裁决方向待拍板

