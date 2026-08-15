# P1 存储与 SSE 修复 — 设计规格（SSE 重连终态 / checkpoint 类型破坏 / Milvus 残留与 MinIO 孤儿对象）

> 状态：草稿待审。
> 决策来源：2026-08-14 对「待修复 bug 总清单」第二波 P1 三项的**源码逐条核验**（主 agent 直接核验全部涉及文件，含 SAA 1.1.2.0 jar 级实证，见 §2.1）。
> 本 spec 是总清单**第二波 P1**（第三波 P2/P3 另立 spec），依赖约束：三修复点相互独立，可分批实施，但同波交付。

## 0. 核验结论与范围

| 条目 | 核验结论 | 证据位置 |
|---|---|---|
| P1-2 SSE 断线重连无终态事件（run 已完成时重连，前端永久"生成中"） | 存在 | `ChatController.reconnect` :256-286（replay 失败→PG 回放成功→subscribe 静默失败→心跳保活）；`MemoryStreamBridge.subscribe` :69-76（ring==null 时 log.warn 后 return，emitter 不 complete） |
| P1-2 关联竞态 B5（replay 与 subscribe 之间窗口事件丢失） | 存在 | `ChatController` :256（replay）与 :275/:282（subscribe）两段之间无原子保障；chat() 端点 :173 为"先 subscribe 再入队"，reconnect 未采用同序 |
| P1-3 取消回滚 checkpoint 类型破坏（Jackson 反序列化丢失 Message 类型） | 存在 | `ChatRequestWorker.deepCopyState` :513-526（`readValue(json, Map.class)` 无多态注册）；`rollbackCheckpoint` :779-796（写入类型已毁 state）；下游 `persistMessages` :580 `instanceof Message` 静默跳过 → 消息不落库；`LeadAgentGraph.queryRewriteNode` :149-152 instanceof 全 false → 重写失效 |
| P1-4-1 reparse 后 Milvus 旧向量永久残留 | 存在 | `DocumentService.reparse` :241-269 软删旧 chunk 不调 Milvus；`EtlPipeline.deleteFromMilvusByDocId` :326-336 用 MP selectList（`DocumentChunk.java:120` `@TableLogic` 自动过滤 deleted=0）→ 只遍历到新 chunk |
| P1-4-2 课程删除未同步 Milvus delete | 存在 | `CourseService.deleteCourse` :218-263 级联软删 5 表全程无 Milvus 调用（类内未注入 EtlPipeline） |
| P1-4-3 MinIO 删除失败静默，DB 已删对象残留 | 存在 | `MinioStorageService.deleteFile` :104-112 吞异常仅 log.warn；`DocumentService.delete` :209-227 先软删 PG 后删 MinIO |
| P1-4-4 知识库删除不清理 MinIO 对象 | 存在 | `KnowledgeBaseService.delete` :148-177 只做 Milvus + PG 三表软删，未注入 MinioStorageService |
| P1-4-5 上传失败残留孤儿 PENDING 记录 | 存在 | `DocumentService.upload` :94-109 先 insert（PENDING）再 MinIO 上传，失败无补偿；updateById 失败则 MinIO 对象成孤儿 |

范围外（后续波次）：P2-1 ETL 幂等、P2-2 跨端契约（改后端迁就前端文档）、P2-3 403 契约统一、P3 观察项。

**DB 约定**（进度文档锁定）：本波无 DB 变更（P1-3 走代码修复，不动 checkpoint 表结构）。

## 1. P1-2 SSE 断线重连无终态事件（含 B5 竞态）

### 1.1 方案

**1.1.1 终态判定（主修复）**——`ChatController.reconnect` PG 降级回放成功后，查询 run 状态决定后续行为：

1. `replayFromPg` 返回值由 `boolean` 改为 `long`（最后回放的 seq；`-1` 表示 PG 无数据或回放失败——语义与现 `false` 分支完全对齐，调用处 `-1` 走现有 ERROR 分支）。
2. PG 回放成功（lastSeq ≥ 0）后，查 `chatRunService.findById(runId)`：
   - **status ∈ {COMPLETED, CANCELLED, ERROR}（终态）**：推送 `END` 事件（`SseEventType.END`，seq = lastSeq + 1，payload `{"runId":"<runId>","status":"<run.status>"}`，与 `ChatRequestWorker.handleCompleted/handleCancelled/handleError` 的 END payload 格式一致）→ `emitter.complete()` → 直接返回（**不 subscribe、不心跳**）。
   - **非终态（ACTIVE/QUEUED 等）**：走现有 `bridge.subscribe` + `startHeartbeat`（run 仍在执行，继续接收后续事件）。
3. `bridge.subscribe` 对 ring 不存在时的行为保持现状（log.warn + return，不抛异常）——终态判定后该路径已不会触发"永久生成中"。

**1.1.2 B5 竞态原子化（回放+订阅一体）**——`MemoryStreamBridge`：

1. `Ring` 新增私有锁 `stateLock`（Object monitor）：
   - `push`：`synchronized (stateLock)` 内完成「closed 检查 + buffer/fallback 写入 + head 递增」（纯内存操作，无 IO）；锁外对 subscribers 逐个 `sendEvent`（保持现状，IO 不持锁）。
   - 新增 `boolean replayAndSubscribe(long lastEventId, SseEmitter emitter)`：
     - `synchronized (stateLock)` 内：降级模式收集 fallback 中 `seqId > lastEventId` 的事件；正常模式校验 `lastEventId < oldestSeq` → 返回 false（ring 覆盖，需降级 PG），`lastEventId > head` → 空集合；收集 `(lastEventId, head]` 区间事件 → **注册 emitter（subscribers.add + onCompletion/onTimeout/onError 回调）**（无 IO）。
     - 锁外：向 emitter 发送收集的回放事件（send 失败 → 移除 emitter + 返回 false，与现 `replay` 语义一致）。
   - 正确性：push 与 replayAndSubscribe 共享 `stateLock`，回放区间 `(lastEventId, head]` 与注册原子完成；push 的新事件（head 之后）实时推送到已注册 emitter → **无丢失、无重复**（对比现"先 replay 后 subscribe"的窗口丢失）。
2. `MemoryStreamBridge` 新增 `boolean replayAndSubscribe(String runId, long lastEventId, SseEmitter emitter)`（ring == null → false，其余委托 Ring）。
3. `ChatController.reconnect` ring 回放分支改调 `bridge.replayAndSubscribe(runId, lastEventId, emitter)`：true → 心跳 + 返回；false → 走现有 PG 降级路径（含新的终态判定）。
4. **删除本次改动产生的死代码**（调用点已核验：`replay` 生产调用点仅 `ChatController:256`，本次替换后归零；`replayFrom` 生产零调用，纯设计文档命名兼容）：删除 `Ring.replay`、`MemoryStreamBridge.replay`、`MemoryStreamBridge.replayFrom`，回放区间逻辑并入 `replayAndSubscribe`（区间校验/降级遍历/范围检查语义不变）；`MemoryStreamBridgeTest` 的 5 个 replay 测试改写为 `replayAndSubscribe` 等价断言（覆盖边界/覆盖降级/ring 不存在场景），同步删除失效断言。

**1.1.3 明确接受的取舍**：PG 降级路径中 `replayFromPg` 与 `subscribe` 之间仍存在毫秒级窗口（该路径本身是 ring 覆盖后的尽力而为降级，事件已大量丢失，且终态场景已由 1.1.1 消除，非终态 + ring 覆盖 + 恰好窗口命中三条件叠加概率极低）——不为此引入额外复杂度，spec 记录在案。

### 1.2 验证标准

- `MemoryStreamBridgeTest` 新增：`replayAndSubscribe` 回放区间正确（lastEventId 边界：0 / 恰为最旧 seq / 超 head / 覆盖返回 false / ring 不存在返回 false）；**并发原子性**（多线程 push 与 replayAndSubscribe 同时进行，断言 emitter 收到的事件 seq 连续无重复、无缺失，且回放后新 push 事件实时到达）。
- `ChatControllerTest` 更新/新增：ring 回放成功分支调用 `replayAndSubscribe` 并启动心跳；**终态 reconnect**（PG 回放成功 + run.status=COMPLETED → emitter 收到 `end` 事件后 complete，不 subscribe 不心跳）；非终态 reconnect（run.status=ACTIVE → subscribe + 心跳）；`-1`（PG 无数据）分支保持 ERROR 事件。
- 全量 `mvn.cmd test` 通过。

## 2. P1-3 取消回滚 checkpoint 类型破坏

### 2.1 SAA 1.1.2.0 jar 级实证（本地仓库 `spring-ai-alibaba-graph-core-1.1.2.0.jar`）

1. `OverAllState.updateState(Map,Map,Map)` 字节码：`Stream.collect(toMapRemovingNulls(...))` → 合并结果收集为**新 Map**，不原地修改传入的 state Map。
2. `AppendStrategy.apply` 字节码：多处 `new ArrayList<>(Collection)` → messages 追加生成**新 List**，不原地修改旧 List。
3. `Checkpoint.updateState` 委托 `OverAllState.updateState` 并 `new Checkpoint(...)` → 每次 checkpoint 更新产生新对象。
4. 结论：**pre-run 捕获的 checkpoint state 在图执行期间不会被原地修改** —— 取消回滚无需深拷贝即可保证快照独立，且类型 100% 保留（项目注入的 Spring Boot 默认 ObjectMapper 无 Spring AI Message 多态注册，JSON 往返必然破坏类型，报告结论成立）。

### 2.2 方案

**容器级浅拷贝替代 JSON 深拷贝**（类型保留 + 零序列化风险 + 无 SAA API 耦合）：

1. `ChatRequestWorker.captureSnapshot`（:473-501）：`deepCopyState(cp.getState())` 改为 `new HashMap<>(cp.getState())`（顶层 Map 容器独立，值对象引用共享——SAA 实证值对象不可变更新，快照安全）。`historyMessageCount` 计算不变（从拷贝 Map 取 messages size）。
2. 删除 `deepCopyState` 方法（:503-526，本次改动产生的死代码）及其 `@SuppressWarnings("unchecked")` 说明。
3. `rollbackCheckpoint`（:779-796）逻辑不变（用 `snapshot.state()` 写新 checkpoint，新 id/ts，不删旧 checkpoint —— 符合设计文档 §3.4"恢复 pendingWrites"原始意图）。
4. `RunSnapshot` 注释更新："深拷贝副本（不可变副本）"改为"容器级拷贝（顶层 Map 独立，值对象引用共享；SAA 执行期不做原地修改，实证见 spec §2.1）"。
5. 取消路径整体行为不变：取消 → END(CANCELLED) 事件 → 状态 CANCELLED → 回滚 checkpoint。

### 2.3 验证标准

- `ChatRequestWorkerTest` 新增：构造含 `UserMessage`/`AssistantMessage`/`ToolResponseMessage` 的 checkpoint state → `captureSnapshot` → `rollbackCheckpoint` → 断言 `saver.put` 收到的 checkpoint state 中 messages 元素 `instanceof Message`（UserMessage/AssistantMessage/ToolResponseMessage 各自断言）；快照后修改原 state Map（put 新键）不影响快照内容。
- 真实环境验证（修复合入后手动跑一轮）：第 2 轮对话发起后取消 → 再发新消息 → 断言 PG `chat_message` 正常落库（无静默跳过）、无 ClassCastException、run 状态流转正常。
- 全量 `mvn.cmd test` 通过。

## 3. P1-4 Milvus 残留与 MinIO 孤儿对象（5 子项）

### 3.1 Bug 1：reparse 后 Milvus 旧向量残留

**方案**：`EtlPipeline.deleteFromMilvusByDocId`（:326-336）改为**按 Milvus 字段过滤一次删除**，不再查 PG chunk 表：

```java
// 按 doc_id 字段过滤直接删除（不依赖 PG 行，规避逻辑删除过滤漏删；单次 API 调用替代逐 chunk 删除）
String filter = "doc_id == \"" + docId + "\"";
DeleteReq deleteReq = DeleteReq.builder().collectionName(COLLECTION_NAME).filter(filter).build();
milvusClientV2.delete(deleteReq);
```

- Milvus v2 `DeleteReq.filter` 支持非主键字段过滤（`deleteFromMilvusByChunkId` :360-371 已有 `chunk_id == "..."` 先例）；`doc_id` 为 Milvus 现有字段（`insertToMilvus` :394 写入）。
- **异常处理（修正自相矛盾点）**：`deleteFromMilvusByDocId`/`deleteFromMilvusByKbId`/`deleteFromMilvusByCourseId` 的 Milvus 删除失败**上抛 RuntimeException**，阻断调用方 PG 软删（与 Bug 3 MinIO 处理一致的"失败可见可重试"哲学——若静默吞掉，PG 软删继续、Milvus 残留，正是本波要修的删除不一致）；`deleteFromMilvusByChunkId` 保持既有 catch（单 chunk 重向量化场景，删除失败后 insert 新向量覆盖，不阻断流程）。
- 覆盖链路：`reparse` 触发 ETL → `embedAndIndex`（:254）调用修复后的方法 → 旧向量（含已软删 chunk 的）被按 doc_id 清理 → 新向量插入，无残留。
- 同法修复 `deleteFromMilvusByKbId`（:341-353）：`kb_id == "<id>"` 过滤删除（`KnowledgeBaseService.delete` :156 与 `deleteCourse` 场景均受益）。

### 3.2 Bug 2：课程删除未同步 Milvus

**方案**：

1. `EtlPipeline` 新增 `public void deleteFromMilvusByCourseId(String courseId)`：filter `course_id == "<courseId>"` 删除（course_id 为 Milvus 现有 VARCHAR 字段，`insertToMilvus` :417 写入；过滤值格式与 `CourseService.deleteCourse` 软删 chunk 的 `courseIdStr` 一致）。
2. `CourseService` 注入 `EtlPipeline`（与 DocumentService/KnowledgeBaseService 注入模式一致），`deleteCourse` 在软删 document_chunk（:249-254）**之前**调用 `deleteFromMilvusByCourseId(courseIdStr)`（删除范围与 PG 软删范围对齐：按 course_id 过滤）。

### 3.3 Bug 3：MinIO 删除失败静默

**方案**：

1. `MinioStorageService.deleteFile`（:104-112）：删除失败由 `log.warn` 吞异常改为抛 `RuntimeException`（与 `uploadFile` :77-79 风格一致），日志保留 error 级别。
2. `DocumentService.delete`（:200-230）调整顺序：**先 MinIO 删除（失败上抛阻断，PG 记录保留可重试）→ 再软删 PG chunk/doc**。
   - 收敛性：MinIO `removeObject` 对不存在对象幂等成功（S3 DELETE 语义）→ 任一侧先失败重试均可收敛到"对象已删 + 记录已删"。
   - 异常出口：`RuntimeException` → `GlobalExceptionHandler.handleException`（:73-77）返回 500（MinIO 不可用属基础设施故障，500 合理，本波不动契约）。

### 3.4 Bug 4：知识库删除不清理 MinIO 对象

**方案**：

1. `KnowledgeBaseService` 注入 `MinioStorageService`。
2. `delete`（:148-177）在软删 document（:164-168）**之前**补充：查 KB 下全部未删 document 的 `source_path`（`select(Document::getSourcePath).eq(kbId)`，MP 自动过滤 deleted=0）→ 逐个 `deleteFile`（失败上抛阻断，同 3.3 收敛语义）→ 再走现有 Milvus + PG 软删流程。

### 3.5 Bug 5：上传失败残留孤儿 PENDING 记录

**方案（用户 2026-08-15 裁决：objectKey 改 `{kbId}/{uuid}.{ext}`，uuid 预生成去横线；DB 记录照常自动生成 ID；所有类似"先需要外部资源 key 再落库"的创建一律用 uuid 先行，禁止先插 DB 拿 ID）**：

`DocumentService.upload`（:75-117）流程重排为「uuid 预生成 → 先传 MinIO → 再插 DB」：

```java
// ① 预生成 uuid（去横线，32 位 hex）作 objectKey 标识——不依赖 docId，
//    DB 记录 id 仍由 MP 自动生成（ASSIGN_ID 雪花）
String uuid = UUID.randomUUID().toString().replace("-", "");
// ② 先传 MinIO（objectKey = {kbId}/{uuid}.{ext}；失败上抛 → DB 无任何残留，
//    PUT 是对象级原子写入：失败即对象不存在，响应丢失场景重试同 key 覆盖，均可收敛）
String objectKey = minioStorageService.uploadFile(kbId, uuid, inputStream, fileType);
// ③ 再插 DB（id 自动生成，sourcePath 一步带入，不再有第二步 updateById）
doc.setSourcePath(objectKey);
try {
    documentMapper.insert(doc);
} catch (Exception e) {
    // 单向补偿：唯一可能残留的方向是「MinIO 已传、DB 未落」→ 删 MinIO 对象（幂等）后上抛
    minioStorageService.deleteFile(objectKey);
    throw e;
}
```

- 配套签名调整：`MinioStorageService.uploadFile(Long kbId, Long docId, InputStream, String ext)` → `(Long kbId, String uuid, InputStream, String ext)`，内部 `buildObjectKey(kbId, uuid, ext)` 产出 `{kbId}/{uuid}.{ext}`。
- 兼容性核验：`uploadFile` 生产调用点唯一（`DocumentService:107`）；路径消费全部读 DB 字段 `doc.getSourcePath()`（DocumentService :226/:293、EtlPipeline :130），**无任何代码按 `{kbId}/{docId}.{ext}` 反推路径** → 格式变更不影响存量对象（旧路径仍在 DB，照常下载/删除）。
- 对比原方案收益：双存储一致性从「三步两补偿」降为「两步一补偿」；DB 记录一步到位（要么完整含 sourcePath，要么不存在），PENDING 垃圾行窗口彻底消失；objectKey 与业务主键解耦，先占外部资源再落库，失败仅需单向回收。

### 3.6 验证标准

- `EtlPipelineTest` 更新：`deleteFromMilvusByDocId`/`deleteFromMilvusByKbId` 改为断言 `milvusClientV2.delete` 收到 `doc_id == "..."`/`kb_id == "..."` filter（不再断言 chunk 查询）；新增 `deleteFromMilvusByCourseId` 断言。
- `CourseServiceTest` 新增：`deleteCourse` 调用 `deleteFromMilvusByCourseId(courseIdStr)`，且调用先于 chunk 软删。
- `MinioStorageServiceTest` 更新：`deleteFile_failure_silent` 改为断言抛异常（改名 `deleteFile_failure_throws`）。
- `DocumentServiceTest` 新增/更新：`delete` 顺序（MinIO 删除先于 PG 软删；MinIO 失败 → 抛出且 PG 未软删）；`upload` 新流程（uuid 格式断言：objectKey = `{kbId}/{32位hex}.{ext}`；MinIO 失败 → insert 不被调用、无残留；insert 失败 → deleteFile 单向补偿 + 抛出；insert 记录含 sourcePath、id 由 MP 自动生成，无第二步 updateById）。
- `KnowledgeBaseServiceTest` 更新：`delete_cascadeAll` 补充 MinIO 对象删除断言（查 source_path → 逐个 deleteFile）。
- 集成验证（修复合入后手动）：已 INDEXED 文档点"重新解析" → 完成后 Milvus 检索该 doc 无旧向量残留（query 抽查）；删除课程 → 按 course_id 检索无命中。
- 全量 `mvn.cmd test` 通过。

## 4. 受影响测试清单

**本次改动失效需同步修改**（同一提交内，禁留过渡）：
- `EtlPipelineTest.deleteFromMilvusByDocId_deletesAllChunks` / `deleteFromMilvusByKbId_deletesAllChunks`（断言从"查 chunk 逐个删"改为"filter 一次删"）
- `MinioStorageServiceTest.deleteFile_failure_silent`（断言从"不抛异常"改为"抛异常"）
- `ChatControllerTest.reconnect_replaySuccess_subscribesAndReturnsEmitter` / `reconnect_replayFailure_doesNotSubscribe`（replay 调用改为 replayAndSubscribe）
- `KnowledgeBaseServiceTest.delete_cascadeAll`（补充 MinIO 删除断言）
- `MemoryStreamBridgeTest` 的 5 个 replay 测试（`replay` 方法删除，改写为 `replayAndSubscribe` 等价断言）

**本次改动新增测试**（与实现同次提交）：见 §1.2 / §2.3 / §3.6 各验证标准。

## 5. 明确取舍与范围外

- P1-2 PG 降级路径的 subscribe 竞态：接受（见 §1.1.3）。
- P1-3 不动 PostgresSaver/SAA 序列化机制（类型破坏根因在应用侧 JSON 深拷贝，SAA 自身序列化保类型，已实证）。
- P1-4 MinIO 删除失败返回 500（异常上抛）而非补偿队列/重试任务——简单优先（AGENTS.md §7.2），removeObject 幂等 + 用户重试即可收敛，补偿队列留待 P3 观察项按需引入。
- 本波不触碰：P2-1 ETL 状态机/幂等、P2-2 契约对齐、P2-3 403 契约统一、P3 死代码清单中非本次改动引入的项（如 `SseEvent.toSseText`、`runSnapshot` 只写不读等，如与本次改动产生关联则顺带处理并在 spec 标注）。
