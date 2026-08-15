# 对话端点水平越权：chat/cancel/reconnect 均无 runId/sessionId 归属校验

- **风险类别**：权限绕过（IDOR，跨用户数据读写 + 拒绝服务）
- **严重度**：P0（已登录任意用户可触发）
- **变更范围**：未提交工作区全部新增代码

## 证据

1. `backend/src/main/java/com/commerce/rag/controller/ChatController.java:151-158`（chat 接受任意 sessionId）：
   ```java
   Long sessionId = request.sessionId();
   if (sessionId == null) { ... }
   // 直接使用客户端传入的 sessionId
   ChatRun run = chatRunService.createRun(sessionId, userId);
   ```
   `ChatRunService.createRun`（`ChatRunService.java:45-60`）不校验 `chat_session.user_id == userId`。

2. `ChatController.java:197-202`（cancel 无归属校验）：
   ```java
   @PostMapping("/{runId}/cancel")
   public ResponseEntity<Void> cancel(@PathVariable String runId) {
       worker.cancel(runId);   // 无任何 userId 校验
   ```

3. `ChatController.java:221-257`（reconnect 无归属校验）：
   ```java
   boolean success = bridge.replay(runId, lastEventId, emitter);   // ring 全局按 runId 索引
   ...
   boolean pgOk = replayFromPg(runId, lastEventId, emitter);       // 读取任意 run 的 chat_message
   ```
   `ChatMessageService.findByRunId`（`ChatMessageService.java:81-86`）仅按 runId 查询，无 userId 过滤。

4. `ChatController.java:64-66`：ChatController 是唯一**没有** `@PreAuthorize` 的控制器，仅有"已登录"拦截器（AuthInterceptor），不校验"本人"。

## 触发路径与影响

- **越权读**：任意已登录用户枚举/猜测雪花 runId → `GET /api/v1/student/chat/{runId}/reconnect` 回放他人对话全文（含 thinking、工具调用明细）——跨用户对话内容泄露。
- **越权写/污染**：请求体传他人 sessionId → run 挂在他人会话下，Worker 把消息写入他人 `chat_message`、更新他人 `last_message_at`，并可能触发他人会话的并发 run 冲突（`ConcurrentRunException`）。
- **拒绝服务**：任意已登录用户可取消他人正在执行的 run（`worker.cancel(runId)`），配合消息重复/丢失问题造成他人数据缺失。

## 建议修复方向

chat/cancel/reconnect 三端点统一校验：run 必须属于当前登录用户（查 chat_run.user_id 或 chat_session.user_id），不匹配返回 403/404。
