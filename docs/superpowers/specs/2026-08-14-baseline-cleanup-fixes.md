# 基线清理修复 — 设计规格（S2 认证安全 + S3 Tab 语义）

> 状态：草稿待审。
> 决策来源：2026-08-14 对进度文档 S6→S2→S3→S5 四个问题块的**源码逐项核验**（本会话实锤），修复方案沿用进度文档「已锁定决策」与 bug 报告「建议修复方向」。
> 本 spec 是 S1 主任务前置的**干净基线**修复规格，独立于 `2026-08-12-multimodal-rag-design.md`。

## 0. 核验结论与范围界定（2026-08-14 实锤）

| 问题块 | 核验结论 | 处理 |
|---|---|---|
| S6 杂项修正（5 项） | **不存在**。GraphConfig 无 extractParam/DataSource 参数、D5 已是 PATCH、yml 已含 max-context-tokens/window-ratio/summary-model、Hook 已 6 参读配置、测试已同步；`git diff HEAD` 为空 → 修复已在 HEAD（0c08d32）中，进度文档「均未动代码」记录过时 | 不纳入本 spec，零动作 |
| S2 认证安全（3 项） | **存在**（见 §1~§3） | 纳入本 spec |
| S3 CourseApiTool Tab 语义 | **存在**（见 §4） | 纳入本 spec |
| S5 教师/学生拆表 | **问题形态不符**。sys_user 为纯账号表（db-schema.md 与 V6 实际 DDL 一致），无教师/学生专属展示字段；设计文档明确 instructor_name 纯文本不关联 sys_user；全仓库无 teacher_profile/student_profile。实施 = 纯新增功能，非缺陷修复 | 移出本 spec，留待单独设计 |
| P0 四大问题（权限断裂恒403 / 对话端点IDOR / 教师越权 / 消息持久化重复） | **均仍存在**（抽查实锤），但不在进度文档 S6→S2→S3→S5 清单内，且超出本次用户指定核验范围 | 本 spec 不覆盖，见 §7 遗留事项（待用户决策） |

**范围**：仅 S2（3 项）+ S3（1 项），共 4 个修复任务。

## 1. S2-1 登出不吊销令牌修复

### 1.1 问题实锤

- `AuthConfig.java:26-28` 将 `/api/v1/auth/**` 整体排除在 AuthInterceptor 外；
- `AuthController.logout`（:236-261）依赖拦截器注入的 `ATTR_USER_ID/ATTR_JTI`，二者恒为 null → 黑名单与 login_record REVOKED 分支**永不执行**，仅清 cookie；
- 后果：登出后 AT（15min 内）与 RT（7d 内）继续有效，可 refresh 出新 Token 对；审计记录失真。bug 报告严重度 P1。

### 1.2 修复方案（锁定）

logout 改为**自行提取并解析 AT**，不依赖拦截器 attribute：

1. 提取 AT：Authorization header（`Bearer xxx`）优先，cookie 兜底（与 `AuthInterceptor.extractToken` 同序）；提取逻辑抽为公共方法复用（AuthInterceptor 提取方法静态化或抽 TokenExtractor 工具，实现细节在实施计划定，禁止两处复制）。
2. 宽松解析：新增 `TokenService.parseClaimsLoose(String token)`——校验签名、**不校验过期**（AT 过期后 15min 内 RT 仍有效，必须仍能定位 login_record 吊销 RT）；沿用 `extractJti/extractUserId/extractTokenType`，tokenType 非 ACCESS 视为无效。
3. 吊销流程（解析成功时）：
   - 按 `(user_id, jti_at, ACTIVE)` 查 login_record 取 `jti_rt`；
   - AT jti 入黑名单（`ACCESS`，reason=`MANUAL_REVOKE`，TTL=AT 剩余有效期）；
   - **jti_rt 一并入黑名单**（`REFRESH`，reason=`MANUAL_REVOKE`，TTL=RT 剩余有效期）——进度文档锁定项；
   - login_record → `REVOKED`。
4. 幂等降级：token 缺失/解析失败/类型错误 → 仅清 cookie，仍返回 `ApiResponse.ok()`（登出不因无效 token 报错）；吊销过程异常只 log.warn 不抛出。
5. 最后清 cookie（保持现行为）。

### 1.3 验证标准

- 登出后：旧 RT 调 `/api/v1/auth/refresh` 返回 401（RT 已入黑名单）；旧 AT 访问业务接口被拒。
- PG：`sys_login_record` 置 REVOKED；`sys_token_blacklist` 同时有 ACCESS + REFRESH 两条记录。
- 无 token 登出：仅清 cookie，返回 ok。
- `mvn.cmd test` 全过（AuthControllerTest 同步修改/新增）。

## 2. S2-2 设备互踢 Redis 成功路径缺 PG 审计

### 2.1 问题实锤

- `DeviceKickService.kickAndLogin`（:117-130）Lua 成功后直接 `parseKickResult(result)` 返回，**无 PG 审计**：旧 login_record 永久保持 ACTIVE、旧 jti 不入 PG 黑名单；`disableUser.findActiveLoginRecords` 重复捞到已踢设备，活跃会话统计失真。对照 PG 降级路径 `kickAndLoginPgFallback`（:313-331）有完整 REVOKED + 黑名单写入。bug 中置信清单 A10，P2~P3。

### 2.2 修复方案（锁定）

Redis Lua 成功且 `KickResult.kicked() == true` 时，同步落 PG 审计（复用 `disableUserPgAudit` 同款模式，新增私有方法 `kickPgAudit(Long userId, KickResult result)`）：

- `UPDATE sys_login_record SET status='REVOKED', updated_at=now() WHERE user_id=? AND jti_at=? AND status='ACTIVE'`；
- `addToBlacklistPg(oldJtiAt, "ACCESS", userId, null, "DEVICE_KICKED")`；
- `addToBlacklistPg(oldJtiRt, "REFRESH", userId, null, "DEVICE_KICKED")`。

约束：

- 审计异常必须吞掉（log.warn），**不得影响登录主流程**（登录已在前一步完成 login_record INSERT，Lua 也已完成踢出）；
- 幂等：`addToBlacklistPg` 已忽略唯一索引冲突，REVOKED UPDATE 带 `status='ACTIVE'` 条件；
- 同步执行（与 disableUserPgAudit 一致），不引入新线程池——登录路径单次审计 3 条 SQL，量级可接受。

### 2.3 验证标准

- 同设备二次登录踢出旧设备后：PG `sys_token_blacklist` 有旧 jti_at + jti_rt 两条 `DEVICE_KICKED` 记录；旧 login_record 置 REVOKED。
- Redis 故障降级路径行为不变（已有测试保持通过）。
- 新增 DeviceKickServiceTest（或并入现有测试）覆盖：kicked=true 落审计 / kicked=false 不落 / PG 审计异常不影响登录。
- `mvn.cmd test` 全过。

## 3. S2-3 THINKING_END 几乎不触发

### 3.1 问题实锤

- `SseEventTransformer.transformModelStreaming` 收到 reasoningContent 只发 THINKING；thinking→text 切换时（text 分支）**不补发 THINKING_END**；
- `transformModelFinished` 仅在 FINISHED 累积消息仍带 reasoningContent 时发 THINKING_END，而 qwen 思考模型流式 thinking/text 两阶段互斥（官方实证），FINISHED 时 reasoningContent 常缺 → THINKING_END 几乎不触发，前端永久停留「思考中」状态。

### 3.2 修复方案（锁定：进度文档「THINKING_END 补发必须做」）

`RunState` 增加两个标志（均 `AtomicBoolean`，`create()` 工厂同步初始化）：

- `thinkingSent`：本 run 是否已发过 THINKING 事件（收到 reasoningContent 时置 true）；
- `thinkingEndSent`：本 run 是否已发过 THINKING_END（CAS 去重）。

转换逻辑调整：

- `transformModelStreaming`：
  - reasoning 分支：发 THINKING + `thinkingSent.set(true)`（不变语义）；
  - text 分支：**先**判断 `thinkingSent.get() && thinkingEndSent.compareAndSet(false, true)` → 补发 THINKING_END（空 payload），**再**发 DELTA —— 保证「含 thinking 的流首条 DELTA 前必有 THINKING_END」。
- `transformModelFinished`：现有「累积消息带 reasoningContent → THINKING_END」保留，但改为 `thinkingEndSent.compareAndSet(false, true)` 去重（thinking 阶段已在流式时结束并补发过的场景不再重复）；若 FINISHED 发现 reasoningContent 且未发过 THINKING，先置 `thinkingSent` 再按 CAS 发 THINKING_END。
- 纯文本 run（无 reasoning）：`thinkingSent` 恒 false → 任何路径都不发 THINKING_END。

### 3.3 验证标准

- 含 thinking 的流：事件序为 THINKING* → THINKING_END → DELTA*，且 THINKING_END 恰好一次。
- 纯文本流：无 THINKING_END。
- FINISHED 去重：流式已补发后 FINISHED 不再重复发。
- SseEventTransformerTest 同步 RunState 构造变化 + 新增上述用例；`mvn.cmd test` 全过。

## 4. S3 CourseApiTool Tab 语义修正

### 4.1 问题实锤

- 权威定义（db-schema.md §8）：`course_content.content_type` ∈ **intro / syllabus / instructor / faq**（4 Tab，sort_order 0~3）；
- `CourseApiTool.java:94-95` 硬凑映射：`prerequisites = extractContent(contents, "instructor")`、`targetAudience = extractContent(contents, "faq")` —— instructor Tab 内容塞进 prerequisites、faq Tab 内容塞进 targetAudience，语义错乱；
- `CourseDetailResult` 无 intro/faq 对应字段。

### 4.2 修复方案（锁定：方案 1「改 DTO 迁就 DB」）

`CourseDetailResult` 四个 Tab 内容字段与 Tab 一一对应（删除 prerequisites/targetAudience 两个错位字段）：

- `String introContent` ← extractContent(contents, "intro")（原 description 字段位置）
- `String syllabusContent` ← extractContent(contents, "syllabus")
- `String instructorContent` ← extractContent(contents, "instructor")（原 prerequisites 位置）
- `String faqContent` ← extractContent(contents, "faq")（原 targetAudience 位置）

命名理由：四字段对称带 Content 后缀，与 `InstructorInfo`（讲师姓名/头衔/简介，来自 course_info.instructor_name 纯展示文本，保持不动）明确区分，避免同名歧义。

同步修改：

- `CourseApiTool.queryCourseDetail` 映射 + `emptyDetail` 构造（@Tool description 已兼容，无需改）；
- `CourseDetailResult` record 字段 + 注释；
- `docs/plans/2026-07-16-backend-design.md` §2.4 契约表格同步（description/syllabus/prerequisites/targetAudience → 四新字段）；
- `CourseApiToolTest` 同步（已确认 prompt 文件无 prerequisites/targetAudience 引用，无其他影响面）。

### 4.3 验证标准

- `queryCourseDetail` 返回：instructorContent 内容 = DB instructor Tab 内容、faqContent = DB faq Tab 内容（测试用固定 fixture 断言）；
- 全仓库 grep 无 prerequisites/targetAudience 残留（源码 + 测试 + 设计文档）；
- `mvn.cmd test` 全过。

## 5. 组件变更清单

| 文件 | 动作 |
|---|---|
| `backend/src/main/java/com/commerce/rag/controller/AuthController.java` | 修改 logout（§1） |
| `backend/src/main/java/com/commerce/rag/auth/TokenService.java` | 新增 parseClaimsLoose（§1） |
| `backend/src/main/java/com/commerce/rag/auth/AuthInterceptor.java` | token 提取逻辑抽公共方法（§1） |
| `backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java` | 新增 kickPgAudit（§2） |
| `backend/src/main/java/com/commerce/rag/stream/SseEventTransformer.java` | RunState 加双标志 + 转换逻辑（§3） |
| `backend/src/main/java/com/commerce/rag/bot/tool/CourseApiTool.java` | 映射修正（§4） |
| `backend/src/main/java/com/commerce/rag/bot/tool/dto/CourseDetailResult.java` | 字段重定义（§4） |
| `backend/src/test/java/com/commerce/rag/controller/AuthControllerTest.java` | 修改/新增（§1） |
| `backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java` | 新建（§2） |
| `backend/src/test/java/com/commerce/rag/stream/SseEventTransformerTest.java` | 修改/新增（§3） |
| `backend/src/test/java/com/commerce/rag/bot/tool/CourseApiToolTest.java` | 修改（§4） |
| `docs/plans/2026-07-16-backend-design.md` | §2.4 契约同步（§4） |

约束：注释/日志全中文；测试与实现同一次提交；因改动失效的旧测试直接删除；Entity 不出数据层、MapStruct 约定与本次改动无关。

## 6. 执行顺序与提交节奏

安全优先、每任务独立可验证、TDD（先写失败测试 → 最小实现 → 全量测试 → 提交）：

1. **S2-1** logout 吊销（P1 安全路径）→ 验证 §1.3 → 提交
2. **S2-2** kick PG 审计 → 验证 §2.3 → 提交
3. **S2-3** THINKING_END 补发 → 验证 §3.3 → 提交
4. **S3** Tab 语义修正 → 验证 §4.3 → 提交
5. 全部完成后 `cd backend && mvn.cmd test` 全量通过 = 本 spec 完成

## 7. 范围外遗留事项（待用户决策，本 spec 不覆盖）

1. **P0 四大问题均仍存在且不在本 spec**：权限机制断裂（SecurityConfig `anyRequest().permitAll()` 无 JWT→Security 桥接，@PreAuthorize 恒 403）、对话端点水平越权（cancel/reconnect 无归属校验）、教师越权（download/用户管理无归属校验）、消息持久化重复丢失锁死。**注意：P0 未清零前，全量测试通过 ≠ 干净基线**。修复顺序约束：权限桥接须与越权群同步（记忆已记录）。建议单独立项或并入后续 spec，请用户拍板。
2. **S5 教师/学生拆表**：核验为纯新增设计（无字段可迁），移出本 spec，留待单独 brainstorm。
3. 进度文档 `docs/progress/2026-08-14-多模态rag重构spec定稿.md` §2.2 的 S6 记录已过时（修复已在 HEAD），本 spec 批准实施后应同步更新进度文档。
