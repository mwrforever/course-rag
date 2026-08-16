package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
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
     * 取消链路：发消息后立即 cancel → 图检查点抛 CancelledException → run 状态 CANCELLED。
     *
     * <p>说明：本用例将模型流式输出 stub 为 8 秒延迟，制造图执行中的取消竞态窗口；
     * cancel 在 chat 受理后立刻下发（cancelFlags 置位），首个流式 chunk 到达时
     * checkCancelled 触发 CancelledException → handleCancelled → run 转 CANCELLED。
     *
     * <p>断言链：
     * <ol>
     *   <li>POST /api/v1/student/chat → 200 + run 创建</li>
     *   <li>POST /api/v1/student/chat/{runId}/cancel → 200（取消已下发）</li>
     *   <li>PG 轮询 run 状态 → CANCELLED（30s 上限，8s 延迟 chunk 触发取消）</li>
     * </ol>
     */
    @Test
    void 发消息后取消_run状态转为CANCELLED() {
        // 慢速模型流：制造 8s 执行窗口，保证 cancel 落在图执行期间
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("慢速回复")))))
                        .delayElements(Duration.ofSeconds(8)));

        String token = loginAndGetToken(USERNAME, DEFAULT_DEVICE);
        Long sessionId = createSession(token, "取消测试会话");

        int chatStatus = postChatAndCloseStream(token, sessionId, "请取消这条消息");
        assertEquals(200, chatStatus, "对话端点应受理请求并返回 200");

        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM chat_run WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                Long.class,
                sessionId);
        assertNotNull(runId, "chat_run 应已创建");

        // 下发取消（归属校验通过 → worker.cancel 置位 cancelFlags）
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<Void> cancelResp = restTemplate.exchange(
                "/api/v1/student/chat/" + runId + "/cancel", HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertEquals(200, cancelResp.getStatusCode().value(), "取消接口应返回 200");

        // 图检查点检测到取消 → run 终态 CANCELLED
        String finalStatus = awaitTerminalStatus(runId, 30_000);
        assertEquals("CANCELLED", finalStatus, "取消后 run 应转为 CANCELLED（当前状态: " + finalStatus + "）");
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
}
