# 合规修正全量实施 — 设计规格(A1/A2/B1/B2/C2/C3 + 单类门禁)

> 状态:已获用户批准(2026-08-16)。
> 决策来源:docs/progress/2026-08-16-宪法落地与待优化清单.md 待办项 + 2026-08-16 澄清确认(Testcontainers / 全量 VO 化 / 单类门禁 / ChatController 补到 80%+)。
> 范围:除 S1 多模态 RAG 主任务(D1)外的全部待办项,一次性全量实施,不再拆阶段。

## 0. 背景与目标

工程宪法落地(64096a6)后核验发现 6 项合规缺口,本次一次性全量修正:

| 编号 | 问题 | 现状(2026-08-16 实测) |
|---|---|---|
| A1 | 集成测试缺失(AGENTS.md §6.1 强制) | 0 个 @SpringBootTest,551 测试全为 Mockito 单测 |
| A2 | 8 个类行覆盖率 <80% | 38.3%~73.5%,测试类均已存在但分支覆盖不足 |
| B1 | controller 内部持有 entity(宪法"Entity 不出 service 边界"字面违规) | 7 个 controller import entity,出参已合规但 service 返回 entity |
| B2 | StudentController 依赖 ChatController(controller→controller) | StudentController.java:59 注入,仅 J8 /chat/stream 转发 |
| C2 | TASK.md §2 低覆盖清单严重过时 | DeviceKickService 44%→实际 92.9% 等 4 项过时 |
| C3 | 5 个 mapper XML 无执行级测试 | 仅编译期验证 + service 层 mock 间接验证 |

附加:jacoco 增加**单类** ≥80% 门禁规则防回归(用户已批准)。

**成功标准**:
1. `mvn verify` 全绿:spotless + checkstyle + spotbugs + jacoco(含新单类规则)
2. 集成测试真实连接 Testcontainers PG/Redis 并通过
3. `grep -rln "import com.commerce.rag.entity" backend/src/main/java/com/commerce/rag/controller/` 归零
4. TASK.md 低覆盖清单与 jacoco.csv 实测一致

## 1. A1 集成测试(Testcontainers)

### 1.1 依赖(pom.xml)

新增 test scope 依赖:

- `org.springframework.boot:spring-boot-testcontainers`(Boot 3.5.8 BOM 管理版本)
- `org.testcontainers:postgresql`(junit-jupiter 扩展)
- `org.testcontainers:junit-jupiter`

Redis 用 `GenericContainer("redis:7-alpine")`(Testcontainers 无专用 redis 模块),command 带 `--requirepass` 与生产一致。

### 1.2 测试基建 `src/test/java/com/commerce/rag/test/IntegrationTestBase.java`

- `@Testcontainers` + `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles("test")`
- static `PostgreSQLContainer` + static `GenericContainer`(redis),类级复用
- `@DynamicPropertySource` 覆盖 `spring.datasource.url/username/password` 与 `spring.data.redis.host/port/password`
- `@MockitoBean`(Spring 6.2,替代已弃用 @MockBean)替换 LLM 模型 bean:`ChatModel` / `EmbeddingModel` / `RerankModel`(DashScope 空 key 启动风险)
- `src/test/resources/application-test.yml`:**Flyway 关闭**(Testcontainers 每次全新容器,迁移在测试内用 `@Sql` 或前置脚本执行)——注意:先实测 Flyway 开 vs 关;若 Flyway 开启可正常迁移则保留开启(与生产一致,迁移脚本 V7 checkpoint 三表是 GraphConfig.postgresSaver 前置条件)
- **可行性实锤**(已核源码):
  - `MilvusConfig.milvusClientV2`:构造懒连接,无 Milvus 不失败
  - `MilvusCollectionInitializer.run`:异常降级(warn 跳过),不阻断启动
  - `MinioStorageService.initBucket`(@PostConstruct):异常降级(warn),不阻断启动
  - `GraphConfig.postgresSaver`:构造器真实建连 PG → Testcontainers PG 必须提供(checkpoint 三表 DDL 由 Flyway V7 幂等管理,故 Flyway 需开启或测试前置执行 V7)
  - `ChatRequestWorker.start`(@PostConstruct):启动 Redis XREADGROUP 消费线程 → Testcontainers Redis 提供;stream 不存在时 XREADGROUP 返回空不崩溃

### 1.3 用例(核心链路)

| 测试类 | 覆盖链路 |
|---|---|
| `AuthIntegrationTest` | 注册/预置用户 → 登录(PG sys_user + JWT) → 受保护接口 200 → 第二设备登录触发互踢(FOR UPDATE + Redis Lua,首设备 token 失效) → 登出进黑名单(jti) |
| `ChatFlowIntegrationTest` | 登录 → 创建会话 → 发消息(ChatRun 创建 + Redis Stream XADD 入队) → 心跳/状态正确 → cancel 接口生效;不等待 LLM 完整流式响应(worker 消费依赖 mock 模型,流式桥接链路留单测) |
| `SecurityIntegrationTest` | 无 token → 401;错误角色 → 403;正确角色真实 JWT → 200 |

### 1.4 运行策略

surefire 默认全跑(含集成测试),本地与 CI 均要求 Docker 可用(GitHub Actions ubuntu runner 自带)。集成测试不得用 `@Transactional` 回滚伪造(Testcontainers 每次新容器保证干净库);测试内数据清理用 `@Sql` 或 `JdbcTemplate` 显式 DELETE。

## 2. A2 低覆盖类补测(8 类 → ≥80%)

全部在**现有测试类**上扩充分支覆盖(禁新建重复测试类),按易到难:

| 类 | 现状 | 补测要点 |
|---|---|---|
| `storage/MinioStorageService` | 38.3% | initBucket(存在/不存在/异常降级)、uploadFile 成功/失败、downloadFile、deleteFile、deleteFiles(分批 100/批、DeleteError 抛异常)、buildObjectKey;mock MinioClient |
| `service/impl/DocumentChunkServiceImpl` | 61.8% | findById 含/无权限重载、findPage TEACHER 子查询分支、updateContent(触发 reEmbedAndUpsert)、delete(Milvus 清理+软删)、updateCollectionType、findContext 各方向、batchUpdate(去重+syncDocToMilvus)、batchCorrected、findPending、checkOwnership/checkOwnershipBatch 异常路径 |
| `bot/graph/LeadAgentGraph` | 66.2% | build() 全流程拓扑、buildQueryRewriteNode 正常/异常、buildReactAgent(methodTools/hooks/interceptor/ModelCallLimitHook)、extractLastUserQuery |
| `worker/ChatRequestWorker` | 67.9% | start/stop/cancel、processRequest 快照、handleCompleted/handleCancelled/handleError、XPENDING/XCLAIM 回收、persistMessages 批量 |
| `bot/tool/SearchKnowledgeTool` | 67.5% | searchSingle dense/sparse 双路、buildFilterExpression(course_id DEFAULT/IN)、RRF 融合、异常降级返回空 |
| `controller/ChatController` | 69.8% | chat 入队成功/失败回滚(含 ring 清理)、cancel 归属校验、reconnect 终态补发/降级 replayFromPg、checkRunOwnership 异常、startHeartbeat/truncateTitle/normalizeToolPayload/escapeJson 私有辅助 |
| `bot/graph/PromptLoader` | 71.1% | loadRawAndReplace/loadAndReplace 占位符替换、缺失 key 异常、多级嵌套 flatten、缓存命中 |
| `bot/hook/ReminderHook` | 73.5% | beforeModel 已存在 REPLACE 分支、rewrittenQueries 缺失分支、异常路径 |

ChatController 的 SSE 流式完整渲染不追求 mock 到流字节级——以"控制器编排分支全覆盖"为准(真实流式由 S1 阶段真实环境验证)。

## 3. B1 controller 全量 VO 化(7 controller)

### 3.1 改造原则

- **controller 调用的 service 方法改返回 VO/DTO**(service 内 MapStruct 转换,转换器放 convert/),controller 零 entity import
- **仅 service 间共用**的方法保留 entity 返回(entity 在 service 边界内传递合法)
- `IPage<Entity>` → `IPage<VO>`:service 内用 MP 框架 API `page.convert(...)` 一行转换(框架能力,不算手写转换)
- 转换器命名 `XxxConverter`,新增方法 `toXxxVo(entity)` / `toXxxVoList(list)`;已有转换器优先扩展

### 3.2 涉及清单

| Controller | entity import | 改造方式 |
|---|---|---|
| AdminCourseController | CourseInfo | courseService.findPage/findById/createCourse 返回 CourseVO;list 的 IPage<CourseVO> |
| AdminLoginRecordController | SysLoginRecord/SysTokenBlacklist | findPage/findBlacklistPage 返回 VO |
| AdminScheduleController | CourseSchedule | findByCourseId/create/findById 返回 CourseScheduleVO |
| AdminSessionController | ChatMessage/ChatSession | findAllSessions/findById 返回 VO;findBySessionId 返回 ChatMessageVO |
| FeedbackController | UserFeedback | create 返回 UserFeedbackVO |
| ChatController | ChatMessage/ChatRun/ChatSession | chat/reconnect/checkRunOwnership 经 VO 流转;**replayFromPg 回放逻辑下沉 IChatMessageService 返回 VO** |
| StudentController | ChatSession/CourseInfo/DocumentChunk | myCourses/courseMaterials/knowledgeBase/chunkContext/mySessions/createSession 全部 VO 化 |

### 3.3 验证

`grep -rln "import com.commerce.rag.entity" backend/src/main/java/com/commerce/rag/controller/` 归零;controller 测试 stub 同步适配 VO。

## 4. B2 StudentController → ChatController 依赖消除

- 新建 `stream/ChatStreamEntry`(@Component):收编 ChatController 的 chat/cancel/reconnect 编排(SseEmitter 生命周期、Redis Stream 入队、心跳、归属校验),注入原 ChatController 的 8 个依赖(worker/bridge/chatRunService/chatSessionService/chatMessageService/redisTemplate/streamProperties/objectMapper)
- `ChatController` 瘦身为 3 个薄端点(仅 @PreAuthorize + 调用 ChatStreamEntry)
- `StudentController` 改为注入 `ChatStreamEntry`(删除 ChatController 字段),`/chat/stream` 转发改为调用 ChatStreamEntry.chat
- 两个 controller 各自保留自己的 @PreAuthorize 角色约束(Security 层职责不动)
- ChatControllerTest 的编排断言迁移/补充到 ChatStreamEntryTest

## 5. C2 TASK.md 更新

- §2「遗留低覆盖类」更新为 8 类最新清单(38.3%~73.5%,含类名 Impl 后缀);删除 DeviceKickService 44%/EtlPipeline 37%/CustomSummarizationHook 35%/CourseQueryService 20% 过时数据(实际 92.9%/80.1%/97.9%/100%)
- 路线图标记:总覆盖率 87.0% 达标(551 测试)、集成测试引入(Testcontainers)、单类门禁上线

## 6. C3 mapper XML 执行级测试

### 6.1 方式

与 A1 共用 Testcontainers PG + Spring 上下文(已实锤:UserFeedbackMapper 的 `to_char` 为 PG 特有,H2 不兼容,FOR UPDATE 亦需真实 PG),直接注入 mapper 执行真实 SQL 断言结果。目录 `src/test/java/com/commerce/rag/mapper/`(新建)。

### 6.2 用例(5 个 XML)

| XML | 断言要点 |
|---|---|
| DocumentChunkMapper.selectPageFilteredByTeacher | 预置 2 用户文档:只返回 created_by 本人的 chunk;docId/kbId/pendingOnly 条件过滤;deleted=1 排除 |
| SysLoginRecordMapper | selectActiveForUpdate 行锁生效、updateStatusById、updateStatusByIdIfActive 幂等(仅 ACTIVE)、updateStatusByUserAndJtiActive、selectActiveByUserId |
| SysTokenBlacklistMapper.countByJti | 命中/未命中/软删排除 |
| SysUserMapper.selectByIdsIn | IN 列表投影(id/username/display_name)、空列表行为 |
| UserFeedbackMapper | selectDailyFeedbackCount(to_char 按天分组)、selectIntentStats(SUM CASE 赞/踩)、selectFeedbackStatsByPeriod(周期 total/liked) |

### 6.3 数据管理

测试内 JdbcTemplate/mapper insert 预置数据 + 测试后显式清理(@Sql 或 DELETE),不依赖 @Transactional 回滚。

## 7. jacoco 单类门禁

- jacoco check 增加规则:`<element>CLASS</element>`,`LINE` `COVEREDRATIO` `minimum=0.80`(与 AGENTS.md §6.4 非核心下限对齐)
- **豁免清单(先实测后定稿)**:
  - 纯数据类:entity 全部(Lombok 样板)、dto/、vo/、record/、enums/
  - 构造器真实建连配置类:MilvusConfig、GraphConfig(现有豁免依据延续)
  - MapStruct 生成的 `XxxConverterImpl`(编译期生成,若实测 <80% 加入)
  - 其余业务类一律 ≥80%,不允许豁免
- 实施流程:`mvn verify` 跑一次 → 读 jacoco.csv 统计 <80% 类 → 按上述原则定豁免名单 → 配置 excludes → 复跑确认

## 8. 实施顺序(单次全量,不拆阶段)

1. 启动 Docker Desktop 并验证 Testcontainers 可拉镜像(PG/redis)
2. B2 ChatStreamEntry 抽取(低风险先行,为 ChatController 改造铺路)
3. B1 全量 VO 化(逐 service:接口→impl→转换器→controller→测试适配,每步 `mvn test`)
4. A2 低覆盖补测 8 类
5. A1 集成测试基建 + 3 个集成测试类 + C3 mapper 测试(共用基建)
6. C2 TASK.md 更新
7. jacoco 单类规则 + 豁免清单定稿
8. `mvn verify` 全量验证 + 交付核对

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Docker Desktop 未运行/Testcontainers Windows 兼容性未验证 | 实施第 1 步启动并验证;失败则降级方案(docker-compose 直连)需用户重新批准 |
| @SpringBootTest 全上下文个别 bean 启动失败(空 api-key 等) | @MockitoBean 逐项排除,预留排查时间 |
| B1 全量 VO 化改动面大 | 按 service 逐个改造 + 每步 mvn test,MapStruct 改造后必须 `mvn clean` 重编译 |
| jacoco 单类规则误伤样板类 | 豁免清单实测后定稿,只豁免纯数据类/建连配置类 |
| ChatRequestWorker 后台线程干扰集成测试 | 测试结束 @AfterAll 调 stop();worker 消费依赖 mock 模型,不等待流式完成 |

## 10. 验收标准(与成功标准对应)

1. `mvn verify` 全绿(spotless + checkstyle + spotbugs + jacoco 含单类规则)
2. 集成测试类真实连接 Testcontainers PG/Redis 全部通过(测试日志可见容器启动)
3. controller 目录 entity import 归零;ChatController 无 ChatStreamEntry 之外的编排
4. TASK.md 与 jacoco.csv 实测一致(8 类清单 + 总覆盖率)
