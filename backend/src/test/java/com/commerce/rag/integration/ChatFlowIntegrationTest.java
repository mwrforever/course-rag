package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.commerce.rag.properties.StreamProperties;
import com.commerce.rag.stream.MemoryStreamBridge;
import com.commerce.rag.stream.SseEvent;
import com.commerce.rag.stream.SseEventType;
import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 对话链路集成测试（真实连接 Testcontainers PG + Redis + 真实 SAA 图执行）
 *
 * <p>覆盖链路（ChatController/StudentController → ChatStreamEntry → Redis Stream → ChatRequestWorker → SAA 图）：
 * <ol>
 *   <li>登录 → 创建会话（chat_session 落库）</li>
 *   <li>发起对话：SSE 端点受理（HTTP 200）→ run 创建（chat_run QUEUED）→ 消息入队（chat:request Stream）</li>
 *   <li>Worker 真实消费（XREADGROUP）→ run 进入 ACTIVE → 图执行（ChatModel 为 @MockitoBean，
 *       不依赖真实模型服务）→ run 收敛终态 COMPLETED（默认快速 mock）</li>
 *   <li>取消链路：慢速 mock 流制造执行窗口 → cancel 端点下发 → 图检查点抛 CancelledException →
 *       run 状态 CANCELLED</li>
 * </ol>
 *
 * <p>断言边界说明：SSE 端点返回 SseEmitter（流式响应），同步客户端读 body 会阻塞到流结束，
 * 故以「HTTP 200 + run 已创建 + Stream 已入队 + run 终态（PG 轮询）」为断言边界，
 * 不与 Worker 后台推送事件流耦合（避免 flaky）。
 *
 * @author commerce-rag
 */
class ChatFlowIntegrationTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(ChatFlowIntegrationTest.class);

    private static final String USERNAME = "chat_student";
    /** Redis Stream 名（与 application.yml stream.request-stream 一致） */
    private static final String REQUEST_STREAM = "chat:request";
    /** 消费组名（与 application.yml stream.consumer-group 一致） */
    private static final String CONSUMER_GROUP = "chat-workers";

    /** 图 checkpoint saver（M2 取消前滚 checkpoint 断言：thread_id = sessionId 直查） */
    @Autowired
    private BaseCheckpointSaver saver;

    /** 流桥接（M6.1 集成：长流 resume 回放断言在真实 ring 上执行） */
    @Autowired
    private MemoryStreamBridge bridge;

    /** 流配置（M6.2 容量断言：真实 application.yml 注入的 ring-buffer-size） */
    @Autowired
    private StreamProperties streamProperties;

    @BeforeEach
    void setUpChatFlow() {
        registerUser(USERNAME, "STUDENT");
        ensureConsumerGroupExists();
        log.info("对话集成测试前置完成: user={}, stream={}", USERNAME, REQUEST_STREAM);
    }

    /**
     * 预创建 Redis Stream + 消费组（幂等）。
     *
     * <p>背景：ChatRequestWorker.start() 在上下文启动时调用 XGROUP CREATE，但此时 stream 尚不存在
     * （XGROUP CREATE 对不存在 key 报错），导致消费组创建失败；后续 XADD 建流后 Worker 的
     * XREADGROUP 会因 NOGROUP 一直无法消费。本方法在用例前置中补齐：
     * XADD 临时消息建流 → XGROUP CREATE 建组（已存在则忽略 BUSYGROUP）→ XDEL 临时消息，
     * 保留空流 + 消费组结构，保证用例消息可被 Worker 消费。
     */
    private void ensureConsumerGroupExists() {
        try {
            // XADD 临时消息创建 stream（XGROUP CREATE 依赖 key 存在）
            RecordId initId = redisTemplate.opsForStream().add(REQUEST_STREAM, Map.of("init", "1"));
            try {
                // 创建消费组（已存在时抛 BUSYGROUP，属正常）
                redisTemplate.opsForStream().createGroup(REQUEST_STREAM, ReadOffset.from("0"), CONSUMER_GROUP);
                log.info("预创建 Redis Stream 消费组: stream={}, group={}", REQUEST_STREAM, CONSUMER_GROUP);
            } catch (Exception e) {
                log.debug("消费组已存在，跳过创建: {}", e.getMessage());
            }
            // 删除临时消息（保留空流与消费组结构）
            redisTemplate.opsForStream().delete(REQUEST_STREAM, initId.getValue());
        } catch (Exception e) {
            log.warn("预创建 Stream 消费组失败（用例可能受 Worker 消费时序影响）: {}", e.getMessage());
        }
    }

    /**
     * 完整对话链路：建会话 → 发消息入队 → Worker 消费 → 图执行（mock 模型）→ run COMPLETED。
     *
     * <p>断言链：
     * <ol>
     *   <li>POST /api/v1/student/sessions → 200 + sessionId（chat_session 落库）</li>
     *   <li>POST /api/v1/student/chat → HTTP 200（SSE 受理，消息已 XADD 入队）</li>
     *   <li>chat:request Stream XLEN ≥ 1（入队证据）</li>
     *   <li>chat_run 存在且归属正确会话（PG 落库）</li>
     *   <li>Worker 真实消费并执行图 → run 终态 COMPLETED（PG 轮询，30s 上限）</li>
     *   <li>chat_message 落库用户消息（图执行后持久化）</li>
     * </ol>
     */
    @Test
    void 建会话并发消息_worker消费后run完成() {
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "集成测试会话");

        // 发起对话（SSE 端点：取 200 后立即关闭流，不等待事件流结束）
        int chatStatus = postChatAndCloseStream(token, sessionId, "你好，介绍一下课程体系");
        assertEquals(200, chatStatus, "对话端点应受理请求并返回 200");

        // run 已创建且归属正确会话
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM chat_run WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                Long.class,
                sessionId);
        assertNotNull(runId, "chat_run 应已创建");

        // Redis Stream 已入队
        Long streamLen = redisTemplate.opsForStream().size(REQUEST_STREAM);
        assertTrue(streamLen != null && streamLen >= 1, "chat:request Stream 应包含入队消息");

        // Worker 消费并执行图（mock 模型快速收敛）→ 终态 COMPLETED
        String finalStatus = awaitTerminalStatus(runId, 30_000);
        assertEquals("COMPLETED", finalStatus, "run 应由 Worker 执行至 COMPLETED（当前状态: " + finalStatus + "）");

        // 图执行后用户消息持久化（chat_message 至少 1 条 USER）
        Integer msgCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND role = 'USER'", Integer.class, runId);
        assertTrue(msgCount != null && msgCount >= 1, "run 应持久化用户消息");
    }

    /**
     * 取消链路（M2）：流式中途 cancel → 立即 dispose 收敛 CANCELLED + 增量落库 + checkpoint 不回滚。
     *
     * <p>mock 栈下的确定性构造（两阶段。注：reactAgent 的模型 chunk 经 Spring AI ChatClient 的
     * MessageAggregator 缓冲，mock 流不完成则 delta 不可达客户端——半截内容因此经 QU 直推的
     * understanding 思考通道构造；delta 通道的等价断言由 ChatRequestWorkerTest 单测覆盖）：
     * <ol>
     *   <li>第一轮默认快速 mock 完成——建立会话 pre-run checkpoint 基线（D6 不回滚断言参照）；</li>
     *   <li>第二轮模型流双段 stub：QU 消费首段（单 chunk 携带 reasoningContent → 客户端实时收到
     *       THINKING(understanding)，QU 完成并写节点 checkpoint）；reactAgent 消费次段「永不完成」
     *       流 → run 停在生成中途（无 chunk、无终态），制造确定的取消窗口。</li>
     * </ol>
     *
     * <p>断言链（spec M2 不变量）：
     * <ol>
     *   <li>客户端收到 query_plan 事件（QU 完成、checkpoint 已写）后 POST cancel → 200，
     *       SSE 端 5s 内收到 end CANCELLED（取消即时性——worker 立刻 dispose 图流订阅 + 主动唤醒
     *       收尾；reactAgent 流永不完成，不取消将悬挂至 5 分钟兜底超时误走 ERROR）；</li>
     *   <li>PG 增量落库 ≡ 已推送事件序列：thinking 行内容 = 客户端收到的 THINKING 拼接、
     *       USER 行存在、无 assistant 正文行（本场景无 delta 推送）；</li>
     *   <li>checkpoint 未回滚（D6）：messages = pre-run 基线 + 本轮用户消息（旧行为回滚 pre-run
     *       快照会把本轮用户消息一并抹掉，只剩基线）；无已推送 delta 故不前滚追加。</li>
     * </ol>
     */
    @Test
    void 发消息后取消_增量落库并保留会话checkpoint() throws Exception {
        // ── 第一轮：快速完成，建立 checkpoint 基线 ──
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "取消保留测试会话");
        assertEquals(200, postChatAndCloseStream(token, sessionId, "第一轮问题"));
        Long run1 = latestRunId(sessionId);
        assertNotNull(run1, "第一轮 chat_run 应已创建");
        assertEquals("COMPLETED", awaitTerminalStatus(run1, 30_000), "第一轮应完成（建立基线）");
        List<Message> preRunMessages = readCheckpointMessages(sessionId);
        assertFalse(preRunMessages.isEmpty(), "第一轮完成后会话应存在 checkpoint messages");

        // ── 第二轮：QU 段推思考后完成；reactAgent 段永不完成（生成中途取消窗口） ──
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        // 首段（QU 消费，逐 chunk 直推不经聚合器）：reasoningContent 实时推
                        // THINKING(understanding) 到客户端；content 非 JSON → QU 降级 unknown（不拒答）
                        Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                                .content("非JSON输出")
                                .properties(Map.of("reasoningContent", "理解问题的思考片段"))
                                .build())))),
                        // 次段（reactAgent 消费）：永不完成——run 停在生成中途，取消落点确定
                        Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("不应到达")))))
                                .concatWith(Flux.never()));

        // 打开 SSE 流并后台读取（断言以「客户端实际收到的事件」为事实源）
        InputStream body = postChatKeepStream(token, sessionId, "请取消这条消息");
        try {
            SseStreamReader reader = new SseStreamReader(objectMapper);
            reader.start(body);
            // query_plan 到达 = QU 完成 + 该节点 checkpoint 已写（本轮用户消息已入 checkpoint，
            // D6 不回滚断言的确定前提）；THINKING 在 query_plan 之前到达（QU 聚合期直推）
            assertTrue(reader.awaitQueryPlan(15_000), "应在超时前收到 query_plan 事件（QU 完成）；已收到事件: " + reader.eventNames());
            assertTrue(reader.thinkingText().contains("理解问题的思考片段"), "QU 思考片段应已实时推送到客户端");
            Long run2 = latestRunId(sessionId);
            assertNotNull(run2, "第二轮 chat_run 应已创建");

            // 2026-09-03 多会话并发历史可见：run 进行中（ACTIVE，尚未取消）USER 行已由
            // worker 认领时提前落库——用户切走再切回会话，历史回显即可见本次提问
            awaitRowCount(
                    "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND role = 'USER'",
                    run2,
                    1,
                    "run 进行中用户消息行应已提前落库（认领即写，2026-09-03）");

            // 下发取消（M2：worker 立刻 dispose 图流订阅 + 主动唤醒终态收尾——reactAgent 流永不完成，
            // 不取消将悬挂至 5 分钟兜底超时误走 ERROR）
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<Void> cancelResp = restTemplate.exchange(
                    "/api/v1/student/chat/" + run2 + "/cancel", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
            assertEquals(200, cancelResp.getStatusCode().value(), "取消接口应返回 200");

            // M2 即时性：end CANCELLED 应秒级到达（不等 reactAgent 流完成/兜底超时）
            assertTrue(reader.awaitTerminal(5_000), "取消后应及时收到 end 终态事件（不等流完成）");
            assertEquals("CANCELLED", reader.terminalStatus(), "SSE 终态应为 CANCELLED: " + reader.terminalStatus());
            assertEquals("CANCELLED", awaitTerminalStatus(run2, 30_000), "run 终态应为 CANCELLED（非 ERROR）");

            // M2 断言①：PG 增量落库 ≡ 已推送事件序列——thinking 行 = 客户端收到的 THINKING 拼接；
            // USER 行存在；本场景无 delta 推送（reactAgent 流未放行任何 chunk）→ 无 assistant 正文行
            awaitRowCount(
                    "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND role = 'USER'", run2, 1, "取消路径应落库本轮用户消息行");
            String persistedThinking = jdbcTemplate.queryForObject(
                    "SELECT content FROM chat_message WHERE run_id = ? AND message_type = 'thinking' AND thinking_stage = 'understanding'",
                    String.class,
                    run2);
            assertEquals(
                    reader.thinkingText(), persistedThinking, "取消路径落库 thinking 行必须等于已推送 THINKING 拼接（终态落库 ≡ 已推送事件序列）");
            Integer bodyRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND role = 'ASSISTANT' AND message_type IS NULL",
                    Integer.class,
                    run2);
            assertEquals(0, bodyRows, "无 delta 推送的场景不得落 assistant 正文行");
            assertFalse(reader.eventNames().contains("delta"), "客户端不应收到任何 delta（reactAgent 流未完成）");

            // M2 断言②：checkpoint 未回滚（D6）——messages = 基线 + 本轮用户消息；
            // 旧行为（回滚 pre-run 快照）会把本轮用户消息一并抹掉、只剩基线；无已推送 delta
            // 故不前滚追加半截 AssistantMessage（有 delta 时的前滚断言由单测覆盖）
            List<Message> afterMessages = awaitCheckpointMessageCount(sessionId, preRunMessages.size() + 1);
            assertEquals(
                    preRunMessages.size() + 1,
                    afterMessages.size(),
                    "取消后 checkpoint = pre-run 基线 + 本轮用户消息（回滚则只剩基线 " + preRunMessages.size() + " 条）");
            UserMessage lastMessage = assertInstanceOf(
                    UserMessage.class, afterMessages.get(afterMessages.size() - 1), "末位应为本轮用户消息（回滚会把它抹掉）");
            assertEquals("请取消这条消息", lastMessage.getText());
        } finally {
            // 释放连接（读取线程为守护线程，服务端关流后自然退出）
            body.close();
        }
    }

    /**
     * 长流（事件数 > ring 容量）中断 → resume（lastEventId=0）→ 保留窗口全量回放（M6.1 集成）。
     *
     * <p>场景映射（spec §3 根因 / M8 复现路径）：长生成（约 2~4 分钟 >256 事件）期间刷新/切回，
     * 前端 resume(runId) → reconnectChat(runId, null) → controller 默认 lastEventId=0；修复前
     * {@code 0 < evictFloor} 直接返回 false → PG 降级 → ACTIVE run 无落库行 → 已生成内容全空，
     * 修复后钳位到 evictFloor 从最早保留事件回放。
     *
     * <p>断言链（真实 Spring 上下文：ring 容量经 application.yml stream.ring-buffer-size 注入）：
     * <ol>
     *   <li>容量基线：StreamProperties.ringBufferSize() = 512（M6.2 配置接线证据）；</li>
     *   <li>push 600 事件（head=600，evictFloor=600-512=88，保留窗口 [89,600]）；</li>
     *   <li>replayAndSubscribe(runId, 0, emitter) 返回 true，首条 seq=89、共 512 条（M6.1 钳位）；</li>
     *   <li>回放注册后续接实时：push seq=601 → 续接送达（共 513 条）。</li>
     * </ol>
     */
    @Test
    @DisplayName("长流（>300 事件）中断 → resume（lastEventId=0）→ 全部保留窗口事件回放（M6.1 集成）")
    void longStream_resumeReplaysRetainedWindow() throws Exception {
        // 容量基线（M6.2：256→512）：真实配置注入决定下方保留窗口数学（600-512=88）
        assertEquals(512, streamProperties.ringBufferSize(), "M6.2：ring 容量应为 512（application.yml 注入）");
        String runId = "9001";
        bridge.createRing(runId);
        try {
            // 在场订阅者：吸收广播事件并作为「outbox 已排空」的观测点（回放批次入队确定性前提）。
            // 分块推送（100/块 « 容量 512）+ 逐块等待送达：outbox 积压恒低于容量，
            // 不触发「队列满摘除订阅者」防御（600 次密集 push 会积满 outbox 致观测点被摘）
            SseEmitter live = mock(SseEmitter.class);
            assertTrue(bridge.subscribe(runId, live), "在场订阅应成功（ring 开放）");
            int delivered = 0;
            for (int chunkStart = 1; chunkStart <= 600; chunkStart += 100) {
                for (int seq = chunkStart; seq < chunkStart + 100; seq++) {
                    bridge.push(
                            runId,
                            new SseEvent(
                                    SseEventType.DELTA,
                                    seq,
                                    "{\"text\":\"帧" + seq + "\"}",
                                    System.currentTimeMillis()));
                }
                delivered += 100;
                int expectedSends = delivered;
                // 等待本块全部送达（块间无新事件入队，次数精确收敛；此块结束时 outbox 已排空）
                verify(live, timeout(10_000).times(expectedSends)).send(any(SseEmitter.SseEventBuilder.class));
            }

            // resume 语义（reconnectChat(runId, null) → controller 默认 lastEventId=0）
            SseEmitter resumed = mock(SseEmitter.class);
            assertTrue(
                    bridge.replayAndSubscribe(runId, 0, resumed),
                    "M6.1：lastEventId=0 应钳位回放保留窗口，而非 0<evictFloor=88 降级 false");

            // 保留窗口 [89,600] 共 512 条全量回放：首条 = 最早保留事件 seq=89、末条 = head=600
            verify(resumed, timeout(10_000).times(512)).send(any(SseEmitter.SseEventBuilder.class));
            verify(resumed, never()).complete();
            List<String> ids = sentEventIds(resumed);
            assertEquals(512, ids.size(), "回放事件数应等于保留窗口容量");
            assertEquals("89", ids.get(0), "回放首条应为最早保留事件 seq=89（evictFloor+1）");
            assertEquals("600", ids.get(ids.size() - 1), "回放末条应为 head=600");

            // 回放注册后续接实时事件：push seq=601 → resumed 一并收到（总数 513）
            bridge.push(runId, new SseEvent(SseEventType.DELTA, 601, "{\"text\":\"续接帧\"}", System.currentTimeMillis()));
            verify(resumed, timeout(10_000).times(513)).send(any(SseEmitter.SseEventBuilder.class));
        } finally {
            // 清理专用 ring（防跨用例泄漏投递线程）
            bridge.removeRing(runId);
        }
    }

    /**
     * replay EDIT 全链路（M5）：软删目标 run 行 + checkpoint 回滚（去除原用户消息）+
     * 新 run 以编辑后的问题重答 + 历史接口不含软删行。
     *
     * <p>断言链（spec M5.2 / D2 / D5）：
     * <ol>
     *   <li>第一轮完成后 replay EDIT（targetRunId=run1）→ HTTP 200 + 新 run 创建；</li>
     *   <li>run1 的 chat_message 行软删（deleted=1 保留审计）、历史接口不再返回其内容；</li>
     *   <li>新 run 的 USER 行 = 编辑后的问题（图输入经 worker 全链路组装 UserMessage）；</li>
     *   <li>checkpoint 回滚生效：新 run 完成后 messages = [编辑后的问题, 新回答]——
     *       不含第一轮问答（回滚失败时为 4 条：第一轮问答 + 新问答）；</li>
     *   <li>历史接口包含新 run 内容、不含被软删的第一轮内容。</li>
     * </ol>
     */
    @Test
    @DisplayName("replay EDIT 全链路：软删 + checkpoint 回滚 + 新 run 以编辑后问题重答 + 历史接口不含软删行")
    void replayEdit_endToEnd() throws Exception {
        // ── 第一轮：快速完成，建立 run1 与 checkpoint 基线 ──
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "replay 编辑测试会话");
        assertEquals(200, postChatAndCloseStream(token, sessionId, "第一轮问题"));
        Long run1 = latestRunId(sessionId);
        assertNotNull(run1, "第一轮 chat_run 应已创建");
        assertEquals("COMPLETED", awaitTerminalStatus(run1, 30_000), "第一轮应完成");
        // 基线：run1 完成后的 checkpoint messages（用户消息 + 图内追加的回答侧消息，规模以实测为准）
        List<Message> run1Messages = readCheckpointMessages(sessionId);
        assertTrue(run1Messages.size() >= 2, "第一轮后 checkpoint 至少含用户消息与回答");

        // ── replay EDIT：编辑第一轮问题后重答 ──
        int replayStatus = postReplayAndCloseStream(token, sessionId, "EDIT", "编辑后的问题", run1);
        assertEquals(200, replayStatus, "replay 端点应受理并返回 200");
        Long run2 = latestRunId(sessionId);
        assertNotEquals(run1, run2, "replay 应创建新 run");
        assertEquals("COMPLETED", awaitTerminalStatus(run2, 30_000), "新 run 应完成重答");

        // run1 行软删（deleted=1 保留审计）；新 run 的 USER 行 = 编辑后的问题
        Integer run1Deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND deleted = 1", Integer.class, run1);
        assertTrue(run1Deleted != null && run1Deleted >= 1, "run1 的消息行应被软删（保留审计）");
        Integer run1Visible = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND deleted = 0", Integer.class, run1);
        assertEquals(0, run1Visible, "run1 不应再有可见消息行");
        String newUserContent = jdbcTemplate.queryForObject(
                "SELECT content FROM chat_message WHERE run_id = ? AND role = 'USER'", String.class, run2);
        assertEquals("编辑后的问题", newUserContent, "新 run 的 USER 行应为编辑后的问题");

        // checkpoint 回滚生效：新 run 完成后 messages 规模与第一轮同构（回滚失败未剥离第一轮
        // 问答则翻倍）、首条为编辑后的用户消息、全程不含第一轮问题（上下文已回滚）
        List<Message> finalMessages = awaitCheckpointMessageCount(sessionId, run1Messages.size());
        assertEquals(run1Messages.size(), finalMessages.size(), "回滚后上下文规模应与第一轮同构（第一轮问答已被剥离）");
        UserMessage editedUser = assertInstanceOf(UserMessage.class, finalMessages.get(0), "首条应为编辑后的用户消息");
        assertEquals("编辑后的问题", editedUser.getText());
        assertTrue(
                finalMessages.stream()
                        .noneMatch(m -> m.getText() != null && m.getText().contains("第一轮问题")),
                "回滚后上下文不应再含第一轮问题");

        // 历史接口：含新 run 内容、不含被软删的第一轮内容
        ResponseEntity<String> history =
                getWithToken("/api/v1/student/sessions/" + sessionId + "/messages?page=1&size=50", token);
        assertEquals(200, history.getStatusCode().value());
        assertNotNull(history.getBody());
        assertTrue(history.getBody().contains("编辑后的问题"), "历史接口应含新 run 内容");
        assertFalse(history.getBody().contains("第一轮问题"), "历史接口不应返回软删行（第一轮问答）");
    }

    /**
     * replay REGENERATE 全链路（M5）：目标回答行软删 + checkpoint 回滚到原用户消息 +
     * 新 run 从该点续跑（图输入 messages 置空）+ 用户消息不重复。
     *
     * <p>断言链：
     * <ol>
     *   <li>第一轮完成后 replay REGENERATE（targetRunId=run1）→ 200 + 新 run；</li>
     *   <li>run1 行软删；新 run 的 USER 行 = 原问题文本（服务端回填，历史回显用户气泡依据）；</li>
     *   <li>checkpoint 回滚生效：新 run 完成后 messages = [原用户消息, 新回答]（用户消息仅一条
     *       ——回滚失败未剥离旧回答则为 3 条，REGENERATE 空 messages 输入重复合并也为 3 条）；</li>
     *   <li>历史接口含原问题与新回答、不含软删的旧回答行。</li>
     * </ol>
     */
    @Test
    @DisplayName("replay REGENERATE 全链路：目标回答软删 + 新 run 从原用户消息续跑 + 用户消息不重复")
    void replayRegenerate_endToEnd() throws Exception {
        // ── 第一轮：快速完成，建立 run1 与 checkpoint 基线 ──
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "replay 重生成测试会话");
        assertEquals(200, postChatAndCloseStream(token, sessionId, "重新生成这个问题"));
        Long run1 = latestRunId(sessionId);
        assertNotNull(run1, "第一轮 chat_run 应已创建");
        assertEquals("COMPLETED", awaitTerminalStatus(run1, 30_000), "第一轮应完成");
        // 基线：run1 完成后的 checkpoint messages（规模以实测为准，同构对比见下方断言）
        List<Message> run1Messages = readCheckpointMessages(sessionId);
        assertTrue(run1Messages.size() >= 2, "第一轮后 checkpoint 至少含用户消息与回答");

        // ── replay REGENERATE：重新生成第一轮回答 ──
        int replayStatus = postReplayAndCloseStream(token, sessionId, "REGENERATE", null, run1);
        assertEquals(200, replayStatus, "replay 端点应受理并返回 200");
        Long run2 = latestRunId(sessionId);
        assertNotEquals(run1, run2, "replay 应创建新 run");
        assertEquals("COMPLETED", awaitTerminalStatus(run2, 30_000), "新 run 应完成重新生成");

        // run1 行软删；新 run 的 USER 行 = 原问题文本（服务端回填）
        Integer run1Visible = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND deleted = 0", Integer.class, run1);
        assertEquals(0, run1Visible, "run1 不应再有可见消息行（软删保留审计）");
        String newUserContent = jdbcTemplate.queryForObject(
                "SELECT content FROM chat_message WHERE run_id = ? AND role = 'USER'", String.class, run2);
        assertEquals("重新生成这个问题", newUserContent, "新 run 的 USER 行应回填原问题文本");

        // checkpoint 回滚生效：新 run 完成后 messages 规模与第一轮同构、原用户消息仅一条
        // （锚点未剥离旧回答则多 1 条；空 messages 输入重复合并用户消息也多 1 条）
        List<Message> finalMessages = awaitCheckpointMessageCount(sessionId, run1Messages.size());
        assertEquals(run1Messages.size(), finalMessages.size(), "回滚后上下文规模应与第一轮同构");
        long originalUserCount = finalMessages.stream()
                .filter(m -> m instanceof UserMessage && "重新生成这个问题".equals(m.getText()))
                .count();
        assertEquals(1, originalUserCount, "原用户消息应恰好一条（REGENERATE 不重复合并）");
        assertInstanceOf(AssistantMessage.class, finalMessages.get(finalMessages.size() - 1), "末条应为重新生成的回答");

        // 历史接口：含原问题与新回答、不含软删的旧回答
        ResponseEntity<String> history =
                getWithToken("/api/v1/student/sessions/" + sessionId + "/messages?page=1&size=50", token);
        assertEquals(200, history.getStatusCode().value());
        assertNotNull(history.getBody());
        assertTrue(history.getBody().contains("重新生成这个问题"), "历史接口应含原问题（新 run USER 行）");
    }

    /**
     * M7 判死重试集成（真实 Spring 上下文 + Testcontainers PG/Redis + 真实 SAA 图执行）：
     * stub 模型前两次调用在 reactAgent 段立即断流（IOException）、第三次成功 → 同 run
     * 自动重试恢复 → END COMPLETED + 正文落库完整 + checkpoint 用户消息不重复。
     *
     * <p>mock 分发（chatModel.stream 按调用到达序）：QU 与 reactAgent 交替消费——
     * QU 段统一用无 reasoning 的非 JSON 输出（静默完成 + 降级 unknown 不拒答，保证
     * hasProduced 五信号全空——QU reasoning 会经 ThinkingPusher 直推构成产出导致不可重试）；
     * reactAgent 段前两次 Flux.error(IOException)（连接级断流，N3 实证的即时 RST 形态）、
     * 第三次正常完成。
     *
     * <p>断言链（spec M7.2 / R4 处理点 c）：
     * <ol>
     *   <li>SSE 客户端收到 end COMPLETED 终态（重试期间无 ERROR 终态、无重复终态）；</li>
     *   <li>chat_message 落库 USER 行 + 含完整回答的 assistant 行（重试成功走正常完成路径，
     *       陈旧 QU 捕获被处理点 d 定向清除、不产生重复实体行）；</li>
     *   <li>checkpoint 用户消息恰好一条（处理点 c：重试前 pre-run 快照回滚生效——失败
     *       尝试的 __START__ 合并被剥离；回滚失效则同 inputs 重试重复合并为多条）。</li>
     * </ol>
     */
    @Test
    @DisplayName("判死重试集成：stub 模型首两次断流、第三次成功 → 前端最终看到完整回答（M7）")
    void stallRetry_recoversOnThirdAttempt() throws Exception {
        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "判死重试测试会话");

        // QU 段统一响应：无 reasoning、content 非 JSON → QU 降级 unknown（无思考推送，
        // hasProduced 保持 false，reactAgent 断流可重试）
        ChatResponse quSilent = new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("非JSON输出").build())));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(
                        // 尝试 1：QU 静默完成 → reactAgent 断流（无产出 → RETRYABLE）
                        Flux.just(quSilent),
                        Flux.error(new IOException("模拟连接中断")),
                        // 尝试 2（退避 + pre-run 快照回滚后）：QU 静默完成 → reactAgent 再断流
                        Flux.just(quSilent),
                        Flux.error(new IOException("模拟连接中断")),
                        // 尝试 3：QU 静默完成 → reactAgent 成功完整回答
                        Flux.just(quSilent),
                        Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("第三次尝试成功的完整回答"))))));

        // 打开 SSE 流并后台读取（断言以「客户端实际收到的事件」为事实源）
        InputStream body = postChatKeepStream(token, sessionId, "判死重试问题");
        try {
            SseStreamReader reader = new SseStreamReader(objectMapper);
            reader.start(body);
            // 两次断流退避（默认 2s/4s）+ 三次图执行，30s 预算内收敛终态
            assertTrue(reader.awaitTerminal(30_000), "重试后应收到终态事件；已收到事件: " + reader.eventNames());
            assertEquals("COMPLETED", reader.terminalStatus(), "终态应为 COMPLETED（自动重试恢复）: " + reader.terminalStatus());

            Long runId = latestRunId(sessionId);
            assertNotNull(runId, "chat_run 应已创建");
            assertEquals("COMPLETED", awaitTerminalStatus(runId, 30_000), "run 终态应为 COMPLETED（同 run 重试不换 runId）");

            // 正文落库完整：assistant 行含第三次成功回答（重试成功走正常完成路径）
            awaitRowCount(
                    "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND content LIKE '%第三次尝试成功的完整回答%'",
                    runId, 1, "重试成功的完整回答应落库 assistant 行");
            awaitRowCount(
                    "SELECT COUNT(*) FROM chat_message WHERE run_id = ? AND role = 'USER'", runId, 1, "重试成功应落库本轮用户消息行");

            // 处理点 c：重试回滚后 checkpoint 用户消息恰好一条（不回滚则失败尝试的
            // __START__ 合并残留，重试重复合并为多条）
            List<Message> finalMessages = awaitCheckpointMessageCount(sessionId, 2);
            long userCount = finalMessages.stream()
                    .filter(m -> m instanceof UserMessage && "判死重试问题".equals(m.getText()))
                    .count();
            assertEquals(1, userCount, "重试回滚后用户消息应恰好一条（重复合并即回滚失效）: " + finalMessages.size() + " 条");
        } finally {
            // 释放连接（读取线程为守护线程，服务端关流后自然退出）
            body.close();
        }
    }

    /**
     * 调用 replay SSE 端点并立即关闭流（POST /api/v1/student/chat/session/{sessionId}/replay）。
     *
     * <p>与 {@link IntegrationTestBase#postChatAndCloseStream} 同断言边界：受理 200 + 新 run
     * 创建 + XADD 入队即返回，流式事件由 Worker 异步推送、run 终态经 PG 轮询断言。
     *
     * @param token     Access Token
     * @param sessionId 会话 ID
     * @param mode      重放模式（EDIT / REGENERATE）
     * @param query     新问题文本（EDIT 非空；REGENERATE 传 null——服务端回填原问题）
     * @param targetRunId 目标 run ID
     * @return HTTP 状态码（预期 200）
     */
    private int postReplayAndCloseStream(String token, Long sessionId, String mode, String query, Long targetRunId) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            String body = "{\"mode\":\"" + mode + "\",\"query\":" + (query == null ? "null" : "\"" + query + "\"")
                    + ",\"targetRunId\":" + targetRunId + "}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            "http://localhost:" + port + "/api/v1/student/chat/session/" + sessionId + "/replay"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            // 立即关闭流：不等待 SSE 结束（断言以 PG/历史接口为准，避免与 Worker 推送耦合）
            response.body().close();
            return status;
        } catch (Exception e) {
            throw new IllegalStateException("replay 端点调用失败", e);
        }
    }

    /**
     * 提取 mock emitter 已送达事件的 seqId 序列（反射读取 SseEventBuilder 的 dataToSend——
     * Spring 6.2 把 id/name/data 拆为多个 DataWithMediaType 部分，取「id:」前缀段解析）。
     *
     * @param emitter 已投递完成的 mock emitter（调用前须 verify 等待 send 次数到位）
     * @return 按送达顺序的 seqId 字符串列表
     */
    private List<String> sentEventIds(SseEmitter emitter) throws Exception {
        List<String> ids = new ArrayList<>();
        for (var invocation : org.mockito.Mockito.mockingDetails(emitter).getInvocations()) {
            if (!invocation.getMethod().getName().equals("send") || invocation.getArguments().length == 0) {
                continue;
            }
            Object builder = invocation.getArguments()[0];
            Field dataField = builder.getClass().getDeclaredField("dataToSend");
            dataField.setAccessible(true);
            for (Object part : (java.util.Set<?>) dataField.get(builder)) {
                Object data = part.getClass().getMethod("getData").invoke(part);
                // id 段形如 "id:89\nevent:delta\ndata:"——取首个 \n 前数字
                if (data instanceof String text && text.startsWith("id:")) {
                    int end = text.indexOf('\n');
                    ids.add(end < 0 ? text.substring(3) : text.substring(3, end));
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * 创建会话（POST /api/v1/student/sessions）。
     *
     * @param token Access Token
     * @param title 会话标题
     * @return 会话 ID（chat_session.id）
     */
    private Long createSession(String token, String title) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/student/sessions",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", title), headers),
                String.class);
        assertEquals(200, response.getStatusCode().value(), "创建会话应返回 200");
        try {
            JsonNode data = objectMapper.readTree(response.getBody()).get("data");
            assertNotNull(data, "创建会话响应 data 不应为空");
            return data.get("id").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("创建会话响应解析失败: " + response.getBody(), e);
        }
    }

    /** 查询会话最新 run id（chat_run 按 created_at 倒序取首条） */
    private Long latestRunId(Long sessionId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM chat_run WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                Long.class,
                sessionId);
    }

    /**
     * 读取会话 checkpoint 的 messages（thread_id = sessionId；PostgresSaver 经 Java 序列化
     * 往返保留 Spring AI Message 类型）。
     *
     * @return messages 列表（无 checkpoint 时为空列表）
     */
    private List<Message> readCheckpointMessages(Long sessionId) {
        RunnableConfig config =
                RunnableConfig.builder().threadId(String.valueOf(sessionId)).build();
        return saver.get(config)
                .flatMap(cp -> Optional.ofNullable(cp.getState().get("messages")))
                .filter(messages -> messages instanceof List<?>)
                .map(messages ->
                        ((List<?>) messages).stream().map(m -> (Message) m).toList())
                .orElse(List.of());
    }

    /**
     * 调用 SSE 对话端点并保持流打开（M2 取消链路：需读取流中事件断言半截 delta 与终态）。
     *
     * @return 响应体 InputStream（调用方负责关闭）
     */
    private InputStream postChatKeepStream(String token, Long sessionId, String query) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            String body = "{\"sessionId\":" + sessionId + ",\"query\":\"" + query + "\"}";
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/student/chat"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, response.statusCode(), "对话端点应受理请求并返回 200");
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("SSE 对话端点调用失败", e);
        }
    }

    /**
     * SSE 流后台读取器（M2 取消链路断言的确定性前提）：逐行解析 event/data 帧，
     * 捕获推送的 thinking/delta 全文、query_plan 到达信号与 end/error 终态。
     * 取消必须发生在「QU 完成（query_plan 已到、checkpoint 已写）」之后，断言
     * （落库 ≡ 已推送、checkpoint 不回滚）才有确定输入；终态到达时刻同时是取消即时性的证据。
     */
    private static final class SseStreamReader {
        private final ObjectMapper mapper;
        private final CountDownLatch queryPlanSeen = new CountDownLatch(1);
        private final CountDownLatch terminalSeen = new CountDownLatch(1);
        private final StringBuilder thinkingText = new StringBuilder();
        private final StringBuilder deltaText = new StringBuilder();
        private final AtomicReference<String> terminalStatus = new AtomicReference<>();
        /** 诊断：按到达序记录全部事件名（断言失败时输出，定位卡在哪个阶段） */
        private final List<String> eventNames = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        SseStreamReader(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        /** 启动后台守护线程读取 SSE 流（服务端关流后自然退出） */
        void start(InputStream body) {
            Thread thread = new Thread(() -> read(body), "chat-sse-reader");
            thread.setDaemon(true);
            thread.start();
        }

        private void read(InputStream body) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String event = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring("event:".length()).trim();
                        eventNames.add(event);
                    } else if (line.startsWith("data:") && event != null) {
                        handleData(event, line.substring("data:".length()).trim());
                    }
                }
            } catch (Exception e) {
                // 连接关闭属正常收尾，读取线程静默退出（断言由 latch 超时兜底）
            }
        }

        private void handleData(String event, String data) {
            try {
                JsonNode json = mapper.readTree(data);
                switch (event) {
                    case "thinking" -> thinkingText.append(json.path("delta").asText(""));
                    case "delta" -> deltaText.append(json.path("text").asText(""));
                    case "query_plan" -> queryPlanSeen.countDown();
                    case "end" -> {
                        terminalStatus.set(json.path("status").asText("UNKNOWN"));
                        terminalSeen.countDown();
                    }
                    case "error" -> {
                        terminalStatus.set("ERROR:" + json.path("message").asText(""));
                        terminalSeen.countDown();
                    }
                    default -> {
                        // 其余事件（metadata/stage/heartbeat 等）不参与断言
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON 数据行忽略（心跳等）
            }
        }

        boolean awaitQueryPlan(long timeoutMs) throws InterruptedException {
            return queryPlanSeen.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        boolean awaitTerminal(long timeoutMs) throws InterruptedException {
            return terminalSeen.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        String thinkingText() {
            return thinkingText.toString();
        }

        String deltaText() {
            return deltaText.toString();
        }

        String terminalStatus() {
            return terminalStatus.get();
        }

        /** 已收到的全部事件名（诊断输出用） */
        List<String> eventNames() {
            return List.copyOf(eventNames);
        }
    }

    /**
     * 轮询等待行数条件满足（终态事件先于异步落库到达的竞态兜底；10s 上限）。
     *
     * @param sql      COUNT 查询（单参数 runId）
     * @param runId    Run ID
     * @param expected 期望行数下限
     * @param message  未满足时的失败文案
     */
    private void awaitRowCount(String sql, Long runId, int expected, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Integer count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = jdbcTemplate.queryForObject(sql, Integer.class, runId);
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertEquals(expected, count, message + "（当前: " + count + "）");
    }

    /**
     * 轮询等待会话 checkpoint messages 达到期望数量（取消终态先于 checkpoint 视图可见的竞态兜底）。
     *
     * @return 满足数量时的 messages 列表（超时返回最后一次读取结果，由调用方断言暴露）
     */
    private List<Message> awaitCheckpointMessageCount(Long sessionId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        List<Message> messages = List.of();
        while (System.currentTimeMillis() < deadline) {
            messages = readCheckpointMessages(sessionId);
            if (messages.size() >= expected) {
                return messages;
            }
            Thread.sleep(100);
        }
        return messages;
    }
}
