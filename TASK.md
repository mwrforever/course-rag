# TASK.md — 待办任务清单

> 记录「暂缓落地、需前置条件」的任务。已完成事项见 git 历史，不在此列。

---

## 1. 多实例部署（雪花 ID worker-id / 本地缓存一致性）— ✅ 已批准立项（2026-08-25 拍板）

**背景**（2026-08-16 24h 审查 BUG-8 + 性能 L-14）：application.yml 雪花 ID `worker-id: 1`、
`datacenter-id: 1` 硬编码——多实例部署时主键跨实例冲突（写失败/数据覆盖）；
CourseQueryService 等 Caffeine 本地缓存多实例间写失效不互通（脏读至 TTL 上界）。

**当前状态**：用户 2026-08-25 拍板方案 = **worker-id/datacenter-id 改 env 配置化静态指定
（每实例配不同值，运维保证唯一）+ 课程查询缓存（courseQueryCache）Caffeine→Redis 分布式
（其余三处缓存接受 TTL 上界并文档声明）**；实施提交见 git 历史（2026-08-25）。

**接入清单**（2026-08-25 已批准，实施状态）：
- [x] 雪花 ID worker-id/datacenter-id 配置化指定（env 注入，多实例唯一，注释自认未实现 → 已实现）
- [x] 多实例缓存一致性评估并落地：课程查询缓存 Redis 分布式化（TTL/失效语义与 Caffeine 一致），
      dashboard/附件/偏好三处接受 TTL 上界（评估结论落 `docs/progress/2026-08-25-TASK遗留项清理交接.md` 第 2.8 节与实施提交）

---

## 2. 前端 E2E（Playwright）— ✅ 已完成（2026-08-24 双前端落地 PR#5/PR#6）

**背景**：CI 集成表要求后端配套 Playwright E2E。当前 `frontend/` 与 `student-frontend/`
均为空目录（设计文档定 Next.js App Router + TS，**两前端完全独立，禁共享包/跨引用**），
无 UI 可测，故本阶段不集成 E2E，仅记录待办。

**前置条件**（AGENTS.md 强制）：前端 AI 对话 / 消息渲染 / 会话界面落地前**必须与用户沟通**；
管理端界面按生产标准直接落地。

**接入清单**（已全部完成，2026-08-24）：
- [x] `frontend/`（管理端）：Playwright 23 用例（认证/仪表盘/知识库/文档/分片/课程/用户/反馈-权限）
- [x] `student-frontend/`（C 端）：Playwright 29 用例（SSE 10 事件逐类断言 + 生命周期 + 附件/反馈）
- [x] `.github/workflows/ci.yml` e2e job 落地（PR#6 后 CI 三 job 全绿）
- [x] 两前端 E2E 工程各自独立（route-mock 模式，零共享包）

---

## 3. JaCoCo 覆盖率补测路线图（~~37% → 80%~~ ✅ 已完成 2026-08-15 晚，实测 80.1%；2026-08-16 二轮收官 LINE 95.0%）

**达成记录**：
- 2026-08-15 晚：`mvn verify` 全绿（spotless/checkstyle/spotbugs/jacoco LINE 80.1%）；全量测试 480/480。
  4 批次 11 提交（af1ccc2..7952066）：批次A config+controller 0% 类 → 62.4%；批次B service 0% 五类 → 67.8%；
  批次C FusionService+bot（QueryRewriter/Graph/Interceptor/Hook）→ 73.1%；批次D 低覆盖大块扩展 → 80.1%。
- 2026-08-16（A1/A2/B1/B2/C2/C3 轮）：全量测试 707（680 单测 + 9 集成 + 1 冒烟 + 17 mapper），
  jacoco BUNDLE LINE 95.0%（指令 94.3%），0.80 总门禁达标；新增集成测试（Testcontainers PG+Redis，
  Auth/ChatFlow/Security 三链路 9 用例）与 mapper XML 执行级测试（5 个 XML、17 用例，真实 PG 断言 SQL 结果）。

**遗留低覆盖类**（原 8 类，2026-08-16 本轮全量补测后全部 ≥80% 达标，实测覆盖率见下表）：

| 类 | 指令覆盖率 | 行覆盖率 |
|---|---|---|
| MinioStorageService | 100.0% | 100.0% |
| DocumentChunkServiceImpl | 100.0% | 100.0% |
| LeadAgentGraph | 100.0% | 100.0% |
| SearchKnowledgeTool | 100.0% | 100.0% |
| ReminderHook | 99.1% | 100.0% |
| ChatRequestWorker | 98.7% | 99.5% |
| PromptLoader | 94.3% | 96.1% |
| ChatStreamEntry（原 ChatController 编排，Task 1 抽取后改名） | 96.4% | 94.6% |

注：8 类均已 ≥80% 达标，非门禁阻塞；jacoco 单类 ≥80% 门禁计划中（Task 12 上线）。
GraphConfig.postgresSaver 与 MilvusConfig 已豁免（构造器真实建连，外部依赖）。

**历史路线图存档**（2026-08-12 基线 37%）：

| 包 | 覆盖率 | 行数(覆盖/总) |
|---|---|---|
| bot/graph（LeadAgentGraph 等图编排） | 0.6% | 1/172 |
| bot/rewrite（QueryRewriter） | 0.0% | 0/44 |
| 根包（启动类） | 0.0% | 0/3 |
| service | 22.4% | 238/1063 |
| controller | 24.5% | 169/690 |
| etl | 28.5% | 79/277 |
| auth | 29.4% | 88/299 |
| worker | 36.6% | 140/382 |
| controller/dto、entity | 45.5% | 15/33 |
| storage | 50.0% | 22/44 |
| bot/hook | 56.4% | 211/374 |
| retrieval | 58.3% | 49/84 |
| bot/tool | 76.2% | 147/193 |
| stream | 80.2% | 182/227 |
| config | 81.0% | 166/205 |

**补测顺序建议**（按「核心功能 100% / 非核心 ≥80%」的 AGENTS.md 要求，先核心后外围）：
1. **auth**（认证/权限 = 核心安全路径，目标 100%）：TokenService 签发/校验/刷新、
   AuthInterceptor 全分支、DeviceKickService 互踢、黑名单
2. **controller**（对外 API = 核心）：各端点正常/鉴权失败/参数异常三分支
3. **service**（业务主体）：KnowledgeBaseService / DocumentChunkService / UserFeedbackService 等
4. **bot/rewrite + bot/graph**（对话链路）：QueryRewriter、LeadAgentGraph 状态流转
5. **etl / worker / storage**（管道与队列）
6. **entity/dto 样板类**：与业务断言合写，禁止为凑数写空断言测试

**约束**（AGENTS.md 6.2/6.5）：测试与实现同一次提交；新测试必须覆盖
正常路径 + 边界 + 异常三类场景；禁止空断言凑覆盖率；禁止针对已废弃行为的测试。

---

---

## 4. Milvus sparse/BM25 检索恢复（milvus-sdk-java EmbeddedText 中文崩溃）— 暂缓，用户 2026-08-18 拍板搁置

**背景**（2026-08-18 S1 计划 2/5 手动验证发现；issue #1402 系 2025-05-12 社区提报，非本项目）：milvus-sdk-java
（2.6.11 及 2.6.21）的 `EmbeddedText` 在 sparse/混合检索与服务端 BM25 Function 组合下失败——服务端
INTERNAL 后 SDK 无限重试至超时（实测 75 次/210s）静默失败；pymilvus 同请求正常。已加
`retrieval.sparse-enabled=false` 降级开关（dense-only 检索，实测 1.6s 正常），全文检索能力暂时关闭。

**issue #1402 评论区解决方案标记（2026-08-26 核查，13 条评论全量已阅）**：
- **维护者 yhmo（milvus-io 核心）2025-05-13 官方正确用法**（本 issue 核心答复）：
  full-text-match 应由**服务端 BM25 Function 自动生成 sparse 向量**——text 字段
  `enableAnalyzer(true)`（必开）+ `FunctionType.BM25`（input=text → output=sparse_vector）；
  用户**只插入文本、检索也只传文本**（`EmbeddedText` 包装），不提供也不传手动 sparse 向量；
  参考示例 `milvus-sdk-java/examples/.../FullTextSearchExample.java`（GitHub master）
- **SDK 版本线**：提报者 qidafang0413 实证 SDK 2.5.2→2.5.9 修复（Milvus 2.5.11）；yhmo 确认
  HybridSearch 全文检索自 **SDK v2.5.4** 起支持——本项目 SDK 2.6.11 已远超修复线
- **中文崩溃实锤**：提报者 2025-05-16 实测——EmbeddedText 输入**中文**导致服务端容器崩溃
  （goroutine/grpc panic 日志），英文输入正常；核心开发者 xiaofan-luan 2025-05-21 指出
  **中文检索必须用 jieba 分词器**（服务端 analyzer 配置），并提示 bulk import 存在
  tokenizer leakage 已知问题（施测时避开批量导入路径）

**当前状态**：**已降级不阻塞**（dense-only 检索可用），用户 2026-08-18 指示暂缓、后置处理。

**恢复方案候选**（2026-08-25 用户拍板 = 方案一，见下）：
- [x] **维持降级（已拍板）**：2026-08-25 核实 milvus-sdk-java#1402 仍 Open、无指派无修复版本；
      用户裁决继续 dense-only 降级运行，**每季度复查 issue 状态**（下次复查 2026-11 前后），
      修复发布后 `retrieval.sparse-enabled=true` 一行还原并全量回归（检索链路集成测试 + SSE 对话带来源手动验证）
- [ ] **按维护者官方用法整改（2026-08-26 标记的新候选，待用户批准）**：不升级/不等待 SDK——
      服务端 collection 重建时 text 字段配置 **jieba 中文分词器**（enableAnalyzer + analyzer_params）+
      BM25 Function（D.5.1 drop 重建流程），检索 sparse 路改传 `EmbeddedText(查询文本)` 让服务端分词；
      相较「应用侧 BM25 向量」方案**无需 ETL 计算 sparse 向量与引入第三方分词器**（中文分词交给服务端 jieba），
      改动集中在 schema 重建 + 检索节点（application.yml sparse-enabled 还原 + 全量回归）；
      验收必须含**中文语料真实检索**（覆盖 issue 实证的中文崩溃场景）与 pymilvus 对照
- [ ] 应用侧 BM25 向量方案：ETL 自行计算 sparse 向量（需中文分词器）+ Milvus collection 重建去掉 BM25
      Function（sparse 字段改存向量 + IP 检索），检索用 SparseFloatVec——改动大（schema/ETL/检索三处），
      未获批准，候选保持
- [x] 附件链路（计划 3/5）已定案**保持纯向量**（用户 2026-08-18 拍板），不受本项影响

---

## 5. 宪法调研不可得项登记表（2026-08-25 constitution-generator 调研）

**来源**：`docs/agmds-research/2026-08-25-{java-spring-boot|mybatis-plus-postgresql|redis-minio|milvus|spring-ai-alibaba-agent|frontend-dual|build-test-ci}.md`
各报告的「检索不可得项」。回填后删除对应行。

| 待回填项（{待调研项}） | 涉及段 | 回填状态（2026-08-25 二轮双源取证） |
|---|---|---|
| A-3 Lombok × record 注解兼容性的官方完整矩阵 | A.1.3 | 不可得保留：官方无兼容矩阵文档；FAQ 页 404 已实锤，权威说明 = @NonNull features 页（record 组件支持原文）+ changelog 零散条目（1.18.20/24/28/30/32 record 适配）；以 1.18.42 为准，逐注解兼容仍须编译实测 |
| B-6 Flyway 在 PG 上非事务性 DDL 完整行为矩阵 | A.4.2 | 不可得保留：官方 PG 页仅覆盖默认事务锁 / CREATE INDEX CONCURRENTLY 冲突 / 事务锁切会话级锁 / clean 不删扩展对象 / pg_dump 兼容；无 ALTER TYPE/VACUUM 等完整非事务 DDL 清单，仅可查 Release Notes |
| B-7 Redis 缓存穿透/雪崩官方专页 | A.5 | 不可得保留：官方文档站无专页；仅官方博客 thundering herd 主题（非文档级），缓存穿透等术语散见社区实践 |
| B-16 Milvus SDK 端 Session 一致性写后立查时序细节 | D.5.8 | 不可得保留：官方 v2.6 一致性权威页实锤四种级别定义与 GuaranteeTs 机制（Session=客户端插入最新时间点作 GuaranteeTs），但无 SDK 层 batch 写入后立即 search 时序细节，确认需集成测试实证 |
| E-1 Maven 命令行侧注解处理器陈旧生成物的官方直述 | A.6 | 不可得保留：无"命令行必须 clean"字面直述；补到机制级官方依据 maven-compiler-plugin `useIncrementalCompilation`（3.1.0+ 默认 true，false 模式导致引用失效方法的类不重编译），clean 纪律为工程化兜底 |
| E-2 SpotBugs check 失败语义的官方成文描述 | A.6 | 不可得保留：官方 check-mojo 页 Description 为空，失败语义只能从参数默认值推断（failOnError=true、maxAllowedViolations=0） |
| E-5 Testcontainers Milvus 模块版本线与 core 的耦合矩阵 | A.6 | 不可得保留：模块页确认独立版本线（org.testcontainers:testcontainers-milvus:2.0.5，与 core 1.21.x 分离），兼容矩阵官方未说明 |

**二轮回填纪要（2026-08-25）**：A/B/C/D/E 五组 36 条全量双源取证（官方文档 + GitHub/源码版本文档）。**29 条定论删行**，已按实锤修订宪法 5 处（A.3.1 / A.4.10 / A.6 / B.4 / D.5.9），修订记录见 CHANGELOG。三处重要修正：
- Milvus v2.6.x 版本文档实存于 milvus-docs 仓库 `v2.6.x` 分支（upsert/partition-key/add-fields 页均在线，原"仅 v3.0"系查 v2.3 下划线命名所致）；
- MP 官方存在 `@MybatisPlusTest` 切片与 JSONB TypeHandler 专页（原登记"第三方官方无覆盖"不成立）；
- SAA 1.1.2 Java 侧 rerank 官方集成存在（DashScopeRerankModel + `spring.ai.dashscope.rerank.*`），默认 gte-rerank 随百炼 2026-05-30 下线，项目已显式配 qwen3-rerank；`spring.ai.dashscope.read-timeout` 属性已绑定但不生效（超时实为 SDK 硬编码 60s/180s）。

---

---

## 6. 双前端 UI 全面重构 — ✅ 已完成（2026-08-25 PR#7/PR#8）

**背景**：用户判定 C 端/B 端界面样式结构奇丑、管理端侧栏层级不合理、C 端课堂/首页/课程助手几乎无法使用，责令全面重构；课程助手参照参考稿 `D:\code\project\assert\kimi\README.md` 按项目调整，配色必须高级，首页要求内容滚动动效。

**交付**（两 PR 待合并 dev）：
- C 端（PR#7）：kimi 蓝系设计令牌（暖米白 + #2F8BF5 蓝族 + 圆角/阴影/动效体系）；首页电商风（Hero 光晕 + 分类筛选条 + 推荐课程网格 + motion whileInView 滚动动效）；课程助手 kimi 式改造（/chat 迁出 (main) 组 → (chat) 组全局左侧栏：品牌/新建对话 Ctrl+K/会话历史/用户区，260↔64px 折叠持久化；消息气泡/思考卡/来源卡/20px 输入区参照稿逐项落地）；课堂/会话/个人中心/登录页品牌化
- B 端（PR#8）：深色侧栏（ink 板 + 图标分组展开 + 激活光条 + 折叠持久化）+ 面包屑 + 路由过渡；职责拆分（/students 学生管理两角色 + /teachers 教师管理仅超管，/users 重定向；课程详情五子路由 概览/内容/排期/教师/学生；/404 页；知识库管理入侧栏）
- 门禁：C 端 358 单测 + 29 E2E；B 端 278 单测 + 23 E2E；覆盖率核心文件 100% 行铁律保持；lint/typecheck/format 全绿

**遗留（后续打磨候选）**：~~B 端 Feedback/Sessions 会话回放 Drawer 抽公共组件、DocumentsView 行菜单 overflow 裁切、Dashboard ECharts 色值令牌化、C 端学科兜底渐变令牌化~~（**2026-08-25 全部完成**，见 git 历史）、C 端意图体系相关 UI 微调（无具体项，等用户提出）。

**评审修复轮（2026-08-25，superpowers 双评审 With fixes → 合入前修复）**：
- C 端 PR#7 修复：scrollbar-none 死类补 @utility；工作区 sessionId 落位即失效侧栏会话缓存；Ctrl+K 流式守卫（新增 ChatStreamingProvider，(chat) 布局回归服务端组件）；HERO rgba 字面量/徽章/CTA 遮罩改 overlay 与 brand-light 语义令牌 + 孤儿令牌清零；筛选 chips tab 角色 → aria-pressed 按钮组；注释漂移修正；+1 侧栏 E2E（共 30）
- B 端 PR#8 修复：路由过渡 :key 从 RouterView 归位至页面 vnode（新增 resolvePageKey 纯函数：同实体子路由切换壳存活不重取数，跨实体导航重挂载重取数免 watch）；页面淡入过渡移除——`<Transition>` 包裹 RouterView 插槽在 vue@3.5.41 + vue-router 组合下导航后新视图永不挂载（真实浏览器实证，与 key 取值/是否 out-in 无关；旧实现把 key 挂 RouterView 实为绕开缺陷的变通、过渡从未真正播放），待依赖升级后重评；仪表盘「添加学生」入口直指 /students（不再依赖重定向兜底）；TeachersView 列表变量名 students→teachers
- **拍板落地（2026-08-25）**：B 端远程状态 vue-query 化——用户批准**分两批迁移**：批 1 = 17 个手动 load() 视图（合计 6,879 行，模板统一机械迁移，估 1-2 PR）；批 2 = Chunks/Documents 两混合视图补强（2,197 行，上传/删除改 useMutation + 竞态守卫收敛）；迁移保持接口契约与交互不变、mutation 后按 queryKey 失效、测试同步改造且门禁全绿；**批 1 已完成合入 dev（2026-08-26，PR#9 = 5c2940b+e2ec442+登记 06e530b+审查修复 9d5f2b8）：17 视图全部 useQuery+useMutation 化——列表/报表用 computed queryKey（筛选/页码/路由派生态变化自动重查）、Promise.all 多接口合并单查询、mutation 后 invalidateQueries 失效（末页空页回退仅限删除类操作）、编辑器/表单查询 refetchOnWindowFocus:false 防后台覆盖未保存编辑、404 以 queryFn 返回 null 标记；审查修复 F1-F6（六视图非删除操作误弹页/编辑器覆盖等）；门禁全绿 lint+typecheck+288 单测+24 E2E+CI 三 job**；**批 2 已完成合入 dev（2026-08-26，PR#10：Chunks/Documents 两视图写操作全部 useMutation 化——批量修正/标记已修正/编辑保存/批量删除/单条删除/重新解析/改标题/上传，onSuccess invalidateQueries 按查询键失效且末页空页回退（单条删除 + 批量删除当前页全删成功双路径）；上传 XHR 进度条留在组件本地 ref（瞬时 UI 状态不进 query 缓存）；KB 下拉 onMounted 手动 fetch 收敛为 ['admin-kbs-options'] useQuery 双视图共用；上下文 Drawer 自增 seq 竞态守卫收敛为 enabled 按需 useQuery（查询键切换天然收敛竞态，删除 loadContext/loadSeq）；修复存量隐性 bug：单条删除后勾选集残留（原 filter 引用已置空的 deletingDoc）；遗留四项全部完成：①五视图 + documents 单删/批删「删除末页回退」测试补齐（共 8 新用例，按 courses 模板）；② ~15 处 mutation 后 flushPromises 立即断言 refetch 结果改 vi.waitFor 收敛（含 chunks 上下文三用例——query 化后为异步调度）；③ CourseTeachersView 刷新失败 toast 恢复（vue-query 实证三连后定案：refetchQueries 返回 Promise<void> 拿不到单查询结果、getQueryState 需精确键、v5 refetch() 失败也 resolve——最终方案 await refetch() + getQueryState 精确键 error 判定）；④ 教师候选池失效范围评估结论 = 保持合并单查询（候选池 ≤100 行 + 低频操作 + 拆双查询需整页四态合并且无性能收益，代码注释 + 本登记记录）；门禁全绿 lint+typecheck+296 单测+24 E2E+coverage 91.6%**；**批 3 候选（2026-08-26 批 2 执行中新发现，待用户批准立项）**：①DocumentsView 上传 Dialog `searchCourses` / ChunksView 批量修正 Dialog `searchBatchCourses` 两处课程搜索选择器仍为手动 async 模式（两视图仅剩的 onMounted/手动 fetch 残余），「输入即查」天然适配 computed queryKey + enabled 收敛；②students/teachers 视图测试 beforeEach 用 `vi.clearAllMocks()`，与其余视图测试统一 `vi.restoreAllMocks()` 不一致——clearAllMocks 不清 mock 实现（实证），mockResolvedValueOnce 数量不匹配即跨用例泄漏，需对齐；③KnowledgeBasesView 行无 `row-{id}` testid（仅 edit-{id}/delete-{id}），与其他列表视图（row-{id}）不一致，观察项；④上传进度条仅有单测覆盖（宽度断言）、无 E2E 用例（route-mock 模式难以模拟 XHR onUploadProgress 渐进回调），覆盖缺口登记；B 端路由淡入过渡重评：前置 = Vue/vue-router 依赖升级（vue@3.5.41 Transition 包 RouterView 插槽缺陷实证），升级后以 Playwright 真浏览器验证恢复点（AdminLayout.vue 注释标明）
- 附带发现：TASK.md 本节路径曾含 BEL 控制字符（0x07，前会话转义事故），本轮修复
