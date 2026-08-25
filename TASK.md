# TASK.md — 待办任务清单

> 记录「暂缓落地、需前置条件」的任务。已完成事项见 git 历史，不在此列。

---

## 1. 多实例部署（雪花 ID worker-id / 本地缓存一致性）— 待用户批准立项

**背景**（2026-08-16 24h 审查 BUG-8 + 性能 L-14）：application.yml 雪花 ID `worker-id: 1`、
`datacenter-id: 1` 硬编码——多实例部署时主键跨实例冲突（写失败/数据覆盖）；
CourseQueryService 等 Caffeine 本地缓存多实例间写失效不互通（脏读至 TTL 上界）。

**当前状态**：单实例无影响，**未实现**。用户 2026-08-16 指示：后续扩展，吸纳为待办，
**批准后才能动**。

**接入清单**（批准后执行）：
- [ ] 雪花 ID worker-id/datacenter-id 从 Redis/DB 分配或配置化（多实例唯一，注释自认未实现）
- [ ] 多实例缓存一致性评估（Redis 分布式缓存替代 Caffeine，或接受 TTL 上界）

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

## 4. Milvus sparse/BM25 检索恢复（milvus-sdk-java EmbeddedText bug）— 暂缓，用户 2026-08-18 拍板搁置

**背景**（2026-08-18 S1 计划 2/5 手动验证发现）：milvus-sdk-java（2.6.11 及 2.6.21）的
`EmbeddedText` 在 sparse/混合检索与服务端 BM25 Function 不兼容（GitHub issue
milvus-io/milvus-sdk-java#1402，仍 Open），服务端 INTERNAL 后 SDK 无限重试至超时（实测 75 次/210s）
静默失败；pymilvus 同请求正常。已加 `retrieval.sparse-enabled=false` 降级开关（dense-only 检索，
实测 1.6s 正常），全文检索能力暂时关闭。

**当前状态**：**已降级不阻塞**（dense-only 检索可用），用户 2026-08-18 指示暂缓、后置处理。

**恢复方案候选**（执行时选择其一，均需回归验证）：
- [ ] 等 milvus-sdk-java 修复 EmbeddedText（关注 issue #1402，修复后 `retrieval.sparse-enabled=true` 一行还原）
- [ ] 应用侧 BM25 向量方案：ETL 自行计算 sparse 向量（需中文分词器）+ Milvus collection 重建去掉 BM25
      Function（sparse 字段改存向量 + IP 检索），检索用 SparseFloatVec——改动大（schema/ETL/检索三处）
- [ ] 附件链路（计划 3/5）已定案**保持纯向量**（用户 2026-08-18 拍板），不受本项影响

---

## 5. 宪法调研不可得项登记表（2026-08-25 constitution-generator 调研）

**来源**：`docs/agmds-research/2026-08-25-{java-spring-boot|mybatis-plus-postgresql|redis-minio|milvus|spring-ai-alibaba-agent|frontend-dual|build-test-ci}.md`
各报告的「检索不可得项」。回填后删除对应行。

| 待回填项（{待调研项}） | 涉及段 | 原因 | 回填状态 |
|---|---|---|---|
| A-1 REST API 版本化的官方统一规范 | A.3.1 | Spring 官方无版本化策略条文，/api/v1 为团队约定 | 待回填 |
| A-2 Java 17 preview 特性"禁生产"的官方一句话禁令 | A.1.2 | 官方仅标注 preview 语义，禁止系工程推论 | 待回填 |
| A-3 Lombok × record 注解兼容性的官方完整矩阵 | A.1.3 | 官方 FAQ 页 404，仅 changelog 零散条目，以 1.18.42 实测为准 | 待回填 |
| A-4 Spring Boot 官方对 MyBatis-Plus 的测试切片等价物 | A.6 | 第三方库官方无覆盖，用 @SpringBootTest + Testcontainers | 待回填 |
| B-1 「本表 this.lambdaQuery() 链式、禁 new Wrapper」官方条文 | A.4.3 | 官方仅推荐 lambda 链式，使用边界为项目内部约束 | 待回填 |
| B-2 「saveBatch 必须在事务内」官方条文 | A.4.6 | 官方仅写批量"事务默认关闭"，须由 Spring 事务语义推得 | 待回填 |
| B-3 「禁手动 IdWorker」官方条文 | A.4.6 | 官方仅说明 ASSIGN_ID 自动分配 | 待回填 |
| B-4 「查询必带分页」及 maxLimit=2000 出处 | A.4.7 | 官方仅定义 maxLimit 行为，阈值为项目配置决策 | 待回填 |
| B-5 PG JSONB 与 MP String 绑定兼容性官方依据 | A.4.10 | 官方无 ORM 绑定说明，系项目历史实证 | 待回填 |
| B-6 Flyway 在 PG 上非事务性 DDL 完整行为矩阵 | A.4.2 | 官方仅覆盖事务锁与 CREATE INDEX CONCURRENTLY | 待回填 |
| B-7 Redis 缓存穿透/雪崩官方专页 | A.5 | 官方文档站无专页，散见博客 | 待回填 |
| B-8 Lettuce commandTimeout 官方推荐值 | A.5 | SDR 文档未展开超时参数，待补查 | 待回填 |
| B-9 MinIO 环境变量配置官方页（MINIO_ROOT_USER 等） | A.2.3 | AIStor 参考页本轮未核验 | 待回填 |
| B-10 MinIO/S3 对象 key 命名限制官方专页 | A.5.7 | 本轮未抓取 AWS object-keys 页 | 待回填 |
| B-11 Redis Streams 消费者组子页完整正文 | A.5.3 | 主文档抓取截断，命令页可逐个补查 | 待回填 |
| B-12 Milvus 2.6 upsert 官方行为确认页 | D.5.9 | v2.6.x 文档无独立页，仅 v2.3 遗留页 | 待回填 |
| B-13 Milvus v2.6.x partition key / add-fields 规范页 | D.5.7 | v2.6.x 版本页 404，仅当前版（v3.0） | 待回填 |
| B-14 Milvus ConnectConfig 参数默认值（SDK 2.6.11 精确值） | D.5.10 | 官方两参考页默认值矛盾，以 SDK 源码为准 | 待回填 |
| B-15 Milvus RRF k 值面向 RAG 的官方调参数据 | D.5.3 | 官方仅给区间 [10,100]，需项目评测集实测 | 待回填 |
| B-16 Milvus SDK 端 Session 一致性写后立查时序细节 | D.5.8 | 官方未详述 batch 写入后立即 search 时序，需集成测试 | 待回填 |
| C-1 同 thread_id 并发执行/防重复执行官方语义 | B.3.9 | SAA/LangGraph4j 官方无覆盖（Python 侧有 INVALID_CONCURRENT_GRAPH_UPDATE），项目侧自研 | 待回填 |
| C-2 DashScope HTTP 超时（connect/read timeout）配置 | B.4 | 官方集成页仅重试参数，需实测或读 starter 源码 | 待回填 |
| C-3 rerank 模型的 Java 官方集成用法 | B.4 | Java 侧官方文档缺失，按百炼原始 API 为准 | 待回填 |
| C-4 SSE 事件协议（帧格式/心跳/重连）官方推荐 | B.3.7 | 官方仅保证 Flux 流式，封装属应用层自定义 | 待回填 |
| C-5 langgraph4j 官方文档站点完整性 | B.3 | 原站点 404，以 langgraph4j.github.io/main/ 与仓库 mkdocs 为准 | 待回填 |
| C-6 "checkpoint 状态不可变"官方字面陈述 | B.3.3 | 为源码 + 上游文档实证的设计事实 | 待回填 |
| D-1 TanStack Query 官方 SSE 消费集成指南 | C.1.9 | v5 官方文档无 SSE 集成页，社区实现非官方 | 待回填 |
| D-2 Next.js 官方独立 linting 引导页 | C.2.1 | 16.x 文档站对应页 404；项目 15.5 用 next lint | 待回填 |
| D-3 Playwright 官方"单测/E2E 职责边界"权威划分 | C.2.2 | 官方仅原则性表述，边界属工程决策 | 待回填 |
| D-4 Vercel 独立数据获取最佳实践页 | C.1 | 未定位可独立引用页面，Next.js 官方指南已覆盖 | 待回填 |
| E-1 Maven 命令行侧注解处理器陈旧生成物的官方直述 | A.6 | 官方仅覆盖 IDE 增量场景，clean 纪律为工程化兜底 | 待回填 |
| E-2 SpotBugs check 失败语义的官方成文描述 | A.6 | 官方 mojo 页 Description 为空，从参数推断 | 待回填 |
| E-3 Testcontainers 跨 CI job 共享容器的官方规范 | A.6 | reuse 仅 Experimental，无正式最佳实践 | 待回填 |
| E-4 GitHub Actions 步骤级自动重试 | D.7 | 官方无内置 retry 语义，重跑为人工操作 | 待回填 |
| E-5 Testcontainers Milvus 模块版本线与 core 的耦合矩阵 | A.6 | 模块独立版本线，兼容矩阵官方未说明 | 待回填 |
| E-6 记忆/偏好提取异步化参数细则（防抖/超时/失败丢弃） | B.5.5 | 官方无条文，系项目实证（详见 `docs/superpowers/specs/` 记忆设计） | 待回填 |

**B 组待决策项**（延续既有待办，引用不重复正文）：
- 多实例部署批准确认 → TASK.md §1
- Milvus sparse 检索恢复方案三选一 → TASK.md §4

---
