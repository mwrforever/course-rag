package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
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
