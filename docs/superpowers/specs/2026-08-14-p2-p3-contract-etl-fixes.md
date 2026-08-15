# P2/P3 契约与 ETL 修复 — 设计规格（ETL 幂等 / 跨端契约对齐 / 403 统一 / 死代码清理 / 环境问题）

> 状态：草稿待审。
> 决策来源：2026-08-15 对「待修复 bug 总清单」第三波 P2/P3 的**源码逐条核验**（主 agent 直接核验全部涉及文件 + `docs/plans/2026-07-16-frontend-design.md` 契约行号对齐）；范围按进度文档 §2.2 已锁定决策执行。
> 本 spec 是总清单**第三波 P2/P3**，依赖约束：契约对齐（P2-2）与 403 统一（P2-3）必须同波完成（前端按文档落地前的最后窗口）；环境问题三项为用户 2026-08-15 授权纳入（开发库无业务数据，可 drop 重建）。

## 0. 核验结论与范围

| 条目 | 核验结论 | 证据位置 |
|---|---|---|
| P2-1-1 ETL 无状态守卫重复执行 | 存在 | `EtlPipeline.process` :98-109 无 parse_status 检查；upload/reparse 均可触发 process，无互斥 |
| P2-1-2 embedAndIndex 部分失败误标 INDEXED | 存在 | `EtlPipeline.embedAndIndex` :238-282 每 chunk try-catch continue，循环后无条件 updateDocStatus(INDEXED) |
| P2-1-3 Tika 解析异常流未关闭 | 存在 | `EtlPipeline.parseDocument` :130-138 `inputStream.close()` 在 parser.parse 之后无 finally |
| P2-1-4 文件类型无白名单 + maxFileSizeMb 死配置 | 存在 | `AdminDocumentController.extractFileExtension` :140-146 任意扩展名放行（无扩展名返回 "bin"）；`EtlProperties.maxFileSizeMb`（:28）全仓库零引用 |
| P2-2-1 分片编辑路径冲突 | 存在 | 实现 `PUT /api/v1/admin/chunks/{id}/content`（AdminChunkController:76）vs 前端文档 :933 `PUT /api/v1/admin/chunks/{id}` |
| P2-2-2 batch-corrected 方法冲突 | 存在 | 实现 `@PatchMapping("/batch-corrected")`（AdminChunkController:122）vs 前端文档 :926 `POST` |
| P2-2-3 文档列表筛选参数缺失 | 存在 | 实现 findPage 仅 kbId/page/size（AdminDocumentController:84-91）vs 前端文档 :871 `status/q/sort` |
| P2-2-4 dashboard 统计三接口全部缺失 | 存在 | 实现仅有 `GET /api/v1/admin/feedbacks/stats`（意图分组，无 period）vs 前端文档 :783-786 三接口（dashboard/stats、feedback/stats?period、feedback/trend?days）全部 404 |
| P2-3 403 双轨错误契约（HTTP 200 + body code） | 存在 | `GlobalExceptionHandler` 四个 handler 返回 ApiResponse 无 @ResponseStatus/ResponseEntity（HTTP 恒 200）；仅 handleAccessDeniedException 有 @ResponseStatus(403)；测试只断言 body code 不断言 HTTP 状态 |
| P3-9 死代码四项 | 存在 | `ChatRequestWorker.runSnapshot`（:92/:356/:433 只写不读）；`SseEvent.toSseText`（:27-29 零引用）；`ChatRunService.findActiveRun/cancelRun`（:96-115 全库零调用）；`AuthConfig:28` 排除 `/api/v1/public/**`（无该前缀端点） |
| P3-2 RT 旋转原子性（A11） | 存在（可选） | `AuthController:178-191 isRefreshTokenUsed` + `DeviceKickService:196-209 markRefreshTokenUsed` 两次独立 Redis 调用，并发窗口可双签 |
| 环境-1 V6 TIMESTAMPTZ vs 实体 LocalDateTime | 存在（2026-08-15 真实环境实证） | PG JDBC 42.7.8 下任何查询抛 "Cannot convert TIMESTAMPTZ to LocalDateTime"，登录即 500 |
| 环境-2 V6 jsonb vs varchar 插入 | 存在 | chat_run.meta_json / course_info.tags 等 jsonb 列，实体字段为 String，插入必报 "column is of type jsonb but expression is of type character varying" |
| 环境-3 种子 admin 密码 hash 与注释不符 | 存在 | V6:327-333 注释 "admin123" 但 hash 实际不匹配（真实环境登录 401 实证） |

范围外（记录不修，spec 标注取舍）：P3-6 Ring 背压（B11）、P3-3 黑名单 fail-open（A12）、B8 reclaimPending 双执行、B9 WarningHook 取消路径不清理、A10 设备互踢 PG 审计回填、SOURCES 事件观察项、toolCallId/cancelled 事件契约观察项（前端未实现无法定论）。

**DB 约定**（进度文档锁定）：环境修复直接改 `V6__full_schema_v5.sql` + drop 重建（开发库无业务数据，用户 2026-08-15 确认）；不新增迁移文件。

## 1. P2-1 ETL 幂等与流泄漏（4 子项）

### 1.1 状态守卫（原子抢占）

**方案**：`EtlPipeline.process(Long docId)` 入口改为**条件更新抢占状态**（CAS 式状态机，消除并发窗口）：

```java
// 原子抢占：仅 PENDING/FAILED 可抢到 PARSING（update 返回行数=0 说明已在执行/已完成，跳过）
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
```

- EtlPipeline 内既有 5 处全限定名 `new com.baomidou...LambdaQueryWrapper/LambdaUpdateWrapper`（:249/:609/:624/:635/:647）随本任务一并改为 import + Wrappers 链式（本波触碰文件合规化，宪法强制）。
- 同文件 Java 全局合规（宪法强制）：`parsedTextCache` 字段全路径类名 `java.util.concurrent.ConcurrentHashMap`（:70）改 import 短类名；手写样板构造器（:73-86）改 `@RequiredArgsConstructor`（构造器无初始化逻辑，@RequiredArgsConstructor 生成同签名构造器，测试无需改动）。

- `parseDocument` 内的 `updateDocStatus(docId, "PARSING", null)` 保留（幂等重复置位无害）。
- 任何阶段失败 → `updateDocStatus(FAILED)` → 下次 process 可重试（FAILED 在抢占白名单内）。
- 覆盖链路：upload（PENDING→ETL）、reparse（置 PENDING→ETL）、FAILED 重试（手动重试或前端重新解析）。

### 1.2 embedAndIndex 部分失败标 FAILED

**方案**：`embedAndIndex` 循环内统计失败 chunk 数，循环后：

```java
if (failedCount > 0) {
    updateDocStatus(docId, "FAILED", "分片向量化失败: " + failedCount + "/" + chunks.size());
    return;
}
updateDocStatus(docId, "INDEXED", null);
```

（修复前部分失败仍标 INDEXED → 检索漏召回。）

### 1.3 parseDocument 流关闭

**方案**：`parseDocument` 改为 try-with-resources：

```java
try (InputStream inputStream = minioStorageService.downloadFile(doc.getSourcePath())) {
    ...parser.parse(...)
}
```

（Tika 解析失败/损坏文件路径不再泄漏 MinIO GetObject 句柄。）

### 1.4 文件类型白名单 + maxFileSizeMb 引用

**方案**：`AdminDocumentController.upload`（:49-68）加校验（前端文档限定 PDF/PPTX/DOCX/MD/TXT，最大 100MB）：

```java
// 文件类型白名单（前端文档 2.6.2 限定）
private static final Set<String> ALLOWED_FILE_TYPES = Set.of("pdf", "docx", "pptx", "md", "txt");

// upload 中（extractFileExtension 之后）：
if (!ALLOWED_FILE_TYPES.contains(fileType)) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的文件类型: " + fileType);
}
// 大小校验（引用 EtlProperties.maxFileSizeMb，修复死配置）：
if (fileSize > maxFileSizeMb * 1024 * 1024L) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "文件大小超过限制: " + maxFileSizeMb + "MB");
}
```

- `AdminDocumentController` 注入 `EtlProperties`（配置类注入 controller 属现有模式，`EtlConfig`/`EtlProperties` 已有）；注入方式随本波改 `private final` + `@RequiredArgsConstructor`（Java 全局宪法：禁止手写样板构造器，@RequiredArgsConstructor 生成同签名构造器，测试构造方式不变）。
- 校验位置：controller 层入参校验（符合分层职责），Service.upload 不变。
- 测试：非法类型 400、超限 400、合法类型放行。

### 1.5 验证标准

- EtlPipelineTest 新增：process 抢占（PENDING→执行、INDEXED→跳过、FAILED→重试、并发双调用仅一次执行——mock update 返回 0/1 断言）；embedAndIndex 部分失败标 FAILED；parseDocument 异常时流关闭（mock inputStream.close 被调用）。
- AdminDocumentController 测试（或 DocumentServiceTest 补充）：非法类型/超限 400，合法放行。
- 全量 `mvn.cmd test` 通过。

## 2. P2-2 契约对齐（改后端迁就前端文档）

### 2.0 契约对齐表（前端文档行号 vs 后端现状 vs 目标）

| # | 前端文档定义 | 后端现状 | 目标 |
|---|---|---|---|
| 1 | :933 `PUT /api/v1/admin/chunks/{id}`（改 content→重新向量化） | `PUT /{id}/content`（AdminChunkController:76） | 改 `@PutMapping("/{id}")`，请求体不变（ChunkContentUpdateRequest） |
| 2 | :926 `POST /api/v1/admin/chunks/batch-corrected` | `@PatchMapping("/batch-corrected")`（:122） | 改 `@PostMapping` |
| 3 | :871 `GET /api/v1/admin/documents?kbId=&status=&q=&page=&size=&sort=` | 仅 kbId/page/size（AdminDocumentController:84-91） | 加 `status/q/sort` 参数并透传 Service 过滤 |
| 4 | :783 `GET /api/v1/admin/dashboard/stats` | 无（404） | 新建 AdminDashboardController 提供 |
| 5 | :784 `GET /api/v1/admin/feedback/stats?period=today` | 仅有 `/api/v1/admin/feedbacks/stats`（意图分组，无 period） | 新增 `/api/v1/admin/feedback/stats?period=`（保留现有 /feedbacks 列表/意图统计——前端文档未定义这些接口，无冲突） |
| 6 | :785 `GET /api/v1/admin/documents?sort=created&size=5` | 无 sort 参数 | 由 #3 的 sort 参数一并支持 |
| 7 | :786 `GET /api/v1/admin/feedback/trend?days=7` | 无（404） | 新增 |

### 2.1 分片端点对齐（#1/#2）

- `AdminChunkController.updateContent`：`@PutMapping("/{id}/content")` → `@PutMapping("/{id}")`（与既有 GET /{id}、DELETE /{id} 共存，无路径冲突）。
- `AdminChunkController.batchCorrected`：`@PatchMapping` → `@PostMapping`。

### 2.2 文档列表筛选（#3/#6）

- `AdminDocumentController.findPage` 加 `@RequestParam(required=false) String status`、`String q`、`String sort`（默认 "created"）。
- `DocumentService.findPage` 扩展签名（加 status/q/sort），实现：
  - `status` → `eq(Document::getParseStatus, status)`
  - `q` → `like(Document::getTitle, q)`
  - `sort` → `created`=created_at 降序（默认）/ `updated`=updated_at 降序；非法值按 created 处理（宽松，前端文档未限定取值集合）
  - 既有 TEACHER 数据权限过滤（created_by）保持
- 服务层签名变更影响：`findPage(kbId, page, size, userId, role)` → `findPage(kbId, status, q, sort, page, size, userId, role)`；调用方仅 AdminDocumentController:91 一处。

### 2.3 dashboard 统计三接口（#4/#5/#7）

**新文件** `AdminDashboardController`（`@RequestMapping("/api/v1/admin")`，方法级路径区分，`@PreAuthorize("hasAnyRole('SUPER_ADMIN','TEACHER')")` 与 AdminChunkController 一致）：

- 依赖注入（Java 全局宪法强制）：`AdminDashboardController` 与 `DashboardService` 均用 `private final` 字段 + Lombok `@RequiredArgsConstructor`（禁止字段 @Autowired、禁止手写样板构造器）。

| 端点 | 返回结构 | 统计口径 |
|---|---|---|
| `GET /dashboard/stats` | `ApiResponse<Map>`：`{"documentCount":n,"pendingChunkCount":n}` | documentCount=document 未删计数；pendingChunkCount=chunk 未删且 correction_status='PENDING' 计数 |
| `GET /feedback/stats?period=today` | `ApiResponse<Map>`：`{"sessionCount":n,"likeRate":0.xx}` | period ∈ {today, week, month}，默认 today（today=当天 0 点起；week=近 7 天；month=近 30 天）；sessionCount=chat_run 周期内创建且未删计数；likeRate=周期内 user_feedback 未删中 is_liked=true 数 / 总数（总数 0 时返回 0） |
| `GET /feedback/trend?days=7` | `ApiResponse<List<Map>>`：`[{"date":"2026-08-09","count":n}]` | days 默认 7（1~90 钳位）；user_feedback 未删按 created_at 按天分组计数，近 N 天含 0 补位，日期升序 |

- 分层：`AdminDashboardController` → 新 `DashboardService`（统计查询）→ 现有 mapper。**宪法强制（用户 2026-08-15 定调）：service 禁止拼接 SQL 字符串**——简单计数走 MP `Wrappers.lambdaQuery()` 链式；`feedback/trend` 的按天分组聚合 SQL（to_char/COUNT/GROUP BY）在 `UserFeedbackMapper` 声明方法 + 新建 `src/main/resources/mapper/UserFeedbackMapper.xml` 映射实现（mapper-locations 已配置 `classpath*:mapper/**/*.xml`，当前无任何 XML 文件，本任务新建基建）。
- 与现有 `AdminFeedbackController`（/api/v1/admin/feedbacks 列表/删除/意图统计）**并存不冲突**（前缀不同：feedbacks vs feedback）。

### 2.4 验证标准

- 契约测试（controller 层）：`PUT /chunks/{id}`、`POST /batch-corrected` 端点存在且方法正确（MockMvc 或反射断言 @Mapping 注解）；文档列表 status/q/sort 参数生效。
- DashboardService 单测：各口径计数正确（mock mapper 返回值断言统计逻辑）、period 边界、days 钳位、0 除保护。
- 全量 `mvn.cmd test` 通过。

## 3. P2-3 403 双轨错误契约统一（真实 HTTP 状态码）

### 3.1 方案

`GlobalExceptionHandler` 五个 handler 统一为真实 HTTP 状态码（与 AccessDenied 的 403 风格一致；P0 波 ChatController 的 ConcurrentRunException→409/DataAccessException→503 已用 ResponseEntity 真实状态，风格对齐）：

| handler | 现状 | 目标 |
|---|---|---|
| `handleResponseStatusException` | 返回 ApiResponse（HTTP 200） | 改返回 `ResponseEntity<ApiResponse<Void>>`，`status(e.getStatusCode())` |
| `handleIllegalArgumentException` | 返回 ApiResponse（HTTP 200） | 加 `@ResponseStatus(HttpStatus.BAD_REQUEST)` |
| `handleSecurityException` | 返回 ApiResponse（HTTP 200） | 加 `@ResponseStatus(HttpStatus.FORBIDDEN)` |
| `handleAccessDeniedException` | 已有 @ResponseStatus(403) | 不变 |
| `handleException` | 返回 ApiResponse（HTTP 200） | 加 `@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)` |

（`@ResponseStatus` 与 `ResponseEntity` 两种风格并存：既有 @ResponseStatus(403) 保持，新增统一用 @ResponseStatus 最小改动；ResponseStatusException 因状态码动态必须用 ResponseEntity。）

**已知遗留（spec 标注，不在本波）**：controller 内联 `ApiResponse.error(404, ...)`（如 AdminChunkController:58、AdminDocumentController:93）HTTP 仍 200——前端未实现无紧迫，且逐一改造涉及多 controller，列入后续观察。

### 3.2 验证标准

- `GlobalExceptionHandlerTest` 更新：新增断言 HTTP 状态（@ResponseStatus 从注解反射断言——现有 handleAccessDeniedException 测试 :69 已有同款模式；ResponseStatusException 断言 ResponseEntity.status）。
- 全量 `mvn.cmd test` 通过。

## 4. P3 低成本子集

### 4.1 死代码清理（4 项，本次改动确认后同提交删除）

| 项 | 位置 | 处理 |
|---|---|---|
| runSnapshot ThreadLocal | `ChatRequestWorker` :92 字段、:356 set、:433 remove | 删除字段与 set/remove 调用（RunSnapshot 类保留——captureSnapshot 仍返回它）；删除后确认无其他引用 |
| SseEvent.toSseText | `SseEvent.java` :27-29 | 删除方法（record 构造校验保留） |
| ChatRunService.findActiveRun/cancelRun | `ChatRunService.java` :96-115 | 删除两方法（全库零调用已核验；删除前确认方法体不引用被其他方法使用的私有辅助） |
| AuthConfig 排除 `/api/v1/public/**` | `AuthConfig.java:28` | excludePathPatterns 仅保留 `/api/v1/auth/**` |

**范围外死代码（不属本次改动产生，精准修改原则指出不改）**：`ChatMessage.java` 等历史文件中的其他未引用项若核验存在，列清单标注不删（用户决策）。

### 4.2 RT 旋转原子性（A11 Lua 化——**用户 2026-08-15 批准纳入**）

- 现状：`AuthController:178-191`（isRefreshTokenUsed 检查）与 `DeviceKickService:196-209`（markRefreshTokenUsed 置位）两次独立 Redis 调用 → 并发两个 refresh 均通过检查 → 双签。
- 方案：合并为单条 Lua 脚本（`if GET key == "1" then return 0 else SET key "1" EX ttl return 1` 语义），`DeviceKickService` 新增 `boolean markRefreshTokenUsedAtomic(String)`（返回 false=已被使用），`AuthController.refresh` 调用之。
- 实现要点：Lua 脚本字符串常量 + `DefaultRedisScript<Long>` bean（或方法内构建）+ 测试（首次 true、重复 false、过期后恢复）。

### 4.3 验证标准

- 死代码：grep 零残留（runSnapshot/toSseText/findActiveRun/cancelRun/public 前缀）；全量测试通过。
- A11（若采纳）：DeviceKickServiceTest 新增原子性测试（Lua 脚本 mock RedisScript 执行结果断言：首次 true、重复 false、过期后恢复）；AuthControllerTest refresh 双并发仅一次成功。

## 5. 环境问题修复（用户 2026-08-15 授权纳入，开发库可 drop 重建）

### 5.1 V6 TIMESTAMPTZ → TIMESTAMP

- 全表批量替换 `V6__full_schema_v5.sql` 中所有 `TIMESTAMPTZ` → `TIMESTAMP`（created_at/updated_at 等全部列；`DEFAULT now()` 语义不变）——与实体 LocalDateTime 对齐（PG JDBC 42.7.8 下 TIMESTAMPTZ→LocalDateTime 转换必炸，已真实环境实证）。
- V7 checkpoint 三表若含 TIMESTAMPTZ 一并检查替换。
- 重建：drop 库 + 重启后端（Flyway 重跑 V6/V7）。

### 5.2 V6 jsonb → TEXT

- 全表批量替换 jsonb 列 → TEXT（chat_run.meta_json、course_info.tags 等全部；实体字段均为 String）。
- 索引调整：`idx_course_info_tags`（jsonb gin 路径索引）在 TEXT 上不适用——V6 中删除该索引（tags 无 like 检索需求，如后续需要改用 trgm）。

### 5.3 种子 admin 密码 hash 修正

- V6:330 的 hash 替换为真实 admin123 的 BCrypt hash：`$2a$10$4Tr8GR4XD98OTopP6/vK5eYsK8yRsRPOjdYzBgK9eahMJDo6KpL8.`（2026-08-15 真实环境验证可登录；注释保留 "默认密码明文：admin123"）。

### 5.4 验证标准

- 重建后真实环境验证：登录 admin/admin123 成功；上传文档→ETL→INDEXED 全链路（2026-08-15 已验证的链路重跑一遍）；`mvn.cmd test` 全过（单测不依赖 DB，回归保护不变）。

## 6. 受影响测试清单

**本次改动失效需同步修改**：
- `GlobalExceptionHandlerTest`（四个 handler 断言补充 HTTP 状态；现有 body code 断言保留）
- `DocumentServiceTest`/`AdminDocumentController` 相关（findPage 签名变化）
- `EtlPipelineTest`（process 抢占逻辑变化——process_fullPipeline 需适配状态守卫：mock update 返回 1）
- `ChatRunServiceTest`（若有 findActiveRun/cancelRun 测试则删除）

**本次改动新增测试**：见 §1.5/§2.4/§3.2/§4.3/§5.4 各验证标准。

## 7. 明确取舍与范围外

- P2-2 契约对齐仅覆盖前端文档**明确定义**的端点；`/api/v1/admin/feedbacks` 列表/意图统计保留（前端文档未定义，无冲突，反馈管理页后续需要）。
- 403 统一不包含 controller 内联 `ApiResponse.error(404)` 场景（已知遗留，见 §3.1）。
- P3 观察项范围外：B8（reclaimPending 双执行）、B9（WarningHook 清理）、A10（设备互踢 PG 审计回填）、A12（黑名单 fail-open）、B11（Ring 背压）、SOURCES 观察、toolCallId/cancelled 契约观察——记录在案，逐项在 spec 标注取舍：均需更大改动面或依赖前端定论，本轮不修。
- 环境修复不改实体/代码（仅 V6 迁移类型与种子数据对齐实体与真实行为），drop 重建无数据损失（开发库无业务数据已确认）。
