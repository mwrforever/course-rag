# RAG 课程助手（commerce-customer）项目宪法

> 项目最高规范：**只存原则、约束与数据契约**。功能实现细节见代码与 `docs/superpowers/specs/` 设计文档，待办登记见 `TASK.md`，工程变更记录见 `CHANGELOG.md`，本文档不重复。
> 任何与本文档冲突的代码或设计不得合入主分支。
> 注释 / 日志 / 编码 / 测试覆盖 / CI 门禁规范见全局 `C:\Users\Lenovo\.zcode\AGENTS.md`（§一 / §二 / §三 / §五 / §六 / §七 / §八），本项目不重复正文，强制生效。
> 业务规则（意图体系、状态机、字段口径、检索链路设计）以 `docs/superpowers/specs/` 设计文档为准，本文件只管工程技术原则。

**配套文件职责**：
- `docs/superpowers/specs/` —— 功能设计：意图体系、状态机、记忆体系、检索链路等细节的唯一权威来源，宪法不重复；
- `TASK.md` —— 待办与登记台：调研不可得项（{待调研项}）、待决策项、TODO 工单；回填/完成后删除对应行；
- `CHANGELOG.md` —— 工程变更记录：宪法修订与重大工程变更追加记录（日期 · 变更简述 · 原因），先记变更再改正文；
- `docs/` —— 过程文档：bug 审查 / 性能建议 / 进度 / 接口契约 / 调研报告（`docs/agmds-research/`）。

**四段结构**：Part A Java+Spring Boot 通用 / Part B Spring AI Alibaba Agent 架构层 / Part C 前端 / Part D commerce-customer 实际。

---

# Part A — Java 17 + Spring Boot 3.5 通用规范

## A.1 编码约束

1. 语言版本下限 Java 17（`maven.compiler.release=17`）；文件编码 UTF-8 无 BOM、行尾 LF，遵循全局 §三，不重复。
2. Java 17 语言特性边界：record 仅用于不可变数据载体（DTO/VO/属性类），sealed 用于封闭类型层级；**preview 特性（switch 模式匹配、record 模式等）禁止入生产**；数据库实体（需无参构造 + 可变 setter）禁止用 record。
3. Lombok 仅使用稳定功能（@Data / @Value / @RequiredArgsConstructor / @Slf4j 等），变量 POJO 用 @Data，不可变对象优先 record 原生或 @Value。
4. 依赖注入统一 `private final` 字段 + `@RequiredArgsConstructor`（构造器注入），禁止字段 @Autowired、禁止手写样板构造器；构造参数过多视为坏味道，拆分职责。
5. 禁止全路径类名，一律 import 后使用短类名。
6. 注解优先：能用注解/框架声明式能力解决的，禁止手写样板代码。
7. 禁用弃用 API：整个 Java 程序（JDK、Spring、MyBatis-Plus、Guava 等所有依赖）禁止使用 `@Deprecated` 标记的方法/类。
8. 并发边界：共享可变状态必须线程安全（ConcurrentHashMap/原子类），禁止裸 HashMap 跨线程共享；并行场景用 CompletableFuture（注意超时控制与单点失败隔离）；SSE 流式用响应式（Flux），禁止阻塞请求线程；各业务独立线程池（ETL / 检索 / 偏好与记忆提取），禁止共用一个池。

## A.2 配置管理

1. 注册代码归位：所有 `@Configuration`/`@Bean` 集中 `config/`；所有 `@ConfigurationProperties` 属性类集中 `properties/`；业务常量（Redis key 前缀、状态串）集中 `constants/` 接口静态常量；阈值等配置数值全配置化归 `properties/`，禁止散落硬编码。
2. 相关配置项一律 `@ConfigurationProperties` 强类型绑定（kebab-case），禁止散落 `@Value` 逐条注入；属性类必须加 `@Validated` + jakarta.validation 约束做启动期校验，配置非法直接启动失败。
3. 敏感值（DB / Redis / MinIO / JWT / 模型密钥）禁止明文进 git，一律环境变量或外部 Secret 注入；`.env` 类文件 gitignore。
4. 全项目统一一种配置格式（yaml），禁止 yml/properties 混用。

## A.3 API 设计

1. 版本化前缀 `/api/v1`（团队约定，官方无统一条文，见 TASK.md §5 登记）。
2. 请求/响应一律契约对象：controller 入参走 DTO、出参走 VO；Entity 禁止出 service 边界（见 D.4）。
3. 入参校验走 Bean Validation（DTO 字段约束 + `@Valid`），禁止手写 if 校验散落 controller。
4. 统一错误处理：业务错误一律抛 `BizException(ErrorCode, 消息)`，ErrorCode 与 HTTP 状态码同值（保持 ApiResponse.code = HTTP 状态码契约）；`GlobalExceptionHandler` 统一处理，禁止 controller 局部 `@ExceptionHandler`；控制流异常独立定义放 `exception/`。
5. HTTP 状态码语义化：创建 201 + Location、删除 204、未找到 404、成功 200。
6. 接口演进向后兼容：只加字段不删字段，DTO/VO 按接口独立演化。

## A.4 数据库操作

1. 表结构变更必须经 Flyway 版本化迁移（`V<版本>__<描述>.sql`，统一 `src/main/resources/db/migration/`）；**迁移脚本一经合入禁止修改**，后续变更新增版本滚动；禁止对任何环境执行 flyway clean。
2. 迁移默认单事务原子执行；PG 下 `CREATE INDEX CONCURRENTLY` 等不可事务内语句需先评估事务锁影响（配置切会话级锁）再引入。
3. 本 service 主表操作一律 ServiceImpl 内置链式 `this.lambdaQuery()/this.lambdaUpdate()`（IService 能力），不构建 wrapper；查询目标非本 service 主表（副表/跨模块）才用 `Wrappers` 静态工厂；**wrapper 禁止跨层/跨线程传递**。
4. 按需取列：查询必须 select() 精确投影，禁止 SELECT *、禁止全字段取回后丢弃；循环内单查改批量（in 批量），拒绝 N+1。
5. 复杂 SQL（连表 / 分组统计 / 聚合 / 复杂条件）必须走 mapper 接口 + `resources/mapper/` XML 映射文件（类名.xml），禁止业务层拼 SQL、禁止 JdbcTemplate 字符串拼接；XML 只用常用标签（select/insert/update/delete/where/if/foreach/set/choose）。
6. ID 生成：实体 `@TableId(ASSIGN_ID)` 自动雪花 ID，禁止手动 IdWorker；批量插入用 saveBatch（JDBC 批处理，自动填充 ID，**须在事务内调用**）。
7. 查询必带分页（PaginationInnerInterceptor maxLimit=2000）；分页/取前 N 必须带 ORDER BY 约束唯一顺序；大 OFFSET 深翻页改 Keyset（游标）方案。
8. 逻辑删除统一 `@TableLogic` + 全局配置（logic-delete-value=1 / logic-not-delete-value=0）；并发敏感实体用 `@Version` 乐观锁 + OptimisticLockerInnerInterceptor。
9. 注册 BlockAttackInnerInterceptor，拦截无 WHERE 条件的全表 UPDATE/DELETE（命中抛异常拒绝执行）。
10. JSON 数据列：仅原文存取/透传展示用 TEXT 列（项目历史实证：PG JSONB 与 MP String 绑定不兼容）；出现结构化 JSON 检索需求才评估 jsonb + GIN 专用列（须先验证类型处理器兼容）。
11. 索引克制且可验证：新索引走 Flyway 迁移，用 EXPLAIN ANALYZE 校验执行计划，pg_stat_user_indexes 核对使用率。
12. 事务边界：`@Transactional` 放在 service 实现层方法级最小边界，禁止 controller 开事务、禁止业务手动 commit/rollback；批量写连接开启 `reWriteBatchedInserts`（pgJDBC 参数名，注意并非 MySQL 的 rewriteBatchedStatements）。

## A.5 基础设施生命周期

1. 外部连接资源进程级单例（RedisTemplate / Milvus 客户端 / MinIO 客户端），Spring Bean 管理 + destroyMethod 显式释放，禁止请求级创建。
2. Spring Data Redis 的 RedisConnection 非线程安全，业务线程一律经线程安全的 RedisTemplate，禁止跨线程共享连接；阻塞命令（XREAD BLOCK 等）独占一条连接。
3. 任务队列（Redis Stream 消费组）：**读-执行分离**——阻塞读线程只取消息不执行业务，业务经独立线程池执行；读到即 ACK，业务失败不依赖 PEL 重投，走独立补偿（PG 状态巡检）；禁止 long-polling 无限期占住消费线程。
4. 缓存必须显式 TTL，禁止永久缓存；**一致性铁律：先写 DB（事务内）→ 后失效缓存**，禁止先改缓存后写库；读取不续期。
5. 键命名统一「业务:实体:id」三段式（业务前缀 + 主键）；生产代码禁止 KEYS/SMEMBERS 全量遍历，一律 SCAN 游标迭代。
6. 原子操作（设备互踢 / 黑名单 / RT 旋转等）用 Lua 脚本，禁止应用层读-改-写竞态；脚本只经 KEYS[]/ARGV[] 取参、保持短小（原子执行阻塞全部客户端）、DefaultRedisScript 单例复用（EVALSHA）。
7. MinIO 对象 key 一律「业务前缀 + uuid 预生成」，先占资源再落库；上传失败/未落库的孤儿对象需应用层补偿清理（MinIO 不支持 AbortIncompleteMultipartUpload 生命周期动作，删除非即时，不得依赖生命周期做即时语义）。
8. 附件对外分发一律短时效 presigned URL（秒-分钟级授权），禁止公开 bucket 与长期匿名读取；presigned URL 是 bearer token，持有即授权，禁止在日志/响应重复暴露。
9. 服务端发起外部 URL 请求前必须校验目标地址（拒绝回环 / 链路本地 / 云元数据 169.254.169.254 / RFC1918 内网段），禁用自动重定向，防 SSRF。
10. 各业务独立线程池，禁止共用；并行任务注意超时与单点失败隔离（引用 A.1.8）。

## A.6 注释 / 日志 / 测试（引用全局）

见 `C:\Users\Lenovo\.zcode\AGENTS.md` §一（注释）/ §二（日志）/ §六（测试覆盖） / §七（行为准则）。本项目强制生效。

项目落地补充：
- 集成测试用 Testcontainers（PG + Redis 单例容器模式），容器生命周期须与 Spring TestContext 缓存对齐（容器 Bean 化 / @ServiceConnection）；**单元测试不得依赖外部容器**。
- JaCoCo 覆盖率门禁绑定 `mvn verify`（BUNDLE 全局 + CLASS 单类双规则），豁免清单见 `backend/pom.xml`。
- 修改 MapStruct 转换接口或相关 DTO 后必须 `clean` 再编译（增量编译不重新生成实现类，不改干净会跑旧实现）。

---

# Part B — Spring AI Alibaba Agent 架构层规范

## B.1 目录职责边界

| 目录 | 职责边界 |
|------|------|
| `controller/` | 接口层：入参校验（A.3.3）、调用 Service；禁止业务逻辑 |
| `dto/` `vo/` | 接口传输对象：每接口独立定义，根目录下，禁止跨层复用（D.4） |
| `service/`（`impl/`） | 业务层：接口 I 前缀 + 实现类；事务边界所在（A.4.12） |
| `convert/` | MapStruct 转换器（XxxConverter），全部集中 |
| `exception/` | BizException / ErrorCode / 控制流异常 |
| `record/` | 杂项对象（不隶属任何层/模块） |
| `properties/` `constants/` | 属性绑定类 / 业务常量（A.2.1） |
| `config/` | @Configuration / @Bean 注册，全部集中（A.2.1） |
| `mapper/` `entity/` | 数据层：MP 数据访问 / 表映射对象，均不出数据层 |
| `bot/` | Agent 图编排：graph（图/节点）/ tool（@Tool 工具）/ rewrite / prompt / hook |
| `retrieval/` | 检索链路：融合 / rerank / ContextBuilder |
| `etl/` | ETL 管道（解析 / 分块 / embedding / 入库） |
| `auth/` | 认证与安全（JWT / 拦截器 / Lua 原子操作） |
| `storage/` | MinIO 存储 |
| `stream/` | SSE 流式 |
| `worker/` | 队列消费（Redis Stream 消费组驱动图执行） |
| `enums/` | 枚举 |

## B.2 层级依赖（强制）

```
controller -> service -> mapper
agent 工具(@Tool) -> Service            （禁止直接访问数据层）
检索链路: 图节点 -> ContextBuilder -> Milvus/PG
worker -> service                       （禁止直接操作 mapper/DB）
```

1. 禁止跨层调用（controller 禁调 mapper、service 禁写 SQL、mapper 不含业务逻辑、实体不出数据层）。
2. 依赖方向单向无环；出现循环依赖时**拆层切断**（交叉查询下沉为独立 service，双方只依赖下沉层），禁止用 @Lazy/ObjectProvider 延迟注入掩盖。
3. Service 一律「接口 + 实现」：`IXxxService` + `XxxServiceImpl`（`service/impl/`）；CRUD 型接口 `extends IService<Entity>`、实现 `extends ServiceImpl<Mapper, Entity>`；聚合查询型接口不继承 IService，实现注入所需 mapper；调用方一律注入接口类型。
4. 跨 service 复用查询：经依赖注入调用对方 service 公开方法（含对方实例的 lambdaQuery/lambdaUpdate 链式能力），禁止直接操作他人 mapper、禁止复制查询逻辑。
5. agent 工具（@Tool）不直接访问数据层，一律经 Service 封装；图节点不得拼 SQL 或裸调 Milvus 客户端。

## B.3 运行时原则（图运行时）

1. **图节点职责单一**：LLM 调用、数据检索、外部操作拆分为独立节点，禁止"大节点包一切"；checkpoint 在节点边界生成，节点越小失败重执行范围越小。
2. **状态先行**：先定义 state schema 与每个 key 的更新策略（Replace/Append 等 reducer）；state 只存原始数据，禁止存拼接好的 prompt/模板；可派生数据不落 state。
3. **checkpoint 状态不可变语义**：节点/钩子禁止原地修改 state 对象，更新一律经 strategy/reducer 返回新值（updateState 按各 key 策略应用，不直接覆盖）。
4. **生产必须持久化 checkpointer**（PostgresSaver 落 PG，禁 MemorySaver 用于生产）；每次执行必带 thread_id（会话维度唯一、确定性生成，禁全局单值复用）。
5. **路由经条件边显式声明**：EdgeAction 读 state 返回分支名，映射表覆盖所有分支（含 END）；节点内不得擅自改变执行流。
6. 中断-恢复（HITL）必须依赖持久化 checkpointer（当前无 HITL 场景；引入前先配 saver，禁止无 checkpointer 时中断）。
7. **SSE 流式统一响应式（Flux）**：graph.stream() 惰性执行，必须订阅才真正启动；流式仅走响应式栈；落库以最终汇总消息为准，SSE 通道只透传不二次持久化。
8. **取消语义**：客户端断开/超时映射为 Flux dispose（cancel 传播至子图），禁止后台空转与重复写 chat_message。
9. worker 驱动图执行（A.5.3）；**同 thread_id 并发执行官方无语义保证**，项目侧以业务落库状态机（chat_run）保证单会话串行与幂等，checkpoint 只承载图状态不承载业务幂等。
10. 钩子/拦截器顺序契约：before 类按注册序、after 类逆序、拦截器嵌套（先注册者包外层）；消息改写优先 MessagesModelHook（APPEND/REPLACE 策略），需全局 state 才用 ModelHook。

## B.4 LLM 网关（DashScope）

1. 模型一律经 ChatModel/StreamingChatModel Bean 注册（按职责分 Bean：主对话 / 意图理解 / caption / embedding / rerank），禁止散落 new SDK 客户端；模型级 base-url/api-key 独立覆盖，运行时 options 优先于启动默认。
2. 版本基线对齐：spring-ai-alibaba 1.1.2.0 + Spring AI 1.1.2 + Spring Boot 3.5.8 为锁定组合，升级必须三件套同步对齐。
3. 凭证一律环境变量注入（A.2.3）；LLM 调用记录请求/响应摘要日志，**禁止打印完整响应体**。
4. 重试与限流分层：DashScope 重试参数（默认 max-attempts=10 指数退避）改为配置项，避免长链路叠加放大延迟；429 限流走平滑退避/备选模型切换，禁止无限重试（on-client-errors 语义下 4xx 不重试）。
5. 限流预算：限流按主账号汇总、充值不改变阈值；高频调用（意图理解/embedding）选高额度模型，重活（批量 caption）走 Batch API 或低峰窗口。
6. 上下文管理预算化：对话历史窗口受控（保留最近 N 条 + 系统消息），所有阈值配置化归 `properties/`；摘要压缩待长会话场景论证后引入。

## B.5 横切关注点

1. **工具装配**：@Tool 必须详尽 description（中文，说明业务意图）+ @ToolParam 参数描述与必填；工具白名单显式注册（toolNames + resolver），禁止全局默认工具；参数校验失败返回错误信息让模型修正。
2. **工具是模型唯一授权边界**：模型永远无法访问工具内部 API，工具内数据访问一律经 Service（B.2.5）；会话级上下文经 ToolContext / RunnableConfig.metadata 传入，对模型隐藏，禁止把 userId/sessionId 等暴露给模型。
3. **认证与安全**：JJWT 双 Token 无状态认证（AT 短期 + RT 一次性旋转）+ AuthInterceptor + Redis 黑名单；方法级安全显式 @EnableMethodSecurity + @PreAuthorize，未注解方法需 HttpSecurity 兜底；JWT 无状态 + 前后端分离场景全局禁 CSRF 符合官方边界（引入 cookie 会话类端点须重新评估）；密码存储统一 DelegatingPasswordEncoder（bcrypt strength 10，工作因子约 1 秒校验）。
4. **审计**：graphId/threadId 贯穿日志与追踪；checkpoint 历史（getStateHistory）即图状态审计留痕；敏感信息（PII）脱敏后再入日志。
5. **记忆/偏好提取异步化**：独立线程池 + 防抖 + 超时 + 失败丢弃不重试（打分规则、晋升/失效状态机等细节见 `docs/superpowers/specs/` 记忆设计，宪法不重复）。

---

# Part C — 前端规范

> 用户拍板前置条件（强制）：C 端 AI 对话 / 消息渲染 / 会话界面落地前必须与用户沟通确认；管理端界面按生产标准直接落地，不前置沟通。

## C.1 工程形态与技术栈

两个独立前端 app（C 端 `student-frontend/` + B 端 `frontend/`），pnpm workspace 单仓管理：
- C 端：Next.js 15.5 App Router + React 19 + Tailwind CSS v4 + @tanstack/react-query 5 + zod（SSE 流式 AI 对话渲染为核心卖点）
- B 端：Vue 3.5 + Vite 8 + Pinia + @tanstack/vue-query 5 + Tailwind v4 自研 shadcn 风格组件（`components/ui/`，无 Element Plus/antd）

工程纪律（双端同规）：
1. 包间禁止互引（根 `check:cross-imports` 断言强制）；共享代码必须下沉为独立 workspace 包并以 `workspace:` 协议引用，禁止 workspace 循环依赖。
2. 环境变量前缀纪律：`NEXT_PUBLIC_` / `VITE_` 前缀变量构建期内联进客户端 bundle，**禁止放 API key 等敏感值**（敏感值只存服务端）；`.env*.local` 一律 gitignore。
3. 组件边界：Next.js 布局/页面默认服务端组件，`'use client'` 只加在需要交互的叶子组件，禁止大范围标记客户端；敏感数据/凭据只存在于服务端（`server-only` 兜底构建期报错）。
4. 状态管理边界：远程/异步/易过时的服务端状态全部交给 TanStack Query，Pinia/组件 state 只承担纯客户端 UI 状态；mutation 成功后必须按 queryKey 失效（invalidateQueries），onSuccess 返回 Promise 保证写后读一致。
5. TypeScript `strict: true` 双端强制（B 端含 vue-tsc typecheck）。
6. Vue 组合式 API 类型化：`<script setup>` 中 defineProps/defineEmits 纯类型声明，禁止运行时声明与类型声明混用；组件多词命名、v-for 必带 key、禁止同元素 v-if/v-for 并用、组件样式必须 scoped 或 CSS modules。
7. Tailwind v4 视觉令牌统一定义在 CSS `@theme` 块（单一事实源），禁止散落硬编码色值、禁止按 v3 方式在 JS 配置定义主题。
8. 不可信输入（表单提交 / API 入参 / 外部回调）在边界处用 zod schema parse 校验（z.infer 同源推导 TS 类型），校验逻辑禁止散落组件内部。
9. SSE 流式契约（C 端核心卖点）：流式端点走 Route Handler + Web Streams API；流一旦开始 HTTP 状态码与响应头不可再改；部署层必须禁用反向代理缓冲（X-Accel-Buffering: no），防止流被吞。

## C.2 lint / format / 测试门禁

1. 双端齐备且合并主干前必须全过：ESLint（flat config）+ Prettier + typecheck（tsc/vue-tsc）+ Vitest 单测 + Playwright E2E。
2. 测试分层：组件/业务逻辑单测走 Vitest（jsdom / vue-test-utils / Testing Library），完整用户旅程走 Playwright E2E。
3. E2E 断言一律 web-first（expect 轮询重试 + 自动可操作性等待），**禁止固定 sleep** 与裸手动断言；CI 中优先稳定性（workers=1），测试失败必须使流水线失败并保留报告产物（upload-artifact，`if: always()`）。
4. E2E 浏览器安装保持单 job 串行（双端并行装浏览器会抢锁，CI 已有实证），浏览器二进制不缓存。

---

# Part D — RAG 课程助手（commerce-customer）项目实际

## D.1 系统定位

企业级多模态 RAG 课程助手：C 端学生 AI 对话（意图体系 knowledge_question / chat / unknown，意图与检索解耦、元数据过滤收窄检索），B 端知识库/课程管理。核心链路：ETL 多模态入库 → Agent 图编排（意图理解 → 混合检索 → 生成）→ SSE 流式对话，叠加偏好与情景记忆体系。
业务规则（意图体系、state 键清单、记忆打分/晋升/失效状态机、检索链路的图节点设计）以 `docs/superpowers/specs/` 为准，本文件不重复。

## D.2 技术栈选型（版本为项目实测，不得更改基线）

| 职责 | 技术 | 版本 |
|------|------|------|
| 语言 / 构建 | Java 17 / Maven 3.9.16（本地仓库 `D:/code/java/maven/apache-maven-3.9.16/repository`） | 锁定 |
| Web 框架 | Spring Boot（spring-boot-starter-parent） | 3.5.8 |
| Agent 框架 | spring-ai-alibaba（agent-framework + graph-core + starter-dashscope） | 1.1.2.0 |
| LLM 集成 | Spring AI（DashScope OpenAI 兼容端点） | 1.1.2 |
| ORM | MyBatis-Plus（starter + 独立 jsqlparser 依赖） | 3.5.12 |
| 数据库迁移 | Flyway | 11.7.2 |
| 数据库 | PostgreSQL（pgJDBC 42.7.8，镜像 postgres:latest） | HikariCP 6.3.3（maximum-pool-size=20） |
| 向量数据库 | Milvus standalone（镜像 v2.6.19，etcd + MinIO） | SDK 2.6.11 |
| 缓存 / 队列 | Redis（Lettuce 6.6.0 + commons-pool2，Stream 消费组） | 镜像 redis:latest |
| 对象存储 | MinIO（SDK 8.5.17，生产双实例：Milvus 存储 + 业务存储） | 镜像 pgsty/minio:latest |
| 认证 | JJWT 双 Token + Spring Security 6.5.7（AuthInterceptor + Lua 原子脚本） | 0.12.6 |
| 文档解析 | Apache Tika（parsers-standard + core） | 2.9.2 |
| 层间映射 | MapStruct（lombok-mapstruct-binding 0.2.0） | 1.6.3 |
| 测试 | JUnit 5.12.2 / Mockito 5.17.0（@MockitoBean）/ Testcontainers 1.21.3 | Boot BOM |
| 质量门禁 | Spotless 2.46.1 / Checkstyle 3.6.0 / SpotBugs 4.9.8.5 / JaCoCo 0.8.15（绑定 verify） | pom.xml 锁定 |
| C 端前端 | Next.js 15.5.23 + React 19.1 + Tailwind v4 + TanStack Query 5.102 + Vitest 4.1 + Playwright 1.62 | pnpm-lock 锁定 |
| B 端前端 | Vue 3.5.41 + Vite 8.2.2 + Pinia 4.0.3 + Tailwind v4 + Vitest 4.1 + Playwright 1.62 | pnpm-lock 锁定 |
| CI | GitHub Actions（JDK temurin 17 / Node 22 / pnpm 9.15.9） | `.github/workflows/ci.yml` |

## D.3 目录结构

```
commerce-customer/
├── backend/                     # Spring Boot 单模块后端（artifactId commerce-rag）
│   └── src/main/
│       ├── java/com/commerce/rag/
│       │   ├── controller/ dto/ vo/ service/ convert/ exception/ record/
│       │   ├── properties/ constants/ config/
│       │   ├── mapper/ entity/ enums/
│       │   ├── bot/ retrieval/ etl/ auth/ storage/ stream/ worker/
│       │   └── CommerceRagApplication.java
│       ├── resources/
│       │   ├── application.yml   # 单一配置文件（A.2.4）
│       │   ├── db/migration/     # Flyway 迁移 V6~V14
│       │   ├── mapper/           # 复杂 SQL XML（A.4.5）
│       │   ├── lua/              # Redis 原子脚本（A.5.6）
│       │   └── prompts/          # 提示词 YAML（8 份）
│       └── test/                 # 单测 + Testcontainers 集成测试
├── student-frontend/            # C 端 Next.js 15.5 App Router
├── frontend/                    # B 端 Vue 3.5 + Vite
├── docs/                        # 过程文档（bugs/ pref/ progress/ contracts/
│                                #  superpowers/specs/ plans/ design/ agmds-research/）
├── docker-compose.yml           # 生产编排（PG / Redis / MinIO×2 / etcd / Milvus）
├── docker-compose.dev.yml       # dev 编排（单 MinIO 实例）
├── tools/check-cross-imports.sh # 双前端禁互引断言（C.1.1）
├── TASK.md / CHANGELOG.md / AGENTS.md
└── .github/workflows/ci.yml     # 三 job：后端 verify / 前端门禁 / E2E
```

## D.4 数据契约

1. **Entity**（数据库表映射对象，MP 实体）只存在于数据层（mapper/service），禁止出 service 边界、禁止直接返回给 controller/前端。
2. **DTO/VO 等传输对象各层独立定义，禁止跨层复用**——即使字段完全相同，只要不是表映射对象一律不复用；controller 入参走 DTO、出参走 VO（根目录 dto/vo 包），每接口独立，保证接口契约独立演化。
3. 层间转换**必须使用 MapStruct**（convert/ 包 XxxConverter，编译期生成实现），禁止手写转换代码；修改转换接口或相关 DTO 后必须 clean 再编译（A.6）。
4. ID 与外部资源键：数据库主键雪花 ASSIGN_ID（A.4.6）；**MinIO objectKey 等外部资源 key 一律 uuid 预生成**，先占资源再落库（A.5.7），禁止先插 DB 拿 ID 再拼资源键。
5. 消息落库契约：对话上下文双写 `chat_run`（业务入口）+ `chat_message`（渲染审计），附件 URL 双存；图 state 不存附件 URL，后续轮次以 chat_run 为入口重建。
6. 缓存一致性铁律：先写 DB → 后失效缓存；原子操作走 Lua（A.5.4 / A.5.6）。

## D.5 检索存储契约（Milvus 2.6）

1. **collection 架构变更（加向量字段 / 改 embedding 维度 / 改主键）必须可 drop 重建 + 重灌数据，禁止线上原地改造**；embedding 模型升级（维度变化）即触发重建流程（drop → 建 schema → 建索引 → 批量重灌 → load）。
2. 搜索前 collection 必须 load 且全部向量字段索引就绪（缺失即报错），启动/变更后显式校验，失败快速失败。
3. 混合检索（稠密 + 稀疏）= 多路有界 AnnSearch + 重排融合（RRF，k 默认 60、官方推荐 [10,100] 区间实测调优）→ 模型 rerank；Index 切换自动删旧索引，索引类型变更视为架构变更走重建流程。
4. 稠密索引默认 HNSW / AUTOINDEX（按 recall/QPS 评测固化），稀疏向量必须 SPARSE_INVERTED_INDEX；禁止使用已弃用索引类型（如 SPARSE_WAND）。
5. **查询必带 topK 有界召回**：单次 ANN 搜索 limit+offset < 16384，超出改 search iterator，禁止无 limit 检索。
6. 元数据过滤在 ANN 前执行收窄检索范围，过滤表达式保持简单；项目的 course_id 是相关性收窄而非权限（权限语义见 `docs/contracts/`）。
7. 多租户隔离：user_id 硬隔离独立 collection（物理隔离）；租户规模上百万或 collection 数逼近上限再评估 partition key（启用后搜索/删除必须带 partition key 过滤，隔离特性仅支持 HNSW）。
8. 一致性级别按场景选：常规对话检索默认 Bounded；写后立查（记忆写入后立即召回）用 Session/Strong。
9. 写入：在线批量分批 insert；全量重建走 bulk import；upsert 本质 delete+insert（性能折损、主键不可更新、autoID 不可用），按需使用不滥用。
10. SDK 连接单例（Spring Bean + destroyMethod close）；显式配置 rpcDeadlineMs（默认 0 = 无截止，防挂死）；读路径可依赖默认重试，**写路径收紧重试上限与总时限**（默认 75 次无总时限）；SDK 2.6.x 与服务端 2.6.x 版本匹配（升级前对照兼容表）。
11. sparse/BM25 检索当前降级（milvus-sdk-java EmbeddedText 兼容 bug，dense-only 可用），恢复候选方案见 TASK.md §4。

## D.6 变更控制

1. 本文件为项目最高规范，修改需记录原因和日期。
2. 新增模块 / 调整层级依赖 / 换技术栈 / 依赖版本基线变更，须先更新本文件。
3. 代码审查以本文件为第一准则。
4. **变更记录**：追加至 `CHANGELOG.md`（`{日期} · {变更简述} · {原因}`，先记变更再改正文）。

## D.7 永久环境约束 + 后续实现索引

**永久环境约束**（本机/构建硬约束，非待办）：
- Maven 本地仓库固定 `D:/code/java/maven/apache-maven-3.9.16/repository`（由 Maven 安装目录 conf/settings.xml 的 `localRepository` 指定，构建依赖此路径）。
- GitHub push 大流量间歇 reset：origin push URL 已切 HTTPS（fetch 仍走 SSH），勿改回。
- pre-commit 通用文件检查已限定 `stages: [pre-commit]`，禁止扩到 pre-push（全仓历史扫描与 stash 冲突会阻断 push）。
- SAA 1.1.2 新模型仅 OpenAI 兼容接口：dev 启动需按 `docs/progress/` 对应记录注入模型选择环境变量（SPRING_AI_DASHSCOPE_CHAT_OPTIONS_MODEL 等）。

**后续实现索引**（详情见 `TASK.md`）：

| 功能 | 索引 | 状态 |
|------|------|------|
| 多实例部署（雪花 worker-id / 本地缓存一致性） | TASK.md §1 | 待用户批准 |
| Milvus sparse/BM25 检索恢复 | TASK.md §4 | 暂缓（SDK bug #1402） |
| 宪法调研不可得项登记（{待调研项}） | TASK.md §5 | 待回填 |

**本地 hook / CI 门禁**（全局 §八落地形态）：
- 首次 clone 后：`pre-commit install --hook-type pre-commit --hook-type pre-push`；pre-commit 跑快检（spotless / checkstyle / 双前端 eslint + prettier + 通用文件检查），pre-push 跑 `mvn -B verify` 全量。
- 云端 CI（GitHub Actions）三 job：后端 `mvn -B verify`（含 JaCoCo ≥80%）/ 前端 `pnpm lint && typecheck && test:cov`（含 check:cross-imports）/ E2E（chromium 单 job 安装）。
- 不合规代码或测试不过不得合入主干，CI 自动阻断。
