package com.commerce.rag.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.alibaba.cloud.ai.model.RerankModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.milvus.v2.client.MilvusClientV2;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.core.publisher.Flux;

/**
 * 集成测试基类 —— Testcontainers PG+Redis + Spring Boot 全上下文（RANDOM_PORT）
 *
 * <p>职责：
 * <ul>
 *   <li>单例容器（PG 16 + Redis 7）：类加载时启动一次，所有集成测试类共享同一容器实例，
 *       Spring 上下文缓存复用（@DynamicPropertySource 仅首次求值）时连接地址稳定；
 *       容器在 JVM 退出时由 Testcontainers Ryuk 自动清理</li>
 *   <li>{@code @DynamicPropertySource} 将容器地址注入数据源/Redis 属性（不用 @ServiceConnection，
 *       避免与手动注册重复）</li>
 *   <li>LLM 模型 bean（ChatModel/EmbeddingModel/RerankModel）与 Milvus 客户端以 {@code @MockitoBean}
 *       替换——DashScope 真实模型与 Milvus 服务不参与集成测试；mock 的 call/stream 默认返回固定文本，
 *       保证 Worker 后台真实执行 SAA 图时快速收敛到终态（COMPLETED/ERROR），不依赖外部模型服务</li>
 *   <li>提供公共工具：用户预置（BCrypt 密码）、登录、SSE 端点调用（JDK HttpClient 取状态码后即关流）、
 *       数据库/Redis 清理、run 状态轮询</li>
 * </ul>
 *
 * <p>注意：不用 {@code @Testcontainers}/{@code @Container} 生命周期管理——其类级默认行为会在
 * 每个测试类结束后停止容器，而三个集成测试类共享同一缓存 Spring 上下文（DataSource 连接池
 * 指向旧容器端口），容器重启后旧端口失效导致 Connection refused。
 *
 * <p>数据隔离：每个测试类方法执行前清理业务表与 Redis 认证相关 key（容器全新 + Flyway 迁移保证库结构干净）；
 * 注意不清 Redis Stream（chat:request 的消费组结构依赖其存在，见 ChatFlowIntegrationTest）。
 *
 * <p>依赖：Task 0 已引入 spring-boot-testcontainers / testcontainers junit-jupiter / postgresql 依赖；
 * Docker Desktop 需处于运行状态（Task 0 已实锤可用）。
 *
 * @author commerce-rag
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(IntegrationTestBase.class);

    /** 集成测试统一测试密码（明文仅存在于测试代码，预置用户统一使用该密码） */
    protected static final String TEST_PASSWORD = "password123";
    /** 默认设备类型（与生产默认一致） */
    protected static final String DEFAULT_DEVICE = "WEB_DESKTOP";

    /** 预置用户 ID 生成器（每个测试类独立递增，保证同库内不冲突） */
    private final AtomicLong userIdSeq = new AtomicLong(10_000L);

    // ── 单例容器：类加载时启动一次（生命周期见类注释，跨测试类共享） ──
    static final PostgreSQLContainer<?> postgres = startPostgres();
    static final GenericContainer<?> redis = startRedis();

    /** 启动 PG 容器（postgres:16-alpine，Flyway V6+V7 迁移在其上执行） */
    private static PostgreSQLContainer<?> startPostgres() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.start();
        log.info("Testcontainers PG 已启动: jdbc={}", container.getJdbcUrl());
        return container;
    }

    /** 启动 Redis 容器（redis:7-alpine，需密码认证，与主配置默认密码一致） */
    private static GenericContainer<?> startRedis() {
        GenericContainer<?> container = new GenericContainer<>("redis:7-alpine")
                .withCommand("redis-server", "--requirepass", "rag_redis_2024")
                .withExposedPorts(6379);
        container.start();
        log.info("Testcontainers Redis 已启动: host={}, port={}", container.getHost(), container.getMappedPort(6379));
        return container;
    }

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // 数据源指向 Testcontainers PG（Flyway 迁移 + 业务查询全部走真实数据库）
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Redis 指向 Testcontainers Redis（互踢 Lua / Token 黑名单 / Stream 队列全部走真实 Redis）
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "rag_redis_2024");
    }

    // ========================================================================
    // LLM 模型 mock bean（@MockitoBean 替换真实 DashScope 模型）
    // ========================================================================

    @MockitoBean
    protected ChatModel chatModel;

    @MockitoBean
    protected EmbeddingModel embeddingModel;

    @MockitoBean
    protected RerankModel rerankModel;

    @MockitoBean
    protected MilvusClientV2 milvusClientV2;

    // ========================================================================
    // Spring 测试基础设施注入
    // ========================================================================

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    @Autowired(required = false)
    protected StringRedisTemplate redisTemplate;

    @LocalServerPort
    protected int port;

    // ========================================================================
    // 测试前置：数据隔离 + 模型 mock 默认行为
    // ========================================================================

    /**
     * 每个测试方法执行前清理数据（子类可通过覆写扩展，但必须调用 super 或自行清理）。
     *
     * <p>清理范围：
     * <ul>
     *   <li>业务表（按子表→主表依赖顺序）：chat_message / chat_run / chat_session /
     *       sys_token_blacklist / sys_login_record / sys_user</li>
     *   <li>Redis 认证相关 key（auth:cur:* 互踢指针、auth:bl:* 黑名单、auth:rt:used:*、
     *       auth:disable:*、chat:result:* 结果缓存）——不清 chat:request 流（Worker 消费组结构依赖）</li>
     * </ul>
     *
     * <p>模型 mock 默认行为：call（QueryRewriter/CustomSummarizationHook 走同步调用）与
     * stream（ReactAgent 走流式）均返回固定文本回复，使 Worker 后台执行图时快速收敛；
     * 子类如需慢速流（如 cancel 竞态窗口）可在自身 @BeforeEach 中覆盖 stub。
     */
    @BeforeEach
    void setUpBase() {
        cleanupBusinessTables();
        cleanupRedisAuthKeys();
        stubDefaultModelBehavior();
        log.info("集成测试前置清理完成: port={}", port);
    }

    /**
     * 清理业务表（顺序：子表 → 主表，无外键约束但按依赖顺序删除更安全）。
     */
    protected void cleanupBusinessTables() {
        jdbcTemplate.update("DELETE FROM chat_message");
        jdbcTemplate.update("DELETE FROM chat_run");
        jdbcTemplate.update("DELETE FROM chat_session");
        jdbcTemplate.update("DELETE FROM sys_token_blacklist");
        jdbcTemplate.update("DELETE FROM sys_login_record");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    /**
     * 清理 Redis 认证相关 key 与结果缓存 key（不动 chat:request 流，保留 Worker 消费组结构）。
     */
    protected void cleanupRedisAuthKeys() {
        if (redisTemplate == null) {
            return;
        }
        Set<String> keys = redisTemplate.keys("auth:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        Set<String> resultKeys = redisTemplate.keys("chat:result:*");
        if (resultKeys != null && !resultKeys.isEmpty()) {
            redisTemplate.delete(resultKeys);
        }
    }

    /**
     * 重置模型 mock 默认行为（@MockitoBean 每个测试方法后重置，此处每次重新注册）。
     */
    protected void stubDefaultModelBehavior() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("集成测试固定回复")))));
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("集成测试固定回复"))))));
    }

    // ========================================================================
    // 公共辅助方法
    // ========================================================================

    /**
     * 预置系统用户（直接写 sys_user 表）。
     *
     * <p>业务背景：系统无公开注册端点（用户由管理端创建），集成测试直接以
     * JdbcTemplate 预置 ACTIVE 用户 + BCrypt 密码哈希，等价于管理端创建后的数据形态。
     *
     * @param username 用户名（业务唯一，测试类内需自行保证不重复）
     * @param role     角色（STUDENT / TEACHER / SUPER_ADMIN）
     * @return 预置用户 ID（雪花 ID 由固定序列生成，测试内唯一即可）
     */
    protected Long registerUser(String username, String role) {
        Long id = userIdSeq.incrementAndGet();
        String passwordHash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, username, password_hash, display_name, role, status, created_by, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, 'ACTIVE', NULL, 0)",
                id,
                username,
                passwordHash,
                username,
                role);
        log.info("预置用户: id={}, username={}, role={}", id, username, role);
        return id;
    }

    /**
     * 登录并返回响应体 JSON（不自动断言，由用例自行断言业务码与状态码）。
     *
     * @param username 用户名
     * @param password 密码（预置用户统一使用 {@link #TEST_PASSWORD}）
     * @param device   设备类型（互踢用例需与旧会话相同以触发踢出）
     * @return 登录接口响应体（{@code code/message/data} 三层结构）
     */
    protected JsonNode login(String username, String password, String device) {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/auth/login",
                Map.of("username", username, "password", password, "deviceType", device),
                String.class);
        assertNotNull(response.getBody(), "登录响应体不应为空");
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("登录响应 JSON 解析失败: " + response.getBody(), e);
        }
    }

    /**
     * 携带 Bearer Token 的 GET 请求（断言用：返回原始响应便于用例校验状态码/响应体）。
     *
     * @param path  接口路径（以 / 开头，不含主机）
     * @param token Access Token（可为 null = 不带认证头，验证 401 场景）
     * @return HTTP 响应（String body）
     */
    protected ResponseEntity<String> getWithToken(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null && !token.isEmpty()) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * 调用 SSE 对话端点（POST /api/v1/student/chat）。
     *
     * <p>说明：端点返回 SseEmitter（text/event-stream），同步 HTTP 客户端读 body 会阻塞到流结束，
     * 因此用 JDK HttpClient 以 {@code BodyHandlers.ofInputStream()} 接收，拿到状态码后立即关闭流——
     * 断言边界为「请求已受理 + run 已创建 + 消息已入队」，流的完整事件由 Worker 异步推送，
     * 不在本方法内等待（避免与 Worker 后台执行耦合产生 flaky）。
     *
     * @param token     Access Token
     * @param sessionId 会话 ID（null 时服务端自动创建新会话）
     * @param query     用户问题（非空）
     * @return HTTP 状态码（预期 200）
     */
    protected int postChatAndCloseStream(String token, Long sessionId, String query) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            String body =
                    "{\"sessionId\":" + (sessionId == null ? "null" : sessionId) + ",\"query\":\"" + query + "\"}";
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/student/chat"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            // 立即关闭流：不等待 SSE 结束（Worker 后台推送；run 终态由轮询 PG 断言）
            response.body().close();
            return status;
        } catch (Exception e) {
            throw new IllegalStateException("SSE 对话端点调用失败", e);
        }
    }

    /**
     * 轮询 chat_run 状态直到出现终态（COMPLETED/CANCELLED/ERROR）。
     *
     * @param runId    Run ID
     * @param timeoutMs 最长等待毫秒数
     * @return 终态字符串；超时未达终态返回当前状态（由用例断言失败暴露）
     */
    protected String awaitTerminalStatus(Long runId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status = jdbcTemplate.queryForObject("SELECT status FROM chat_run WHERE id = ?", String.class, runId);
            if (isTerminal(status)) {
                return status;
            }
            sleepQuietly(300);
        }
        log.warn("轮询超时未达终态: runId={}, 当前状态={}", runId, status);
        return status;
    }

    /** 判断 run 状态是否为终态 */
    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "ERROR".equals(status);
    }

    /** 静默休眠（轮询间隔用） */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 断言登录成功并返回 accessToken（供用例复用） */
    protected String loginAndGetToken(String username, String device) {
        JsonNode body = login(username, TEST_PASSWORD, device);
        assertEquals(0, body.get("code").asInt(), "登录应返回业务码 0");
        JsonNode data = body.get("data");
        assertNotNull(data, "登录响应 data 不应为空");
        String token = data.get("accessToken").asText();
        assertTrue(token != null && !token.isEmpty(), "登录应签发 Access Token");
        return token;
    }
}
