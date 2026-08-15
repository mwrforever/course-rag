# TASK.md — 待办任务清单

> 记录「暂缓落地、需前置条件」的任务。已完成事项见 git 历史，不在此列。

---

## 1. 前端 E2E（Playwright）— 暂缓，待前端落地

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

## 2. JaCoCo 覆盖率补测路线图（~~37% → 80%~~ ✅ 已完成 2026-08-15 晚，实测 80.1%）

**达成记录**：`mvn verify` 全绿（spotless/checkstyle/spotbugs/jacoco LINE 80.1%）；全量测试 480/480。
4 批次 11 提交（af1ccc2..7952066）：批次A config+controller 0% 类 → 62.4%；批次B service 0% 五类 → 67.8%；
批次C FusionService+bot（QueryRewriter/Graph/Interceptor/Hook）→ 73.1%；批次D 低覆盖大块扩展 → 80.1%。

**遗留低覆盖类**（非门禁阻塞，后续可选补测）：DeviceKickService 44%、EtlPipeline 37%（chunkDocument/splitLargeParagraph 等需构造长文本）、
CustomSummarizationHook 35%、CourseQueryService 20%（Db 静态工具需 mockStatic）、ChatController 69%（reconnect/replayFromPg 需 SAA 流 mock）、
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
