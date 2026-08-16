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

## 2. 前端 E2E（Playwright）— 暂缓，待前端落地

**背景**：CI 集成表要求后端配套 Playwright E2E。当前 `frontend/` 与 `student-frontend/`
均为空目录（设计文档定 Next.js App Router + TS，**两前端完全独立，禁共享包/跨引用**），
无 UI 可测，故本阶段不集成 E2E，仅记录待办。

**前置条件**（AGENTS.md 强制）：前端 AI 对话 / 消息渲染 / 会话界面落地前**必须与用户沟通**；
管理端界面按生产标准直接落地。

**接入清单**（前端落地后执行）：
- [ ] `frontend/`（管理端）：Playwright 冒烟 + 核心流程（登录、课程管理、知识库上传、文档管理）
- [ ] `student-frontend/`（C 端）：AI 对话 SSE 流式渲染（**10 事件协议逐类断言**，协议见设计文档）、
      会话列表、反馈提交
- [ ] `.github/workflows/ci.yml` 增加 `e2e` job（playwright install → 启动服务 → 全流程断言）
- [ ] 两前端 E2E 工程各自独立，不得共享包

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
