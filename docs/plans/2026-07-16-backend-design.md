# 后端 Agent 架构设计 v2（已批准功能完整实现规格）

> **v2 更新说明**：在 v1 蒸馏 F#1~F#3 基础上，补全 F#4（用户体系 / 认证安全 / 会话层 / ETL 管道 / MinIO / CRUD 矩阵 / 检索优化），为每个模块添加实现级描述 + 历史记忆文件索引。本文件与 `2026-07-15-db-schema.md`（v5）、`2026-07-16-frontend-design.md`（v2）并列，构成「DB / 前端 / 后端」三文档完整闭环。
>
> **零发明约束**：本文件为**已批准决策的整理**，不引入任何新决策、新字段、新接口。每一条款均可在 `MEMORY.md`、日记忆（07-11 ~ 07-16）或现有设计稿中找到出处。每个模块末尾附历史记忆索引链接——实现者若发现歧义，**必须回读对应日记忆源文件**，不得自行补全。
>
> **实现前必读顺序**：`MEMORY.md`（决策索引）→ 本文件 + DB 设计稿 + 前端设计稿 → `.workbuddy/skills/spring-ai-alibaba-best-practices/`（框架实证纪律）。
>
> **⚠️ 实现纪律声明**：整个开发过程必须遵循 `superpowers` 技能的开发流程（TDD / 系统化调试 / 代码审查 / 验证后完成）。实现顺序：**先后端，再前端**。前端设计必须调用 `ui-ux-pro-max` 规划整体设计，调用 `taste skill` 对每个组件、功能、动画效果进行详细设计实现。前端 Agent 相关界面、消息渲染展示、会话界面必须和用户沟通才能落地；普通管理界面按生产环境标准直接落地。

---

## 〇、实现前强制前置条件（版本基线对齐）

| 项 | 现状（pom.xml） | 设计基线要求 | 影响 |
|----|----------------|-------------|------|
| spring-ai-alibaba | `1.0.0.4` | `v1.1.2.0` | **实现前必须升级** |
| spring-ai | `1.0.0`（仅 dashscope） | `1.1.2` + 新增 graph-core / agent-framework | **实现前必须升级** |
| DashScope SDK | 主仓库 | 独立仓库 `yuhuangbin/spring-ai-extensions` | 依赖坐标不同，需单独引入 |

> ⚠️ **关键矛盾**：项目 `pom.xml` 仍 `spring-ai-alibaba 1.0.0.4` + `spring-ai 1.0.0`，而本设计全部条款基于 `v1.1.2.0` 源码实证。任何实现动作的第一步是升级对齐，否则 `ReactAgent.asNode` / `PostgreSqlSaver` / `ModelCallLimitHook` 等 API 形态不匹配。

**DashScope `reasoning_content` 字段名实证**：SDK 将 `reasoning_content` 注入 `AssistantMessage.metadata`，key 名为 `"reasoningContent"`（非 `"reasoning_content"`）。`ChatCompletionMessage` 的 `content()` 与 `reasoningContent()` 是两个独立字段，可同 chunk 取值；`content()` 在 `rawContent == null` 时返回 `""`（判空用 `isEmpty()`）。**不存在 `<think>` 标签正则解析**——完全信任 API 层字段。

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §〇（前置说明）
> - SAA 技能：`.workbuddy/skills/spring-ai-alibaba-best-practices/SKILL.md`
> - **历史记忆**：`2026-07-14.md` §"源码实证"（DashScope SDK reasoningContent 字段实证 + OutputType 8 值枚举），`2026-07-12.md` §"决策3 + 决策4"（框架版本基线调查）

---

## 一、模型选型（终版，已封板）

| 用途 | 模型 | 维度/说明 |
|------|------|----------|
| Chat | **qwen3.7-max** | 最终确认（原 qwen-plus 升级） |
| Embedding | **text-embedding-v4** | 1024 维（原 text-embedding-v3 升级） |
| Rerank | **qwen3-rerank** | `DashScopeRerankModel`，覆写注入 instruct（非 LLM 打分） |
| 向量库 | **Milvus 2.5** | Dense + Sparse BM25 GA，chinese analyzer |

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"模型修正（终版）"（qwen-plus→qwen3.7-max / text-embedding-v3→v4）

---

## 二、F#1 Agent 架构

### 2.1 总体编排（StateGraph）

```
START → queryRewriteNode → ReactAgent.asNode(true, false, "agent_output") → END
```

**实现描述**：
- 文件位置：`com.commerce.rag.bot.graph.LeadAgentGraph`（Builder 类，`@Configuration`）
- `queryRewriteNode` 自定义图节点（`AsyncNodeActionWithConfig`），调用 QueryRewriter 生成 3 条覆盖性查询 → 写入 `State.rewrittenQueries`
- `ReactAgent.asNode(includeContents=true, returnReasoningContents=false)`，outputKey 在 Builder 独立设置（非第三参）
- **CompiledGraph 编译一次复用**：不同请求通过 `RunnableConfig.threadId` 隔离状态；`systemPrompt` 通过 builder 设置一次
- **循环上限**：框架**无 `maxIterations` 常量**（仅 `_MODEL_ITERATION_` 日志计数）。用内置 `ModelCallLimitHook(runLimit=15)`（见 §4.7）
- **Config 内容**：`threadId` + `userId` 放 `RunnableConfig`，**不放 State**
- **thread_id 映射**：`session_id`（BIGINT 雪花）直接作为 SAA checkpoint `thread_id`（`toString()`），不加独立 thread_id 字段

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §三（chat_session / chat_message / chat_run）
> - 前端设计：`2026-07-16-frontend-design.md` §1.6（AI 对话页 CSR + SSE10 事件）
> - **历史记忆**：`2026-07-12.md` §"决策5"（图编排接线 + 上下文管理三层分离），`2026-07-14.md` §"SAA 源码实证深化"（ReactAgent.asNode 仅 2 参实证 + 无 maxIterations 常量实证）

### 2.2 State 定义（OverAllState: Map\<String,Object\>）

**实现描述**：
- 文件位置：`com.commerce.rag.bot.graph.OverAllState`（接口继承 `org.springframework.ai.graph.StateGraph.State`）
- `KeyStrategyFactory` 逐 key 指定策略；内置策略**仅 2 种**：`ReplaceStrategy` + `AppendStrategy`（无 MergeStrategy、无 add_messages）

| State Key | 策略 | 类型 | 说明 |
|-----------|------|------|------|
| `messages` | `AppendStrategy` | `List<Message>` | 对话消息（SAA 框架要求 key 名必须 `"messages"`） |
| `rewrittenQueries` | `ReplaceStrategy` | `List<String>` | 查询重写结果（queryRewriteNode 写入） |
| `agent_output` | `ReplaceStrategy` | `String` | ReactAgent 最终输出键 |
| `safety_warnings` | `AppendStrategy` | `List<String>` | 安全告警队列（F#3 WarningHook 写入，见 §4.3） |

> 📋 **详细设计索引**：历史记忆 `2026-07-12.md` §"决策3 + 决策4"（State 字段确认 + 策略确认）

### 2.3 意图与工具集

**实现描述**：
- **2 种意图类型**：`TECHNICAL_QA` + `COURSE_INFO`（枚举类 `com.commerce.rag.bot.IntentType`，已移除 HeuristicMode）
- **统一检索工具**：`searchKnowledge(List<TypedQuery>)` —— 方法签名位于 `com.commerce.rag.bot.tool.SearchKnowledgeTool`
  - `TypedQuery` record：`collectionType`（IntentType）+ `queryText`（String）+ `courseId`（String, nullable）
  - **单 Collection `knowledge_chunks` + 标量字段过滤**：Milvus 表达式 `collection_type == "TECHNICAL_QA"` 或 `collection_type == "COURSE_INFO"`
  - 内部 `CompletableFuture.allOf` 并行检索 + 容错（catch 返回空列表，不中断其他）
- **API 工具组优先调用**（`com.commerce.rag.bot.tool.CourseApiTool`）：
  - `listCourses(keyword, page)` → DTO `CourseListResult`
  - `queryCourseDetail(courseId)` → DTO `CourseDetailResult`
  - `queryEnrollment(courseId)` → DTO `EnrollmentResult`
  - 工具能直接返回结果的优先调用，不满足时再查知识库
- **QueryRewriter**：所有模式必须使用，默认 3 条，配置管理
  - 文件位置：`com.commerce.rag.bot.rewrite.QueryRewriter`（调用 LLM + query-rewrite.yml 模板）
- **融合**：跨查询 RRF 二次融合 + rerank 精排
  - 文件位置：`com.commerce.rag.retrieval.FusionService`（RRF 融合）+ `com.commerce.rag.retrieval.RerankService`（qwen3-rerank）

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §四（Milvus Collection schema）
> - **历史记忆**：`2026-07-12.md` §"已确认决策汇总"（统一工具 + API 优先 + 并行检索 + QueryRewriter 3 条），`2026-07-13.md` §"审计发现"（澄清方向调整 → 模糊意图通过查询重写内部处理）

### 2.4 工具返回值 DTO（record，包 `com.commerce.rag.bot.tool.dto`）

> 用 record DTO 而非 String，只含核心有意义字段。

| DTO | 核心字段 |
|-----|---------|
| `CourseListResult` | 分页（page/total/pageSize）+ `List<CourseSummary>`（courseId/title/category/price/discount/difficulty/status/nextStartDate/tags） |
| `CourseDetailResult` | 含 `ScheduleInfo`（nextStartDate/duration/totalLessons/schedule）+ `InstructorInfo`（name/title/bio）+ introContent/syllabusContent/instructorContent/faqContent/enrollmentUrl/tags（四 Tab 与 course_content.content_type 枚举一一对应） |
| `EnrollmentResult` | price/discountPrice/enrollmentUrl/nextSchedule（精简，不含完整课程信息） |
| `KnowledgeSearchResult` | `List<KnowledgeChunk>`（chunkId/content/source/headingPath/score/collectionType）— chunkId 保留用于 B 端追溯 |

> 📋 **详细设计索引**：历史记忆 `2026-07-12.md` §"决策2 补充"（DTO 字段详细定义）

### 2.5 提示词体系（YAML 管理）

**实现描述**：
- 存放位置：`src/main/resources/prompts/`
- 讨论与确认顺序：`query-rewrite.yml`(✅) → `system-base.yml`(✅) → `agent-instruction.yml`(待确认) → `dynamic-context.yml`(待确认) → `rerank-instruct.yml`(待确认)
- **双通道设计模式**（来源 skill `system-prompt-template`）：
  - **静态通道**：`system-base.yml` 渲染，字节级稳定，prefix cache 友好。通过 `builder.systemPrompt()` 设置。
  - **动态通道**：`rewrittenQueries` 等每 turn 变化内容，通过 `<system-reminder>` 注入——自定义 `ReminderHook`（BEFORE_MODEL，读 `State.rewrittenQueries`）
- **instruction 注入**：直接 prepend 为普通 `UserMessage`（v1.1.2.0 无 `InstructionAgentHook`，`AgentInstructionMessage` 是 `UserMessage` 子类型，支持模板渲染）

> 📋 **详细设计索引**：历史记忆 `2026-07-12.md` §"提示词讨论顺序" + §"决策3 + 决策4"（双通道模式 + system-reminder 注入）

### 2.6 上下文三层分离（核心架构纪律）

| 层 | 职责方 | 职责 | 落盘？ |
|----|--------|------|--------|
| **Hook 层** | 我们管 | 每次模型调用前主动装载系统提示词 + 动态上下文 + 上下文压缩 | ✅ 持久（改 State） |
| **Interceptor 层** | 我们管 | 修改单次模型调用请求，注入/合并，不改变 State/checkpoint | ❌ 瞬时 |
| **Checkpoint 层** | 框架管 | `PostgreSqlSaver` 管图执行状态快照，用于中断恢复 | ✅ 持久 |
| **chat_message 层** | 我们管 | 纯前端渲染展示，不参与上下文重建 | ✅ 持久（独立渲染表） |

> **关键纪律**：不依赖 checkpoint 管上下文装载，由 Hook 统一管理。Interceptor（瞬时，改请求）vs Hook（持久，改 State/checkpoint）职责严格分离。

> 📋 **详细设计索引**：历史记忆 `2026-07-12.md` §"决策5"（上下文管理三层分离纠正），`2026-07-13.md` §"动态上下文拼接管线设计确认"

### 2.7 Hook / Interceptor 组件清单（自定义方案）

**实现描述**：全部位于 `com.commerce.rag.bot.hook` 包。

| # | 组件 | 类型 | 职责 | 执行时机 |
|---|------|------|------|---------|
| 1 | `CustomSummarizationHook` | 自定义 `MessagesModelHook` | 增量压缩，排除 SM(summary旧) + SM(reminder) | BEFORE_MODEL |
| 2 | `CoalescingInterceptor` | 自定义 `ModelInterceptor` | 合并多条 SM 为一条（**SAA 不提供此能力**） | 每次模型调用前 |
| 3 | `ReminderHook` | 自定义 `MessagesModelHook` | 注入/替换 rewrittenQueries 的 system-reminder | BEFORE_MODEL |
| 4 | `builder.systemPrompt()` | 标准路径 | 设置 `SystemMessage(base)` 作为 `ModelRequest.systemMessage` | 图编译时 |

- **替代关系**：`CustomSummarizationHook` 替代内置 `SummarizationHook`
- **注册顺序（重要）**：AFTER_MODEL 逆序执行——注册越晚越先跑
- **SM(base) 不在 messages 中**：通过 `AgentLlmNode.apply()` 设置到 `ModelRequest.systemMessage` 独立字段，与 `state["messages"]` 完全分离——`CustomSummarizationHook` 无需排除它

> 📋 **详细设计索引**：
> - SAA 技能：`.workbuddy/skills/spring-ai-alibaba-best-practices/approved-decisions.md` §"自定义 Hook 决策"
> - **历史记忆**：`2026-07-13.md` §"组件最终清单"（自定义 Hook 方案 4 组件确认），`2026-07-13.md` §"SAA 源码实证"（SM(base) 不在 messages 实证 + SAA 无 CoalescingInterceptor 实证）

### 2.8 自定义 SummarizationHook 设计

**实现描述**：
- 文件位置：`com.commerce.rag.bot.hook.CustomSummarizationHook`
- 继承 `MessagesModelHook`，实现 `BeforeModelAction`
- **摘要存放位置**：仍在 `messages` 作为 `SystemMessage`（带 `summaryPrefix = "## 对话摘要:"` 标记），不放独立 State key。这是设计选择（手动拼装可控、LLM 直接可见），非技术约束。
- **排除对象**：SM(summary旧) + SM(reminder)。SM(base) 无需排除——因为它压根不在 `state["messages"]` 中（见 §2.9 实证）。
- **增量摘要（融合更新，非追加）**：
  1. 识别旧摘要（通过 `summaryPrefix` 标记）→ 提取文本为 `previous_summary`
  2. 从 `toSummarize` 移除旧摘要 SM
  3. `newMessages` 拼装为 `[SM(base), SM(summary新), SM(reminder)?, firstUM?, ...recent]`
  4. 提示词明确禁止拼接行为（"不是在旧摘要末尾追加新内容""禁止分成两段分别陈述"）
- **两套模板**：有旧摘要版（融合更新）+ 无旧摘要版（首次压缩）
- **约束**：第三人称客观陈述、800 字上限、只输出正文。融合规则 5 条（必须融入/禁止分两段/禁止来源标记/必须保留课程数据与诊断结论/可丢弃寒暄与技术细节与过期信息）。

> 📋 **详细设计索引**：历史记忆 `2026-07-13.md` §"自定义 SummarizationHook 方案确认" + §"摘要提示词模板确认"（融合规则 5 条 + 800 字上限 + summaryPrefix）

### 2.9 SAA 框架实证关键结论（v1.1.2.0 源码实锤，实现纪律）

> 以下逐条经 GitHub `alibaba/spring-ai-alibaba` 的 `v1.1.2.0` tag 源码实锤，任何实现须严格遵守。

1. **SM(base) 不在 messages 列表中**：`AgentLlmNode.apply()` 中 `requestBuilder.systemMessage(new SystemMessage(this.systemPrompt))`——systemPrompt 存为 `ModelRequest.systemMessage` 独立字段，与 messages 列表完全分离。不落 checkpoint。
2. **内置 SummarizationHook 不过滤 SystemMessage**：`toSummarize` 仅排除 firstUserMessage，把 SM 也压进摘要文本。→ 自定义版必须显式排除 SM(summary旧) + SM(reminder)。
3. **SAA 不自动合并多条 SystemMessage（无 CoalescingInterceptor）**：`appendSystemPromptIfNeeded` 仅把 `modelRequest.getSystemMessage()` 前置到 index 0，其余 SM 原样保留；>2 SM 仅打警告。**LangGraph 有 `SystemMessageCoalescingMiddleware`，SAA 没有** → `CoalescingInterceptor` 是必要主动设计。
4. **SkillsInterceptor 修改 `ModelRequest.systemMessage`（瞬时）**：每轮改写，不落 checkpoint。
5. **内置版无增量摘要能力**：`DEFAULT_SUMMARY_PROMPT` 仅有 `%s` 占位符，无 `{previous_summary}` 变量。每次全量重做。
6. **内置版 SUMMARY_PREFIX = `"## Previous conversation summary:"`**；我们自定义前缀 `"## 对话摘要:"`。
7. **Hook 执行机制**：`MessagesModelHook.BeforeModelAction` 是 `AsyncNodeActionWithConfig` 图节点，在 model 节点前执行。`UpdatePolicy.REPLACE` 使用 `ReplaceAllWith.of(command.getMessages())`（reducer 机制），`UpdatePolicy.APPEND` 由 AppendStrategy 处理。
8. **`ReactAgent.asNode` 仅 2 参** `(includeContents, returnReasoningContents)`；`outputKey` 是 Builder 独立字段。
9. **框架无 `maxIterations` 常量**；循环上限用内置 `ModelCallLimitHook(runLimit=15)`。

> 📋 **详细设计索引**：
> - SAA 技能：`.workbuddy/skills/spring-ai-alibaba-best-practices/references/framework-source-analysis.md`（12 核心类逐类 `file:line` 实证）
> - **历史记忆**：`2026-07-13.md` §"SAA 1.1.2.0 源码实证验证"（6 源码文件实锤），`2026-07-14.md` §"SAA 源码实证深化"（asNode 2 参修正 + 无 maxIterations 常量实证）

### 2.10 Saver 与 Redis 角色

**实现描述**：
- **Saver**：`PostgreSqlSaver`（PG 同库），配在 `StateGraph` 的 `CompileConfig`，**不在 `ReactAgent.builder().saver()`**
  - 配置位置：`com.commerce.rag.bot.graph.LeadAgentGraph` 的 `@Bean` 方法中 `CompileConfig.builder().saver(postgreSqlSaver).build()`
- **Redis 角色**：仅用于 SSE Stream 事件推送（不存上下文、不存 checkpoint）

> 📋 **详细设计索引**：历史记忆 `2026-07-12.md` §"决策3 + 决策4"（Saver 配置位置 + Redis 角色确认）

---

## 三、F#2 流式管线

### 3.1 端到端管线

```
Controller → Redis chat:request → Worker consumeLoop(调度)
  → runPool(执行) → SAA compiledGraph.stream
  → SseEventTransformer(OutputType → 自定义事件)
  → MemoryBridge(ring buffer) → SSE Bridge → 前端 EventSource
终态：PG 批量写入 + Redis 缓存最终结果
```

**实现描述**：
- **Controller 层**：`com.commerce.rag.controller.ChatController`
  - `POST /api/v1/student/chat` — 发起对话，`X-Staff-Id` 头鉴权，返回 `SseEmitter`
  - `POST /api/v1/student/chat/{runId}/cancel` — 取消正在执行的 run
  - `GET /api/v1/student/chat/{runId}/reconnect?lastEventId={id}` — 断线重连
- **Worker 层**：`com.commerce.rag.worker.ChatRequestWorker`
  - Redis Streams `chat:request` + `chat-workers` 消费组
  - 主消费循环 `consumeLoop()` 不阻塞：`runPool.submit(processRequest)`
  - ACK 在 `finally` 块确保失败消息回 pending
- **Transformer 层**：`com.commerce.rag.stream.SseEventTransformer`
  - 输入：SAA `OutputType` 枚举（8 值）
  - 输出：10 事件 SSE（见 §3.2）
- **MemoryBridge 层**：`com.commerce.rag.stream.MemoryStreamBridge`
  - per-run ring buffer（256 条）+ 订阅者唤醒 + O(1) 回放

> 📋 **详细设计索引**：
> - 前端设计：`2026-07-16-frontend-design.md` §1.6.4（SSE 事件渲染映射表）
> - **历史记忆**：`2026-07-14.md` §"Feature #2 流式管线设计收束"（10 项决策终态 + 端到端管线），`2026-07-13.md` §"SSE schema"（10 事件确认）

### 3.2 SSE 10 事件协议

```
metadata / thinking / thinking_end / delta / tool_call / tool_result
/ sources / error / end / :heartbeat
```

**实现描述**：
- 枚举类：`com.commerce.rag.stream.SseEventType`（10 值枚举，从旧枚举迁移）
- **传输**：纯内存 ring buffer（256 条，`MemoryStreamBridge` 模式）→ 实时推前端；中间 chunk 不穿 Redis
- **Redis 角色**：仅缓存完整最终结果 JSON（TTL 300s），不做中间 chunk 冷备
- **断线重连**：前端带 `runId` + `lastEventId` → 内存 ring buffer O(1) 回放。实现：`MemoryStreamBridge.replayFrom(lastEventId)`
- **工具关联**：`tool_call` / `tool_result` 用 `tool_call_id` 关联，配对渲染成卡片
- **thinking 持久化**：存 PG `chat_message` 表（`msg_type='thinking'`），用于历史回放和审计

> 📋 **详细设计索引**：
> - 前端设计：`2026-07-16-frontend-design.md` §1.6.4（SSE 事件 → 前端渲染映射表）+ §1.6.5（断线重连流程）
> - **历史记忆**：`2026-07-13.md` §"SSE schema"（10 事件协议确认），`2026-07-14.md` §"StreamingOutput 行为"（text=delta / thinking=reasoningContent 提取）

### 3.3 并发模型（Worker）

**实现描述**：
- **自研队列**：`ChatRequestWorker` + Redis Streams `chat:request` + `chat-workers` 消费组
  - 消费组 consumer：每个实例一个唯一 consumer name
  - Pending 重投：`XPENDING` 检查 → `XCLAIM` 回收超时消息
  - 租约回收：`stale-lease-minutes=30`
- **主消费循环不阻塞**：`runPool.submit(processRequest)`；ACK 在 `finally` 块确保失败消息回 pending
- **线程池**：`runPool`（`core=CPU*2, max=CPU*2, queue=100`，可配），与 ETL `etl.executor` 独立
- **明确否决**：不采用 `Redisson RExecutorService`（项目已自研同模式机制，且 Redisson 需引入第二 Redis 客户端、worker 硬崩溃 failover gap 等边界问题）

> 📋 **详细设计索引**：历史记忆 `2026-07-14.md` §"Redisson RExecutorService 技术评估"（否决 Redisson 的 4 条理由），`2026-07-14.md` §"Feature #2 流式管线"（Worker 并发模型决策）

### 3.4 取消 / 回滚（pre-run 快照）

**实现描述**：
- **取消检测**：`ChatRequestWorker.processRequest()` 中 `doOnNext` 检查取消标记（`ConcurrentHashMap<runId, AtomicBoolean>`）→ `throw CancelledException` → `doOnError` 发 `cancelled` SSE 事件 + 部分数据持久化
- **pre-run 快照**：`saver.getTuple(config)` → deepCopy（`channelValues` + `channelVersions` + `pendingWrites`）→ 存 worker 线程局部变量 `ThreadLocal<RunSnapshot>`（**不进 OverAllState**）
- **快照失败降级**：`snapshotCaptureFailed = true` → 记 warn，不卡 run
- **回滚**：写新 checkpoint（新 id/ts），恢复 `pendingWrites`，**不删旧 checkpoint**

> 📋 **详细设计索引**：
> - **历史记忆**：`2026-07-15.md` §"Pre-run 快照 + 取消回滚"（完整快照/回滚流程确认），`2026-07-14.md` §"Feature #2 流式管线"（取消决策第 6 条）

### 3.5 PG 渲染表 vs SAA checkpoint

**实现描述**：
- **独立渲染表 `chat_message`**（DDL 见 `db-schema.md` §三）：
  - 字段：`run_id` + `seq` + `msg_type` + `role` + `content`(JSONB) + `created_at`
  - 与 SAA checkpoint 表（checkpoints / checkpoint_writes / checkpoint_blobs）完全分离
- **持久化时机**：run 结束时一次性批量 INSERT（JdbcTemplate `batchUpdate`）；不做增量持久化（SAA checkpoint 管中间恢复，渲染表只做终态记录）
- **`msg_type` 内容存储规则（方案 A，已确认）**：

| msg_type | role | content 存储格式 |
|--------------|------|------------------|
| null（普通消息） | USER | 用户原文（纯文本） |
| null（普通消息） | ASSISTANT | AI 回答正文（纯文本） |
| thinking | ASSISTANT | reasoning 内容（纯文本） |
| TOOL_CALL | ASSISTANT | `{"tool":"searchKnowledge","args":{...}}` |
| TOOL_RESULT | ASSISTANT | `{"tool":"searchKnowledge","result":"..."}` |

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §三（chat_message DDL + 索引 + 并发守卫 partial unique index）
> - **历史记忆**：`2026-07-14.md` §"Feature #2 流式管线"（渲染表决策第 5/7/11 条 + 方案 A 存储规则），`2026-07-15.md` §"Feature #2 补充"（增量持久化不做 + thinking 存 PG）

### 3.6 Redis 降级

**实现描述**：
- ring buffer 失败 → fallback `ConcurrentLinkedQueue`（内存降级）
- 结果缓存失败 → fallback 查 PG `chat_message` 表
- 均不终止 run（`try-catch` 包裹，记 warn 日志）

### 3.7 Text / thinking 行为（StreamingOutput 源码实证）

**实现描述**（`SseEventTransformer` 核心逻辑）：
- `NodeExecutor`：`isStreaming=true`（中间 chunk）发射**原始 ChatResponse delta**；`isStreaming=false`（流结束）发射**累积后的完整 ChatResponse**。Gateway 层直接用 text 当 delta，不需要 diff。
- **Text**（→ SSE `delta` 事件）：流式中间 = delta，终态 = 累积
- **thinking_delta**（→ SSE `thinking` 事件）：从 `metadata.get("reasoningContent")` 提取
- `OutputType` 8 值枚举按 nodeId 前缀自动判定：
  - `AGENT_MODEL_STREAMING` → delta / thinking 事件
  - `AGENT_MODEL_FINISHED` → 累积 text / thinking_end 事件
  - `AGENT_TOOL_*` → tool_call / tool_result 事件
  - `GRAPH_NODE_*` → 可忽略或映射到 metadata 事件

### 3.8 F#2 待实现文件清单

| 文件/类（包路径） | 职责 |
|---------|------|
| `com.commerce.rag.stream.SseEventTransformer` | `OutputType` → `SseEvent` 状态机（含 thinking/text 转换检测） |
| `com.commerce.rag.stream.MemoryStreamBridge` | per-run ring buffer + 订阅者唤醒 + O(1) 回放 |
| `com.commerce.rag.worker.ChatRequestWorker`（改造） | 线程池 + SAA 图执行 + Transformer + MemoryBridge |
| `com.commerce.rag.controller.ChatController`（改造） | 感知断线重连 + 取消端点 + SSE 返回 |
| `com.commerce.rag.stream.SseEventType` | 10 事件枚举迁移（从旧枚举） |
| `chat_message` DDL | 渲染表建表（见 db-schema.md §三） |
| `application.yml` worker 配置块 | `run-pool.*` + `stale-lease-minutes` + `redis.ttl` |

---

## 四、F#3 死循环 / Token / 置信度

### 4.1 三组配置（@ConfigurationProperties，沿用 `retrieval.*` 模式）

**实现描述**：
- 三组配置类位于 `com.commerce.rag.config` 包，通过 `application.yml` 覆盖默认值

**loop-detection**（`LoopDetectionProperties`）：
```yaml
rag.loop-detection:
  hash:
    window-size: 20    # 滑动窗口大小
    warn: 3            # 触发警告次数
    hard-stop: 5       # 强制终止次数
  per-tool:
    default:
      warn: 15
      hard-stop: 25
    overrides:
      searchKnowledge: { warn: 25, hard-stop: 40 }
      listCourses:     { warn: 5,  hard-stop: 10 }
```
- Layer 1（hash）：SHA-256 hash 滑动窗口，检测重复内容
- Layer 2（per-tool）：按工具分别计数，`ToolOverride` 未配字段 fallback 到 default

**token-budget**（`TokenBudgetProperties`）：
```yaml
rag.token-budget:
  max-tokens-per-run: 200000
  warn-ratio: 0.8
  hard-stop-ratio: 1.0
```
- 基于 qwen3.7-max 128K 上下文，200K 给约 2.5× 余量

**confidence**（`ConfidenceProperties`）：
```yaml
rag.confidence:
  search-high-threshold: 0.8
  search-medium-threshold: 0.5
  multi-source-min: 2
```
- 判定规则：API 工具 → HIGH；检索分 ≥0.8 → HIGH；≥0.5 → MEDIUM；多源 ≥2 → MEDIUM 兜底；否则 LOW

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"Feature #3 配置管理"（三组配置完整参数 + 默认值 + 频率阈值修正 15/25）

### 4.2 软停行为（不抛异常）

**实现描述**（位于对应 Hook 的 `AFTER_MODEL` 回调）：
1. 三处**同清** `tool_calls`：结构化字段 `message.getToolCalls()` + `additional_kwargs["tool_calls"]` + `additional_kwargs["function_call"]`
2. 设置 `finish_reason = "stop"`
3. 追加 `"[FORCED STOP]"` 到消息末尾
4. **不抛异常**（让 run 自然结束，走正常持久化路径）

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"Feature #3 边界决策"（软停行为确认 + 三处同清 + 不抛异常）

### 4.3 告警延迟注入

**实现描述**：
- `AFTER_MODEL` Hook 写 `State.safety_warnings` 队列（AppendStrategy）
- 下一轮 `BEFORE_MODEL` Hook 排空队列，注入 `HumanMessage(name="loop_warning", text="⚠️ [警告内容]")`
- 延迟一轮的原因：避免在同一个模型调用中插入额外消息打破流式输出

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"Feature #3 边界决策"（告警延迟注入确认）

### 4.4 Hook 注入方式（方案 B，已确认）

**实现描述**：
- 独立 `WarningHook`（BEFORE_MODEL），与 `ReminderHook` 职责分离
- 文件位置：`com.commerce.rag.bot.hook.WarningHook`
- 不合并到 ReminderHook（单一职责原则：ReminderHook 管动态上下文注入，WarningHook 管安全告警）

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"Feature #3 边界决策"（方案 B 独立 Hook 确认）

### 4.5 多层告警优先级

```
TokenBudget HardStop > Loop Hash HardStop > Loop ToolFreq HardStop
（同序 Warn）
```

**实现描述**：三个 Hook 按优先级注册，遇到 HardStop 条件时，高优先级 Hook 先触发，设置标记位阻止低优先级 Hook 重复处理。

### 4.6 Hook 注册顺序

**实现描述**：
- `AFTER_MODEL` **逆序执行**：注册越晚越先跑
- 推荐注册顺序（`LeadAgentGraph` Builder 中）：
  ```java
  .registerHook(warningHook)           // 最后注册 = 最先执行 AFTER_MODEL
  .registerHook(reminderHook)
  .registerHook(customSummarizationHook) // 最先注册 = 最后执行 AFTER_MODEL
  ```

### 4.7 ModelCallLimitHook

**实现描述**：
- SAA 内置 Hook，一行 YAML 配置：
  ```yaml
  rag.agent.run-limit: 15
  ```
- 在 `LeadAgentGraph` Builder 中注册：`new ModelCallLimitHook(15)`

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"ModelCallLimitHook"（runLimit=15 确认），`2026-07-14.md` §"SAA 源码实证深化"（框架无 maxIterations 常量 + 用 ModelCallLimitHook 替代）

---

## 五、F#4 数据与存储层

### 5.1 用户体系（三层用户）

**实现描述**：
- 表：`sys_user`（DDL 见 `db-schema.md` §二）
- 三层角色枚举（`com.commerce.rag.enums.UserRole`）：

| 角色 | 说明 | 权限范围 |
|------|------|---------|
| `SUPER_ADMIN` | 超级管理员（1 个，不可删/禁） | 全权限 |
| `TEACHER` | 教师 | 管理自己创建的资源 |
| `STUDENT` | 学生 | 仅查询自己有权的资源 |

- **教师删除策略**：软删 `sys_user(TEACHER)` → `course_teacher` 级联软删，课程保留由超管重分配
- **instructor_name**：纯展示文本，不关联 `sys_user`

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §二（sys_user DDL + 12 个索引 + 级联删除策略）
> - **历史记忆**：`2026-07-15.md` §"全量 DB 表设计 v1"（三层角色确认 + 教师删除策略确认）

### 5.2 认证安全（双 Token + 设备互踢 Redis Lua）

**实现描述**：

**Token 体系**（与前端协同，前端视角见 `frontend-design.md` §3.4）：
- **AT（Access Token）**：15min 有效期，无状态 JWT，payload 含 `userId + role + jti`
- **RT（Refresh Token）**：7d 有效期，一次性旋转（复用即全量作废）
- 传输：httpOnly cookie，前端静默刷新

**DB 表**（DDL 见 `db-schema.md` §八）：
- `sys_login_record`：会话注册表。jti_at + jti_rt + user_id + device_type + ip_address + expires_at + status
- `sys_token_blacklist`：黑名单。jti + token_type + user_id + blacklisted_by + reason + expires_at

**Redis 数据结构**（执法层）：
```
auth:cur:{uid}:{device}  → jti（活跃设备指针，String）
auth:bl:{jti}            → "1"（黑名单，String，TTL=Token 剩余有效期）
auth:rt:used:{jti_rt}    → "1"（RT 一次性标记，String，TTL=7d）
```

**设备互踢 Lua 脚本**（`kick_and_login.lua`）：
```
-- 输入: KEYS[1]=auth:cur:{uid}:{device}, KEYS[2]=auth:bl:{old_jti}, ARGV[1]=new_jti, ARGV[2]=old_jti, ARGV[3]=old_jti_rt, ARGV[4]=ttl
-- 1. GET 旧设备 jti
-- 2. SET 新设备指针（覆盖）
-- 3. SETEX 旧 jti 入黑名单
-- 4. 返回 {ok, old_jti} 或 {error, msg}
```

**管理员禁用用户**（`disable_user.lua`）：
```
-- 批量 SETEX 该用户所有活跃 session jti 入黑名单
-- 防重入：NX + EX 300s
```

**降级策略**：
- Redis 不可用 → PG `FOR UPDATE` 行锁事务保证原子性（查 ACTIVE 记录 → UPDATE REVOKED → INSERT 黑名单）
- Redis 恢复后 → PG 异步审计回填 Redis

**后端实现文件**：
- `com.commerce.rag.auth.TokenService` — JWT 签发/验证/刷新
- `com.commerce.rag.auth.DeviceKickService` — Redis Lua 脚本执行 + PG 降级
- `com.commerce.rag.auth.AuthInterceptor` — Spring MVC Interceptor，校验 AT + 注入 userId 到 RequestContext

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §八（sys_login_record + sys_token_blacklist DDL + 索引）+ §认证安全章节（Redis Lua 详细流程 + 降级策略）
> - 前端设计：`2026-07-16-frontend-design.md` §3.4（双 Token 完整前端流程 + 设备互踢被踢出错误处理）
> - **历史记忆**：`2026-07-16.md` §"DB Schema v5 前端驱动重构"（新增两表原因），`2026-07-16.md` §"认证层重构：设备互踢改为 Redis 驱动"（Redis Lua 执法层 + PG 审计层双层架构）

### 5.3 会话层（精简 + 并发守卫）

**实现描述**：
- 表：`chat_session`（DDL 见 `db-schema.md` §三）
- **已删除 `active_run_id` 列 + 对应索引**：冗余字段，`chat_run` 表的 partial unique index 是并发守卫唯一真相源
- **session_id 即 thread_id**：`session_id`（BIGINT 雪花）直接作为 SAA checkpoint `thread_id`（`toString()`），不加独立字段
- **并发守卫**：`chat_run` 表 partial unique index `uniq_active_run_per_session`
  ```sql
  CREATE UNIQUE INDEX uniq_active_run_per_session ON chat_run(session_id)
  WHERE status IN ('QUEUED', 'ACTIVE') AND deleted = 0;
  ```
  创建时即 DB 级互斥（覆盖 QUEUED + ACTIVE，扩大的覆盖范围）

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §三（chat_session + chat_run DDL + 并发守卫索引）
> - **历史记忆**：`2026-07-16.md` §"会话层精简"（删除 active_run_id + 扩大并发守卫覆盖 + session_id 即 thread_id）

### 5.4 ETL 异步管道（旁路修正式）

**实现描述**：
- 文件位置：`com.commerce.rag.etl.EtlPipeline`（Spring `@Service`）
- **旁路修正模式**：Pipeline 一次性跑完到 INDEXED，知识库立即可检索，不阻断。B 端后续批量修正 chunk 元数据。

**异步状态机**（`document.parse_status`）：
```
PENDING → PARSING → PARSED → CHUNKING → CHUNKED → EMBEDDING → INDEXED
                                                                     ↓
                                                                  FAILED
```

**document_chunk 扩展字段**（DDL 见 `db-schema.md` §四）：
- `correction_status VARCHAR(20) DEFAULT 'PENDING'`（PENDING / CORRECTED）
- `dense_vector BYTEA`（dense 向量 PG 冗余存储，float[1024]→byte[]，4KB/chunk）
  - 用途：Milvus upsert 免回查 + Milvus 重建能力

**重新向量化策略**：
| 修正操作 | 需要重新向量化？ | 说明 |
|---------|:---:|------|
| 改 `collection_type` | ❌ | 标量字段，与向量无关 |
| 改 `course_id` | ❌ | 标量字段，与向量无关 |
| 改 `content`（chunk 文本） | ✅ | dense_vector 从 content 生成，必须重调 embedding API |

**Milvus upsert**：delete-then-insert，即使只改标量也需提供完整记录（含 dense_vector）。PG 冗余 dense_vector 避免回查 Milvus。

**新增 CRUD 接口**（B 端管理）：
- `POST /api/v1/admin/chunks/batch-update` — 批量改 collection_type + course_id（不重新向量化）
- `PATCH /api/v1/admin/chunks/batch-corrected` — 批量标记 correction_status=CORRECTED
- `GET /api/v1/admin/chunks/pending` — 按 kb_id/doc_id 筛选 PENDING chunks

**线程池**（`application.yml`）：
```yaml
etl.executor:
  core-size: 2
  max-size: 4
  queue-capacity: 20
  thread-name-prefix: etl-
```
与对话 Worker 线程池完全分离。

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §四（document + document_chunk DDL + 索引）+ §ETL 异步化章节（完整状态机 + chunk 修正机制 + Milvus upsert 策略）
> - **历史记忆**：`2026-07-15.md` §"ETL 异步链路设计"（旁路修正模式 + 异步状态机 + 重新向量化策略 + CRUD 接口 D7-D9 + 线程池 + 索引 idx_document_chunk_correction）

### 5.5 MinIO 文件存储

**实现描述**：
- 独立 MinIO 实例 `minio-storage`（端口 9002/9003），与 Milvus 内部 MinIO 解耦
- 镜像：`pgsty/minio:latest`，bucket=`rag-documents`
- **文件路径**：`{kb_id}/{doc_id}.{ext}`，`document.source_path` 存 MinIO object key
- **重新 ETL**：从 MinIO 拉取原文件，不要求重新上传
- `application.yml` 新增配置块：
  ```yaml
  minio:
    endpoint: http://localhost:9002
    access-key: minioadmin
    secret-key: minioadmin
    bucket: rag-documents
  ```
- 依赖：pom.xml 添加 `io.minio:minio` SDK（实现阶段统一处理）

> 📋 **详细设计索引**：历史记忆 `2026-07-15.md` §"文件存储 — MinIO 集成"（独立实例 + bucket + 文件路径 + application.yml 配置块）

### 5.6 知识检索优化

**实现描述**（5 项已确认优化，在 F#1 工具集实现中落地）：

1. **分片策略升级**：递归分片 + 父子关联（`chunk-size=768, overlap=128`）
   - `document_chunk` 新增 5 字段：`parent_chunk_id, prev_chunk_id, next_chunk_id, char_offset_start, char_offset_end`
2. **Milvus 索引升级**：IVF_FLAT → HNSW（M=16, efConstruction=200, ef=64）
3. **Milvus schema 加字段**：`collection_type`（VarChar）+ `course_id`（VarChar）用于过滤
4. **展示策略**：上下文展开（父子 chunk）+ 相邻合并（兄弟 chunk）+ rerank score 降序

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §四（document_chunk 扩展字段 + Milvus Collection schema）
> - **历史记忆**：`2026-07-15.md` §"检索链路 5 项优化"（递归分片 + HNSW + 展示策略确认）

### 5.7 MyBatis-Plus 组合（雪花 + 软删除 + 拦截器）

**实现描述**：
- **主键策略**：所有业务表统一 BIGINT 雪花算法（`IdType.ASSIGN_ID`）
- **软删除**：所有表 `deleted BIGINT DEFAULT 0`，删除时写时间戳。MyBatis-Plus `@TableLogic`
- **拦截器**：`PaginationInnerInterceptor`（`maxLimit=2000`，防全表查询）
- **组合方式**：单表 CRUD 用 Lambda 链式 API（`LambdaQueryWrapper`），多表/复杂操作用 XML 映射文件
  - 详见技能 `.workbuddy/skills/mybatis-plus-best-practise/SKILL.md`

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §〇（主键策略 + 软删除约定）
> - **历史记忆**：`2026-07-12.md` §"已确认决策汇总"（MyBatis + MyBatis Plus 组合 + 雪花算法主键 + 拦截器 max 2000），`2026-07-15.md` §"全量 DB 表设计 v1"（软删除 + URL 主键 UUID/BIGINT 混用 → 统一 BIGINT 雪花）

---

## 六、CRUD 管理功能矩阵

> 完整端点清单 + 权限矩阵见 `db-schema.md` §CRUD 管理功能清单。以下为后端实现分配概要。

### 6.1 权限分级

| 角色 | Controller 层拦截 | Service 层数据权限 |
|------|-------------------|-------------------|
| `SUPER_ADMIN` | 全部 Controller 可访问 | 无过滤 |
| `TEACHER` | 管理类 Controller 可访问 | `WHERE created_by = currentUserId` |
| `STUDENT` | 仅 C 端 Controller 可访问 | `WHERE ... AND enrollment 关联` |

### 6.2 功能矩阵（A~J 共 10 大模块）

| 模块 | Controller | Service | 权限 |
|------|-----------|---------|------|
| A. 用户管理 | `AdminUserController` | `SysUserService` | SUPER_ADMIN |
| B. 知识库管理 | `AdminKnowledgeBaseController` | `KnowledgeBaseService` | SUPER_ADMIN + TEACHER |
| C. 文档管理 | `AdminDocumentController` | `DocumentService` | SUPER_ADMIN + TEACHER |
| D. 分片管理 | `AdminChunkController` | `DocumentChunkService` | SUPER_ADMIN + TEACHER |
| E. 课程管理 | `AdminCourseController` | `CourseService` | SUPER_ADMIN + TEACHER |
| F. 排期管理 | `AdminScheduleController` | `CourseScheduleService` | SUPER_ADMIN + TEACHER |
| G. 选课管理 | `AdminEnrollmentController` | `EnrollmentService` | SUPER_ADMIN + TEACHER |
| H. 会话管理 | `AdminSessionController` | `ChatSessionService` | SUPER_ADMIN |
| I. 反馈管理 | `AdminFeedbackController` | `UserFeedbackService` | SUPER_ADMIN |
| J. C 端学生功能 | `StudentController` | 聚合多个 Service | STUDENT |

### 6.3 级联删除策略

| 删除主体 | 级联影响 |
|---------|---------|
| `knowledge_base` | → document + document_chunk + Milvus `deleteByKbId` |
| `document` | → document_chunk + Milvus `deleteByDocId` |
| `course_info` | → course_schedule + course_teacher + course_enrollment + document_chunk(课程专属) |
| `sys_user(TEACHER)` | → course_teacher 软删（课程保留由超管重分配） |
| `chat_session` | → chat_message + chat_run |

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §CRUD 管理功能清单（A~J 模块完整端点 + 权限矩阵 + DTO）+ §级联删除策略
> - **历史记忆**：`2026-07-15.md` §"全量 CRUD 管理功能"（功能矩阵 10 大模块 + 权限分级 + 级联删除策略确认）

---

## 七、异常处理 / 降级策略

> 此策略同时适用于 F#1（工具调用）和 F#2（流式管线）。

| 故障 | 策略 | 实现位置 |
|------|------|---------|
| LLM API | 重试 2 次（1s / 3s 间隔）→ SSE `error` 事件 `"AI 暂不可用"` → 终止 run | `SseEventTransformer` / `ChatRequestWorker` |
| Milvus | `searchKnowledge` 返回空 + `degraded:true` → 不终止，靠 API 工具兜底 | `SearchKnowledgeTool` |
| Redis | ring buffer fallback `ConcurrentLinkedQueue`；结果缓存 fallback 查 PG `chat_message` 表 → 不终止 | `MemoryStreamBridge` / `ChatRequestWorker` |
| PostgreSQL | 直接 503 → 不启动 run | `ChatController` 全局异常处理 |

> 📋 **详细设计索引**：
> - 前端设计：`2026-07-16-frontend-design.md` §3.5（前端错误处理映射）
> - **历史记忆**：`2026-07-15.md` §"异常处理/降级策略"（4 故障降级策略确认）

---

## 八、反馈系统

**实现描述**：
- 端点：`POST /api/v1/student/feedbacks`（C 端），`GET /api/v1/admin/feedbacks`（B 端统计）
- Controller：`com.commerce.rag.controller.FeedbackController`
- Service：`com.commerce.rag.service.UserFeedbackService`
- **规则**：
  - `is_liked BOOLEAN`（NULL=未评，TRUE=赞，FALSE=踩），删 `comment` 字段
  - 每个 (user_id, message_id) 只允许一条反馈（`UNIQUE (user_id, message_id)`）
  - 反馈=纯统计无闭环（不触发知识库更新或模型微调）

> 📋 **详细设计索引**：
> - DB 设计：`2026-07-15-db-schema.md` §五（user_feedback DDL + 索引）+ §CRUD 功能矩阵 J5（C 端反馈端点）
> - **历史记忆**：`2026-07-15.md` §"反馈系统"（is_liked BOOLEAN + 删 comment 确认）

---

## 九、完整实现清单与顺序

### 9.1 完整文件清单

| # | 文件/包 | 所属 Feature | 状态 |
|---|---------|-------------|------|
| 1 | `pom.xml` 版本升级 | 前置条件 | ⚠️ 1.0.0.4 → 1.1.2.0 |
| 2 | `LeadAgentGraph.java` | F#1 | 待实现 |
| 3 | `OverAllState.java` | F#1 | 待实现 |
| 4 | `IntentType.java` | F#1 | 待实现 |
| 5 | `QueryRewriter.java` | F#1 | 已有，待确认 |
| 6 | `SearchKnowledgeTool.java` | F#1 | 待实现 |
| 7 | `CourseApiTool.java` | F#1 | 待实现 |
| 8 | `Tool DTOs`（4 个 record） | F#1 | 待实现 |
| 9 | `CustomSummarizationHook.java` | F#1 | 待实现 |
| 10 | `CoalescingInterceptor.java` | F#1 | 待实现 |
| 11 | `ReminderHook.java` | F#1 | 待实现 |
| 12 | `WarningHook.java` | F#3 | 待实现 |
| 13 | 提示词 YAML 文件（5 个） | F#1 | query-rewrite/system-base ✅，其余 3 待确认 |
| 14 | `SseEventTransformer.java` | F#2 | 待实现 |
| 15 | `MemoryStreamBridge.java` | F#2 | 待实现 |
| 16 | `SseEventType.java`（10 事件枚举） | F#2 | 待实现 |
| 17 | `ChatRequestWorker.java`（改造） | F#2 | 已有，待改造 |
| 18 | `ChatController.java`（改造） | F#2 | 已有，待改造 |
| 19 | `LoopDetectionProperties.java` | F#3 | 待实现 |
| 20 | `TokenBudgetProperties.java` | F#3 | 待实现 |
| 21 | `ConfidenceProperties.java` | F#3 | 待实现 |
| 22 | `TokenService.java` | F#4 | 待实现 |
| 23 | `DeviceKickService.java` | F#4 | 待实现 |
| 24 | `AuthInterceptor.java` | F#4 | 待实现 |
| 25 | `kick_and_login.lua` | F#4 | 待实现 |
| 26 | `disable_user.lua` | F#4 | 待实现 |
| 27 | `EtlPipeline.java` | F#4 | 已有，待改造 |
| 28 | `MinioStorageService.java` | F#4 | 待实现 |
| 29 | `FusionService.java` | F#1 | 待实现 |
| 30 | `RerankService.java` | F#1 | 已有，待确认 |
| 31 | B 端 CRUD Controllers（10 个） | F#4 | 待实现 |
| 32 | B 端 CRUD Services（10+ 个） | F#4 | 待实现 |
| 33 | `UserFeedbackService.java` | F#4 | 待实现 |
| 34 | `PostgreSqlSaver` 配置 | F#1 | 待实现 |
| 35 | `application.yml` 配置块（5 组） | F#1~F#4 | 待更新 |
| 36 | DB DDL（15 张表 + 55 个索引） | 全 Feature | 待执行 |
| 37 | Milvus Collection 创建脚本 | F#1+F#4 | 待实现 |

### 9.2 实现顺序

```
Phase 0（前置）:
  ├── pom.xml SAA 版本升级（1.0.0.4 → v1.1.2.0）
  ├── DB DDL 执行（15 张表 + 55 个索引）
  └── Milvus Collection 创建脚本

Phase 1（F#1 Agent 编排）:
  ├── OverAllState + IntentType
  ├── LeadAgentGraph（StateGraph 接线）
  ├── 工具集（SearchKnowledgeTool + CourseApiTool + 4 DTO）
  ├── QueryRewriter + FusionService + RerankService
  ├── 4 个 Hook/Interceptor（CustomSummarizationHook / CoalescingInterceptor / ReminderHook / WarningHook）
  ├── 提示词 YAML（system-base / query-rewrite / agent-instruction / dynamic-context / rerank-instruct）
  └── PostgreSqlSaver 接入 CompileConfig

Phase 2（F#2 流式管线）:
  ├── SseEventType 枚举（10 事件）
  ├── SseEventTransformer
  ├── MemoryStreamBridge
  ├── ChatRequestWorker 改造（线程池 + 图执行 + Transformer + MemoryBridge）
  └── ChatController 改造（SSE 返回 + 断线重连 + 取消）

Phase 3（F#3 三套配置）:
  ├── LoopDetectionProperties + TokenBudgetProperties + ConfidenceProperties
  ├── WarningHook（独立 Hook）
  └── ModelCallLimitHook(runLimit=15) 注册

Phase 4（F#4 数据与存储）:
  ├── TokenService + DeviceKickService + AuthInterceptor
  ├── kick_and_login.lua + disable_user.lua
  ├── EtlPipeline 改造（异步状态机 + chunk 修正 + Milvus upsert）
  ├── MinioStorageService
  └── 异常降级 + 反馈（UserFeedbackService）

Phase 5（管理端 CRUD）:
  ├── 10 个 B 端 Controller + Service
  └── C 端 StudentController（聚合）
```

> 以上后端实现须与前端（`frontend-design.md` §3.4 双 Token 流程 / SSE 10 事件）及 DB（`db-schema.md` v5）保持接口一致，禁止单方面变更契约。

---

## 十、交叉引用索引（记忆文件完整映射）

下表为每个已批准功能模块到历史记忆文件的精确索引。实现者在规划任何模块前，**必须先阅读对应日记忆**中的决策上下文。

| 功能模块 | DB 设计 | 前端设计 | 日记忆（决策上下文） |
|---------|---------|---------|---------------------|
| 版本基线对齐 | §〇 | — | `07-14` §"SAA 源码实证深化"（版本矛盾标红） |
| 模型选型 | — | — | `07-15` §"模型修正（终版）" |
| F#1 Agent 编排 | — | — | `07-12` §"决策5"（图编排接线 + 上下文三层分离） |
| F#1 State 定义 | — | — | `07-12` §"决策3+4" |
| F#1 意图与工具集 | — | — | `07-12` §"已确认决策汇总" + `07-13` §"审计发现#10"（模糊意图） |
| F#1 Tool DTO | — | — | `07-12` §"决策2 补充" |
| F#1 提示词体系 | — | — | `07-12` §"提示词讨论顺序" + §"决策3+4" |
| F#1 Hook/Interceptor | — | — | `07-13` §"组件最终清单" + §"源码实证"（SAA 6 条实锤） |
| F#1 CustomSummarizationHook | — | — | `07-13` §"自定义 SummarizationHook" + §"摘要提示词模板" |
| F#1 Saver/Redis | — | — | `07-12` §"决策3+4" |
| F#2 端到端管线 | §三 | §1.6 | `07-14` §"Feature #2 终态" |
| F#2 SSE 10 事件 | — | §1.6.4 | `07-13` §"SSE schema" + `07-14` §"StreamingOutput 实证" |
| F#2 Worker 并发 | — | — | `07-14` §"Redisson 评估" + §"Worker 并发模型" |
| F#2 取消/回滚 | — | — | `07-15` §"Pre-run 快照 + 取消回滚" |
| F#2 渲染表 | §三 | — | `07-14` §"渲染表决策" + `07-15` §"thinking 持久化" |
| F#3 三组配置 | — | — | `07-15` §"Feature #3 配置管理" |
| F#3 软停/告警/优先级 | — | — | `07-15` §"Feature #3 边界决策" |
| F#3 ModelCallLimitHook | — | — | `07-15` §"ModelCallLimitHook" + `07-14` §"asNode 2参修正" |
| F#4 用户体系 | §二 | — | `07-15` §"全量 DB v1"（三层角色 + 教师删除） |
| F#4 认证安全 | §八 | §3.4 | `07-16` §"DB v5 重构" + §"Redis 驱动设备互踢" |
| F#4 会话层 | §三 | — | `07-16` §"会话层精简" |
| F#4 ETL 管道 | §四 + ETL 章节 | — | `07-15` §"ETL 异步链路设计" |
| F#4 MinIO | §MinIO | — | `07-15` §"MinIO 集成" |
| F#4 检索优化 | §四 | — | `07-15` §"检索链路 5 项优化" |
| F#4 MyBatis-Plus | §〇 | — | `07-12` §"已确认决策" + `07-15` §"全量 DB v1" |
| F#4 CRUD 矩阵 | §CRUD 清单 | — | `07-15` §"全量 CRUD 管理功能" |
| 异常降级 | — | §3.5 | `07-15` §"异常处理/降级策略" |
| 反馈系统 | §五 | — | `07-15` §"反馈系统" |

**使用方式**：例如实现「F#1 CustomSummarizationHook」时，先查上表 → 读 `2026-07-13.md` §"自定义 SummarizationHook 方案确认" + §"摘要提示词模板确认" → 获取完整决策上下文（增量摘要机制 / 融合规则 5 条 / 800 字上限 / summaryPrefix 标记）→ 再参照本文件 §2.8 的实现描述开始编码。

---

> **v2 结语**：本文件覆盖项目全部已批准后端功能（F#1~F#4），每个模块均标注实现描述 + 日记忆索引。与 `2026-07-15-db-schema.md`(v5)、`2026-07-16-frontend-design.md`(v2) 构成三文档完整闭环。新会话实现按 `MEMORY.md`（决策索引）→ 三详细设计文档的顺序阅读即可零歧义上手。
