# 后端代码全量回归分析报告

> 审计日期：2026-07-23
> 对照文档：`2026-07-15-db-schema.md`（v5）+ `2026-07-16-backend-design.md`（v2）
> 审计范围：backend/src 全量代码（130+ Java 文件 + DDL + YAML + Lua + Python）

---

## 总览

| 维度 | 数量 |
|------|------|
| 审计模块 | 5 大模块（DB Schema / F#1 / F#2 / F#3 / F#4+CRUD） |
| 偏离总数 | **56 项**（去重后约 53 项） |
| 🔴 严重 | **11 项** |
| 🟡 中等 | **26 项** |
| 🟢 轻微 | **19 项** |
| 审计通过项 | 70+ 项（详见各模块附录） |

---

## 严重偏离汇总（11 项，需优先修复）

| # | 模块 | 偏离描述 | 文件 |
|---|------|---------|------|
| S1 | F#1 | QueryRewriter + PromptLoader 交互断裂，查询重写始终降级为原始查询 | QueryRewriter.java / PromptLoader.java |
| S2 | F#1 | ReminderHook 闭合标签 bug，`substring(1)` 产生非法闭合标签 | ReminderHook.java:130 |
| S3 | F#1 | 缺失 OverAllState.java 独立接口文件（设计明确要求） | bot/graph/ 目录 |
| S4 | F#2 | 取消后回滚机制完全缺失，RunSnapshot 捕获但从未用于回滚 | ChatRequestWorker.java |
| S5 | F#2 | reclaimPending idleTime 计算错误，所有 pending 消息首次回收即被判定超时 | ChatRequestWorker.java:269 |
| S6 | F#2 | reclaimPending 回收的消息不被重新处理，永久丢失 | ChatRequestWorker.java:264 |
| S7 | DB | course_content Entity 枚举值与设计完全不同（SYLLABUS/DESCRIPTION vs intro/syllabus/instructor/faq） | CourseContent.java:18-19 |
| S8 | F#4 | MyBatis-Plus PaginationInnerInterceptor 未配置，所有分页查询返回全量数据 | 全局缺失 |
| S9 | F#4 | @RequestHeader("X-User-Id") 安全漏洞，8 个 Controller 共 29 个端点可被伪造 | 8 个 Controller |
| S10 | F#4 | SecurityConfig 要求 authenticated() 但无 JWT 认证过滤器，所有请求将被 401 | SecurityConfig.java:52-55 |
| S11 | F#4 | DocumentService delete/reparse 缺少 operatorId 权限校验 | DocumentService.java:157,191 |

---

## 模块一：DB Schema DDL + Entity（11 项偏离）

### 严重偏离（1 项）

| # | 表名 | 偏离类型 | 设计预期 | 实际实现 | 严重程度 |
|---|------|---------|---------|---------|---------|
| S7 | course_content | Entity 枚举值不一致 | `intro / syllabus / instructor / faq` | `SYLLABUS / DESCRIPTION / PREREQUISITES / TARGET_AUDIENCE` | 🔴 严重 |

**影响**：枚举值完全不同，开发者按注释实现会导致课程详情页 4 个 Tab 无法正确加载。

### 中等偏离（6 项）

| # | 表名 | 偏离类型 | 设计预期 | 实际实现 | 严重程度 |
|---|------|---------|---------|---------|---------|
| D1 | sys_user | DDL 多出字段 | 9 字段（无 phone/email/created_by） | 12 字段（多 phone, email, created_by） | 🟡 中等 |
| D2 | sys_user | DDL 多出索引 | 3 个索引 | 4 个索引（多 idx_sys_user_created_by） | 🟡 中等 |
| D3 | 整体 | 索引总数不匹配 | 55 个 | 56 个（多 1 个） | 🟡 中等 |
| D4 | 整体 | 超管初始化缺失 | DDL 或应用层应有超管 INSERT | DDL 中无 INSERT 语句 | 🟡 中等 |
| D5 | course_schedule | Entity 状态值不一致 | `UPCOMING / IN_PROGRESS / COMPLETED` | `UPCOMING / ONGOING / ENDED` | 🟡 中等 |
| D6 | 全部 15 表 | @TableLogic 配置风险 | deleted 应存时间戳（毫秒） | 未显式指定 delval，MP 默认可能设为 1 | 🟡 中等 |

### 轻微偏离（4 项）

| # | 表名 | 偏离类型 | 设计预期 | 实际实现 | 严重程度 |
|---|------|---------|---------|---------|---------|
| D7 | course_schedule | Entity 枚举值缺失 | `ONLINE / OFFLINE / HYBRID` | `ONLINE / OFFLINE`（缺 HYBRID） | 🟢 轻微 |
| D8 | knowledge_base | Entity 状态值不一致 | `ACTIVE / ARCHIVED` | `ACTIVE / INACTIVE` | 🟢 轻微 |
| D9 | course_info | Entity 状态值不一致 | `ACTIVE / ARCHIVED` | `ACTIVE / INACTIVE` | 🟢 轻微 |
| D10 | sys_user | Entity 多出字段 | 无 phone/email/created_by | Entity 含 phone, email, createdBy | 🟡 中等（与 D1 同源） |

### 审计通过项

- 10/15 张表 DDL + Entity 完全一致：document, document_chunk, sys_login_record, sys_token_blacklist, course_teacher, course_enrollment, chat_session, chat_run, chat_message, user_feedback
- pg_trgm 扩展已创建 ✅
- 所有 Entity @TableId(ASSIGN_ID) + @TableLogic 正确 ✅
- pom.xml 所有依赖版本对齐 ✅

---

## 模块二：F#1 Agent 架构（16 项偏离）

### 严重偏离（3 项）

#### S1: QueryRewriter + PromptLoader 交互断裂，查询重写功能失效

- **文件**：`QueryRewriter.java` + `PromptLoader.java`
- **设计预期**：QueryRewriter 调用 LLM + query-rewrite.yml 模板，生成 3 条覆盖性查询
- **实际实现**：PromptLoader.doLoad() 通过 flattenMap() 展平 YAML 时**只保留 value、丢弃所有 key**。但 QueryRewriter.extractSection() 依赖查找 "system:" 和 "instruction:" 标记来分段。标记不存在 → extractSection 返回空字符串 → LLM 无任何重写指令 → parseJsonArray 失败 → 降级返回原始查询
- **影响**：查询重写始终降级为原始查询，RRF 跨查询融合形同虚设
- **建议修正方向**：修复 PromptLoader.flattenMap() 保留 key:value 格式，或修改 QueryRewriter 直接使用 YAML 解析后的 Map 结构

#### S2: ReminderHook 闭合标签 bug

- **文件**：`ReminderHook.java:130`
- **设计预期**：`</system-reminder>` 闭合标签
- **实际实现**：`REMINDER_MARKER.substring(1)` → `"system-reminder>"`（缺少 `<` 和 `/`），不是合法闭合标签
- **建议修正方向**：改为 `"</" + REMINDER_MARKER.substring(1) + ">"` 或直接使用常量 `"</system-reminder>"`

#### S3: 缺失 OverAllState.java 独立接口文件

- **文件**：应位于 `bot/graph/OverAllState.java`
- **设计预期**：§2.2 明确要求独立接口文件，继承 `StateGraph.State`
- **实际实现**：无此文件，LeadAgentGraph 直接 import 框架的 `com.alibaba.cloud.ai.graph.OverAllState`。KeyStrategyFactory 在 GraphConfig.java 中以 @Bean 实现（策略本身正确）
- **建议修正方向**：创建独立 OverAllState.java 接口文件，或将设计文档调整为接受当前实现方式

### 中等偏离（6 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F1-4 | CustomSummarizationHook.java:177 | 字段不匹配 | 800 字上限 | 内联提示词写"不超过500字" |
| F1-5 | CustomSummarizationHook.java:175 | 逻辑偏差 | 第三人称 + 融合规则 5 条 + 禁止分两段 | 仅写"保留关键信息，删除冗余" |
| F1-6 | RerankService.java | 逻辑偏差 | 覆写注入 rerank-instruct.yml 指令 | 从未加载 rerank-instruct.yml |
| F1-7 | ReminderHook.java | 逻辑偏差 | 使用 dynamic-context.yml 模板 | 完全内联构建，PromptLoader.loadAndReplace() 从未被调用 |
| F1-8 | LeadAgentGraph.java:171 | 逻辑偏差 | 返回增量更新 `Map.of("rewrittenQueries", rewritten)` | 返回全量 `overAllState.data()`，可能导致 messages 重复追加 |
| F1-9 | WarningHook.java:439 | 逻辑偏差 | 通过 State reducer (AppendStrategy) 写入 | 直接 mutate thread state Map，绕过 reducer |

### 轻微偏离（7 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F1-10 | CourseListResult.java:16 | 字段名 | pageSize | size |
| F1-11 | CourseDetailResult.java | 冗余字段 | duration 仅在 ScheduleInfo 内 | 顶层也有 duration |
| F1-12 | KnowledgeSearchResult.java | 多出字段 | 6 字段 | 11 字段（多 courseId/docId/kbId/chunkIndex/tokenCount） |
| F1-13 | WarningHook.java:120 | 消息格式 | `⚠️ [警告内容]` | `⚠️ [系统告警] 警告内容` |
| F1-14 | ReminderHook.java:126 | 内容缺失 | 3 项提醒 | 仅 2 项（缺"未完成任务请继续完成"） |
| F1-15 | GraphConfig.java | 类名差异 | PostgreSqlSaver | PostgresSaver（SAA 实际 API，非代码错误） |
| F1-16 | CustomSummarizationHook.java:221 | 逻辑偏差 | 保留 firstUserMessage | 不保留 firstUM，可能丢失首条消息 |

### 审计通过项

- StateGraph 接线：START → queryRewriteNode → ReactAgent.asNode(true, false) → END ✅
- ReactAgent.asNode 仅 2 参 ✅，outputKey 在 Builder 独立设置 ✅
- ModelCallLimitHook runLimit=15 ✅
- PostgreSqlSaver 配在 CompileConfig ✅
- KeyStrategyFactory 4 key 策略正确 ✅
- IntentType 仅 TECHNICAL_QA + COURSE_INFO ✅
- TypedQuery courseIds(List) ✅
- SearchKnowledgeTool hybridSearch + IN 列表 + DEFAULT OR ✅
- CourseApiTool 3 方法完整 ✅
- FusionService RRF 公式正确 ✅
- CoalescingInterceptor 合并多条 SM ✅
- CustomSummarizationHook summaryPrefix "## 对话摘要:" ✅
- Hook 注册顺序正确 ✅
- 5 个 YAML 提示词文件全部存在 ✅

---

## 模块三：F#2 流式管线（17 项偏离）

### 严重偏离（3 项）

#### S4: 回滚机制完全缺失

- **文件**：`ChatRequestWorker.java`
- **设计预期**（§3.4）：取消后应"写新 checkpoint，恢复 pendingWrites，不删旧 checkpoint"
- **实际实现**：`handleCancelled()` 仅推送 CANCELLED END 事件 + 更新 run 状态。无任何 checkpoint 回滚代码。RunSnapshot 被捕获但从未用于回滚
- **建议修正方向**：在 handleCancelled() 中实现 checkpoint 回滚逻辑，使用 RunSnapshot 恢复 pendingWrites

#### S5: reclaimPending idleTime 计算错误

- **文件**：`ChatRequestWorker.java:269`
- **设计预期**（§3.3）：XPENDING 检查 → XCLAIM 回收超时消息（stale-lease-minutes=30）
- **实际实现**：`long idleTime = now - pm.getElapsedTimeSinceLastDelivery().toMillis()` — `getElapsedTimeSinceLastDelivery()` 已返回 idle Duration，应直接用其作为 idleTime。当前用 `System.currentTimeMillis() - idleDuration` 得到无意义的大数，导致所有 pending 消息首次回收即被判定超时
- **建议修正方向**：改为 `long idleTime = pm.getElapsedTimeSinceLastDelivery().toMillis()`

#### S6: reclaimPending 回收的消息不被重新处理

- **文件**：`ChatRequestWorker.java:264`
- **设计预期**（§3.3）：XCLAIM 回收后应重新执行
- **实际实现**：XCLAIM 将消息转移给新 consumer `"reclaim-" + UUID`，但 consumeLoop 使用 `"worker-" + UUID` + `ReadOffset.lastConsumed()`（`>`），被 XCLAIM 的消息卡在 reclaim consumer 的 pending 列表中无人消费，永久丢失
- **建议修正方向**：XCLAIM 后直接 `runPool.submit(processRequest)` 处理，或使用与 consumeLoop 相同的 consumer name

### 中等偏离（10 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F2-4 | application.yml:226 | 配置值偏离 | runPool core=CPU*2, max=CPU*2 | core=4, max=8 |
| F2-5 | ChatRequestWorker.java:551 | 数据格式偏离 | `{"tool":"...","args":{...}}` | 仅存储 arguments 字符串 |
| F2-6 | ChatRequestWorker.java:564 | 数据格式偏离 | `{"tool":"...","result":"..."}` | 仅存储 response 数据 |
| F2-7 | ChatRequestWorker.java | 功能缺失 | thinking 持久化到 PG (msg_type='thinking') | toChatMessages 无 reasoningContent 提取 |
| F2-8 | ChatRequestWorker.java | 功能缺失 | Redis 缓存最终结果 JSON (TTL 300s) | responseTtl 字段定义但从未使用 |
| F2-9 | ChatController.java:221 | 降级缺失 | ring buffer 失败 → fallback 查 PG | 直接返回 error，不查 PG |
| F2-10 | SseEventTransformer.java | 功能缺失 | SOURCES 事件推送 | SOURCES 枚举定义但从不产生 |
| F2-11 | ChatRequestWorker.java:430 | 实现不完整 | saver.getTuple + deepCopy(channelValues+channelVersions+pendingWrites) | saver.get + 浅拷贝 state Map |
| F2-12 | ChatRequestWorker.java:467 | 逻辑 Bug | run 结束批量 INSERT | 用户消息可能被 INSERT 两次 |
| F2-13 | ChatController.java:135 | API 契约偏离 | `X-Staff-Id` 头鉴权 | `X-User-Id` 头 |

### 轻微偏离（4 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F2-14 | ChatController.java:283 | SSE 格式偏离 | `:heartbeat`（注释行） | `event: heartbeat\ndata: {}`（普通事件） |
| F2-15 | SseEventTransformer.java:133 | 行为偏离 | AGENT_MODEL_FINISHED 发累积 text | 仅发 THINKING_END + TOOL_CALL |
| F2-16 | MemoryStreamBridge.java:85 | 方法名偏离 | `replayFrom(lastEventId)` | `replay(runId, lastEventId, emitter)` |
| F2-17 | WarningHook.java:120 | 消息格式偏离 | `⚠️ [警告内容]` | `⚠️ [系统告警] 警告内容` |

### 审计通过项

- ChatController 3 端点完整 ✅
- Redis Streams `chat:request` + `chat-workers` 消费组 ✅
- ACK 在 finally 块 ✅
- runPool 独立 ThreadPoolExecutor ✅
- ConcurrentHashMap<runId, AtomicBoolean> 取消标记 ✅
- CancelledException → cancelled SSE 事件 ✅
- 快照失败降级 ✅
- SseEventType 10 值枚举完整 ✅
- thinking 从 metadata.get("reasoningContent") 提取 ✅
- ring buffer 256 + O(1) 回放 ✅
- fallback ConcurrentLinkedQueue ✅
- ChatMessageService 批量 INSERT ✅
- ChatRunService 并发守卫 ✅

---

## 模块四：F#3 安全机制（2 项偏离，与 F#1/F#2 部分重叠）

### 中等偏离（1 项，与 F#1 #9 重叠）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F3-1 | WarningHook.java:438 | 实现方式偏离 | AFTER_MODEL 通过 State reducer (AppendStrategy) 写入 | 直接 mutate thread state Map |

### 轻微偏离（1 项，与 F#1 #13 重叠）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F3-2 | WarningHook.java:122 | API 使用偏离 | `HumanMessage(name="loop_warning")` | `UserMessage` + `metadata.put("name", ...)` |

### 审计通过项

- LoopDetectionProperties: hash(20/3/5) + per-tool(default 15/25, searchKnowledge 25/40, listCourses 5/10) ✅
- TokenBudgetProperties: 200000 / 0.8 / 1.0 ✅
- ConfidenceProperties: 0.8 / 0.5 / 2 ✅
- WarningHook 软停三处同清 tool_calls ✅
- WarningHook finish_reason="stop" ✅
- WarningHook 追加 "[FORCED STOP]" ✅
- WarningHook 不抛异常 ✅
- WarningHook 优先级短路 ✅
- F3Config 注册三组 Properties ✅
- ModelCallLimitHook runLimit=15 ✅
- stale-lease-minutes=30 ✅

---

## 模块五：F#4 数据与存储层 + CRUD 矩阵（10 项偏离）

### 严重偏离（4 项）

#### S8: MyBatis-Plus PaginationInnerInterceptor 未配置

- **文件**：全局缺失（config/ 目录下无 MybatisPlusInterceptor Bean）
- **设计预期**（§5.7）：`PaginationInnerInterceptor`（`maxLimit=2000`，防全表查询）
- **影响**：所有使用 `Page<>` 的分页查询将返回全量数据（无 LIMIT），无 maxLimit 防护
- **建议修正方向**：创建 MyBatisPlusConfig.java，注册 `MybatisPlusInterceptor` + `PaginationInnerInterceptor(DbType.POSTGRE_SQL).setMaxLimit(2000)`

#### S9: @RequestHeader("X-User-Id") 安全漏洞

- **文件**：8 个 Controller 共 29 个端点
  - AdminKnowledgeBaseController (3 端点), AdminDocumentController (2), AdminCourseController (9), AdminScheduleController (4), AdminEnrollmentController (3), StudentController (6), ChatController (1), FeedbackController (1)
- **设计预期**：通过 AuthInterceptor 注入的 `request.getAttribute("currentUserId")` 获取已认证用户 ID
- **实际实现**：使用 `@RequestHeader("X-User-Id") Long userId` 从 HTTP 请求头获取，客户端可伪造
- **影响**：任意客户端可冒充其他用户，绕过身份认证
- **建议修正方向**：统一改为 `request.getAttribute(AuthInterceptor.ATTR_USER_ID)`，或使用 `@CurrentUser` 注解 + ArgumentResolver

#### S10: SecurityConfig 要求 authenticated() 但无 JWT 认证过滤器

- **文件**：`SecurityConfig.java:52-55`
- **设计预期**：AuthInterceptor 负责鉴权，SecurityConfig 应配合
- **实际实现**：`.anyRequest().authenticated()` 但无 JWT Filter，AuthInterceptor 是 HandlerInterceptor 在 Security Filter Chain 之后执行，无法提供 Authentication 对象
- **影响**：所有非 `/api/v1/auth/**` 和 `/api/v1/public/**` 的请求会被 Spring Security 返回 401/403
- **建议修正方向**：将 `.anyRequest().authenticated()` 改为 `.anyRequest().permitAll()`，由 AuthInterceptor 统一鉴权

#### S11: DocumentService delete/reparse 缺少权限校验

- **文件**：`DocumentService.java:157` (delete) / `:191` (reparse)
- **设计预期**：TEACHER 权限校验 `checkOwnership(created_by)`
- **实际实现**：仅接收文档 ID，无 operatorId 参数，无权限校验
- **影响**：任何 TEACHER 可删除/重新解析其他 TEACHER 知识库下的文档
- **建议修正方向**：添加 operatorId 参数 + checkOwnership 校验

### 中等偏离（4 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F4-5 | AdminChunkController.java:98 | 接口契约 | `POST /api/v1/admin/chunks/batch-update` | `PUT /api/v1/admin/chunks/batch` |
| F4-6 | AdminChunkController.java:105 | 接口契约 | `PATCH /api/v1/admin/chunks/batch-corrected` | `PUT /api/v1/admin/chunks/batch/corrected` |
| F4-7 | StudentController.java:14 | 分层违反 | Controller→Service→Mapper 三层 | J2/J3/J4 直接注入 JdbcTemplate 执行 SQL |
| F4-8 | AdminCourseController.java:55 | 逻辑偏差 | AuthInterceptor 鉴权 | 使用 Authentication 参数（始终为 null） |

### 轻微偏离（2 项）

| # | 文件 | 偏离类型 | 设计预期 | 实际实现 |
|---|------|---------|---------|---------|
| F4-9 | 全局缺失 | 配置缺失 | CORS 配置（前端跨域） | 无 CORS 配置 |
| F4-10 | 跨多个 Controller | 一致性 | 统一 getAttribute | 两种模式混用（getAttribute vs @RequestHeader） |

### 审计通过项

- TokenService: JWT AT 15min / RT 7d / 一次性旋转 ✅
- DeviceKickService: Redis Lua + PG 降级 ✅
- AuthInterceptor: 校验 AT + 黑名单 + 注入 userId ✅
- AuthController: login/refresh/logout + httpOnly cookie ✅
- kick_and_login.lua / disable_user.lua ✅
- EtlPipeline: 状态机完整 + 旁路修正 + Milvus v2 ✅
- EtlConfig: 线程池 core=2/max=4/queue=20 ✅
- MinioStorageService: bucket=rag-documents + 路径 {kb_id}/{doc_id}.{ext} ✅
- 所有 CRUD 端点 A1-J8 + K1-K7 完整 ✅
- ApiResponse ok()/ok(data)/fail() ✅
- PageResponse records/total/page/size ✅
- 级联删除策略（KB→Doc→Chunk→Milvus / Course→Content+Schedule+Teacher+Enrollment）✅
- application.yml 配置（etl/minio/auth/milvus）✅

---

## 偏离分布统计

### 按严重程度

```
严重 ███████████████████████████████ 11 项
中等 ██████████████████████████████████████████████████████████ 26 项
轻微 ████████████████████████████████████████████ 19 项
```

### 按模块

| 模块 | 严重 | 中等 | 轻微 | 小计 |
|------|------|------|------|------|
| DB Schema + Entity | 1 | 6 | 3 | 10 |
| F#1 Agent 架构 | 3 | 6 | 7 | 16 |
| F#2 流式管线 | 3 | 10 | 4 | 17 |
| F#3 安全机制 | 0 | 1 | 1 | 2（与 F#1/F#2 部分重叠） |
| F#4 数据层 + CRUD | 4 | 4 | 2 | 10 |
| **合计** | **11** | **26** | **19** | **56**（去重约 53） |

### 按偏离类型

| 偏离类型 | 数量 | 典型案例 |
|---------|------|---------|
| 逻辑偏差 | 18 | QueryRewriter 断裂、回滚缺失、idleTime 计算错误 |
| 功能缺失 | 12 | SOURCES 事件、thinking 持久化、Redis 缓存、PaginationInterceptor |
| 字段/格式不匹配 | 10 | course_content 枚举值、TOOL_CALL 存储格式、字数上限 |
| 安全漏洞 | 6 | X-User-Id 伪造、SecurityConfig 401、权限校验缺失 |
| 配置错误/缺失 | 5 | PaginationInterceptor、CORS、线程池参数 |
| 接口契约偏离 | 3 | D7/D8 HTTP 方法路径、X-Staff-Id vs X-User-Id |
| 文件缺失 | 2 | OverAllState.java、超管初始化 |

---

## 修复优先级建议

### P0 — 立即修复（影响系统可用性/安全性）

1. **S10 SecurityConfig 401 问题** — 所有 API 请求被拦截，系统完全不可用
2. **S9 X-User-Id 安全漏洞** — 任意用户可冒充他人
3. **S8 PaginationInterceptor 缺失** — 所有分页查询返回全量数据，性能灾难
4. **S11 DocumentService 权限缺失** — TEACHER 可删除他人文档

### P1 — 尽快修复（影响核心功能）

5. **S1 QueryRewriter 断裂** — 查询重写失效，检索质量严重下降
6. **S5+S6 reclaimPending Bug** — Pending 消息回收逻辑完全失效
7. **S2 ReminderHook 标签 bug** — system-reminder 标签不闭合，可能影响 LLM 理解
8. **S4 回滚机制缺失** — 取消后无法回滚 checkpoint，状态可能不一致
9. **S7 course_content 枚举值** — 课程详情页 4 个 Tab 无法正确加载

### P2 — 计划修复（影响功能完整性/数据准确性）

10. F2-7 thinking 持久化缺失
11. F2-8 Redis 结果缓存未实现
12. F2-10 SOURCES 事件未产生
13. F1-6 RerankService 未注入 instruct
14. F1-4/F1-5 CustomSummarizationHook 提示词不完整
15. F4-7 StudentController JdbcTemplate 绕过 Service 层

### P3 — 适时修复（代码质量/一致性）

16. D5/D8/D9 Entity 状态值注释不一致
17. F1-10/F1-11/F1-12 DTO 字段名/冗余字段
18. F2-14 heartbeat 事件格式
19. F4-9 CORS 未配置
20. F1-3 OverAllState.java 独立文件

---

## 附录：审计通过项汇总（70+ 项）

以下经逐文件对比确认与设计文档一致：

**DB Schema**: 10/15 表完全一致、pg_trgm 扩展、所有 @TableId/@TableLogic 正确、pom.xml 依赖版本对齐

**F#1**: StateGraph 接线、asNode 2 参、ModelCallLimitHook、PostgreSqlSaver 配置位置、4 key 策略、IntentType、TypedQuery、SearchKnowledgeTool hybridSearch、CourseApiTool 3 方法、FusionService RRF、CoalescingInterceptor、CustomSummarizationHook summaryPrefix、Hook 注册顺序、5 YAML 文件存在

**F#2**: 3 端点完整、Redis Streams 消费组、ACK finally 块、runPool 独立、取消标记、CancelledException、快照降级、SseEventType 10 值、ring buffer 256、O(1) 回放、ConcurrentLinkedQueue 降级、批量 INSERT、并发守卫

**F#3**: 三组配置参数、软停三处同清、finish_reason、[FORCED STOP]、不抛异常、优先级短路、ModelCallLimitHook、stale-lease-minutes

**F#4**: TokenService、DeviceKickService、AuthInterceptor、AuthController、Lua 脚本、EtlPipeline 状态机、EtlConfig 线程池、MinioStorageService、所有 CRUD 端点 A1-K7 完整、ApiResponse/PageResponse、级联删除策略、application.yml 配置

---

*报告生成：软件开发团队主理人齐活林，基于 4 个并行审计 Agent 的独立探索结果汇编。*

---

## 补充偏离（架构师核查追加，2026-07-23）

> 4 个模块核查 Agent 逐项复核当前代码后，发现以下原报告未列入的偏离。其中 N-F2-1（Redis 结果缓存未写入）与原 F2-8 同源、N-F2-2（PG 降级缺失）与原 F2-9 同源，已合并到原偏离项处理，不重复列入下表。

| 编号 | 严重程度 | 模块 | 偏离描述 | 文件 |
|------|---------|------|---------|------|
| N-DB-1 | 🟡 P2 | DB | UserFeedbackService 软删设 deleted=1 非毫秒时间戳，违反软删除约定 | UserFeedbackService.java |
| N-DB-2 | 🟢 P3 | DB | 超管 INSERT 硬编码 BCrypt 哈希 + id=1 | V6__full_schema_v5.sql:324 |
| N-F1-1 | 🔴 P0 | F#1 | PromptLoader.flattenMap 的 key 前缀泄露到 system-base/agent-instruction/dynamic-context 提示词文本 | PromptLoader.java + LeadAgentGraph.java + ReminderHook.java |
| N-F1-2 | 🟡 P2 | F#1 | query-rewrite.yml 的 ${query} 占位符未替换 | query-rewrite.yml:23 + QueryRewriter.java:56 |
| N-F2-3 | 🟢 P3 | F#2 | HEARTBEAT 枚举值(":heartbeat")与 ChatController 实际发送("heartbeat")不一致 | SseEventType.java:18 + ChatController.java:285 |
| N-F2-4 | 🟢 P3 | F#2 | RunnableConfig 仅设 threadId，未写 userId | ChatRequestWorker.java:337 |
| N-F2-5 | 🟢 P3 | F#2 | persistMessages 可能遗漏非 state 消息 | ChatRequestWorker.java:508 |
| N-F4-1 | 🟡 P1 | F#4 | 删 sys_user(TEACHER) 未级联软删 course_teacher | SysUserService.java:229 |
| N-F4-2 | 🟡 P1 | F#4 | checkOwnership 无 SUPER_ADMIN 旁路（3 个 Service） | CourseService:394 / KnowledgeBaseService:160 / DocumentService:267 |
| N-F4-3 | 🟡 P2 | F#4 | KnowledgeBaseService 抛 raw SecurityException | KnowledgeBaseService.java:162 |
| N-F4-4 | 🟡 P1 | F#4 | TEACHER 查询未按 created_by 过滤 | KnowledgeBaseService / DocumentService / DocumentChunkService |
| N-F4-5 | 🟡 P1 | F#4 | 全局缺失 @RestControllerAdvice 异常处理器 | 全局 |
| N-F4-6 | 🟡 P2 | F#4 | AdminChunkController D1-D9 无 userId/无 ownership 校验 | AdminChunkController.java |
| N-F4-7 | 🟢 P3 | F#4 | CourseService.deleteCourse 用 JdbcTemplate 裸 SQL 级联 | CourseService.java:193 |
| N-F4-8 | 🟡 P2 | F#4 | AdminSession/FeedbackController 删除无 operatorId | AdminSessionController:90 / AdminFeedbackController:56 |

---

## 补充审计：报告遗漏偏离（2026-07-23 架构师核查）

> 核查人：架构师高见远（Gao）
> 核查方法：逐文件读取源码 + Grep 精准定位，以当前代码真实状态为准

### N1: @PreAuthorize 注解全部失效（🔴 严重）

| 维度 | 内容 |
|------|------|
| **模块** | F#4 认证安全 |
| **文件** | `auth/AuthInterceptor.java`、`auth/SecurityConfig.java`、`config/AuthConfig.java` |
| **设计预期** | AuthInterceptor 验证 JWT 后注入用户身份，`@PreAuthorize` 基于角色鉴权 |
| **实际实现** | AuthInterceptor（HandlerInterceptor）只设置 `request.setAttribute("currentUserId", userId)` 等 request attribute，**未调用** `SecurityContextHolder.getContext().setAuthentication(...)`。SecurityConfig 使用 `permitAll()` 放行所有请求 + `@EnableMethodSecurity(prePostEnabled = true)` 激活方法级安全。由于 SecurityContext 中无 Authentication 对象，Spring Security 使用 `AnonymousAuthenticationToken`（角色 `ROLE_ANONYMOUS`），导致所有 `@PreAuthorize` 检查返回 false → **403 Forbidden** |
| **影响范围** | 12 个 Controller 的所有 `@PreAuthorize` 注解端点（AdminCourseController、AdminDocumentController、AdminChunkController、AdminKnowledgeBaseController、AdminScheduleController、AdminEnrollmentController、AdminFeedbackController、AdminSessionController、AdminLoginRecordController、AdminUserController、StudentController、FeedbackController）|
| **严重程度** | 🔴 严重 — 所有需要角色权限的端点将返回 403，系统管理功能完全不可用 |
| **修复方向** | 方案 A（推荐）：在 `AuthInterceptor.preHandle` 验证 token 后，构造 `UsernamePasswordAuthenticationToken(userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))` 并设置到 `SecurityContextHolder.getContext().setAuthentication(auth)`，在 `afterCompletion` 中清除。方案 B：在 SecurityFilterChain 中添加 `OncePerRequestFilter` JWT 过滤器替代 HandlerInterceptor 认证逻辑 |
| **依赖** | 无 |
