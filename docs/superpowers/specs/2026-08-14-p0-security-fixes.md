# P0 安全修复 — 设计规格（权限桥接 + 越权集群 + 对话 IDOR + 消息持久化）

> 状态：草稿待审。
> 决策来源：2026-08-14 对「待修复 bug 总清单」第一波 P0 四项的**分域源码核验**（4 并行子 agent + 主 agent 抽查复核，全部逐条实锤存在），修复方案沿用各 bug 报告「修复方向」+ 本项目既有代码模式（checkOwnership / isAdmin 旁路）。
> 本 spec 是总清单**第一波 P0**（后续 P1/P2/P3 波次另立），依赖约束：权限桥接与越权修复必须同波完成。

## 0. 核验结论与范围

| 条目 | 核验结论 |
|---|---|
| P0-1 权限机制断裂（@PreAuthorize 恒失败） | 存在（SecurityConfig 全 permitAll、AuthInterceptor 只 setAttribute、全库无 SecurityContext 写入；12 控制器 24 处 @PreAuthorize） |
| P0-2 教师越权集群 8 子项 | 8/8 存在 |
| P0-3 对话端点 IDOR（chat/cancel/reconnect） | 3/3 存在（chat_run/chat_session 均有 user_id 列可校验） |
| P0-4 消息持久化三缺陷 | 3/3 存在（persistMessages 全量重插 / blockLast catch 无 ERROR 事件 / XADD 无 catch + 唯一索引锁死） |

范围外（后续波次）：P1-2 SSE 重连终态、P1-3 checkpoint 类型破坏、P1-4 Milvus/MinIO、P2-1 ETL、P2-2 契约、P3 观察项。

**DB 约定**（进度文档锁定）：开发阶段直接改 `V6__full_schema_v5.sql` 并 drop 重建，不新增迁移文件。

## 1. P0-1 权限桥接（JWT → SecurityContext）

### 1.1 方案

不改 SecurityConfig 的 permitAll 架构（HTTP 层放行、鉴权由 AuthInterceptor 统一处理是既有设计），补上缺失的桥接环节：

1. `AuthInterceptor.preHandle` 校验通过后写入 `SecurityContextHolder`：
   ```java
   // 权限桥接：将 JWT 鉴权结果写入 Spring Security 上下文，
   // 供 @PreAuthorize 方法级鉴权读取（hasAnyRole 自动补 ROLE_ 前缀）
   SecurityContextHolder.getContext().setAuthentication(
           new UsernamePasswordAuthenticationToken(
                   userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
   ```
   （执行顺序保证：HandlerInterceptor.preHandle 在 DispatcherServlet 内、controller 方法调用前执行，早于方法安全 AOP 拦截器，SecurityContext 在 ThreadLocal 模式请求线程一致。）
2. `AuthInterceptor.afterCompletion` 调用 `SecurityContextHolder.clearContext()`（防 Tomcat 线程池复用污染；afterCompletion 异常时也执行）。
3. `GlobalExceptionHandler` 新增 `@ExceptionHandler(AccessDeniedException.class)`（org.springframework.security.access）→ 返回 403 的 `ApiResponse`（当前该异常落入通用 Exception handler 会错误返回 500）。

### 1.2 验证标准

- 新增 `@WebMvcTest` 切片集成测试（@Import SecurityConfig+AuthConfig，mock TokenService/DeviceKickService，测试用 @RestController 带 @PreAuthorize("hasAnyRole('SUPER_ADMIN','TEACHER')")）：TEACHER 携带合法 token → 200；STUDENT → 403；无 token → 401；响应后 SecurityContext 已清理（afterCompletion 生效）。
- AuthInterceptorTest 补用例：校验通过后 SecurityContextHolder.getAuthentication() 非空且 authorities 含 ROLE_{role}；afterCompletion 后为空。
- 全量 `mvn.cmd test` 通过。

## 2. P0-2 教师越权集群（8 子项）

统一模式：**checkOwnership（created_by 校验，isAdmin 旁路）**，与 DocumentService.delete/reparse 现有模式一致；不匹配抛 `ResponseStatusException(403)` 或返回 null 由 controller 404。

### 2.1 a. 文档改名越权

- `DocumentService.update` 签名改 `update(Long id, String title, Long operatorId, boolean isAdmin)`，`selectById` 后调 `checkOwnership(doc, operatorId, isAdmin)`；
- `AdminDocumentController.update`（:93-98）传 `isAdmin`（与 delete/reparse 端点同款取法）。

### 2.2 b. 文档下载越权

- `DocumentService.download` 签名改 `download(Long id, Long operatorId, boolean isAdmin)`，调 `checkOwnership`；
- `AdminDocumentController.download`（:119-130）加 `HttpServletRequest`，取 userId/isAdmin 传入。

### 2.3 c. 跨知识库上传

- `DocumentService.upload` 签名加 `boolean isAdmin`；kb 存在校验后加归属校验：
  ```java
  // 非超管只能上传到自己创建的知识库（knowledge_base.created_by）
  if (!isAdmin && (kb.getCreatedBy() == null || !kb.getCreatedBy().equals(createdBy))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权向此知识库上传文档");
  }
  ```
- controller 端点同步传参。

### 2.4 d. 教师操作任意学生 + sys_user 无 created_by 列

- V6 `sys_user` 加列 `created_by BIGINT`（可空，注释：创建者用户 ID；超管/种子用户为 NULL）；`SysUser` 实体加 `createdBy` 字段；
- `SysUserService.create` 落库时 `user.setCreatedBy(createdBy)`；
- `checkTeacherPermission` 教师分支补归属校验：
  ```java
  // 教师只能操作自己创建的学生（created_by 归属）
  if (!currentUserId.equals(targetUser.getCreatedBy())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "教师只能操作自己创建的学生");
  }
  ```
  （置入现有"教师只能操作学生"角色检查之后）

### 2.5 e. 创建用户无角色限制

- `CreateUserRequest.role` 加 `@Pattern(regexp = "SUPER_ADMIN|TEACHER|STUDENT", message = "角色取值非法")`；
- `SysUserService.create` 签名加 `String operatorRole`：TEACHER 只能创建 STUDENT（`request.role` 非 STUDENT → 403）；SUPER_ADMIN 不受限（超管唯一性已有）；controller 传 `AuthInterceptor.getCurrentRole(request)`。

### 2.6 f. 用户列表/详情教师无过滤

- `SysUserService.findPage` 签名加 `(Long currentUserId, String operatorRole)`：TEACHER 加 `wrapper.eq(SysUser::getCreatedBy, currentUserId)`；
- `SysUserService.findById` 签名加 `(Long currentUserId, String operatorRole)`：TEACHER 且 `target.getCreatedBy() != currentUserId` → 返回 null（controller 404）；
- `AdminUserController.list/get` 传参。

### 2.7 g. 课程详情无归属校验

- `CourseService` 新增重载 `findById(Long id, Long createdByFilter)`（filter 为 null 不过滤，TEACHER 传 userId）或按现有 `findPage` 模式加过滤参数；不匹配返回 null；
- `AdminCourseController.detail` 加 `HttpServletRequest`，取 userId/role，TEACHER 传 `createdBy=userId`；null → 404（现有逻辑）。

### 2.8 h. 反馈无归属 + user_feedback 无 user_id 列

- V6 `user_feedback` 加列 `user_id BIGINT NOT NULL`；唯一索引由 `(session_id, message_id)` 改为 `(user_id, message_id) WHERE deleted = 0`（索引名同步调整，session_id 保留作统计列）；
- `UserFeedback` 实体加 `userId`；
- `UserFeedbackService.create` 签名加 `Long userId`（新增与更新查询条件均含 user_id，杜绝跨用户改他人反馈）；类注释同步；
- `FeedbackController.create` 取 `AuthInterceptor.getCurrentUserId(request)` 传入（request 参数由未使用变为实际使用）。

## 3. P0-3 对话端点水平越权（IDOR）

统一模式：**归属校验不匹配 → 404/403，不泄露存在性**。run/session 表均有 user_id 列，直接比对。

1. **chat**：`sessionId != null` 时校验会话归属——`ChatSessionService` 补 `findById(Long id)`（无则新增），`session == null || !session.getUserId().equals(userId)` → 403（"无权操作此会话"）；
2. **cancel**：`@PathVariable String runId` 加 `HttpServletRequest` 取 userId；runId parse 失败 → 404；`chatRunService.findById(runId)` 为 null 或 `run.getUserId() != userId` → 404（不泄露存在性）；校验通过才 `worker.cancel(runId)`；
3. **reconnect**：同 cancel 的归属校验（在 replay 之前执行）；
4. 服务层防御：`ChatRunService` 新增 `findByIdAndUser(Long runId, Long userId)`（或 controller 用 findById 后比对——按现有 findById 模式，controller 比对即可，服务层不重复加方法，保持最小改动）。

## 4. P0-4 消息持久化三缺陷

### 4.1 a. 多轮全量历史重复持久化

**游标方案**（利用现有 pre-run 快照）：

- `RunSnapshot` 增加游标信息：`captureSnapshot` 成功且 `stateCopy` 含 "messages" 时记录 `int historyMessageCount = ((List<?>) stateCopy.get("messages")).size()`；snapshot 为 null（无 checkpoint）时游标 = 0（首轮全量，与现行为一致）；
- `persistMessages` 签名加 `int historyCursor`：遍历 rawList 时改为 `for (int i = historyCursor; i < rawList.size(); i++)`，仅转换本轮新增消息；
- 边界：rawList 元素非 Message（checkpoint 反序列化产物）跳过逻辑保持；`historyCursor > rawList.size()` 防御为不转换任何消息。

### 4.2 b. blockLast 超时/同步异常

- `catch (Exception e)` 分支补齐与 `onErrorResume` 对齐的收尾：
  ```java
  // 补齐终态：推送 ERROR 事件 + 持久化已收集消息（与 onErrorResume 分支对齐）
  handleError(runIdStr, runId, runState, e);
  persistMessages(runId, sessionId, userQuery, historyCursor, lastOutput.get());
  cacheFinalResult(runId, lastOutput.get());
  ```
  （`handleError` 已有实现：push ERROR 事件 + updateStatus(ERROR)；errored.set(true) 保留防 doOnComplete 覆盖）

### 4.3 c. XADD 失败锁死

- `ChatController.chat` 的 XADD 包 try/catch：
  ```java
  try {
      redisTemplate.opsForStream().add(streamProperties.requestStream(), message);
  } catch (Exception e) {
      // 入队失败：回滚 run 状态（解除 uniq_active_run_per_session 唯一索引锁死）+ 清理 ring
      log.error("XADD 入队失败，回滚 run: runId={}", runId, e);
      chatRunService.updateStatus(run.getId(), "ERROR");
      bridge.removeRing(runId);
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "消息队列暂不可用，请稍后重试");
  }
  ```

## 5. DB 变更（V6 直接改 + drop 重建）

| 变更 | 内容 |
|---|---|
| `sys_user` | 加列 `created_by BIGINT`（可空，注释：创建者用户 ID） |
| `user_feedback` | 加列 `user_id BIGINT NOT NULL`；唯一索引 `(session_id, message_id)` → `(user_id, message_id) WHERE deleted = 0` |

## 6. 组件变更清单

| 文件 | 动作 |
|---|---|
| `auth/AuthInterceptor.java` | SecurityContext 写入 + afterCompletion 清理 |
| `common/exception/GlobalExceptionHandler.java` | 新增 AccessDeniedException → 403 |
| `service/DocumentService.java` | update/download/upload 归属校验 |
| `controller/AdminDocumentController.java` | 三端点传 isAdmin/userId |
| `service/SysUserService.java` | create/findById/findPage/checkTeacherPermission |
| `controller/AdminUserController.java` | list/get/create 传参 |
| `controller/dto/CreateUserRequest.java` | role @Pattern |
| `entity/SysUser.java` | createdBy 字段 |
| `entity/UserFeedback.java` | userId 字段 |
| `service/UserFeedbackService.java` | create 加 userId |
| `controller/FeedbackController.java` | 取当前登录用户 |
| `controller/AdminCourseController.java` | detail 归属 |
| `service/CourseService.java` | findById 过滤重载 |
| `controller/ChatController.java` | chat/cancel/reconnect 归属 + XADD catch |
| `service/ChatSessionService.java` | 补 findById（若无） |
| `worker/ChatRequestWorker.java` | RunSnapshot 游标 + persistMessages 游标 + catch 补齐 |
| `resources/db/migration/V6__full_schema_v5.sql` | 两表列/索引变更 |
| 测试（修改/新建） | AuthInterceptorTest、DocumentServiceTest（若无则建）、SysUserServiceTest（若无则建）、AdminUserControllerTest（若无则建）、UserFeedbackServiceTest（若无则建）、CourseServiceTest（若无则建）、ChatControllerTest、ChatRequestWorkerTest、新 @WebMvcTest 权限桥接集成测试 |

约束：注释/日志全中文；测试与实现同次提交；因改动失效的旧测试直接删除；Entity 不出数据层（`UserFeedback` 现作为 controller 返回体是历史遗留——本 spec 不扩大改动面，仅加字段，是否改 VO 另行决策）。

## 7. 任务划分与执行顺序（每任务 TDD + 独立提交）

1. **P0-1 权限桥接**（先修：桥接修复后 @PreAuthorize 立即生效，但其后各任务同步补越权校验，本波内闭环）
2. **P0-2 文档权限**（a/b/c 三子项，同文件 DocumentService 集中）
3. **P0-2 用户管理**（d/e/f + V6 sys_user.created_by）
4. **P0-2 课程详情**（g）
5. **P0-2 反馈归属**（h + V6 user_feedback.user_id）
6. **P0-3 对话 IDOR**（chat/cancel/reconnect）
7. **P0-4 消息持久化**（游标去重 + catch 补齐 + XADD 回滚）

顺序说明：任务 2-5 为 P0-2 拆分（每任务独立可验证）；任务 1 与 2-5 构成清单要求的"桥接与越权同波"闭环；任务 7 最后（改动面最大、与取消/checkpoint 交互最多）。

## 8. 验证标准（全波完成后）

- 全量 `mvn.cmd test` 通过；新增/修改测试覆盖每子项的正常/边界/异常场景；
- 手工验证清单（可选，开发环境）：TEACHER 登录后 admin 接口按角色可用、STUDENT 调 admin 接口 403、跨用户 runId 访问 404、登出+重启后权限不残留；
- DB drop 重建后 Flyway 迁移成功（两表新列存在）；
- 因改动失效的旧测试已删除，无死代码残留。

## 9. 范围外（待后续波次）

P1-2/P1-3/P1-4、P2-1/P2-2、P3 观察项 8 项（除已修复的 P3-1）——核验结论已记录，波次顺序沿用总清单三波规划。其中 P2-2 契约裁决方向（改后端/改文档）与 P3 若干权衡项（fail-open 方向、Ring 背压）待对应波次时逐项协商。
