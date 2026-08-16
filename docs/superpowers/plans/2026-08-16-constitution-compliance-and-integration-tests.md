# 合规修正全量实施 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 一次全量实施 A1 集成测试 / A2 低覆盖补测 / B1 VO 化 / B2 controller 依赖消除 / C2 TASK.md / C3 mapper 测试 + jacoco 单类门禁（spec: docs/superpowers/specs/2026-08-16-constitution-compliance-and-integration-tests.md，已获用户批准，不再拆阶段）。

**Architecture:** B2 先抽取 `stream/ChatStreamEntry` 收编 SSE 编排（为后续 VO 化铺路）；B1 按「仅 controller 引用 → 改签名返回 VO；多调用方 → 保留 entity 方法 + 新增 VO 方法」逐 service 改造；A2 在现有测试类上扩充分支；A1/C3 共用 Testcontainers PG+Redis 基建；最后加 jacoco 单类门禁。

**Tech Stack:** Spring Boot 3.5.8 / MyBatis-Plus 3.5.12 / MapStruct 1.6.3 / Testcontainers（junit-jupiter + postgresql）/ SAA 1.1.2.0

## Global Constraints

- 中文注释/日志/文档；注释描述业务意图（为什么），禁止翻译型注释
- Entity 不出 service 边界；controller 入参 DTO、出参 VO（根 dto/、vo/ 包）；层间转换必须 MapStruct（convert/ 包）
- 依赖注入 `private final` + `@RequiredArgsConstructor`（或显式构造器，禁 @Autowired 字段注入）
- 禁弃用 API：`@MockBean` 已弃用，用 `@MockitoBean`（org.springframework.test.context.bean.override.mockito）
- 禁全路径类名；禁手写样板转换；MapStruct 改造后必须 `mvn.cmd clean` 重编译
- 测试与实现同次提交；因改动失效的旧测试直接删除；git add 只加任务文件禁 `git add -A`；提交无需 --no-verify（pre-commit 已正常）
- 本 service 主表用 `this.lambdaQuery()` 链式 + 按需取列；查询必带分页
- 覆盖率目标：单类 ≥80%（jacoco.csv 列 $8=LINE_MISSED、$9=LINE_COVERED）；总门禁 BUNDLE 0.80
- 构建命令：`cd backend && mvn.cmd test`（全量测试）、`mvn.cmd verify`（全门禁）、`mvn.cmd spotless:apply`（格式化）

---

### Task 0: Docker 环境启动 + Testcontainers 冒烟验证（A1 前置）

**Files:**
- Modify: `backend/pom.xml`（加 test 依赖）
- Create: `backend/src/test/java/com/commerce/rag/test/TestcontainersSmokeTest.java`
- Create: `backend/src/test/java/com/commerce/rag/test/IntegrationTestBase.java`（本任务仅建骨架，Task 9 完善）

**Interfaces:**
- Consumes: 无
- Produces: pom 三个 test 依赖；`TestcontainersSmokeTest` 证明 Docker+Testcontainers 可用

- [ ] **Step 1: 启动 Docker Desktop 并确认 daemon 可达**

```bash
# Windows: 启动 Docker Desktop（后台），等待 daemon
start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe" 2>/dev/null || true
# 轮询等待（最多 120s）
for i in $(seq 1 24); do docker info --format '{{.ServerVersion}}' >/dev/null 2>&1 && break; sleep 5; done
docker info --format '{{.ServerVersion}}'
```
Expected: 输出 ServerVersion（如 27.x）；若 120s 后仍失败，停止并报告（阻塞点：需用户介入启动 Docker）

- [ ] **Step 2: pom.xml 添加 test 依赖**

在 `backend/pom.xml` 现有 `spring-boot-starter-test`（line ~217-223）旁添加（Boot BOM 管理版本，不写版本号）：
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 写冒烟测试（只验证容器可起，不起 Spring 上下文）**

`backend/src/test/java/com/commerce/rag/test/TestcontainersSmokeTest.java`：
```java
package com.commerce.rag.test;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.Connection;
import java.sql.DriverManager;

/** Testcontainers 冒烟测试：验证 Docker 环境可拉取并启动 PG/Redis 容器（A1 前置） */
@Testcontainers
class TestcontainersSmokeTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test");

    @Test
    void 容器可启动且可连接() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            conn.createStatement().execute("SELECT 1");
        }
        redis.execInContainer("redis-cli", "-a", "test", "ping");
    }
}
```

- [ ] **Step 4: 运行冒烟测试**

Run: `cd backend && mvn.cmd test -Dtest=TestcontainersSmokeTest`
Expected: PASS（首次拉取 postgres:16-alpine + redis:7-alpine 镜像，约几分钟）；若镜像拉取失败（网络/代理）报告阻塞点

- [ ] **Step 5: 提交**

```bash
git add backend/pom.xml backend/src/test/java/com/commerce/rag/test/TestcontainersSmokeTest.java
git commit -m "test: Testcontainers 冒烟验证（PG/Redis 容器可起，A1 前置）"
```

---

### Task 1: B2 — ChatStreamEntry 抽取 + ChatRequest 迁根 dto/

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/stream/ChatStreamEntry.java`
- Modify: `backend/src/main/java/com/commerce/rag/controller/ChatController.java`（瘦身）
- Modify: `backend/src/main/java/com/commerce/rag/controller/StudentController.java`（ChatController → ChatStreamEntry）
- Modify: `backend/src/main/java/com/commerce/rag/controller/dto/ChatRequest.java` → 移到 `backend/src/main/java/com/commerce/rag/dto/ChatRequest.java`
- Modify: `backend/src/main/java/com/commerce/rag/exception/CancelledException.java`、`stream/MemoryStreamBridge.java`、`worker/ChatRequestWorker.java`（ChatRequest import 更新）
- Create: `backend/src/test/java/com/commerce/rag/stream/ChatStreamEntryTest.java`（编排断言从 ChatControllerTest 迁移）
- Modify: `backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java`（瘦身为薄端点测试）、`backend/src/test/java/com/commerce/rag/controller/StudentControllerTest.java`、`backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`（import 更新）

**Interfaces:**
- Consumes: `controller/dto/ChatRequest`（record：`query()` / `sessionId()`）
- Produces: `stream/ChatStreamEntry`（@Component，方法签名与现 ChatController 三个端点一致）：
  - `SseEmitter chat(HttpServletRequest httpRequest, ChatRequest request)`
  - `ResponseEntity<Void> cancel(String runId, HttpServletRequest httpRequest)`
  - `SseEmitter reconnect(String runId, long lastEventId, HttpServletRequest httpRequest)`
  - `@PostConstruct init()`（心跳 scheduler）、`@PreDestroy destroy()`

- [ ] **Step 1: 迁移 ChatRequest 到根 dto/ 包**

移动 `controller/dto/ChatRequest.java` → `dto/ChatRequest.java`（package 声明同步改），更新 4 个主类 import：`ChatController`、`StudentController`、`CancelledException`、`MemoryStreamBridge`、`ChatRequestWorker`（import `com.commerce.rag.controller.dto.ChatRequest` → `com.commerce.rag.dto.ChatRequest`）；测试 import 同步（ChatControllerTest/StudentControllerTest/ChatRequestWorkerTest）。先跑 `mvn.cmd test` 确认纯迁包无破坏。

- [ ] **Step 2: 创建 ChatStreamEntry（原样搬移 ChatController 编排逻辑）**

新建 `stream/ChatStreamEntry.java`，@Component；将现 ChatController.java 的**全部**逻辑搬入（chat/cancel/reconnect 三个公开方法 + startHeartbeat/truncateTitle/replayFromPg/isTerminalStatus/checkRunOwnership/normalizeToolPayload/escapeJson 私有方法 + init/destroy 心跳生命周期 + 类级 javadoc 线程模型说明），注入原 8 个依赖（构造器：worker/bridge/chatRunService/chatSessionService/chatMessageService/redisTemplate/streamProperties/objectMapper）。类注释更新为「Chat 对话编排入口（SSE 生命周期/Redis 入队/心跳/归属校验），供 ChatController 与 StudentController 共用」。

- [ ] **Step 3: ChatController 瘦身**

保留 `@RestController` / `@RequestMapping("/api/v1/student/chat")` / `@PreAuthorize("hasAnyRole('STUDENT', 'TEACHER', 'SUPER_ADMIN')")`；删除全部编排逻辑与 entity import，改为：
```java
private final ChatStreamEntry chatStreamEntry;
// 构造器注入 chatStreamEntry
@PostMapping
public SseEmitter chat(HttpServletRequest httpRequest, @RequestBody ChatRequest request) {
    return chatStreamEntry.chat(httpRequest, request);
}
@PostMapping("/{runId}/cancel")
public ResponseEntity<Void> cancel(@PathVariable String runId, HttpServletRequest httpRequest) {
    return chatStreamEntry.cancel(runId, httpRequest);
}
@GetMapping("/{runId}/reconnect")
public SseEmitter reconnect(@PathVariable String runId, @RequestParam(defaultValue = "0") long lastEventId, HttpServletRequest httpRequest) {
    return chatStreamEntry.reconnect(runId, lastEventId, httpRequest);
}
```

- [ ] **Step 4: StudentController 改注入 ChatStreamEntry**

删除 `private final ChatController chatController;` 字段与构造器参数，改 `private final ChatStreamEntry chatStreamEntry;`；J8 `/chat/stream` 改为 `return chatStreamEntry.chat(request, chatRequest);`。类 javadoc 中「J8 SSE 流式对话（转发到 ChatController）」改「（经 ChatStreamEntry，不再依赖 ChatController）」。

- [ ] **Step 5: 测试迁移**

- 新建 `ChatStreamEntryTest`：将现 ChatControllerTest 中编排断言（mock worker/bridge/chatRunService/chatSessionService/chatMessageService/redisTemplate/streamProperties/objectMapper 的 chat 成功/入队失败回滚、cancel、reconnect 终态补发/降级 replayFromPg）原样迁移，仅改构造目标为 ChatStreamEntry（@InjectMocks 具体类）
- ChatControllerTest 瘦身：mock ChatStreamEntry，断言 3 端点转发调用（chat 传参一致、cancel/reconnect 转发）
- StudentControllerTest：mock ChatStreamEntry（替换原 ChatController mock）；chatStream 断言转发
- ChatRequestWorkerTest / 其它引用 ChatRequest 的测试：import 更新

- [ ] **Step 6: 全量测试 + 提交**

Run: `cd backend && mvn.cmd test` Expected: 全部通过（551 + 新增，无失败）
```bash
git add backend/src/main/java backend/src/test/java
git commit -m "refactor: ChatStreamEntry 抽取（SSE 编排收编，ChatController/StudentController 共享，ChatRequest 迁根 dto/）"
```

---

### Task 2: B1 第一批 — AdminLoginRecord / AdminSchedule / Feedback VO 化

**Files:**
- Modify: `service/ISysLoginRecordService.java` + `service/impl/SysLoginRecordServiceImpl.java`
- Modify: `service/ICourseScheduleService.java` + `service/impl/CourseScheduleServiceImpl.java`
- Modify: `service/IUserFeedbackService.java` + `service/impl/UserFeedbackServiceImpl.java`
- Modify: `convert/AdminLoginRecordConverter.java`、`convert/ScheduleConverter.java`、`convert/UserFeedbackConverter.java`（补 toVO 方法）
- Create: `vo/CourseScheduleVO.java`
- Modify: `controller/AdminLoginRecordController.java`、`controller/AdminScheduleController.java`、`controller/FeedbackController.java`
- Modify: 对应测试 `controller/AdminLoginRecordControllerTest.java`、`controller/AdminScheduleControllerTest.java`、`controller/FeedbackControllerTest.java`、`service/SysLoginRecordServiceTest.java` 等（stub 改 VO）

**Interfaces:**
- Consumes: `vo/SysLoginRecordVO`、`vo/SysTokenBlacklistVO`、`vo/UserFeedbackVO`（已存在）
- Produces: `vo/CourseScheduleVO(Long id, Long courseId, String title, String startTime, String endTime, String location, String teacherName, String remark, LocalDateTime createdAt, LocalDateTime updatedAt)`（字段以 CourseSchedule entity 为准对齐，record 定义）
  - `ISysLoginRecordService.findPage(int,int,Long,String,String)` → `IPage<SysLoginRecordVO>`
  - `ISysLoginRecordService.findBlacklistPage(int,int,Long,String,String)` → `IPage<SysTokenBlacklistVO>`
  - `ICourseScheduleService.findByCourseId(Long,Long,boolean)` → `List<CourseScheduleVO>`；`create(Long,CreateScheduleRequest,Long,boolean)` → `CourseScheduleVO`；`findById(Long,Long,boolean)` → `CourseScheduleVO`
  - `IUserFeedbackService.create(Long,Long,Long,Boolean,String)` → `UserFeedbackVO`

- [ ] **Step 1: 查调用方确认改签名安全**

`grep -rn 'sysLoginRecordService\.\|scheduleService\.\|feedbackService\.' src/main/java --include='*.java' | grep -v controller/`——若 service/worker 中也有调用（如 DashboardService 用 findStats 不算），对应方法需新增 VO 方法而非改签名；记录结论。

- [ ] **Step 2: 转换器补方法（MapStruct）**

`AdminLoginRecordConverter` 补 `SysLoginRecordVO toVO(SysLoginRecord e)` + `SysTokenBlacklistVO toBlacklistVO(SysTokenBlacklist e)`（已有则跳过）；`ScheduleConverter` 补 `CourseScheduleVO toVO(CourseSchedule e)`；`UserFeedbackConverter` 补 `UserFeedbackVO toVO(UserFeedback e)`。`mvn.cmd clean compile` 验证生成。

- [ ] **Step 3: service 改签名 + impl 转换**

三个 impl 对应方法末尾加 `return converter.toVO(entity);`（分页用 `page.convert(converter::toVO)`）；保留无权限/内部重载的 entity 版方法不动。

- [ ] **Step 4: controller 适配**

- AdminLoginRecordController：`listLoginRecords` 直接 `ApiResponse.ok(result)`（不再手动 map 转 VO，删除 VO 组装代码）；`listBlacklist` 同
- AdminScheduleController：`listByCourse`/`create`/`getById` 直接返回 VO（删除 controller 内转换）
- FeedbackController：`create` 直接返回 `ApiResponse.ok(feedbackService.create(...))`（若返回结构现为 toVO 包一层则简化为直接返回）

- [ ] **Step 5: 测试适配**

各 controller 测试中 `when(service.findPage(...)).thenReturn(IPage<entity>)` 改 `IPage<VO>`，断言响应体与 VO 字段一致；service 测试补/改 impl 转换断言（MapStruct 生成实现可 mock 或真实调用）。

- [ ] **Step 6: 全量测试 + 提交**

Run: `cd backend && mvn.cmd test` Expected: 全绿
```bash
git add backend/src/main/java backend/src/test/java
git commit -m "refactor: B1-1 AdminLoginRecord/AdminSchedule/Feedback 出参 VO 化（service 返回 VO，controller 零 entity）"
```

---

### Task 3: B1 第二批 — AdminCourse / AdminSession / Student VO 化

**Files:**
- Modify: `service/ICourseService.java` + `service/impl/CourseServiceImpl.java`
- Modify: `service/IChatSessionService.java` + `service/impl/ChatSessionServiceImpl.java`
- Modify: `service/IChatMessageService.java` + `service/impl/ChatMessageServiceImpl.java`
- Modify: `service/IEnrollmentService.java` + `service/impl/EnrollmentServiceImpl.java`
- Modify: `service/IDocumentChunkService.java` + `service/impl/DocumentChunkServiceImpl.java`
- Modify: `convert/CourseConverter.java`、`convert/ChatSessionConverter.java`、`convert/StudentConverter.java`、`convert/DocumentChunkConverter.java`（补方法）
- Modify: `controller/AdminCourseController.java`、`controller/AdminSessionController.java`、`controller/StudentController.java`
- Modify: 对应测试（AdminCourseControllerTest/AdminSessionControllerTest/StudentControllerTest/CourseServiceTest/ChatSessionServiceTest/EnrollmentServiceTest/DocumentChunkServiceTest 等）

**Interfaces:**
- Consumes: `vo/ChatSessionVO`（含 userId）、`vo/SessionVO`、`vo/ChunkVO`、`vo/ChunkBriefVO`、`vo/ChunkContextVO`（含 courseId/parentChunkId/prevChunkId/nextChunkId/parent/prev/next）、`vo/StudentCourseVO`、`dto/CourseDTO`（均存在）
- Produces:
  - `ICourseService.findPage(int,int,String,String,Long)` → `IPage<CourseDTO>`；`createCourse(CreateCourseRequest,Long)` → `CourseDTO`；`findById(Long,Long)` → `CourseDTO`（单参 `findById(Long)` 保留 entity 返回）
  - `IChatSessionService.findAllSessions(int,int)` → `IPage<ChatSessionVO>`；`findById(Long)` → `ChatSessionVO`；`findSessionsByUser(Long,int,int)` → `IPage<SessionVO>`；`createSession(Long,String)` → `SessionVO`（ChatController/StudentController 均只需 id）
  - `IChatMessageService.findBySessionId(Long)` → `List<ChatMessageVO>`（`findByRunId` 本任务不动，Task 4 处理）
  - `IEnrollmentService.findStudentCourses(Long)` 保留（AdminEnrollmentController 用）；新增 `List<StudentCourseVO> findStudentCoursesAsVO(Long studentId)`
  - `IDocumentChunkService` 新增：`ChunkContextVO findContext(Long chunkId)`（内部 findById + parent/prev/next 组装）、`List<ChunkVO> findByCourseIdAsVO(Long courseId)`、`IPage<ChunkBriefVO> findByCourseIdDefaultAsVO(int page, int size)`

- [ ] **Step 1: 查调用方**

`grep -rn 'courseService\.\|sessionService\.\|messageService\.\|enrollmentService\.\|documentChunkService\.' src/main/java --include='*.java' | grep -v 'controller/'`——确认每个改签名方法无 service/worker 调用方；有则改走「新增 VO 方法」。

- [ ] **Step 2: 转换器补方法**

- `CourseConverter`：补 `CourseDTO toDTO(CourseInfo)`（无关系版，若已有 toDTO 则复用）
- `ChatSessionConverter`：补 `ChatSessionVO toVO(ChatSession)`、`SessionVO toSessionVO(ChatSession)`
- `DocumentChunkConverter`：补 `ChunkVO toChunkVO(DocumentChunk)`、`ChunkBriefVO toChunkBriefVO(DocumentChunk)`、`ChunkContextVO toContextVO(DocumentChunk chunk, DocumentChunk parent, DocumentChunk prev, DocumentChunk next)`（内部复用 toChunkBriefVO 组装三个引用）
- `StudentConverter`：复用现有 toCourseVO/toChunkVO/toChunkBriefVO/toSessionVO/toChunkContextVO（供 service 调用，转换器跨层共用合法）
- `mvn.cmd clean compile` 验证 MapStruct 生成

- [ ] **Step 3: service 改签名 + impl 转换**

各 impl 方法返回处转换（entity→VO/DTO，`page.convert(...)` 处理分页）。`DocumentChunkServiceImpl.findContext` 实现：
```java
@Override
public ChunkContextVO findContext(Long chunkId) {
    DocumentChunk chunk = this.getById(chunkId); // 完整实体确需（字段多向使用）
    if (chunk == null) return null;
    DocumentChunk parent = chunk.getParentChunkId() == null ? null : this.getById(chunk.getParentChunkId());
    DocumentChunk prev = chunk.getPrevChunkId() == null ? null : this.getById(chunk.getPrevChunkId());
    DocumentChunk next = chunk.getNextChunkId() == null ? null : this.getById(chunk.getNextChunkId());
    return converter.toContextVO(chunk, parent, prev, next);
}
```

- [ ] **Step 4: controller 适配**

- AdminCourseController：`list` 直接用 `result`（IPage<CourseDTO>），删除 `.map(c -> courseService.toDTO(c, false))`；`create`/`getById` 直接返回 DTO
- AdminSessionController：`list`/`detail` 直接用 VO（删除手动转 VO 组装，ChatSessionDetailVO 组装保留——若 detail 由 messageVO + sessionVO 组装则在 controller 内组装 VO 对象，不再碰 entity）
- StudentController：`myCourses` → `enrollmentService.findStudentCoursesAsVO(userId)`（删除 converter 调用）；`courseMaterials` → `documentChunkService.findByCourseIdAsVO(id)`；`knowledgeBase` → `findByCourseIdDefaultAsVO`；`chunkContext` → `chunk = documentChunkService.findContext(id)`（chunk 为 null 抛 404），权限校验改读 `chunk.courseId()`；`mySessions`/`createSession` 直接用 service 返回的 SessionVO

- [ ] **Step 5: 同步适配 ChatStreamEntry（签名变更的连锁编译点）**

Task 1 创建的 ChatStreamEntry 调用了 `chatSessionService.findById(sessionId)`（改签名后返回 ChatSessionVO）与 `chatSessionService.createSession(userId, title)`（改签名后返回 SessionVO），本任务改签名后必须同步适配：
- `chat()`：`ChatSessionVO session = chatSessionService.createSession(...)` → `sessionId = session.id()`；归属校验 `ChatSessionVO session = chatSessionService.findById(sessionId)` → `!session.userId().equals(userId)`
- 适配后 `mvn.cmd clean compile` 确认无编译错误（MapStruct 改动需 clean）

- [ ] **Step 6: 测试适配**

各 controller 测试 stub 改 VO；新增 findContext/findStudentCoursesAsVO/findByCourseIdAsVO/findByCourseIdDefaultAsVO 的 service 测试用例；StudentControllerTest 的 chunkContext 断言改 VO 字段访问（`.courseId()` 等）；ChatStreamEntryTest 的 chat 归属/创建会话 stub 改 ChatSessionVO/SessionVO 返回值。

- [ ] **Step 7: 全量测试 + 提交**

Run: `cd backend && mvn.cmd test` Expected: 全绿
```bash
git add backend/src/main/java backend/src/test/java
git commit -m "refactor: B1-2 AdminCourse/AdminSession/Student 出参 VO 化（findContext 下沉组装，controller 零 entity）"
```

---

### Task 4: B1 第三批 — ChatStreamEntry entity 流转清理

**Files:**
- Create: `vo/ChatRunVO.java`、`convert/ChatRunConverter.java`
- Modify: `service/IChatRunService.java` + `service/impl/ChatRunServiceImpl.java`
- Modify: `service/IChatMessageService.java` + `service/impl/ChatMessageServiceImpl.java`（findByRunId → VO）
- Modify: `stream/ChatStreamEntry.java`（entity → VO）
- Modify: `worker/ChatRequestWorker.java`（若其调用 createRun/findById/findByRunId，则保留 entity 方法并在 worker 继续使用 entity 版——worker 不在本 spec 的 controller 范围，实体流转保持 service+worker 内部）
- Modify: 对应测试（ChatStreamEntryTest/ChatRequestWorkerTest/ChatRunServiceTest/ChatMessageServiceTest）

**Interfaces:**
- Consumes: `vo/ChatMessageVO`（已存在：id/role/content/messageType/intentType/runId/seq/createdAt）、`vo/ChatSessionVO`（含 userId）
- Produces: `vo/ChatRunVO(Long id, Long sessionId, Long userId, String status, LocalDateTime createdAt, LocalDateTime updatedAt)`（字段以 ChatRun entity 对齐）
  - `IChatRunService.createRun(Long,Long)` → `ChatRunVO`；`findById(Long)` → `ChatRunVO`（若 worker 也调用则保留 entity 版私有/重载）
  - `IChatMessageService.findByRunId(Long)` → `List<ChatMessageVO>`

- [ ] **Step 1: 查 ChatRunService/ChatMessageService 调用方**

`grep -rn 'chatRunService\.\|chatMessageService\.' src/main/java --include='*.java'`——确认 worker 是否用 findByRunId/createRun/findById。worker 用则：`IChatRunService` 保留 entity 版（改名 `findByIdEntity`/`createRunEntity` 或加包级方法）+ 新增 VO 版供 ChatStreamEntry；`IChatMessageService.findByRunId` 同样处理。

- [ ] **Step 2: 新建 ChatRunVO + ChatRunConverter**

record `ChatRunVO`（字段见上）；`ChatRunConverter` 接口 `ChatRunVO toVO(ChatRun e)`。`mvn.cmd clean compile` 验证。

- [ ] **Step 3: service 改签名 + ChatStreamEntry 适配**

- ChatStreamEntry.chat：`chatSessionService.findById(sessionId)` 返回 ChatSessionVO → 归属校验改 `session == null || !session.userId().equals(userId)`；`chatRunService.createRun(...)` 返回 ChatRunVO → `run.id().toString()`
- checkRunOwnership：`chatRunService.findById(runIdLong)` 返回 ChatRunVO → `run == null || !run.userId().equals(userId)`
- reconnect 内 `run.getStatus()` → `run.status()`；`isTerminalStatus` 入参不变
- replayFromPg：`chatMessageService.findByRunId(runIdLong)` 返回 `List<ChatMessageVO>` → `msg.role()`/`msg.messageType()`/`msg.content()`（record 访问器）
- 删除 ChatStreamEntry 的 entity import

- [ ] **Step 4: 测试适配**

ChatStreamEntryTest：stub `chatSessionService.findById` 返回 ChatSessionVO、`chatRunService.createRun/findById` 返回 ChatRunVO、`chatMessageService.findByRunId` 返回 List<ChatMessageVO>；断言不变。ChatRunServiceTest/ChatMessageServiceTest 适配新签名。ChatRequestWorkerTest 若 worker 用 entity 版则 stub 对应方法。

- [ ] **Step 5: 全量测试 + 提交**

Run: `cd backend && mvn.cmd test` Expected: 全绿
```bash
git add backend/src/main/java backend/src/test/java
git commit -m "refactor: B1-3 ChatStreamEntry entity 流转清理（ChatRunVO 新建，ChatMessage/ChatSession VO 化）"
```

- [ ] **Step 6: 验证 B1 全部完成**

Run: `grep -rln "import com.commerce.rag.entity" src/main/java/com/commerce/rag/controller/`
Expected: 无输出（归零）

---

### Task 5: A2 第一批 — MinioStorageService / PromptLoader / ReminderHook 补测

**Files:**
- Modify: `src/test/java/com/commerce/rag/storage/MinioStorageServiceTest.java`
- Modify: `src/test/java/com/commerce/rag/bot/graph/PromptLoaderTest.java`
- Modify: `src/test/java/com/commerce/rag/bot/hook/ReminderHookTest.java`

**Interfaces:**
- Consumes: 现有测试类结构（Mockito 纯单测，mock MinioClient / 直接构造 PromptLoader / mock promptLoader+thread state）

- [ ] **Step 1: MinioStorageServiceTest 补测（38.3% → ≥80%）**

现有 23/60 行覆盖。补齐用例（mock `MinioClient` 注入构造器；`bucket` 用 @Value 注入——测试中通过反射或构造器不可行则用 `ReflectionTestUtils.setField`）：
- `initBucket`：bucketExists=true → 不调 makeBucket；=false → 调 makeBucket；抛异常 → warn 不抛（catch 分支）
- `uploadFile`：成功返回 `{kbId}/{uuid}.{ext}` objectKey（verify putObject）；putObject 抛 IOException → 上抛
- `downloadFile`：成功返回流；GetObject 异常上抛
- `deleteFile`：成功/失败上抛
- `deleteFiles`：>100 个分两批（verify removeObjects 调用 2 次）；返回 DeleteError → 抛异常；空列表 → 不调用
- `buildObjectKey`（私有，经 uploadFile 覆盖）

- [ ] **Step 2: PromptLoaderTest 补测（71.1% → ≥80%）**

现有 54/76 行。补齐：
- `loadRawAndReplace`：`${placeholder}` 替换成功/未找到占位符原样保留/多级嵌套（a.b.c 展平后 key 前缀保留）
- `loadAndReplace`：同
- `loadRaw`：递归找唯一叶子字符串（多叶子歧义行为按现有实现断言）
- 文件缺失/解析失败异常路径
- 缓存命中：同文件二次 load 不重复读（verify 次数）

- [ ] **Step 3: ReminderHookTest 补测（73.5% → ≥80%）**

现有 36/49 行。补齐：
- `beforeModel`：rewrittenQueries 存在 → 注入 SystemMessage（REPLACE 策略：已存在 marker 时替换而非追加）
- rewrittenQueries 为空/缺失 → 不注入（原样返回）
- promptLoader 抛异常 → 降级不阻断（返回原消息）
- 时间参数格式化分支

- [ ] **Step 4: 单类覆盖率验证 + 提交**

Run: `cd backend && mvn.cmd test && mvn.cmd jacoco:report`（注意先跑 test 刷新 exec），然后：
```bash
awk -F',' '$3=="com.commerce.rag.storage.MinioStorageService"{printf "Minio %.0f%%\n",$9/($8+$9)*100}' target/site/jacoco/jacoco.csv
awk -F',' '$3=="com.commerce.rag.bot.graph.PromptLoader"{printf "PromptLoader %.0f%%\n",$9/($8+$9)*100}' target/site/jacoco/jacoco.csv
awk -F',' '$3=="com.commerce.rag.bot.hook.ReminderHook"{printf "ReminderHook %.0f%%\n",$9/($8+$9)*100}' target/site/jacoco/jacoco.csv
```
Expected: 三个均 ≥80%；不足则继续补（jacoco.csv 列 $8=LINE_MISSED、$9=LINE_COVERED）
```bash
git add backend/src/test/java
git commit -m "test: A2-1 MinioStorageService/PromptLoader/ReminderHook 补测至 80%+"
```

---

### Task 6: A2 第二批 — DocumentChunkServiceImpl / SearchKnowledgeTool 补测

**Files:**
- Modify: `src/test/java/com/commerce/rag/service/DocumentChunkServiceTest.java`
- Modify: `src/test/java/com/commerce/rag/bot/tool/SearchKnowledgeToolTest.java`

- [ ] **Step 1: DocumentChunkServiceTest 补测（61.8% → ≥80%）**

现有 102/165 行。补齐（mock IDocumentChunkService 依赖的 mapper/etlPipeline/fusion 等，按现有测试的注入方式）：
- `findById`：有/无权限两个重载、null 返回、checkOwnership 不匹配抛 BizException
- `findPage`：TEACHER 走 XML 子查询分支（mock mapper 方法）、非 TEACHER 分支、pendingOnly 过滤
- `updateContent`：触发 `etlPipeline.reEmbedAndUpsert`（verify）、内容相同跳过、无权限异常
- `delete`：Milvus 清理调用、软删、权限异常
- `updateCollectionType`：标量变更同步 Milvus、权限异常
- `findContext`：parent/prev/next 各存在/缺失组合
- `findByCourseId`/`findByCourseIdDefault`：正常/空
- `batchUpdate`：按 docId 去重、syncDocToMilvus 调用、异常传播
- `batchCorrected`：状态更新、权限
- `findPending`：分页
- `checkOwnershipBatch`：任一不匹配抛异常

- [ ] **Step 2: SearchKnowledgeToolTest 补测（67.5% → ≥80%）**

现有 79/117 行。补齐（mock FusionService/RerankService/EmbeddingModel/MilvusClientV2）：
- `searchSingle`：dense 检索成功（FloatVec+COSINE）、sparse 检索（EmbeddedText+BM25）、双路任一失败降级另一路
- `buildFilterExpression`：course_id=DEFAULT 单值、多 course_id IN 列表、collection_type 组合
- `searchKnowledge` 入口：空 TypedQuery 列表、异常降级返回空列表、RRF 融合结果排序
- 超时/单点失败隔离（CompletableFuture 异常路径）

- [ ] **Step 3: 单类覆盖率验证 + 提交**

Run: `cd backend && mvn.cmd test && mvn.cmd jacoco:report`，awk 验证两单类 ≥80%，不足继续补。
```bash
git add backend/src/test/java
git commit -m "test: A2-2 DocumentChunkServiceImpl/SearchKnowledgeTool 补测至 80%+"
```

---

### Task 7: A2 第三批 — LeadAgentGraph / ChatRequestWorker 补测

**Files:**
- Modify: `src/test/java/com/commerce/rag/bot/graph/LeadAgentGraphTest.java`
- Modify: `src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`

- [ ] **Step 1: LeadAgentGraphTest 补测（66.2% → ≥80%）**

现有 43/65 行。补齐（mock 12 个构造依赖：searchKnowledgeTool/courseApiTool/queryRewriter/promptLoader 等，`@Value` 注入 runLimit 用 ReflectionTestUtils）：
- `build()`：返回 CompiledGraph 非空；拓扑含 queryRewriteNode + ReactAgent；compile 调用成功
- `buildQueryRewriteNode`（经 build 覆盖）：QueryRewriter 正常返回 3 条以内 → state 写入；异常 → 降级（默认原样查询）
- `buildReactAgent`：methodTools 注册 2 个工具、4 个 hook、1 个 interceptor、ModelCallLimitHook(runLimit)（反射断言 bean 配置）
- `extractLastUserQuery`（私有，经调用路径覆盖）：UserMessage 提取/非 UserMessage 跳过/空列表

- [ ] **Step 2: ChatRequestWorkerTest 补测（67.9% → ≥80%）**

现有 247/364 行。补齐（mock redisTemplate/compiledGraph/BaseCheckpointSaver/transformer/bridge/chatRunService/chatMessageService/streamProperties/runPool/objectMapper；现有测试已覆盖主路径，补齐）：
- `start`/`stop`：线程启动与关闭（verify 调用/状态）
- `cancel`：cancelFlags 写入、runId 非法处理
- `processRequest` 私有（经 consumeLoop mock 触发）：pre-run 快照捕获、ACTIVE 状态写入
- `handleCompleted`/`handleCancelled`/`handleError`：终态持久化分支、checkpoint 回滚、异常吞并
- XPENDING/XCLAIM 回收：pending 消息重新入队路径
- `persistMessages`：批量插入（verify chatMessageService.batchInsert）、空列表跳过

- [ ] **Step 3: 单类覆盖率验证 + 提交**

Run: `cd backend && mvn.cmd test && mvn.cmd jacoco:report`，awk 验证两单类 ≥80%，不足继续补。
```bash
git add backend/src/test/java
git commit -m "test: A2-3 LeadAgentGraph/ChatRequestWorker 补测至 80%+"
```

---

### Task 8: A2 第四批 — ChatStreamEntry 补测至 80%+

**Files:**
- Modify: `src/test/java/com/commerce/rag/stream/ChatStreamEntryTest.java`

- [ ] **Step 1: 覆盖缺口分析**

Run: `cd backend && mvn.cmd test && mvn.cmd jacoco:report`，`awk -F',' '$3=="com.commerce.rag.stream.ChatStreamEntry"{printf "%d missed/%d covered\n",$8,$9}' target/site/jacoco/jacoco.csv`——定位未覆盖行。

- [ ] **Step 2: 补齐分支（目标 ≥80%）**

在迁移来的编排断言基础上补齐：
- `chat`：query 空/空白 → BizException(BAD_REQUEST)；sessionId 为 null → createSession 路径；sessionId 归属不匹配 → FORBIDDEN；XADD 异常 → 回滚 ERROR + removeRing（finally）+ SERVICE_UNAVAILABLE；updateLastMessageAt 调用
- `cancel`：checkRunOwnership 通过/runId 非法 404/不属于用户 404；worker.cancel 调用
- `reconnect`：replayAndSubscribe=true → 心跳启动；=false 且 run 终态 → 补发 end；=false 且 run 活跃且 subscribe=false 且终态 → 补发 end+complete；=false 且 run 活跃 subscribe=true → 心跳；PG 无数据且 run 活跃 → 仅订阅；PG 无数据且 run 终态 → REPLAY_FAILED error 事件
- 私有：`startHeartbeat`（send 异常 → cancel 定时器）、`truncateTitle`（>30 字符加省略号/空串）、`normalizeToolPayload`（新格式透传/旧格式重建 toolCall/result/解析失败原样）、`escapeJson`（含换行/引号/反斜杠）、`replayFromPg`（USER 角色跳过/thinking/TOOL_CALL/TOOL_RESULT/DELTA 各事件类型/发送 IOException break/runId 解析失败 -1）
- 心跳调度器用真实 scheduler 或 mock，避免线程泄漏（测试后 @AfterEach 清理）

- [ ] **Step 3: 单类覆盖率验证 + 提交**

awk 验证 ≥80%，不足继续补。
```bash
git add backend/src/test/java
git commit -m "test: A2-4 ChatStreamEntry 补测至 80%+"
```

---

### Task 9: A1 集成测试基建 + 3 个核心链路用例

**Files:**
- Create: `backend/src/test/resources/application-test.yml`
- Create: `backend/src/test/java/com/commerce/rag/test/IntegrationTestBase.java`（完善，替代 Task 0 骨架）
- Create: `backend/src/test/java/com/commerce/rag/integration/AuthIntegrationTest.java`
- Create: `backend/src/test/java/com/commerce/rag/integration/ChatFlowIntegrationTest.java`
- Create: `backend/src/test/java/com/commerce/rag/integration/SecurityIntegrationTest.java`
- Modify: `backend/src/test/java/com/commerce/rag/test/TestcontainersSmokeTest.java`（可保留或并入基建）

**Interfaces:**
- Consumes: Task 0 的 testcontainers 依赖；Spring Boot 3.5 全上下文
- Produces: `IntegrationTestBase`（@Testcontainers + @SpringBootTest(RANDOM_PORT) + @MockitoBean ChatModel/EmbeddingModel/RerankModel + @DynamicPropertySource）；3 个集成测试类

- [ ] **Step 1: application-test.yml**

```yaml
# 集成测试配置：覆盖数据源/Redis 由 Testcontainers @DynamicPropertySource 注入；
# Flyway 开启（V7 checkpoint 三表是 GraphConfig.postgresSaver 前置条件，与生产一致）
spring:
  flyway:
    enabled: true
  ai:
    dashscope:
      api-key: test-dummy-key
minio:
  endpoint: http://localhost:19000   # 不可达端口，MinioStorageService @PostConstruct 降级
milvus:
  host: localhost
  port: 19531                        # 不可达端口，MilvusCollectionInitializer 降级
```
（若 Flyway 迁移在集成上下文失败——如 V7 依赖 SAA 特有 SQL——则改 `spring.flyway.enabled: false` 并手动执行 `src/main/resources/db/migration` 全部脚本，记录实测结论）

- [ ] **Step 2: IntegrationTestBase**

```java
package com.commerce.rag.test;

/** 集成测试基类：Testcontainers PG+Redis + Spring Boot 全上下文（RANDOM_PORT），
 *  LLM 模型 bean 以 @MockitoBean 替换（DashScope 空 key 不启动真实模型）。
 *  注意：PG/Redis 属性统一由 @DynamicPropertySource 注册（不用 @ServiceConnection，
 *  避免与手动注册重复）。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withCommand("redis-server", "--requirepass", "rag_redis_2024")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "rag_redis_2024");
    }

    @MockitoBean
    protected ChatModel chatModel;
    @MockitoBean
    protected EmbeddingModel embeddingModel;
    @MockitoBean
    protected RerankModel rerankModel;
}
```
（注意：PostgresSaver 真实建连 Testcontainers PG，checkpoint 三表由 Flyway V7 创建；若上下文启动因缺表失败，按 Step 1 结论处理。`@MockitoBean` 需 import `org.springframework.test.context.bean.override.mockito.MockitoBean`，ChatModel 等为 `org.springframework.ai.chat.model.ChatModel` / `org.springframework.ai.embedding.EmbeddingModel` / `org.springframework.ai.rerank.RerankModel`）

- [ ] **Step 3: AuthIntegrationTest（注册→登录→JWT→互踢→登出）**

```java
package com.commerce.rag.integration;

/** 认证全链路集成测试：PG 用户 → 登录签发 JWT → 受保护接口 → 双设备互踢（FOR UPDATE+Lua）→ 登出黑名单 */
class AuthIntegrationTest extends IntegrationTestBase {
    // 使用 TestRestTemplate 调 RANDOM_PORT 真实端点：
    // 1. POST /api/v1/auth/register（或直接预置 sys_user，按现有 AuthController 端点）
    // 2. POST /api/v1/auth/login → 断言 200 + LoginResponse.accessToken
    // 3. 带 token GET 受保护端点（如 /api/v1/student/courses）→ 200
    // 4. 第二设备再登录 → 首 token 调受保护端点 → 401（互踢，PG FOR UPDATE 锁生效）
    // 5. POST /api/v1/auth/logout → 黑名单（Redis+PG 双写），旧 token → 401
    // 前置：@Sql 清理 sys_user/sys_login_record/sys_token_blacklist
}
```
（具体端点路径以 AuthController 实际 @RequestMapping 为准，先读 `controller/AuthController.java`）

- [ ] **Step 4: ChatFlowIntegrationTest（建会话→发消息入队→cancel）**

登录拿 token → `POST /api/v1/student/sessions` 建会话 → `POST /api/v1/student/chat`（ChatRequest JSON）→ 断言 200 + SseEmitter 建立 → `POST /api/v1/student/chat/{runId}/cancel` → 204；随后查 PG chat_run 状态（JdbcTemplate）为 CANCELLED 或断言 cancelFlags 生效。不等待 LLM 流式完成（mock 模型不产出真实流）。

- [ ] **Step 5: SecurityIntegrationTest（401/403/200）**

- 无 token 访问受保护端点 → 401
- 注册普通 STUDENT 用户登录 → 访问 `hasRole('SUPER_ADMIN')` 端点（如管理端某端点）→ 403
- 正确角色 → 200
（具体端点按 SecurityConfig 授权规则选择）

- [ ] **Step 6: 运行集成测试**

Run: `cd backend && mvn.cmd test -Dtest='*IntegrationTest'`
Expected: 3 类全过（真实连接 Testcontainers PG/Redis，日志可见容器启动）；若有 bean 启动失败，按失败点 @MockitoBean 追加（如 CourseApiTool 依赖的模型类）
若上下文启动超时（首次 Flyway 迁移慢），`mvn.cmd -Dtest='*IntegrationTest' -Dspring.test.timeout=180 test` 调整超时。

- [ ] **Step 7: 全量测试 + 提交**

Run: `cd backend && mvn.cmd test` Expected: 全绿（含集成测试）
```bash
git add backend/pom.xml backend/src/test
git commit -m "test: A1 集成测试基建（Testcontainers PG+Redis）+ 认证/会话流/Security 三链路用例"
```

---

### Task 10: C3 mapper XML 执行级测试（5 个）

**Files:**
- Create: `backend/src/test/java/com/commerce/rag/mapper/DocumentChunkMapperXmlTest.java`
- Create: `backend/src/test/java/com/commerce/rag/mapper/SysLoginRecordMapperXmlTest.java`
- Create: `backend/src/test/java/com/commerce/rag/mapper/SysTokenBlacklistMapperXmlTest.java`
- Create: `backend/src/test/java/com/commerce/rag/mapper/SysUserMapperXmlTest.java`
- Create: `backend/src/test/java/com/commerce/rag/mapper/UserFeedbackMapperXmlTest.java`

**Interfaces:**
- Consumes: `IntegrationTestBase`（同上下文注入 mapper 实例）；`mapper/*.xml` 5 个（只读）
- Produces: 5 个 mapper 执行级测试类（真实 SQL 断言）

- [ ] **Step 1: 数据准备辅助**

各测试类 extends IntegrationTestBase，注入对应 Mapper + JdbcTemplate；`@BeforeEach` 清理相关表（`DELETE FROM xxx`）并用 JdbcTemplate/mapper insert 预置数据。注意 Flyway 已建表，直接 INSERT。

- [ ] **Step 2: 五个测试类用例**

- `DocumentChunkMapperXmlTest.selectPageFilteredByTeacher`：预置 2 个 document（不同 created_by）+ 各自 chunk（含 deleted=1 一条）→ 断言只返回本人 created_by 的未删除 chunk；docId/kbId/pendingOnly 条件各断言一次
- `SysLoginRecordMapperXmlTest`：insert 2 条 ACTIVE + 1 条 REVOKED → selectActiveForUpdate 只返回 ACTIVE（同事务内验证锁不实际并发，仅断言结果集）；updateStatusById 后 status=REVOKED；updateStatusByIdIfActive 对 REVOKED 行不生效（幂等）；updateStatusByUserAndJtiActive 按 jti 生效；selectActiveByUserId 返回活跃记录
- `SysTokenBlacklistMapperXmlTest.countByJti`：命中=1、未命中=0、deleted=1 排除=0
- `SysUserMapperXmlTest.selectByIdsIn`：预置 3 用户 → IN(2 个) 返回 2 条且仅 id/username/display_name 三列（SELECT 投影验证：断言其它字段为 null）
- `UserFeedbackMapperXmlTest`：预置跨两天反馈（不同 intent_type/is_liked）→ selectDailyFeedbackCount 按天分组数正确（to_char 生效）；selectIntentStats 赞/踩 SUM 正确；selectFeedbackStatsByPeriod 周期过滤 + total/liked

- [ ] **Step 3: 运行 + 提交**

Run: `cd backend && mvn.cmd test -Dtest='mapper.*XmlTest'` Expected: 5 类全过（真实 PG 执行 XML SQL）
```bash
git add backend/src/test/java/com/commerce/rag/mapper
git commit -m "test: C3 mapper XML 执行级测试（5 个，真实 PG 断言 SQL 结果）"
```

---

### Task 11: C2 TASK.md 更新

**Files:**
- Modify: `TASK.md`（§2 遗留低覆盖清单 + 路线图）

- [ ] **Step 1: 更新 §2 低覆盖清单**

替换过时数据（DeviceKickService 44%→92.9%、EtlPipeline 37%→80.1%、CustomSummarizationHook 35%→97.9%、CourseQueryService 20%→100%）为最新 8 类清单（MinioStorageService 38.3%、DocumentChunkServiceImpl 61.8%、LeadAgentGraph 66.2%、ChatRequestWorker 67.9%、SearchKnowledgeTool 67.5%、ChatController 69.8%、PromptLoader 71.1%、ReminderHook 73.5%，2026-08-16 实测）并标注「已在本轮全量补测至 ≥80%，jacoco 单类门禁上线」；类名带 Impl 后缀。

- [ ] **Step 2: 更新路线图**

标注：总覆盖率 87.0% 达标（551 测试）、集成测试引入（Testcontainers PG+Redis）、单类 ≥80% 门禁、mapper XML 执行级测试 5 个。

- [ ] **Step 3: 提交**

```bash
git add TASK.md
git commit -m "docs: TASK.md 低覆盖清单与路线图更新（8 类最新数字 + 集成测试/单类门禁）"
```

---

### Task 12: jacoco 单类 ≥80% 门禁 + 豁免清单定稿

**Files:**
- Modify: `backend/pom.xml`（jacoco check 增加 CLASS 规则 + excludes）

- [ ] **Step 1: 统计当前 <80% 类清单**

Run: `cd backend && mvn.cmd verify && awk -F',' '$8>0 && $9/($8+$9)<0.80{printf "%s %.0f%%\n",$3,$9/($8+$9)*100}' target/site/jacoco/jacoco.csv | sort -t' ' -k2`
输出全量 <80% 类。

- [ ] **Step 2: 按原则分类**

- 豁免（纯数据类/建连配置类/生成类）：`entity.*`、`dto.*`、`vo.*`、`record.*`、`enums.*`、`config.MilvusConfig`、`config.GraphConfig`、MapStruct 生成的 `convert.*ConverterImpl`（如出现）
- 不豁免：任何业务类（service/controller/worker/storage/bot/stream/retrieval/etl/auth 等）——若仍 <80%，回到对应 A2 任务继续补测（不允许加进豁免）

- [ ] **Step 3: 配置 excludes**

pom.xml jacoco check 增加规则（与现有 BUNDLE 规则并列）：
```xml
<rule>
    <element>CLASS</element>
    <limits>
        <limit>
            <counter>LINE</counter>
            <value>COVEREDRATIO</value>
            <minimum>0.80</minimum>
        </limit>
    </limits>
</rule>
```
`<excludes>` 按 Step 2 清单配置（包通配：`com.commerce.rag.entity.*` 等）。注意 excludes 同时作用于 report 与 check（如需保持 report 完整，实测后调整——优先保证 check 门禁正确）。

- [ ] **Step 4: 验证门禁**

Run: `cd backend && mvn.cmd verify` Expected: BUILD SUCCESS（若某业务类 <80% 被门禁拦下 → 补测该类的缺口后再跑）

- [ ] **Step 5: 提交**

```bash
git add backend/pom.xml
git commit -m "build: jacoco 单类 ≥80% 门禁 + 豁免清单（纯数据类/建连配置类）"
```

---

### Task 13: 最终全量验证 + 进度文档更新

**Files:**
- Modify: `docs/progress/2026-08-16-宪法落地与待优化清单.md`（或新建当日交接文档）

- [ ] **Step 1: 全门禁验证**

Run: `cd backend && mvn.cmd verify`
Expected: BUILD SUCCESS（spotless + checkstyle + spotbugs + jacoco BUNDLE 0.80 + 单类 0.80 + 集成测试）

- [ ] **Step 2: 交付核对清单**

```bash
# B1: controller entity import 归零
grep -rln "import com.commerce.rag.entity" src/main/java/com/commerce/rag/controller/
# B2: 无 controller 依赖 controller（排除同包互引）
grep -rn "private final ChatController" src/main/java
# A1: 集成测试存在且通过
grep -rln "@SpringBootTest" src/test/java
# A2: 8 类单类覆盖率（从 jacoco.csv 提取）
awk -F',' '$3~/MinioStorageService|DocumentChunkServiceImpl|LeadAgentGraph|ChatRequestWorker|SearchKnowledgeTool|ChatController|PromptLoader|ReminderHook|ChatStreamEntry/{printf "%s %.0f%%\n",$3,$9/($8+$9)*100}' target/site/jacoco/jacoco.csv
# C3: mapper 测试存在
ls src/test/java/com/commerce/rag/mapper/
```
Expected: 全部符合（entity import 无输出；ChatController 无注入；@SpringBootTest 有命中；8 类 + ChatStreamEntry 均 ≥80%；mapper 测试 5 个）

- [ ] **Step 3: 更新进度文档**

更新 `docs/progress/2026-08-16-宪法落地与待优化清单.md`（或新建 2026-08-16 交接文档）：A1/A2/B1/B2/C2/C3 + 单类门禁全部完成，剩余待办 = 仅 S1 主任务（D1）；记录 Testcontainers 基建、VO 化范围、豁免清单、验证结果。git add 后提交：
```bash
git add docs/progress
git commit -m "docs: 会话交接——合规修正全量实施完成（A1/A2/B1/B2/C2/C3+单类门禁，基于 spec 2026-08-16 核验）"
```

- [ ] **Step 4: 收尾报告**

向用户汇报：每项完成状态、覆盖率数字、验证命令输出摘要、遗留观察项（如 controller/dto/ 下其余 14 个 DTO 未归位等历史遗留，不擅自处理）。
