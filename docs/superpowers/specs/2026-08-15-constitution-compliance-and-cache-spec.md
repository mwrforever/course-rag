# Spec — 宪法合规全量修正 + Caffeine 缓存 + git 基线（2026-08-15）

> 来源：`docs/progress/2026-08-15-bug修正与新指示轮.md` §2.2（P1-6 宪法合规组，用户已明确"必须做"）、§2.3（P0-2 git 基线）、§2.4（perf P2-2/P2-3 Caffeine 缓存，用户 2026-08-15 定稿"联网必做"）。
> 本 spec 将"主任务（§2.1 新指示轮四项，待用户确认）"之外的上述问题归纳为一份可执行设计。所有违规存量均经源码实锤（2026-08-15 全量扫描）。

---

## 1. 目标

消除 AGENTS.md 工程宪法全部存量违规（依赖注入 / Wrapper 链式 / 全路径类名 / 手写转换 / Entity 出边界 / 业务层拼 SQL），引入 MapStruct 层间转换与 Caffeine 缓存，最后完成 backend git 基线提交。全程遵循 superpowers 流程：本 spec 审批 → writing-plans 生成计划 → SDD 执行。

## 2. 范围界定

### 2.1 纳入（本次全量修正）

| 编号 | 事项 | 规模（源码实锤） |
|---|---|---|
| A | service 依赖注入合规（@Autowired → `private final` + `@RequiredArgsConstructor`） | 10 个 service，33 处 @Autowired + SysUserService 手写构造器 |
| B | `new LambdaQueryWrapper/UpdateWrapper` → `Wrappers.lambdaQuery()/lambdaUpdate()` 链式 | 79 处 / 12 个文件 |
| C | 全路径类名 → import | 12 处 / 10 个文件 |
| D | 手写转换 → MapStruct（pom 引入 mapstruct + lombok-mapstruct-binding） | 5 处转换（见 §4-D），AuthController 两处 LoginResponse 例外（见 2.2） |
| E | Entity 出边界 → VO 化（4 个 admin controller + 对应 service 方法签名 + 4 个 VO + 4 个 Converter） | DocumentVO/KnowledgeBaseVO/DocumentChunkVO/UserFeedbackVO |
| F | DeviceKickService JdbcTemplate 拼 SQL → mapper XML | 6 处 SQL + 1 个 ResultSet 行映射 |
| G | 调研新发现的同类违规：ChatMessageService 批量 INSERT 常量 SQL、EnrollmentService 动态占位符 IN SQL → mapper XML（**默认纳入**，见 2.2 决策点①） | 2 个 service 的 JdbcTemplate 依赖随之移除 |
| H | Caffeine 缓存：CourseQueryService 查询缓存（perf P2-2）+ DashboardService 统计缓存（perf P2-3） | 2 个 Cache bean + 4 处失效钩子 |
| I | P0-2 git 基线：backend 117 个未跟踪文件一次入库（用户授权的 `git add -A` 例外） | 最后执行 |

### 2.2 决策点（spec 已定默认值，用户审批时可推翻）

1. **G 项纳入**：ChatMessageService 的 BATCH_INSERT_SQL 虽为设计文档 §3.5 选型（JdbcTemplate.batchUpdate 保性能），但与宪法"禁止业务层拼接 SQL 字符串"冲突；改用 ChatMessageMapper XML `foreach` 批量 insert（一条多值 INSERT，性能语义等价，deleted=0 / created_at=now() 与现 SQL 一致）。EnrollmentService 的动态 `?` 占位符 IN SQL 同属违规，一并改 XML。
2. **AuthController 两处 `new LoginResponse(...)` 不纳入 MapStruct**：login(:135) 的源是"标量 token + SysUser 实体"、refresh(:215) 的源是"标量 token + UserDTO"——均为 API 响应组装而非 Entity→DTO 层间对象映射；且 AuthController 直触 SysUser 实体属认证链路既有设计（范围外观察 ②）。如用户要求纳入，可补 AuthConverter（多源映射），边际成本低。
3. **VO 放 `controller/vo` 包**（进度文档已锁定）；已有出参 DTO（CourseDTO/ScheduleDTO/UserDTO/StudentDTO/LoginResponse/PageResponse）保持 `controller/dto` 位置不动（迁移牵动 20+ 文件，记范围外观察 ③）。
4. **缓存失效策略**：详情类键按 courseId 精确失效 + 列表类键（search:*）前缀清理；dashboard 统计 TTL 60s + 写方 invalidateAll。

### 2.3 范围外观察项（记录不修，spec 审批后落盘进度文档）

- ① StudentController 直出 `DocumentChunk`/`IPage<DocumentChunk>`（C 端知识库浏览接口，前端未实现，契约变更需另行裁决）
- ② AuthController 直触 SysUser 实体（认证链路，密码校验/状态检查在 controller；下沉涉及重构，另行评估）
- ③ service 反向依赖 `controller/dto` 包（CourseService→ScheduleDTO 等，出参 DTO 包位置；根 dto/vo 包 vs controller 子包的统一迁移另行立项）
- ④ FeedbackController.create 返回 UserFeedback 实体（C 端反馈接口）
- ⑤ CourseQueryService 使用 MyBatis-Plus `Db` 静态工具（官方 API，合法，不改）

---

## 3. 现状实锤清单（违规存量）

### A. @Autowired 字段注入（33 处）

| 文件 | 行号 | 注入字段 |
|---|---|---|
| service/ChatSessionService.java | 35,38,41 | ChatSessionMapper / ChatMessageMapper / ChatRunMapper |
| service/UserFeedbackService.java | 34 | UserFeedbackMapper |
| service/ChatMessageService.java | 35,38 | ChatMessageMapper / JdbcTemplate |
| service/KnowledgeBaseService.java | 38,41,44,47,50 | KnowledgeBaseMapper / DocumentMapper / DocumentChunkMapper / EtlPipeline / MinioStorageService |
| service/EnrollmentService.java | 38,41,44 | CourseEnrollmentMapper / CourseService / JdbcTemplate |
| service/DocumentChunkService.java | 48,51,54,57 | DocumentChunkMapper / DocumentMapper / KnowledgeBaseMapper / EtlPipeline |
| service/CourseService.java | 69,72,75,78,81,84,87 | CourseInfoMapper / CourseContentMapper / CourseScheduleMapper / CourseTeacherMapper / CourseEnrollmentMapper / DocumentChunkMapper / EtlPipeline |
| service/CourseScheduleService.java | 30,33 | CourseScheduleMapper / CourseService |
| service/DocumentService.java | 45,48,51,54,57,60 | DocumentMapper / DocumentChunkMapper / KnowledgeBaseMapper / MinioStorageService / EtlPipeline / @Qualifier("etlPool") ThreadPoolExecutor |

另：SysUserService 为手写构造器（4 个依赖，无初始化逻辑，纯样板）→ 同样改 `@RequiredArgsConstructor`（DeviceKickService 手写构造器是合法场景——Lua 脚本加载，保持不动）。

### B. `new` Wrapper（79 处）

CourseService 16（161,180,198,245,251,257,263,270,276,296,326,338,352,368,373,411）、ChatSessionService 9（71,86,98,111,139,153,170,176,181）、DocumentChunkService 9（109,135,165,187,249,264,286,322,347）、SysUserService 8（75,84,145,161,219,251,285,292）、SysLoginRecordService 7（66,107,140,167,206,220,223）、EnrollmentService 6（59,108,130,162,181,204）、KnowledgeBaseService 6（105,134,168,178,184,190）、DocumentService 5（212,250,256,283,289）、UserFeedbackService 4（52,59,103,140）、CourseScheduleService 3（92,128,156）、ChatMessageService 3（82,95,109）、AuthSessionService 3（92,140,176）。

### C. 全路径类名（12 处）

CourseService:428/440（`com.commerce.rag.controller.dto.ScheduleDTO`）、PageResponse:19（`com.baomidou...IPage`）、AdminDocumentController:154（`org.springframework.core.io.InputStreamResource`）、KnowledgeBaseService:171/172（Objects/Collectors）、DocumentService:183（Collectors）、EtlPipeline:453（Collectors.joining）、MinioStorageService:151（Collectors）、DeviceKickService:286（`java.time.Duration`；:464 ResultSet/SQLException 随 F 项删除）、CustomSummarizationHook:286（Collections）、PromptLoader:122（`java.util.List<?>`）、MilvusCollectionInitializer:318（Map.of）。

### D. 手写转换（纳入 5 处）

1. SysUserService:352-359 `toDTO(SysUser)` → UserDTO（6 字段）
2. CourseService:425-474 `toDTO(CourseInfo, boolean)` → CourseDTO（16 字段 + 嵌套 CourseContentDTO + ScheduleDTO；includeRelations=true 时内部查库）
3. EnrollmentService:83 `new StudentDTO(...)`（ResultSet + enrollment 内存匹配拼接，5 字段）
4. AdminScheduleController:105-118 `toDTO(CourseSchedule)` → ScheduleDTO（11 字段）
5. AdminCourseController:157 `new CourseDTO.CourseContentDTO(...)`（内联 3 字段）

### E. Entity 直出边界（4 个 admin controller）

- AdminDocumentController：:60 upload 出 `Document`、:93 findById 出 `Document`、:106 findPage 出 `PageResponse<Document>`
- AdminKnowledgeBaseController：:49 create 出 `KnowledgeBase`、:58 findById 出 `KnowledgeBase`、:71 findPage 出 `PageResponse<KnowledgeBase>`
- AdminChunkController：:49 findById 出 `DocumentChunk`、:62 findPage 出 `PageResponse<DocumentChunk>`、:104 findContext 出 `Map<String, DocumentChunk>`、:130 findPending 出 `PageResponse<DocumentChunk>`
- AdminFeedbackController：:43 findPage 出 `PageResponse<UserFeedback>`

对应 service 方法：DocumentService.upload:79/findById:141/findPage:166；KnowledgeBaseService.create:61/findById:80/findPage:103；DocumentChunkService.findById(id,userId,role):78/findPage:101/findContext:210/findPending:341；UserFeedbackService.findPage:96。

### F. DeviceKickService JdbcTemplate（6 处 SQL + 1 行映射）

:185-186 黑名单 COUNT（sys_token_blacklist 表）、:311-315 FOR UPDATE 行锁查询、:330-331 REVOKED update、:355-357 REVOKED update、:380-383 REVOKED+ACTIVE update、:406-410 REVOKED+user_id+jti_at update、:458-461 活跃记录查询、:464-479 mapLoginRecord 行映射。

### G. 其他拼 SQL（调研新发现）

ChatMessageService:30-33 BATCH_INSERT_SQL 常量 + :59-72 batchUpdate；EnrollmentService:72-89 动态占位符 IN SQL + ResultSet 行映射。

### H. 缓存现状

全项目零缓存基础设施（无 cache starter / 无 Caffeine / 无 @Cacheable）。CourseQueryService（唯一课程查询服务，仅被 CourseApiTool 调用）4 个查询方法零缓存；DashboardService 3 个统计方法零缓存。pom 无 caffeine、无 mapstruct。

---

## 4. 分项设计

### A. 依赖注入合规

- 9 个 service：删除 `@Autowired` 字段注解与 import，类上加 Lombok `@RequiredArgsConstructor`，字段改 `private final`。
- DocumentService 的 `@Qualifier("etlPool") ThreadPoolExecutor etlPool` 字段：保留 `@Qualifier` 于字段（Lombok 会把字段注解复制到生成构造器参数，Spring 可解析）。
- SysUserService：删除手写构造器，改 `@RequiredArgsConstructor`。
- **测试适配**：`CourseServiceTest:64` / `DocumentServiceTest:66` / `CourseScheduleServiceTest:38` 目前 `new XxxService()` + 反射按字段名注入 mock → 改为构造器传参注入（构造器参数顺序与 @RequiredArgsConstructor 生成一致）；其余 @InjectMocks 测试（DocumentChunkServiceTest/KnowledgeBaseServiceTest/UserFeedbackServiceTest/SysUserServiceTest 等）由 Mockito 自动构造器注入，无需改动。
- 验证：`grep -rn "@Autowired" backend/src/main/java` 归零（DeviceKickService 除外——它本就不用 @Autowired）；对应单测全绿。

### B. Wrapper 链式化

- 每处 `new LambdaQueryWrapper<X>()` → `Wrappers.<X>lambdaQuery()`；`new LambdaUpdateWrapper<X>()` → `Wrappers.<X>lambdaUpdate()`；删除两个 wrapper import，新增 `import com.baomidou.mybatisplus.core.toolkit.Wrappers;`（若未 import）。
- 纯机械替换，不改变查询语义。CourseService:233-235 已有 `Wrappers.lambdaQuery()` 先例，风格对齐。
- 验证：`grep -rn "new LambdaQueryWrapper\|new LambdaUpdateWrapper" backend/src/main/java` 归零；全量测试绿。

### C. 全路径类名

逐处加 import 换短名。DeviceKickService:286 `java.time.Duration` → import（F 项改造后该行仍存在则保留 import；:464 随 mapLoginRecord 删除而消失）。CourseService:428/440 ScheduleDTO → import（越层问题见观察③）。

### D. MapStruct 层间转换

**pom 依赖（关键实施点）**：
- `org.mapstruct:mapstruct:1.6.3` + `org.mapstruct:mapstruct-processor:1.6.3`
- 必须显式配置 `maven-compiler-plugin` 的 `annotationProcessorPaths`：`lombok` + `lombok-mapstruct-binding:0.2.0` + `mapstruct-processor`（顺序：lombok → lombok-mapstruct-binding → mapstruct-processor）。**不配 binding 时 MapStruct 生成实现读不到 Lombok getter，编译必炸**。
- 依赖引入后 `mvn.cmd dependency:resolve` 联网下载（用户已授权联网），且**每次改转换接口或 DTO 后必须 `mvn clean` 再编译**（宪法：增量编译不重新生成实现类）。

**转换器清单**（全部放 `service` 包，与使用 Service 同包，命名 XxxConverter）：

| 转换器 | 方法 | 说明 |
|---|---|---|
| `SysUserConverter` | `UserDTO toDTO(SysUser user)` | 6 字段全同名字段 |
| `CourseConverter` | `CourseDTO toDTO(CourseInfo course, List<CourseContent> contents, List<CourseSchedule> schedules, List<Long> teacherIds)` | 多源映射；`@Mapping(target="contents", source="contents")` 嵌套 List→List<CourseContentDTO>、`schedules`→List<ScheduleDTO>（MapStruct 1.6 支持 record 嵌套映射，同名字段自动）；`tags` 为 List<String> 直传 |
| `CourseConverter`（同接口） | `CourseContentDTO toContentDTO(CourseContent c)` | AdminCourseController:157 内联转换用 |
| `ScheduleConverter` | `ScheduleDTO toDTO(CourseSchedule s)` | 11 字段同名 |
| `EnrollmentConverter` | `StudentDTO toDTO(SysUser user, CourseEnrollment enrollment)` | 多源：id/username/displayName 来自 user，enrolledAt/status 来自 enrollment |
| `DocumentConverter` | `DocumentVO toVO(Document doc)`；`IPage<DocumentVO>` 由 service 组装 | sourcePath 不在 VO 中（MapStruct 不映射即忽略） |
| `KnowledgeBaseConverter` | `KnowledgeBaseVO toVO(KnowledgeBase kb)` | |
| `DocumentChunkConverter` | `DocumentChunkVO toVO(DocumentChunk chunk)` | denseVector 不在 VO 中 |
| `UserFeedbackConverter` | `UserFeedbackVO toVO(UserFeedback feedback)` | |

**调用方改造**：
- SysUserService：`toDTO(user)` 私有方法删除，改注入 SysUserConverter（返回 UserDTO 语义不变）。
- CourseService：`toDTO(CourseInfo, boolean)` 保留为业务方法——includeRelations=true 时查关联（findContents/findTeacherIds/findSchedules），再调 courseConverter 组装；false 时传空 List。手写 `new CourseDTO(...)` 16 字段代码删除。
- AdminScheduleController：私有 `toDTO` 删除，注入 ScheduleConverter。
- AdminCourseController:157：改调 `courseConverter.toContentDTO(c)`（注入 CourseConverter）或经 CourseService 暴露方法。
- EnrollmentService：ResultSet 行映射删除，改 SysUserMapper XML 批量查用户（见 G 项）→ 内存匹配 → `enrollmentConverter.toDTO(user, enrollment)`。

### E. Entity 出边界 VO 化

**新建 `controller/vo` 包 4 个 record VO**（字段=实体字段剔除敏感列）：

| VO | 字段 |
|---|---|
| `DocumentVO` | id, kbId, title, fileType, fileSize, parseStatus, chunkCount, errorMessage, courseId, createdBy, createdAt, updatedAt（**不含 sourcePath**） |
| `KnowledgeBaseVO` | id, name, description, status, createdBy, createdAt, updatedAt |
| `DocumentChunkVO` | id, docId, kbId, chunkIndex, content, headingPath, parentTitle, startPage, endPage, tokenCount, collectionType, courseId, metadataJson, milvusPk, parentChunkId, prevChunkId, nextChunkId, charOffsetStart, charOffsetEnd, correctionStatus（**不含 denseVector**） |
| `UserFeedbackVO` | id, sessionId, messageId, userId, isLiked, intentType, createdAt |

**service 方法签名改造**（只改 Admin 侧方法，学生端方法保持 Entity——观察①）：

- DocumentService：`upload → DocumentVO`；`findById(Long,Long,String) → DocumentVO`；`findPage → IPage<DocumentVO>`（内部转换）；`download/downloadWithType/update/delete/reparse` 签名不变（不返回 Entity）。
- KnowledgeBaseService：`create → KnowledgeBaseVO`；`findById → KnowledgeBaseVO`；`findPage → Page<KnowledgeBaseVO>`。
- DocumentChunkService：`findById(Long,Long,String) → DocumentChunkVO`；`findPage → IPage<DocumentChunkVO>`；`findContext → Map<String, DocumentChunkVO>`；`findPending → IPage<DocumentChunkVO>`；`findById(Long)`（学生端用）/`findByCourseId`/`findByCourseIdDefault` 保持 Entity。
- UserFeedbackService：`findPage → IPage<UserFeedbackVO>`；`create` 保持返回 UserFeedback（观察④）。
- controller 层：4 个 controller 返回类型改为 `ApiResponse<XxxVO>` / `PageResponse<XxxVO>`；`PageResponse.of()` 泛型天然适配。
- 敏感列防线：VO 中不存在 sourcePath/denseVector 字段，MapStruct 不映射即天然不泄露。
- **测试适配**：AdminDocumentControllerTest/AdminChunkControllerTest/AdminDashboardControllerTest/DocumentServiceTest/DocumentChunkServiceTest/KnowledgeBaseServiceTest/UserFeedbackServiceTest 中断言 Entity 字段处改为断言 VO 字段（字段名大部分同名，改动小）。

### F. DeviceKickService → mapper XML

- `SysTokenBlacklistMapper` 增 `Long countByJti(String jti)`；新建 `resources/mapper/SysTokenBlacklistMapper.xml`：`SELECT COUNT(*) FROM sys_token_blacklist WHERE jti = #{jti} AND deleted = 0`。
- `SysLoginRecordMapper` 增 5 方法；新建 `resources/mapper/SysLoginRecordMapper.xml`（resultType 自动映射，map-underscore-to-camel-case 全局开启）：
  - `selectActiveForUpdate(userId, deviceType)`：`SELECT * FROM sys_login_record WHERE user_id=#{userId} AND device_type=#{deviceType} AND status='ACTIVE' AND deleted=0 FOR UPDATE`
  - `updateStatusById(id)`：`UPDATE sys_login_record SET status='REVOKED', updated_at=now() WHERE id=#{id}`
  - `updateStatusByIdIfActive(id)`：同上 + `AND status='ACTIVE'`
  - `updateStatusByUserAndJtiActive(userId, jtiAt)`：`... WHERE user_id=#{userId} AND jti_at=#{jtiAt} AND status='ACTIVE'`
  - `selectActiveByUserId(userId)`：`SELECT * FROM sys_login_record WHERE user_id=#{userId} AND status='ACTIVE' AND deleted=0`
- DeviceKickService：构造器参数 `JdbcTemplate` 换成两个 mapper；`mapLoginRecord` 私有方法删除；6 处调用点改调 mapper。手写构造器保留（Lua 加载合法场景）。
- **测试适配**：DeviceKickServiceTest 中 mock JdbcTemplate 的 stub 改 mock mapper 方法（查询/更新断言语义不变）。

### G. ChatMessageService / EnrollmentService → mapper XML

- `ChatMessageMapper` 增 `void batchInsert(List<ChatMessage> messages)`；新建 `resources/mapper/ChatMessageMapper.xml`：`<insert>` + `<foreach collection="list" item="m" separator=",">` 多值 INSERT（列与现 BATCH_INSERT_SQL 完全一致：id, session_id, role, content, intent_type, sources_json, token_count, run_id, seq, confidence, trace_id, message_type, deleted=0, created_at=now()）。ChatMessageService 删 BATCH_INSERT_SQL 常量与 JdbcTemplate 依赖，`batchInsert` 改调 mapper。
- `SysUserMapper` 增 `List<SysUser> selectByIdsIn(List<Long> ids)`；新建 `resources/mapper/SysUserMapper.xml`：`SELECT id, username, display_name FROM sys_user WHERE id IN (foreach) AND deleted=0`（**按需返回字段**）。EnrollmentService.findStudents 改：enrollments 查询不变 → 批量查用户 → 内存匹配 → EnrollmentConverter 组装。JdbcTemplate 依赖删除。
- **测试适配**：EnrollmentService 相关测试 mock 从 JdbcTemplate 改 mapper；ChatMessageService 无独立测试（被 ChatRequestWorkerTest 覆盖，batchInsert mock 断言改 mapper 方法）。

### H. Caffeine 缓存（perf P2-2 / P2-3）

**pom**：`com.github.ben-manes.caffeine:caffeine`（Spring Boot 3.5.8 parent 管理版本，无需显式 version；若 parent 未管理则用 3.2.x）。

**新建 `config/CacheConfig.java`**（@Configuration，两个 bean）：
- `Cache<String, Object> courseQueryCache`：`Caffeine.newBuilder().maximumSize(512).expireAfterWrite(Duration.ofMinutes(5)).build()`——TTL 5min 落在用户定稿区间 5-10min。
- `Cache<String, Object> dashboardStatsCache`：`maximumSize(32).expireAfterWrite(Duration.ofSeconds(60)).build()`。

**CourseQueryService（perf P2-2）**：注入 courseQueryCache；4 个查询方法改为"先取缓存、未命中查库后 put"（key：`search:{keyword}:{page}` / `course:{id}` / `contents:{id}` / `schedule:{id}`）；新增 `evictCourse(Long courseId)`：精确 invalidate `course:{id}`/`contents:{id}`/`schedule:{id}`，并 `asMap().keySet().removeIf(k -> k.startsWith("search:"))` 清列表键。

**失效钩子（先写 DB 后失效缓存，一致性铁律）**：
- CourseService 注入 CourseQueryService（CourseQueryService 不依赖 CourseService，无环）：createCourse / updateCourse / deleteCourse / updateContent / batchUpdateContents 末尾调 `courseQueryService.evictCourse(courseId)`。
- CourseScheduleService 注入 CourseQueryService（无环）：create / update / delete 末尾调 `evictCourse(courseId)`（排期变更精确失效）。

**DashboardService（perf P2-3）**：注入 dashboardStatsCache；`dashboardStats/feedbackStats/feedbackTrend` 结果按 `方法名+参数` 键缓存（TTL 60s 兜底）。**写方失效**（直接注入 dashboardStatsCache bean，无环）：
- DocumentService：upload / update / delete / reparse 末尾 `dashboardStatsCache.invalidateAll()`。
- EtlPipeline：`updateDocStatus`（统一状态写入点 :755）末尾 invalidateAll（覆盖 SUCCESS/FAILED/INDEXED 终态）。
- UserFeedbackService：create / delete 末尾 invalidateAll。

**测试**：新增 `CourseQueryServiceTest`（缓存命中不二次查库 / evictCourse 精确失效与 search 清理 / 不同 key 隔离）；DashboardServiceTest 补缓存断言（二次调用不重复查 mapper；写方失效由各写方测试或 DashboardServiceTest 间接覆盖）。

### I. P0-2 git 基线（最后执行）

- 前置：A-H 全部完成、全量测试绿、spotless/checkstyle 通过。
- `git add -A && git commit`（用户授权的唯一 `add -A` 例外；根 .gitignore 已排除 target/、.env 等，backend 无独立 .gitignore，无需新增）。
- 提交信息：`chore: backend 全量基线提交（117 个未跟踪文件入库）+ 宪法合规/缓存改造（2026-08-15）`。
- 验证：`git ls-files backend/ | wc -l` ≈ 全量文件数；fresh checkout 可编译。

---

## 5. 测试与门禁

- 每任务（A-I 每项）完成即跑对应单测；全部完成后 `cd backend && mvn.cmd test`（预期 275/275 + 新增用例全绿）+ `mvn.cmd spotless:apply`（或 check）+ `mvn.cmd checkstyle:check`。
- 测试与实现同一次提交；因改动失效的旧测试直接改/删，不留过渡。
- 新增测试：CourseQueryServiceTest（缓存）、CourseConverter/ScheduleConverter 等转换器测试（随 D/E 任务交付，核心路径 100% 覆盖要求：转换器为对外契约核心，必须覆盖）；VO 字段不泄露断言（DocumentVO 无 sourcePath / DocumentChunkVO 无 denseVector）。

## 6. 风险与注意

1. **MapStruct 与 Lombok 协同**：annotationProcessorPaths 三件套必须配齐，否则编译失败（最常见坑）。引入后首次 `mvn clean compile` 验证。
2. **转换接口或 DTO 修改后必须 clean 重编译**（宪法强制，增量编译会跑旧实现）。
3. **本地仓库缺 mapstruct/caffeine jar**：联网下载已授权（D:/code/envs/maven/3.9.16/repo）。
4. **测试反射注入 final 字段**：改构造器传参后 `new XxxService()` + 反射注入的 3 个测试改为构造器注入。
5. **DeviceKickService 事务语义**：mapper 调用替代 JdbcTemplate 不改变 @Transactional 边界（方法级注解不变）。
6. **EtlPipeline 是核心链路**：加 dashboardStatsCache.invalidateAll() 不改变 ETL 主流程，仅终态写状态处多一行失效。
7. **I 项基线提交前**：工作区还包含主任务（§2.1 新指示轮四项）已落地的未提交改动——基线提交会一并入库，需与主任务确认结论协同（若主任务方案在本次执行期间被用户推翻，对应文件以推翻后代码为准后再提交）。

## 7. 交付物清单

- 主代码：10 service 注入合规、79 处 wrapper 链式、12 处全路径、5 个 MapStruct 转换器 + 8 个转换方法、4 个 VO、4 个 controller 出参改造、2 个 mapper XML（DeviceKick）+ 2 个 mapper XML（ChatMessage/Enrollment）+ 2 个 mapper 接口扩展、CacheConfig + 2 个 service 缓存、pom 3 项依赖（mapstruct×2 + caffeine + lombok-mapstruct-binding）
- 测试：3 个测试构造注入改造、若干断言适配、新增 CourseQueryServiceTest + 转换器测试
- 提交：合规改造分任务提交（A-H）+ 最后基线大提交（I）
