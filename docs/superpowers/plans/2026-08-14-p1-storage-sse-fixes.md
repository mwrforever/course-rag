# P1 存储与 SSE 修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复第二波 P1 三项——SSE 断线重连无终态（含回放竞态）、取消回滚 checkpoint 类型破坏、Milvus/MinIO 删除链路不一致（5 子项），全量测试通过。

**Architecture:** 三个修复点相互独立、按文件域分 8 个任务。SSE 侧：`MemoryStreamBridge.Ring` 内做"回放+订阅"锁内原子化 + `ChatController.reconnect` 补终态判定；checkpoint 侧：取消回滚改容器级浅拷贝（SAA 实证不可变更新）；存储侧：Milvus 删除改 filter 直删（不查 PG）、MinIO 删除失败上抛 + 调序、upload 改 uuid 先行单向补偿。

**Tech Stack:** Spring Boot 3.5.8 / Spring AI 1.1.2 / SAA 1.1.2.0 / MyBatis-Plus 3.5.12 / Milvus SDK 2.6.11 / JUnit5 + Mockito。

## Global Constraints

- 注释/日志/文档全中文；UTF-8 无 BOM；LF 行尾；文件末尾保留换行
- 测试与实现**同一次提交**；因本次改动失效的旧测试**同提交改写或删除**，禁止留过渡
- git add 只加任务明确列出的文件，**禁 `git add -A`**（工作区有历史遗留无关改动）；Windows 用 `mvn.cmd`
- 分层约束：service 层可注入 `EtlPipeline`/`MinioStorageService`（与 DocumentService/KnowledgeBaseService 现有模式一致），禁止跨层
- 死代码零容忍：本次改动产生的废弃方法/测试必须同提交清理
- 每个任务完成后跑 `cd backend && mvn.cmd test -Dtest=<XxxTest>`，全部任务完成后跑全量 `mvn.cmd test`（当前基线 219/219）

---

### Task 1: MemoryStreamBridge 原子化回放+订阅（replayAndSubscribe）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/stream/MemoryStreamBridge.java`（Ring 内部类 :123-249）
- Test: `backend/src/test/java/com/commerce/rag/stream/MemoryStreamBridgeTest.java`

**Interfaces:**
- Produces: `MemoryStreamBridge.replayAndSubscribe(String runId, long lastEventId, SseEmitter emitter)` → `boolean`（true=回放成功且已注册订阅者；false=ring 不存在或 lastEventId 已被覆盖）
- Consumes: 现有 `SseEvent` record（`type/seqId/payload/timestamp`）、`Ring` 内部结构（`buffer/fallback/head/subscribers/closed`）

- [ ] **Step 1: 新增测试**（MemoryStreamBridgeTest 追加两个测试；现有 replay 测试本任务不动）

```java
// ==================== replayAndSubscribe 测试 ====================

@Test
@DisplayName("replayAndSubscribe — 回放 (lastEventId, head] 区间并注册订阅者，新事件实时到达")
void replayAndSubscribe_replaysAndRegisters() throws Exception {
    // Given
    SseEmitter mockEmitter = mock(SseEmitter.class);
    bridge.createRing("run1");
    bridge.push("run1", event(0));
    bridge.push("run1", event(1));
    bridge.push("run1", event(2));

    // When: lastEventId=0 → 回放 seqId=1,2
    boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);

    // Then: 回放 2 个事件
    assertTrue(result);
    verify(mockEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    // 注册后新事件实时推送（第 3 次 send）
    bridge.push("run1", event(3));
    verify(mockEmitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));
}

@Test
@DisplayName("replayAndSubscribe 边界 — lastEventId 超出 head：回放空事件但注册成功")
void replayAndSubscribe_lastEventIdBeyondHead_registersOnly() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    bridge.createRing("run1");
    bridge.push("run1", event(0));

    boolean result = bridge.replayAndSubscribe("run1", 100, mockEmitter);

    assertTrue(result);
    verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    // 注册生效：新事件实时到达
    bridge.push("run1", event(1));
    verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
}

@Test
@DisplayName("replayAndSubscribe 覆盖 — lastEventId 太旧返回 false 且不注册")
void replayAndSubscribe_tooOld_returnsFalseAndNotRegister() throws Exception {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    bridge.createRing("run1");
    for (int i = 0; i < BUFFER_SIZE + 1; i++) {
        bridge.push("run1", event(i));
    }

    boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);

    assertFalse(result);
    verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    // 未注册：后续 push 不送达
    bridge.push("run1", event(BUFFER_SIZE + 1));
    verify(mockEmitter, never()).send(any(SseEmitter.SseEventBuilder.class));
}

@Test
@DisplayName("replayAndSubscribe 不存在的 ring — 返回 false")
void replayAndSubscribe_ringNotExist_returnsFalse() {
    SseEmitter mockEmitter = mock(SseEmitter.class);
    boolean result = bridge.replayAndSubscribe("nonexistent", 0, mockEmitter);
    assertFalse(result);
}

@Test
@DisplayName("replayAndSubscribe 并发 — 与 push 并发执行，emitter 收到事件总数不丢不重")
void replayAndSubscribe_concurrentWithPush_noLossNoDuplicate() throws Exception {
    // Given
    SseEmitter mockEmitter = mock(SseEmitter.class);
    bridge.createRing("run1");
    int total = 200;
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> pushError = new AtomicReference<>();
    Thread pusher = new Thread(() -> {
        try {
            start.await();
            for (int i = 0; i < total; i++) {
                bridge.push("run1", event(i));
            }
        } catch (Throwable t) {
            pushError.set(t);
        }
    });
    pusher.start();
    start.countDown();

    // When: 与 push 并发重连（lastEventId=0 回放全部已推送事件）
    boolean result = bridge.replayAndSubscribe("run1", 0, mockEmitter);
    pusher.join(5000);

    // Then: 回放区间 (0, head@锁内] 与注册后实时区间互斥覆盖全部事件 → 总数恰为 total
    assertTrue(result);
    assertNull(pushError.get());
    verify(mockEmitter, times(total)).send(any(SseEmitter.SseEventBuilder.class));
}
```

新增 import：`java.util.concurrent.CountDownLatch`、`java.util.concurrent.atomic.AtomicReference`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=MemoryStreamBridgeTest`
Expected: FAIL——`replayAndSubscribe` 方法不存在（编译错误）

- [ ] **Step 3: 实现**

`MemoryStreamBridge.java` Ring 内部类改动：

```java
// Ring 字段新增：
/** 回放/订阅与 push 写入共享的锁（回放区间与注册原子，保证不丢不重） */
private final Object stateLock = new Object();

void push(SseEvent event) {
    // buffer 写入与 head 递增在锁内（纯内存操作，无 IO）；send 在锁外（IO 不持锁）
    synchronized (stateLock) {
        if (closed) return;
        if (fallback != null) {
            fallback.offer(event);
        } else {
            long idx = head.getAndIncrement();
            int slot = (int) (idx % capacity);
            buffer[slot] = event;
        }
    }
    // 推送给所有订阅者（CopyOnWriteArrayList 线程安全）
    for (SseEmitter emitter : subscribers) {
        sendEvent(emitter, event);
    }
}

/**
 * 原子「回放 + 订阅」：锁内收集回放区间事件并注册 emitter，锁外发送回放事件。
 *
 * <p>正确性：回放区间 (lastEventId, head@锁内] 与注册在同一临界区完成；
 * 锁内注册后 push 的新事件（head 之后）实时推送到已注册 emitter →
 * 并发下无丢失、无重复（对比旧的 replay+subscribe 两步之间的窗口丢失）。
 *
 * @param lastEventId 客户端最后收到的 eventId
 * @param emitter     重连的 SSE 订阅者
 * @return true=回放成功且已注册；false=lastEventId 已被覆盖（需降级查 PG）
 */
boolean replayAndSubscribe(long lastEventId, SseEmitter emitter) {
    List<SseEvent> replayEvents;
    synchronized (stateLock) {
        if (closed) {
            return false;
        }
        if (fallback != null) {
            // 降级路径：遍历 queue（O(n)，降级场景可接受）
            replayEvents = new ArrayList<>();
            for (SseEvent event : fallback) {
                if (event.seqId() > lastEventId) {
                    replayEvents.add(event);
                }
            }
        } else {
            long currentHead = head.get();
            long oldestSeq = Math.max(0, currentHead - capacity);
            if (lastEventId < oldestSeq) {
                // lastEventId 太旧，ring buffer 已覆盖 → 需降级查 PG
                log.warn("replayAndSubscribe 失败 runId={}: lastEventId={} < oldestSeq={}",
                        runId, lastEventId, oldestSeq);
                return false;
            }
            replayEvents = new ArrayList<>();
            if (lastEventId <= currentHead) {
                for (long seq = lastEventId + 1; seq <= currentHead; seq++) {
                    int slot = (int) (seq % capacity);
                    SseEvent event = buffer[slot];
                    if (event != null && event.seqId() == seq) {
                        replayEvents.add(event);
                    }
                }
            }
        }
        // 锁内注册：回放区间与注册原子；后续 push 实时推送（CopyOnWriteArrayList 遍历）
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
    }
    // 锁外发送回放事件（IO 不持锁）；回放事件 seq ≤ head@锁内，实时事件 seq > head@锁内，无重复
    for (SseEvent event : replayEvents) {
        if (!sendEvent(emitter, event)) {
            return false;
        }
    }
    return true;
}
```

`MemoryStreamBridge` 公共 API 新增（放在 `subscribe` 之后）：

```java
/**
 * 原子「回放 + 订阅」——断线重连主路径（P1-2 B5 竞态修复）。
 *
 * <p>回放 lastEventId 之后的事件并注册 emitter，与 push 并发下不丢不重。
 *
 * @param runId       Run 唯一标识
 * @param lastEventId 客户端最后收到的 eventId
 * @param emitter     SSE 订阅者
 * @return true=回放成功且已注册；false=ring 不存在或 lastEventId 已被覆盖（需降级查 PG）
 */
public boolean replayAndSubscribe(String runId, long lastEventId, SseEmitter emitter) {
    Ring ring = rings.get(runId);
    if (ring == null) {
        log.warn("replayAndSubscribe 失败: runId={} 的 ring 不存在", runId);
        return false;
    }
    return ring.replayAndSubscribe(lastEventId, emitter);
}
```

新增 import：`java.util.ArrayList`、`java.util.List`（List 已有）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=MemoryStreamBridgeTest`
Expected: PASS（现有 replay 测试 + 新增 5 个测试全过）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/stream/MemoryStreamBridge.java backend/src/test/java/com/commerce/rag/stream/MemoryStreamBridgeTest.java
git commit -m "feat: P1-2 MemoryStreamBridge 原子回放+订阅（replayAndSubscribe），消除重连竞态窗口"
```

---

### Task 2: ChatController.reconnect 终态判定 + 接入 replayAndSubscribe

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/controller/ChatController.java`（reconnect :245-287、replayFromPg :364-419）
- Modify: `backend/src/main/java/com/commerce/rag/stream/MemoryStreamBridge.java`（删除 `replay` :84-88 与 `replayFrom` :102-104——Task 1 引入 replayAndSubscribe 后这两方法成为本次改动产生的死代码）
- Test: `backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java`（reconnect 三测试 :296-346）
- Test: `backend/src/test/java/com/commerce/rag/stream/MemoryStreamBridgeTest.java`（删除 5 个 replay 测试 :75-149，改写为 replayAndSubscribe 等价断言——Task 1 已新增同语义测试，直接删除旧测试即可）

**Interfaces:**
- Consumes: `MemoryStreamBridge.replayAndSubscribe(String, long, SseEmitter)`（Task 1）、`chatRunService.findById(Long)`、`chatMessageService.findByRunId(Long)`、`SseEventType.END.getEventName()`
- Produces: `ChatController.replayFromPg(String, long, SseEmitter)` 返回值由 `boolean` 改为 `long`（最后回放的 seq；-1=失败/无数据）；私有 `isTerminalStatus(String)` 辅助方法

- [ ] **Step 1: 改写 + 新增测试**（ChatControllerTest）

删除 :296-346 的 3 个旧 reconnect 测试，替换为：

```java
// ==================== reconnect() 测试（P1-2 终态判定 + replayAndSubscribe） ====================

@Test
@DisplayName("reconnect ring 回放成功 → replayAndSubscribe 被调用，不再单独 subscribe")
void reconnect_replaySuccess_replayAndSubscribe() {
    // Given: run 123 属于当前用户 + replayAndSubscribe 返回 true
    ChatRun ownRun = new ChatRun();
    ownRun.setId(123L);
    ownRun.setUserId(123L);
    when(chatRunService.findById(123L)).thenReturn(ownRun);
    when(bridge.replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class))).thenReturn(true);

    // When
    SseEmitter emitter = controller.reconnect("123", 5L, mockRequestWithUserId(123L));

    // Then: 原子回放+订阅已注册，不单独 subscribe；启动心跳
    verify(bridge).replayAndSubscribe(eq("123"), eq(5L), any(SseEmitter.class));
    verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
    assertNotNull(emitter);
}

@Test
@DisplayName("reconnect PG 回放失败 → 返回 SseEmitter（含 error 事件），不 subscribe")
void reconnect_pgReplayFailure_doesNotSubscribe() {
    // Given: run 123 属于当前用户 + replayAndSubscribe 返回 false + PG 无历史消息
    ChatRun ownRun = new ChatRun();
    ownRun.setId(123L);
    ownRun.setUserId(123L);
    when(chatRunService.findById(123L)).thenReturn(ownRun);
    when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class))).thenReturn(false);
    when(chatMessageService.findByRunId(123L)).thenReturn(null);

    // When
    SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

    // Then: 不调用 subscribe
    verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
    assertNotNull(emitter);
}

@Test
@DisplayName("reconnect PG 回放成功 + run 已终态 → 补发 end 事件收尾，不 subscribe 不心跳")
void reconnect_terminalRun_sendsEndAndCompletes() {
    // Given: run 123 属于当前用户且状态 COMPLETED；replayAndSubscribe 失败（ring 已移除）；PG 有历史消息
    ChatRun ownRun = new ChatRun();
    ownRun.setId(123L);
    ownRun.setUserId(123L);
    ownRun.setStatus("COMPLETED");
    when(chatRunService.findById(123L)).thenReturn(ownRun);
    when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class))).thenReturn(false);

    ChatMessage assistantMsg = new ChatMessage();
    assistantMsg.setRole("ASSISTANT");
    assistantMsg.setMessageType("thinking");
    assistantMsg.setContent("历史思考内容");
    when(chatMessageService.findByRunId(123L)).thenReturn(List.of(assistantMsg));

    // When
    SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

    // Then: 终态分支——不 subscribe、不启动心跳（无额外 push）；PG 回放已执行
    verify(bridge, never()).subscribe(anyString(), any(SseEmitter.class));
    verify(chatMessageService).findByRunId(123L);
    assertNotNull(emitter);
}

@Test
@DisplayName("reconnect PG 回放成功 + run 仍活跃 → 继续 subscribe + 心跳")
void reconnect_activeRun_continuesSubscribe() {
    // Given: run 123 属于当前用户且状态 ACTIVE；replayAndSubscribe 失败（ring 覆盖）；PG 有历史消息
    ChatRun ownRun = new ChatRun();
    ownRun.setId(123L);
    ownRun.setUserId(123L);
    ownRun.setStatus("ACTIVE");
    when(chatRunService.findById(123L)).thenReturn(ownRun);
    when(bridge.replayAndSubscribe(eq("123"), eq(0L), any(SseEmitter.class))).thenReturn(false);

    ChatMessage assistantMsg = new ChatMessage();
    assistantMsg.setRole("ASSISTANT");
    assistantMsg.setMessageType("thinking");
    assistantMsg.setContent("历史思考内容");
    when(chatMessageService.findByRunId(123L)).thenReturn(List.of(assistantMsg));

    // When
    SseEmitter emitter = controller.reconnect("123", 0L, mockRequestWithUserId(123L));

    // Then: 非终态分支——继续订阅接收后续事件
    verify(bridge).subscribe(eq("123"), any(SseEmitter.class));
    assertNotNull(emitter);
}

@Test
@DisplayName("reconnect → 他人 runId 返回 404")
void reconnect_withOthersRun_returns404() {
    ChatRun othersRun = new ChatRun();
    othersRun.setId(1L);
    othersRun.setUserId(2L);
    when(chatRunService.findById(1L)).thenReturn(othersRun);

    ResponseStatusException ex = assertThrows(
            ResponseStatusException.class, () -> controller.reconnect("1", 0, mockRequestWithUserId(123L)));
    assertEquals(404, ex.getStatusCode().value());
    verify(bridge, never()).replayAndSubscribe(anyString(), anyLong(), any(SseEmitter.class));
}
```

新增 import：`com.commerce.rag.entity.ChatMessage`、`java.util.List`。

MemoryStreamBridgeTest 删除 :75-149 的 5 个 replay 测试（replay 方法随 Task 2 删除；等价断言已由 Task 1 新增的 replayAndSubscribe 测试覆盖）。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=ChatControllerTest`
Expected: FAIL——`bridge.replayAndSubscribe` 编译错误（Task 1 已实现，应无编译错误；此步确认新断言失败于实现缺失）

- [ ] **Step 3: 实现**

`ChatController.java` reconnect 方法重写（:245-287）：

```java
@GetMapping("/{runId}/reconnect")
public SseEmitter reconnect(
        @PathVariable String runId,
        @RequestParam(defaultValue = "0") long lastEventId,
        HttpServletRequest httpRequest) {

    Long userId = AuthInterceptor.getCurrentUserId(httpRequest);
    checkRunOwnership(runId, userId);

    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);

    // P1-2: 原子「回放 + 订阅」——回放 lastEventId 之后的事件并注册 emitter，
    // 与 Worker 推送并发下不丢不重（消除旧 replay→subscribe 两步之间的窗口竞态）
    boolean success = bridge.replayAndSubscribe(runId, lastEventId, emitter);
    if (!success) {
        // F2-9: ring buffer 已覆盖/不存在 → 降级查 PG chat_message 表，replay 历史消息（§3.6）
        log.warn("ring 回放失败，降级查 PG: runId={}, lastEventId={}", runId, lastEventId);
        long lastSeq = replayFromPg(runId, lastEventId, emitter);
        if (lastSeq < 0) {
            // PG 也无数据，返回 error 事件
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.ERROR.getEventName())
                        .data("{\"message\":\"会话历史不可用，请重新提问\",\"code\":\"REPLAY_FAILED\"}"));
                emitter.complete();
            } catch (IOException e) {
                // emitter 已关闭，忽略
            }
            return emitter;
        }
        // P1-2 终态判定：run 已完成（ring 已移除）→ 补发 end 事件 + complete，
        // 否则新 emitter 收不到 end，前端状态机永久停在"生成中"
        ChatRun run = chatRunService.findById(Long.parseLong(runId));
        if (run != null && isTerminalStatus(run.getStatus())) {
            // runId/status 均来自服务端白名单值（数字 ID + 枚举状态），拼接安全
            String payload = "{\"runId\":\"" + runId + "\",\"status\":\"" + run.getStatus() + "\"}";
            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(lastSeq + 1))
                        .name(SseEventType.END.getEventName())
                        .data(payload));
                emitter.complete();
            } catch (IOException e) {
                // emitter 已关闭，忽略
            }
            log.info("run 已终态，补发 end 事件收尾: runId={}, status={}", runId, run.getStatus());
            return emitter;
        }
        // 非终态：run 仍在执行，继续订阅接收后续事件
        bridge.subscribe(runId, emitter);
        startHeartbeat(emitter);
        log.info("PG 降级回放成功: runId={}, lastEventId={}", runId, lastEventId);
        return emitter;
    }

    // 回放成功：replayAndSubscribe 已注册 emitter，无需再 subscribe；启动心跳
    startHeartbeat(emitter);

    log.info("断线重连成功: runId={}, lastEventId={}", runId, lastEventId);
    return emitter;
}
```

`replayFromPg` 返回值改 `long`（:364-419，签名与所有 return 分支）：

```java
/**
 * F2-9: 从 PG chat_message 表降级回放历史消息到 emitter（§3.6）。
 *
 * <p>当 ring buffer 已覆盖（lastEventId 太旧）或 ring 不存在时，查 PG chat_message 表
 * 按 runId 获取历史消息，转换为 SSE 事件推送到 emitter。
 * 降级不终止，记 warn。
 *
 * @param runId       Run 唯一标识（字符串）
 * @param lastEventId 客户端最后收到的 eventId（用于 seq 续编号）
 * @param emitter     SSE 订阅者
 * @return 最后回放的 seq（回放成功）；-1=PG 无数据或回放失败
 */
private long replayFromPg(String runId, long lastEventId, SseEmitter emitter) {
    try {
        Long runIdLong = Long.parseLong(runId);
        List<ChatMessage> messages = chatMessageService.findByRunId(runIdLong);
        if (messages == null || messages.isEmpty()) {
            log.warn("PG 降级回放: runId={} 无历史消息", runId);
            return -1;
        }

        long seq = lastEventId;
        for (ChatMessage msg : messages) {
            // 跳过用户消息（客户端已有用户查询）
            if ("USER".equals(msg.getRole())) {
                continue;
            }

            seq++;
            String eventType;
            String payload;

            if ("thinking".equals(msg.getMessageType())) {
                eventType = SseEventType.THINKING.getEventName();
                payload = "{\"delta\":\"" + escapeJson(msg.getContent()) + "\"}";
            } else if ("TOOL_CALL".equals(msg.getMessageType())) {
                eventType = SseEventType.TOOL_CALL.getEventName();
                payload = msg.getContent() != null ? msg.getContent() : "{}";
            } else if ("TOOL_RESULT".equals(msg.getMessageType())) {
                eventType = SseEventType.TOOL_RESULT.getEventName();
                payload = msg.getContent() != null ? msg.getContent() : "{}";
            } else {
                // 普通助手消息 → DELTA 事件
                eventType = SseEventType.DELTA.getEventName();
                payload = "{\"text\":\"" + escapeJson(msg.getContent()) + "\"}";
            }

            try {
                emitter.send(SseEmitter.event()
                        .id(String.valueOf(seq))
                        .name(eventType)
                        .data(payload));
            } catch (IOException e) {
                log.warn("PG 降级回放: emitter 发送失败 runId={} seq={}", runId, seq);
                break;
            }
        }

        log.info("PG 降级回放完成: runId={}, 消息数={}", runId, messages.size());
        return seq;
    } catch (NumberFormatException e) {
        log.warn("PG 降级回放: runId 解析失败 runId={}", runId);
        return -1;
    } catch (Exception e) {
        log.warn("PG 降级回放失败: runId={}", runId, e);
        return -1;
    }
}
```

新增私有辅助方法（放在 `truncateTitle` 附近）：

```java
/**
 * 判断 run 是否已处于终态（COMPLETED/CANCELLED/ERROR）。
 *
 * <p>终态 run 的 ring 已被 Worker 移除，重连时无法通过事件流收到 end 事件，
 * 需由服务端按 run 状态补发（P1-2）。
 */
private boolean isTerminalStatus(String status) {
    return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "ERROR".equals(status);
}
```

`MemoryStreamBridge.java` 删除 `replay`（:84-88）与 `replayFrom`（:102-104）两个方法及其注释（replayAndSubscribe 已替代；`Ring.replay` :184-218 一并删除——确认删除后 `Ring` 内无其他引用）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=ChatControllerTest,MemoryStreamBridgeTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/controller/ChatController.java backend/src/main/java/com/commerce/rag/stream/MemoryStreamBridge.java backend/src/test/java/com/commerce/rag/controller/ChatControllerTest.java backend/src/test/java/com/commerce/rag/stream/MemoryStreamBridgeTest.java
git commit -m "fix: P1-2 reconnect 终态判定补发 end 事件 + 接入 replayAndSubscribe，删除废弃 replay/replayFrom"
```

---

### Task 3: ChatRequestWorker 取消回滚容器级浅拷贝（checkpoint 类型保留）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java`（captureSnapshot :473-501、删除 deepCopyState :503-526）
- Modify: `backend/src/main/java/com/commerce/rag/worker/RunSnapshot.java`（注释 :16-18）
- Test: `backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java`

**Interfaces:**
- Consumes: `BaseCheckpointSaver.get(RunnableConfig)` → `Optional<Checkpoint>`、`Checkpoint.getState()/getNodeId()/getNextNodeId()`、`RunSnapshot` record
- Produces: 行为不变（`captureSnapshot`/`rollbackCheckpoint` 签名不变），仅实现方式从 JSON 深拷贝改为容器浅拷贝

- [ ] **Step 1: 新增测试**（ChatRequestWorkerTest 追加）

```java
// ==================== P1-3 取消回滚 checkpoint 类型保留 ====================

@Test
@DisplayName("取消回滚 — 快照 messages 元素保留 Message 类型（P1-3 容器级浅拷贝）")
void rollbackCheckpoint_preservesMessageTypes() throws Exception {
    // Given: saver.get 返回含真实 Spring AI Message 的 checkpoint（第二轮对话场景）
    Checkpoint cp = Checkpoint.builder()
            .id("cp-1")
            .state(Map.of("messages", List.of(new UserMessage("历史问题"), new AssistantMessage("历史回答"))))
            .nodeId("node-1")
            .nextNodeId("node-2")
            .build();
    when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(cp));

    // 取消路径：执行前设置取消标记，首个 chunk 触发 CancelledException
    worker.cancel("100");
    NodeOutput mockChunk = mock(NodeOutput.class);
    lenient().when(mockChunk.state()).thenReturn(null);
    when(compiledGraph.stream(any(), any(RunnableConfig.class))).thenReturn(Flux.just(mockChunk));
    MapRecord<String, Object, Object> record = createMockRecord("100", "200", "300", "你好");

    // When
    invokeProcessRequest(record);

    // Then: 回滚写入的 checkpoint state 中 messages 元素仍是 Message 子类（非 LinkedHashMap）
    ArgumentCaptor<Checkpoint> cpCaptor = ArgumentCaptor.forClass(Checkpoint.class);
    verify(saver).put(any(RunnableConfig.class), cpCaptor.capture());
    Checkpoint newCp = cpCaptor.getValue();
    List<?> messages = (List<?>) newCp.getState().get("messages");
    assertEquals(2, messages.size());
    assertTrue(messages.get(0) instanceof UserMessage, "回滚后 messages[0] 应为 UserMessage");
    assertTrue(messages.get(1) instanceof AssistantMessage, "回滚后 messages[1] 应为 AssistantMessage");
}
```

新增 import：`com.alibaba.cloud.ai.graph.checkpoint.Checkpoint`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=ChatRequestWorkerTest`
Expected: FAIL——`messages.get(0) instanceof UserMessage` 为 false（当前 deepCopyState JSON 往返产出 LinkedHashMap）

- [ ] **Step 3: 实现**

`ChatRequestWorker.java` captureSnapshot（:473-501）中替换深拷贝调用：

```java
Checkpoint cp = opt.get();
// P1-3: 容器级浅拷贝替代 JSON 深拷贝——SAA 1.1.2.0 实证（OverAllState.updateState
// 用 Stream.collect 产新 Map、AppendStrategy 用 new ArrayList 产新 List）图执行期
// 不原地修改 checkpoint state，顶层 Map 独立即可保证快照安全，且 Message 类型 100% 保留
// （JSON 往返会经无多态注册的 ObjectMapper 把 Message 反序列化为 LinkedHashMap，类型破坏）
Map<String, Object> stateCopy = new HashMap<>(cp.getState());
```

删除 `deepCopyState` 方法（:503-526 含 javadoc 与 `@SuppressWarnings("unchecked")`）。

`RunSnapshot.java` 注释更新（:16-18 与 record 字段注释）：

```java
/**
 * pre-run 快照 —— 在图执行前捕获 checkpoint 状态，用于取消后回滚。
 *
 * <p>实际 SAA 1.1.2.0 API 中 {@code PostgresSaver.get(config)} 返回
 * {@code Optional<Checkpoint>}，Checkpoint 提供 {@code getState()}、
 * {@code getNodeId()}、{@code getNextNodeId()} 等方法。
 * 本 record 对 state 做容器级浅拷贝（顶层 Map 独立、值对象引用共享）——
 * SAA 图执行期不原地修改 checkpoint state（1.1.2.0 jar 实证），
 * 浅拷贝即可保证回滚快照安全且 Message 类型保留（P1-3）。
 *
 * @param runId               Run 唯一标识
 * @param checkpointId        Checkpoint ID
 * @param nodeId              当前节点 ID
 * @param nextNodeId          下一节点 ID
 * @param state               Checkpoint 状态的容器级拷贝（顶层 Map 独立，值对象引用共享）
 * @param historyMessageCount pre-run checkpoint 中 messages 列表长度（持久化游标：本轮只落此数之后的新增消息；无 checkpoint 为 0）
 * @param capturedAt          快照捕获时间戳（毫秒）
 */
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=ChatRequestWorkerTest`
Expected: PASS（含既有取消/异常/持久化测试）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/worker/ChatRequestWorker.java backend/src/main/java/com/commerce/rag/worker/RunSnapshot.java backend/src/test/java/com/commerce/rag/worker/ChatRequestWorkerTest.java
git commit -m "fix: P1-3 取消回滚改容器级浅拷贝，checkpoint messages 保留 Message 类型"
```

---

### Task 4: EtlPipeline Milvus filter 直删（ByDocId/ByKbId）+ 新增 ByCourseId

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java`（deleteFromMilvusByDocId :326-336、deleteFromMilvusByKbId :341-353）
- Test: `backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java`（改写 :80-105 两测试）

**Interfaces:**
- Consumes: `MilvusClientV2.delete(DeleteReq)`、`DeleteReq.builder().collectionName(...).filter(...)`、`MilvusCollectionInitializer.COLLECTION_NAME`
- Produces: `EtlPipeline.deleteFromMilvusByDocId(Long)` / `deleteFromMilvusByKbId(Long)` 行为改为 filter 一次删（不再查 PG）；新增 `EtlPipeline.deleteFromMilvusByCourseId(String courseId)`（void，Milvus 删除失败上抛）

- [ ] **Step 1: 改写 + 新增测试**（EtlPipelineTest 替换 :80-105）

```java
@Test
@DisplayName("deleteFromMilvusByDocId — 按 doc_id filter 一次删除（不查 PG chunk 表）")
void deleteFromMilvusByDocId_deletesByFilter() {
    etlPipeline.deleteFromMilvusByDocId(100L);

    ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
    verify(milvusClientV2).delete(captor.capture());
    assertEquals("doc_id == \"100\"", captor.getValue().getFilter());
    // 不再依赖 PG chunk 行（规避 @TableLogic 过滤漏删已软删 chunk）
    verify(chunkMapper, never()).selectList(any());
}

@Test
@DisplayName("deleteFromMilvusByDocId — Milvus 删除失败上抛（阻断调用方，不静默）")
void deleteFromMilvusByDocId_failure_throws() {
    doThrow(new RuntimeException("connection refused")).when(milvusClientV2).delete(any(DeleteReq.class));

    assertThrows(RuntimeException.class, () -> etlPipeline.deleteFromMilvusByDocId(100L));
}

@Test
@DisplayName("deleteFromMilvusByKbId — 按 kb_id filter 一次删除（不查 PG chunk 表）")
void deleteFromMilvusByKbId_deletesByFilter() {
    etlPipeline.deleteFromMilvusByKbId(10L);

    ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
    verify(milvusClientV2).delete(captor.capture());
    assertEquals("kb_id == \"10\"", captor.getValue().getFilter());
    verify(chunkMapper, never()).selectList(any());
}

@Test
@DisplayName("deleteFromMilvusByCourseId — 按 course_id filter 删除")
void deleteFromMilvusByCourseId_deletesByFilter() {
    etlPipeline.deleteFromMilvusByCourseId("12345");

    ArgumentCaptor<DeleteReq> captor = ArgumentCaptor.forClass(DeleteReq.class);
    verify(milvusClientV2).delete(captor.capture());
    assertEquals("course_id == \"12345\"", captor.getValue().getFilter());
}
```

新增 import：`org.mockito.ArgumentCaptor`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest`
Expected: FAIL——filter 断言不匹配（当前实现逐 chunk 删）与 `deleteFromMilvusByCourseId` 不存在

- [ ] **Step 3: 实现**

`EtlPipeline.java` 替换 :326-353 两个方法并新增第三个：

```java
/**
 * 按 doc_id 删除 Milvus 中该文档的所有分片。
 *
 * <p>P1-4 Bug 1 修复：直接按 Milvus doc_id 字段过滤一次删除，不再查 PG chunk 表——
 * 规避 MP 逻辑删除过滤（@TableLogic 自动过滤 deleted=0）导致已软删 chunk 的向量漏删
 * （reparse 场景旧向量永久残留）。删除失败上抛，阻断调用方 PG 软删（失败可见可重试）。
 */
public void deleteFromMilvusByDocId(Long docId) {
    String filter = "doc_id == \"" + docId + "\"";
    DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(COLLECTION_NAME)
            .filter(filter)
            .build();
    milvusClientV2.delete(deleteReq);
    log.info("Milvus 清理完成（按文档）: docId={}", docId);
}

/**
 * 按 kb_id 删除 Milvus 中该知识库的所有分片。
 *
 * <p>P1-4 修复同 {@link #deleteFromMilvusByDocId(Long)}：filter 直删，不查 PG。
 */
public void deleteFromMilvusByKbId(Long kbId) {
    String filter = "kb_id == \"" + kbId + "\"";
    DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(COLLECTION_NAME)
            .filter(filter)
            .build();
    milvusClientV2.delete(deleteReq);
    log.info("Milvus 清理完成（按知识库）: kbId={}", kbId);
}

/**
 * 按 course_id 删除 Milvus 中该课程标注的所有分片。
 *
 * <p>P1-4 Bug 2 修复：课程删除需同步清理 Milvus（course_id 为 Milvus 现有 VARCHAR 字段，
 * 过滤值格式与 CourseService.deleteCourse 软删 chunk 的 courseIdStr 一致）。
 * 删除失败上抛，阻断课程级联软删（失败可见可重试）。
 *
 * @param courseId 课程 ID 字符串
 */
public void deleteFromMilvusByCourseId(String courseId) {
    String filter = "course_id == \"" + courseId + "\"";
    DeleteReq deleteReq = DeleteReq.builder()
            .collectionName(COLLECTION_NAME)
            .filter(filter)
            .build();
    milvusClientV2.delete(deleteReq);
    log.info("Milvus 清理完成（按课程）: courseId={}", courseId);
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=EtlPipelineTest`
Expected: PASS（含 process 管道测试——`process_fullPipeline` 中 embedAndIndex 调用修复后的 deleteFromMilvusByDocId，mock 的 `milvusClientV2.delete` 无 stub 默认空操作，`chunkMapper.selectList` 的 lenient stub 仍兼容）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/test/java/com/commerce/rag/etl/EtlPipelineTest.java
git commit -m "fix: P1-4 Milvus 删除改 filter 直删（ByDocId/ByKbId 不查 PG）+ 新增 ByCourseId"
```

---

### Task 5: CourseService.deleteCourse 同步 Milvus 清理

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/CourseService.java`（注入 EtlPipeline、deleteCourse :218-263）
- Test: `backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java`

**Interfaces:**
- Consumes: `EtlPipeline.deleteFromMilvusByCourseId(String)`（Task 4）
- Produces: `CourseService.deleteCourse` 在软删 document_chunk 前调用 `deleteFromMilvusByCourseId(courseIdStr)`；新增 `@Autowired private EtlPipeline etlPipeline` 字段

- [ ] **Step 1: 新增测试**（CourseServiceTest 改造 setUp 为全字段反射注入 + 新增测试）

CourseServiceTest 现有 setUp 只注入 courseInfoMapper，deleteCourse 需要全部 mapper。将 setUp 改为 DocumentServiceTest 风格的 switch 反射注入，并补全 @Mock 字段：

```java
@Mock
private CourseInfoMapper courseInfoMapper;

@Mock
private CourseContentMapper courseContentMapper;

@Mock
private CourseScheduleMapper courseScheduleMapper;

@Mock
private CourseTeacherMapper courseTeacherMapper;

@Mock
private CourseEnrollmentMapper courseEnrollmentMapper;

@Mock
private DocumentChunkMapper documentChunkMapper;

@Mock
private EtlPipeline etlPipeline;

private CourseService courseService;

@BeforeEach
void setUp() throws Exception {
    courseService = new CourseService();
    // 字段为 @Autowired 私有字段，通过反射注入 mock
    for (java.lang.reflect.Field f : CourseService.class.getDeclaredFields()) {
        f.setAccessible(true);
        Object value = switch (f.getName()) {
            case "courseInfoMapper" -> courseInfoMapper;
            case "courseContentMapper" -> courseContentMapper;
            case "courseScheduleMapper" -> courseScheduleMapper;
            case "courseTeacherMapper" -> courseTeacherMapper;
            case "courseEnrollmentMapper" -> courseEnrollmentMapper;
            case "documentChunkMapper" -> documentChunkMapper;
            case "etlPipeline" -> etlPipeline;
            default -> null;
        };
        if (value != null) {
            f.set(courseService, value);
        }
    }
}

@Test
@DisplayName("deleteCourse — 先清 Milvus（ByCourseId）再级联软删")
void deleteCourse_cleansMilvusBeforeSoftDelete() {
    // Given: 课程 1 属于创建者 100
    CourseInfo course = new CourseInfo();
    course.setId(1L);
    course.setCreatedBy(100L);
    when(courseInfoMapper.selectById(1L)).thenReturn(course);

    // When
    courseService.deleteCourse(1L, 100L, false);

    // Then: Milvus 清理先于 course_info 软删
    InOrder inOrder = inOrder(etlPipeline, courseInfoMapper);
    inOrder.verify(etlPipeline).deleteFromMilvusByCourseId("1");
    inOrder.verify(courseInfoMapper).update(any(), any());
}

@Test
@DisplayName("deleteCourse — Milvus 删除失败上抛，级联软删不执行")
void deleteCourse_milvusFailure_blocksSoftDelete() {
    CourseInfo course = new CourseInfo();
    course.setId(1L);
    course.setCreatedBy(100L);
    when(courseInfoMapper.selectById(1L)).thenReturn(course);
    doThrow(new RuntimeException("Milvus 不可用")).when(etlPipeline).deleteFromMilvusByCourseId("1");

    assertThrows(RuntimeException.class, () -> courseService.deleteCourse(1L, 100L, false));
    verify(courseInfoMapper, never()).update(any(), any());
}
```

新增 import：`com.commerce.rag.etl.EtlPipeline`、`com.commerce.rag.mapper.*`（CourseContentMapper/CourseScheduleMapper/CourseTeacherMapper/CourseEnrollmentMapper/DocumentChunkMapper）、`org.mockito.InOrder`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=CourseServiceTest`
Expected: FAIL——`verify(etlPipeline).deleteFromMilvusByCourseId` 未被调用（未实现）

- [ ] **Step 3: 实现**

`CourseService.java`：

1. 新增字段（放在 documentChunkMapper 之后）：

```java
@Autowired
private EtlPipeline etlPipeline;
```

新增 import：`com.commerce.rag.etl.EtlPipeline`。

2. deleteCourse（:218-263）在软删 document_chunk 之前插入（放在级联软删注释块之前、checkOwnership 之后）：

```java
// P1-4 Bug 2: 同步清理 Milvus 中该课程标注的向量（失败上抛阻断级联，
// 避免 PG 已删而 Milvus 残留 → 学生端按 course_id 过滤仍命中已删课程内容）
etlPipeline.deleteFromMilvusByCourseId(courseIdStr);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=CourseServiceTest`
Expected: PASS（含既有 findById 测试）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/CourseService.java backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java
git commit -m "fix: P1-4 课程删除同步 Milvus 清理（deleteFromMilvusByCourseId）"
```

---

### Task 6: MinIO 删除失败上抛 + DocumentService.delete 调序

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/storage/MinioStorageService.java`（deleteFile :104-112）
- Modify: `backend/src/main/java/com/commerce/rag/service/DocumentService.java`（delete :200-230）
- Test: `backend/src/test/java/com/commerce/rag/storage/MinioStorageServiceTest.java`（改写 :67-72）
- Test: `backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java`

**Interfaces:**
- Consumes: 无新依赖
- Produces: `MinioStorageService.deleteFile(String)` 删除失败抛 `RuntimeException`；`DocumentService.delete` 顺序改为「MinIO → Milvus → PG 软删」

- [ ] **Step 1: 改写 + 新增测试**

MinioStorageServiceTest :67-72 替换：

```java
@Test
@DisplayName("deleteFile 删除失败 — 抛出 RuntimeException（不静默，供调用方阻断/重试）")
void deleteFile_failure_throws() throws Exception {
    doThrow(new RuntimeException("not found")).when(minioClient).removeObject(any());

    assertThrows(RuntimeException.class, () -> storageService.deleteFile("1/100.pdf"));
}
```

DocumentServiceTest 追加：

```java
@Test
@DisplayName("delete → MinIO 删除先于 PG 软删（失败阻断，记录保留可重试）")
void delete_minioFirst_failureBlocksSoftDelete() {
    // Given
    when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));

    // When（正常路径）
    documentService.delete(1L, 100L, false);

    // Then: MinIO 删除先于 chunk/doc 软删
    InOrder inOrder = inOrder(minioStorageService, chunkMapper, documentMapper);
    inOrder.verify(minioStorageService).deleteFile("kb/1/doc.pdf");
    inOrder.verify(chunkMapper).update(any(), any());
    inOrder.verify(documentMapper).update(any(), any());

    // When（MinIO 失败路径）
    reset(minioStorageService, chunkMapper, documentMapper);
    when(documentMapper.selectById(1L)).thenReturn(mockDoc(1L, 100L));
    doThrow(new RuntimeException("MinIO 不可用")).when(minioStorageService).deleteFile("kb/1/doc.pdf");

    // Then: 异常上抛，PG 不软删（对象/记录可重试收敛）
    assertThrows(RuntimeException.class, () -> documentService.delete(1L, 100L, false));
    verify(chunkMapper, never()).update(any(), any());
    verify(documentMapper, never()).update(any(), any());
}
```

新增 import：`org.mockito.InOrder`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=MinioStorageServiceTest,DocumentServiceTest`
Expected: FAIL——`deleteFile_failure_throws` 断言失败（当前不抛异常）；delete 顺序断言失败

- [ ] **Step 3: 实现**

`MinioStorageService.java` deleteFile（:104-112）：

```java
/**
 * 从 MinIO 删除文件
 *
 * <p>P1-4 Bug 3 修复：删除失败抛异常（不再静默吞掉）——调用方（文档/知识库删除）
 * 先删 MinIO 再软删 PG，失败上抛阻断保证"对象删不掉则记录保留"，可重试收敛
 * （MinIO removeObject 对不存在对象幂等成功）。
 *
 * @param objectKey 文件路径
 * @throws RuntimeException MinIO 删除失败
 */
public void deleteFile(String objectKey) {
    try {
        minioClient.removeObject(
                RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        log.info("文件已从 MinIO 删除: objectKey={}", objectKey);
    } catch (Exception e) {
        log.error("MinIO 删除失败: objectKey={}", objectKey, e);
        throw new RuntimeException("文件删除失败: " + e.getMessage(), e);
    }
}
```

`DocumentService.java` delete（:200-230）重排顺序：

```java
// 1. MinIO 删除（P1-4 Bug 3 修复：先删外部资源，失败上抛阻断 → PG 记录保留可重试；
//    removeObject 幂等，任一侧先失败重试均可收敛到"对象已删 + 记录已删"）
if (doc.getSourcePath() != null) {
    minioStorageService.deleteFile(doc.getSourcePath());
}

// 2. Milvus 清理（filter 直删，失败上抛阻断）
etlPipeline.deleteFromMilvusByDocId(id);

// 3. 软删 document_chunk
LambdaUpdateWrapper<DocumentChunk> chunkWrapper = new LambdaUpdateWrapper<DocumentChunk>()
        .eq(DocumentChunk::getDocId, id)
        .set(DocumentChunk::getDeleted, System.currentTimeMillis());
chunkMapper.update(null, chunkWrapper);

// 4. 软删 document
LambdaUpdateWrapper<Document> docWrapper = new LambdaUpdateWrapper<Document>()
        .eq(Document::getId, id)
        .set(Document::getDeleted, System.currentTimeMillis());
documentMapper.update(null, docWrapper);

log.info("删除文档（级联）: docId={}, operatorId={}", id, operatorId);
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=MinioStorageServiceTest,DocumentServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/storage/MinioStorageService.java backend/src/main/java/com/commerce/rag/service/DocumentService.java backend/src/test/java/com/commerce/rag/storage/MinioStorageServiceTest.java backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java
git commit -m "fix: P1-4 MinIO 删除失败上抛 + DocumentService.delete 先 MinIO 后 PG 软删"
```

---

### Task 7: KnowledgeBaseService 级联删除 MinIO 对象

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/service/KnowledgeBaseService.java`（注入 MinioStorageService、delete :148-177）
- Test: `backend/src/test/java/com/commerce/rag/service/KnowledgeBaseServiceTest.java`（更新 delete_cascadeAll :65-82）

**Interfaces:**
- Consumes: `MinioStorageService.deleteFile(String)`（Task 6 已改为失败上抛）、`DocumentMapper.selectList(LambdaQueryWrapper)`（按 kbId 查 sourcePath）
- Produces: `KnowledgeBaseService.delete` 在 PG 软删前删除 KB 下所有未删 document 的 MinIO 对象；新增 `@Autowired private MinioStorageService minioStorageService` 字段

- [ ] **Step 1: 更新测试**（KnowledgeBaseServiceTest）

```java
@Test
@DisplayName("delete 级联删除 — Milvus + MinIO 对象 + chunk + document + kb 全部调用")
void delete_cascadeAll() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(1L);
    kb.setCreatedBy(1L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);

    // KB 下两个文档（source_path 待删）
    Document doc1 = new Document();
    doc1.setId(10L);
    doc1.setSourcePath("1/10.pdf");
    Document doc2 = new Document();
    doc2.setId(11L);
    doc2.setSourcePath("1/11.pdf");
    when(documentMapper.selectList(any())).thenReturn(List.of(doc1, doc2));

    knowledgeBaseService.delete(1L, 1L, true);

    // 验证 Milvus 清理
    verify(etlPipeline).deleteFromMilvusByKbId(1L);
    // 验证 MinIO 对象删除（P1-4 Bug 4：先删对象再软删，失败上抛阻断）
    verify(minioStorageService).deleteFile("1/10.pdf");
    verify(minioStorageService).deleteFile("1/11.pdf");
    InOrder inOrder = inOrder(minioStorageService, documentMapper);
    inOrder.verify(minioStorageService).deleteFile("1/10.pdf");
    inOrder.verify(documentMapper).update(any(), any());
    // 验证 chunk 软删 + kb 软删
    verify(chunkMapper).update(any(), any());
    verify(knowledgeBaseMapper).update(any(), any());
}
```

在类顶部的 @Mock 区新增：

```java
@Mock
private MinioStorageService minioStorageService;
```

新增 import：`com.commerce.rag.entity.Document`、`com.commerce.rag.storage.MinioStorageService`、`java.util.List`（KnowledgeBaseService 当前未引入 List——delete 方法现有代码无 List 使用，必须新增）、`org.mockito.InOrder`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=KnowledgeBaseServiceTest`
Expected: FAIL——`verify(minioStorageService).deleteFile` 未调用

- [ ] **Step 3: 实现**

`KnowledgeBaseService.java`：

1. 新增字段（放在 etlPipeline 之后）：

```java
@Autowired
private MinioStorageService minioStorageService;
```

新增 import：`com.commerce.rag.storage.MinioStorageService`。

2. delete（:148-177）在 Milvus 清理之后、PG 软删之前插入：

```java
// P1-4 Bug 4: 删除 KB 下所有文档的 MinIO 源文件对象（失败上抛阻断级联，
// 避免对象孤儿永久占存储；removeObject 幂等，重试收敛）
List<Document> docs = documentMapper.selectList(
        new LambdaQueryWrapper<Document>()
                .eq(Document::getKbId, id)
                .select(Document::getSourcePath));
for (Document d : docs) {
    if (d.getSourcePath() != null) {
        minioStorageService.deleteFile(d.getSourcePath());
    }
}
```

新增 import：`com.commerce.rag.entity.Document`、`java.util.List`（List 需确认——类内已有 `java.time.LocalDateTime`，`List` 若未引入则补）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=KnowledgeBaseServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/KnowledgeBaseService.java backend/src/test/java/com/commerce/rag/service/KnowledgeBaseServiceTest.java
git commit -m "fix: P1-4 知识库删除级联清理 MinIO 对象"
```

---

### Task 8: DocumentService.upload uuid 先行 + 单向补偿

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/storage/MinioStorageService.java`（uploadFile 签名 :68-81、buildObjectKey :122-124）
- Modify: `backend/src/main/java/com/commerce/rag/service/DocumentService.java`（upload :75-117）
- Test: `backend/src/test/java/com/commerce/rag/storage/MinioStorageServiceTest.java`（改写 :39-48）
- Test: `backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `MinioStorageService.uploadFile(Long kbId, String uuid, InputStream inputStream, String ext)` → `String`（objectKey=`{kbId}/{uuid}.{ext}`）；`DocumentService.upload` 流程改为「uuid 预生成 → 先传 MinIO → insert（含 sourcePath）」+ insert 失败单向补偿

- [ ] **Step 1: 改写 + 新增测试**

MinioStorageServiceTest :39-48 改写：

```java
@Test
@DisplayName("uploadFile 上传文件 — 返回 objectKey（uuid 格式 {kbId}/{uuid}.{ext}）")
void uploadFile_returnsObjectKey() throws Exception {
    InputStream inputStream = new ByteArrayInputStream("test content".getBytes());
    when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

    String objectKey = storageService.uploadFile(1L, "9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c", inputStream, "pdf");

    assertEquals("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf", objectKey);
    verify(minioClient).putObject(any(PutObjectArgs.class));
}
```

DocumentServiceTest 追加（替换原 upload_kbOwner_succeeds 断言中的 updateById 相关，原测试 :137-146 保留但断言增强）：

```java
@Test
@DisplayName("upload → 成功路径：sourcePath 为 {kbId}/{uuid}.{ext}，无第二步 updateById")
void upload_success_uuidPath() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(1L);
    kb.setCreatedBy(100L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    when(minioStorageService.uploadFile(eq(1L), anyString(), any(), eq("pdf")))
            .thenAnswer(inv -> "1/" + inv.getArgument(1) + ".pdf");

    assertDoesNotThrow(() ->
            documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));

    ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
    verify(documentMapper).insert(captor.capture());
    String path = captor.getValue().getSourcePath();
    assertTrue(path.matches("1/[0-9a-f]{32}\\.pdf"), "路径应为 {kbId}/{uuid}.{ext}: " + path);
    // 无第二步 updateById（DB 记录一步到位）
    verify(documentMapper, never()).updateById(any());
}

@Test
@DisplayName("upload → MinIO 上传失败：insert 不被调用（DB 无残留）")
void upload_minioFailure_noInsert() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(1L);
    kb.setCreatedBy(100L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    doThrow(new RuntimeException("MinIO 不可用"))
            .when(minioStorageService)
            .uploadFile(anyLong(), anyString(), any(), anyString());

    assertThrows(RuntimeException.class, () ->
            documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
    verify(documentMapper, never()).insert(any());
}

@Test
@DisplayName("upload → insert 失败：删除已上传 MinIO 对象（单向补偿）")
void upload_insertFailure_deletesObject() {
    KnowledgeBase kb = new KnowledgeBase();
    kb.setId(1L);
    kb.setCreatedBy(100L);
    when(knowledgeBaseMapper.selectById(1L)).thenReturn(kb);
    when(minioStorageService.uploadFile(anyLong(), anyString(), any(), anyString()))
            .thenReturn("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf");
    when(documentMapper.insert(any(Document.class))).thenThrow(new RuntimeException("DB 不可用"));

    assertThrows(RuntimeException.class, () ->
            documentService.upload(1L, "doc", new ByteArrayInputStream(new byte[0]), "pdf", 10L, 100L, false));
    // 唯一可能的残留方向「MinIO 已传、DB 未落」→ 回收对象（幂等）
    verify(minioStorageService).deleteFile("1/9f8c7b6a5d4c3b2a1f0e9d8c7b6a5d4c.pdf");
}
```

新增 import：`org.mockito.ArgumentCaptor`。

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn.cmd test -Dtest=MinioStorageServiceTest,DocumentServiceTest`
Expected: FAIL——uploadFile 签名变更编译错误 + 新断言失败

- [ ] **Step 3: 实现**

`MinioStorageService.java` uploadFile（:68-81）与 buildObjectKey（:122-124）：

```java
/**
 * 上传文件到 MinIO
 *
 * <p>P1-4 Bug 5 修复（用户裁决）：objectKey 用 uuid 标识（{kbId}/{uuid}.{ext}），
 * 与业务主键 docId 解耦——上传不再依赖 DB 记录先行，外部资源先占、DB 后落。
 *
 * @param kbId        知识库 ID
 * @param uuid        文件唯一标识（32 位 hex，去横线 UUID，调用方生成）
 * @param inputStream 文件输入流
 * @param ext         文件扩展名（如 pdf、docx）
 * @return objectKey（{kb_id}/{uuid}.{ext}）
 */
public String uploadFile(Long kbId, String uuid, InputStream inputStream, String ext) {
    String objectKey = buildObjectKey(kbId, uuid, ext);
    try {
        minioClient.putObject(
                PutObjectArgs.builder().bucket(bucket).object(objectKey).stream(inputStream, -1, 10 * 1024 * 1024)
                        .contentType("application/octet-stream")
                        .build());
        log.info("文件已上传到 MinIO: objectKey={}", objectKey);
        return objectKey;
    } catch (Exception e) {
        log.error("MinIO 上传失败: objectKey={}", objectKey, e);
        throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
    }
}

/**
 * 构造 MinIO objectKey
 *
 * @param kbId  知识库 ID
 * @param uuid  文件唯一标识（32 位 hex）
 * @param ext   文件扩展名
 * @return {kb_id}/{uuid}.{ext}
 */
private String buildObjectKey(Long kbId, String uuid, String ext) {
    return kbId + "/" + uuid + "." + ext;
}
```

`DocumentService.java` upload（:75-117）流程重排：

```java
// 归属校验（:89-92 不变）...

// uuid 先行（用户裁决，AGENTS.md 一致：先占外部资源再落库，单向补偿即可）：
// objectKey 用 uuid（去横线）标识，与 docId 解耦；docId 由 MP 自动生成（ASSIGN_ID 雪花）
String uuid = UUID.randomUUID().toString().replace("-", "");
String objectKey = minioStorageService.uploadFile(kbId, uuid, inputStream, fileType);

// 创建 document 记录（sourcePath 一步带入，id 自动生成）
Document doc = new Document();
doc.setKbId(kbId);
doc.setTitle(title);
doc.setFileType(fileType);
doc.setFileSize(fileSize);
doc.setParseStatus("PENDING");
doc.setChunkCount(0);
doc.setMetadataJson("{}");
doc.setCreatedBy(createdBy);
doc.setSourcePath(objectKey);
try {
    documentMapper.insert(doc);
} catch (Exception e) {
    // 单向补偿：唯一可能残留的方向是「MinIO 已传、DB 未落」→ 删已上传对象（幂等）后上抛
    minioStorageService.deleteFile(objectKey);
    throw e;
}

log.info("文档已上传: docId={}, kbId={}, title={}, fileType={}", doc.getId(), kbId, title, fileType);

// 触发 ETL 异步管道
etlPool.execute(() -> etlPipeline.process(doc.getId()));

return doc;
```

新增 import：`java.util.UUID`（确认类内未引入）。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn.cmd test -Dtest=MinioStorageServiceTest,DocumentServiceTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/storage/MinioStorageService.java backend/src/main/java/com/commerce/rag/service/DocumentService.java backend/src/test/java/com/commerce/rag/storage/MinioStorageServiceTest.java backend/src/test/java/com/commerce/rag/service/DocumentServiceTest.java
git commit -m "fix: P1-4 upload uuid 先行（{kbId}/{uuid}.{ext}）+ insert 失败单向补偿"
```

---

### Task 9: 全量回归 + 真实环境验证

**Files:**
- 无代码改动；仅验证

- [ ] **Step 1: 全量测试**

Run: `cd backend && mvn.cmd test`
Expected: 全过（基线 219/219 + 本次新增/改写用例）

- [ ] **Step 2: 真实环境验证（P1-3 取消+重跑落库）**

启动基础设施 `docker compose -f docker-compose.dev.yml up -d`，启动后端；用学生账号建立会话 → 第 2 轮对话发起后点取消 → 再发新消息 → 断言 PG `chat_message` 正常落库（无静默跳过）、无 ClassCastException、run 状态流转正常。

- [ ] **Step 3: 真实环境验证（P1-4 Milvus/MinIO 链路）**

- 已 INDEXED 文档点"重新解析" → 完成后 Milvus 检索该 doc 无旧向量残留
- 删除课程 → 按 course_id 检索无命中；删除知识库 → MinIO bucket 中 `{kbId}/` 前缀对象清空
- 上传文档后直接停 MinIO → 上传失败 → document 列表无 PENDING 残留行；恢复 MinIO 重试成功

- [ ] **Step 4: 提交（如有验证期间发现的小修复，另行提交并回 Task 对应步骤）**

```bash
git add <修复涉及文件>
git commit -m "fix: 真实环境验证发现的问题"
```

- [ ] **Step 5: 更新进度文档**

修改 `docs/progress/2026-08-14-P0修复与后续波次.md` §2.1：标记 P1 波次完成（含全量测试数、验证结果），推进到第三波 P2/P3 立项。
