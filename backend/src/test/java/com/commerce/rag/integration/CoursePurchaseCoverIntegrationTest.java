package com.commerce.rag.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.commerce.rag.test.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;

/**
 * 课程购买 + 封面上传 + 公开详情三链路集成测试（契约 A/B/C/D 真实执行）
 *
 * <p>在 IntegrationTestBase（Testcontainers PG + Redis + mock 模型）基础上追加真实 MinIO 容器
 * （pgsty/minio，与 docker-compose.dev.yml 同镜像同凭证），覆盖：
 * <ol>
 *   <li>报名链接链路（A.2）：教师创建课程 → 响应与 DB 均为服务端生成链接（请求体值被忽略），
 *       更新接口传 enrollmentLink 不生效；</li>
 *   <li>购买链路（B.2）：学生登录购买 → 插入 ACTIVE 记录，重复购买幂等不重复插行，
 *       课程不存在/已下架 404；</li>
 *   <li>公开详情链路（C.2）：免登录 GET 详情含价格，未知课程 404，列表含价格；</li>
 *   <li>封面链路（D.2）：教师 multipart 上传（uuid 落盘 0/ 目录）→ 免登录 GET 相对 URL
 *       回读字节流（Content-Type + 公开缓存头）→ 白名单外键/不存在对象 404、非法类型 400。</li>
 * </ol>
 *
 * <p>说明：本类 @DynamicPropertySource 追加 minio.* 属性（指向真实容器），与其它集成测试类
 * 属性集不同——Spring 按属性源缓存键独立建上下文，不影响既有类的共享上下文。
 *
 * @author commerce-rag
 */
class CoursePurchaseCoverIntegrationTest extends IntegrationTestBase {

    private static final Logger log = LoggerFactory.getLogger(CoursePurchaseCoverIntegrationTest.class);

    private static final String TEACHER = "cpc_teacher";
    private static final String STUDENT = "cpc_student";

    /** 1x1 透明 PNG 字节（真实图片内容，上传/回读链路全真执行） */
    private static final byte[] PNG_BYTES = new byte[] {
        (byte) 0x89,
        0x50,
        0x4E,
        0x47,
        0x0D,
        0x0A,
        0x1A,
        0x0A,
        0x00,
        0x00,
        0x00,
        0x0D,
        0x49,
        0x48,
        0x44,
        0x52,
        0x00,
        0x00,
        0x00,
        0x01,
        0x00,
        0x00,
        0x00,
        0x01,
        0x08,
        0x06,
        0x00,
        0x00,
        0x00,
        0x1F,
        0x15,
        (byte) 0xC4,
        (byte) 0x89,
        0x00,
        0x00,
        0x00,
        0x0A,
        0x49,
        0x44,
        0x41,
        0x54,
        0x78,
        (byte) 0x9C,
        0x63,
        0x00,
        0x01,
        0x00,
        0x00,
        0x05,
        0x00,
        0x01,
        0x0D,
        0x0A,
        0x2D,
        (byte) 0xB4,
        0x00,
        0x00,
        0x00,
        0x00,
        0x49,
        0x45,
        0x4E,
        0x44,
        (byte) 0xAE,
        0x42,
        0x60,
        (byte) 0x82
    };

    // ── 真实 MinIO 容器（本类独有：镜像/凭证/命令与 docker-compose.dev.yml 一致） ──
    static final GenericContainer<?> minio = startMinio();

    private static GenericContainer<?> startMinio() {
        GenericContainer<?> container = new GenericContainer<>("pgsty/minio:latest")
                .withEnv("MINIO_ROOT_USER", "rag_storage")
                .withEnv("MINIO_ROOT_PASSWORD", "rag_storage_2024")
                .withCommand("minio server /data --console-address :9001")
                .withExposedPorts(9000);
        container.start();
        log.info("Testcontainers MinIO 已启动: endpoint=http://{}:{}", container.getHost(), container.getMappedPort(9000));
        return container;
    }

    @DynamicPropertySource
    static void registerMinioProperties(DynamicPropertyRegistry registry) {
        // 覆盖 application-test.yml 的不可达端口——本类封面链路走真实 MinIO（bucket 由 @PostConstruct 创建）
        registry.add("minio.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
        registry.add("minio.access-key", () -> "rag_storage");
        registry.add("minio.secret-key", () -> "rag_storage_2024");
    }

    @BeforeEach
    void setUpCourseTables() {
        // 课程域数据隔离（基类清理 chat/sys 表，此处补 course 两表；先子表后主表）
        jdbcTemplate.update("DELETE FROM course_enrollment");
        jdbcTemplate.update("DELETE FROM course_info");
    }

    /** 携带 Bearer Token 的 POST JSON 请求 */
    private ResponseEntity<String> postWithToken(String path, String token, Map<String, Object> jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null && !token.isEmpty()) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /** 携带 Bearer Token 的 PUT JSON 请求 */
    private ResponseEntity<String> putWithToken(String path, String token, Map<String, Object> jsonBody) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(jsonBody, headers), String.class);
    }

    /** 创建课程（教师登录态），返回课程 ID */
    private Long createCourse(String teacherToken, Map<String, Object> body) throws Exception {
        ResponseEntity<String> response = postWithToken("/api/v1/admin/courses", teacherToken, body);
        assertEquals(200, response.getStatusCode().value(), "创建课程应 200");
        JsonNode data = readData(response);
        // Long 字段经全局序列化输出 string，统一 asText 再解析
        return Long.parseLong(data.get("id").asText());
    }

    /** 解析 ApiResponse.data 节点（code=0 前置断言） */
    private JsonNode readData(ResponseEntity<String> response) throws Exception {
        assertNotNull(response.getBody(), "响应体不应为空");
        JsonNode root = objectMapper.readTree(response.getBody());
        assertEquals(0, root.get("code").asInt(), "业务码应 0: " + response.getBody());
        return root.get("data");
    }

    /** 构造带恶意报名链接的创建请求体（服务端应忽略该字段，契约 A.2.2） */
    private Map<String, Object> courseBody(String title) {
        return Map.of(
                "title", title, "category", "后端开发", "price", 299.00, "enrollmentLink", "http://evil.example/enroll");
    }

    /** 预置教师并登录 */
    private String teacherToken() {
        registerUser(TEACHER, "TEACHER");
        return loginAndGetToken(TEACHER, DEFAULT_DEVICE);
    }

    /** 预置学生并登录 */
    private String studentToken() {
        registerUser(STUDENT, "STUDENT");
        return loginAndGetToken(STUDENT, DEFAULT_DEVICE);
    }

    // ==================== 链路 1：报名链接自动生成（契约 A.2） ====================

    @Test
    void 创建课程自动生成报名链接且更新接口不覆盖() throws Exception {
        String token = teacherToken();

        Long courseId = createCourse(token, courseBody("Java 后端实战"));

        // DB 中 enrollment_link 为服务端生成值（insert + update 同事务落库）
        String dbLink = jdbcTemplate.queryForObject(
                "SELECT enrollment_link FROM course_info WHERE id = ?", String.class, courseId);
        assertEquals("http://localhost:3000/courses/" + courseId, dbLink, "DB 报名链接应为服务端生成值");

        // 更新接口传 enrollmentLink 不生效（服务端管理字段）
        ResponseEntity<String> updateResp = putWithToken(
                "/api/v1/admin/courses/" + courseId, token, Map.of("enrollmentLink", "http://another.example/x"));
        assertEquals(200, updateResp.getStatusCode().value());
        String dbLinkAfter = jdbcTemplate.queryForObject(
                "SELECT enrollment_link FROM course_info WHERE id = ?", String.class, courseId);
        assertEquals("http://localhost:3000/courses/" + courseId, dbLinkAfter, "更新接口不得覆盖服务端生成的报名链接");
    }

    // ==================== 链路 2：学生购买（契约 B.2） ====================

    @Test
    void 学生购买课程成功且重复购买幂等不重复插行() throws Exception {
        String teacher = teacherToken();
        Long courseId = createCourse(teacher, courseBody("Java 后端实战"));
        String student = studentToken();

        // 首次购买 → 成功 VO（courseId/status=ACTIVE/purchased=true）
        ResponseEntity<String> first =
                postWithToken("/api/v1/student/courses/" + courseId + "/purchase", student, Map.of());
        assertEquals(200, first.getStatusCode().value());
        JsonNode firstData = readData(first);
        assertEquals(String.valueOf(courseId), firstData.get("courseId").asText());
        assertEquals("ACTIVE", firstData.get("status").asText());
        assertTrue(firstData.get("purchased").asBoolean());

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM course_enrollment WHERE course_id = ? AND student_id = (SELECT id FROM sys_user WHERE username = ?)",
                Integer.class,
                courseId,
                STUDENT);
        assertEquals(1, rows, "首次购买应插入一条 ACTIVE 选课记录");

        // 重复购买 → 幂等返回相同成功结构，不重复插行、不报 409
        ResponseEntity<String> second =
                postWithToken("/api/v1/student/courses/" + courseId + "/purchase", student, Map.of());
        assertEquals(200, second.getStatusCode().value());
        readData(second);
        Integer rowsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM course_enrollment WHERE course_id = ? AND student_id = (SELECT id FROM sys_user WHERE username = ?)",
                Integer.class,
                courseId,
                STUDENT);
        assertEquals(1, rowsAfter, "重复购买不得重复插行（幂等）");

        // 未知课程 → 404（不泄露存在性）
        ResponseEntity<String> missing = postWithToken("/api/v1/student/courses/999999/purchase", student, Map.of());
        assertEquals(404, missing.getStatusCode().value());
    }

    @Test
    void 学生购买下架课程返回404() throws Exception {
        String teacher = teacherToken();
        Long courseId = createCourse(teacher, courseBody("已下架课程"));
        // 管理端将课程置 ARCHIVED（非 ACTIVE）
        ResponseEntity<String> archive =
                putWithToken("/api/v1/admin/courses/" + courseId, teacher, Map.of("status", "ARCHIVED"));
        assertEquals(200, archive.getStatusCode().value());
        String student = studentToken();

        ResponseEntity<String> response =
                postWithToken("/api/v1/student/courses/" + courseId + "/purchase", student, Map.of());

        assertEquals(404, response.getStatusCode().value(), "下架课程购买应 404");
    }

    // ==================== 链路 3：公开详情 + 价格（契约 C.2） ====================

    @Test
    void 公开课程详情免登录返回含价格字段() throws Exception {
        String teacher = teacherToken();
        Long courseId = createCourse(teacher, courseBody("Java 后端实战"));

        // 免登录（无 Authorization 头）访问公开详情
        ResponseEntity<String> response = getWithToken("/api/v1/public/courses/" + courseId, null);
        assertEquals(200, response.getStatusCode().value());
        JsonNode data = readData(response);
        assertEquals(String.valueOf(courseId), data.get("id").asText());
        assertEquals("Java 后端实战", data.get("title").asText());
        assertEquals(299.00, data.get("price").asDouble(), 0.001, "公开详情应含价格（单位元）");

        // 公开列表同样下发价格字段
        ResponseEntity<String> listResp = getWithToken("/api/v1/public/courses", null);
        assertEquals(200, listResp.getStatusCode().value());
        JsonNode listData = readData(listResp);
        assertTrue(listData.isArray() && listData.size() >= 1, "公开列表应含刚创建的课程");
        assertEquals(299.00, listData.get(0).get("price").asDouble(), 0.001, "公开列表应含价格字段");

        // 未知课程 → 404
        ResponseEntity<String> missing = getWithToken("/api/v1/public/courses/999999", null);
        assertEquals(404, missing.getStatusCode().value());
    }

    // ==================== 链路 4：封面上传 + 公开访问（契约 D.2） ====================

    /**
     * multipart 封面上传请求
     *
     * <p>part 的 Content-Type 经 HttpEntity 显式指定——Spring FormHttpMessageConverter 会按
     * 文件名自动推导 part MIME（png→image/png），不显式指定则无法构造「扩展名与 MIME 不一致」用例。
     */
    private ResponseEntity<String> uploadCover(String token, String filename, String mime, byte[] bytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        ByteArrayResource fileResource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mime));
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(fileResource, fileHeaders));
        return restTemplate.postForEntity("/api/v1/admin/courses/cover", new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void 封面上传落盘并经公开端点免登录回读() throws Exception {
        String token = teacherToken();

        // 上传 → 契约响应（objectKey 形如 0/{uuid32}.png + 相对 URL）
        ResponseEntity<String> uploadResp = uploadCover(token, "cover.png", "image/png", PNG_BYTES);
        assertEquals(200, uploadResp.getStatusCode().value());
        JsonNode data = readData(uploadResp);
        String objectKey = data.get("objectKey").asText();
        String url = data.get("url").asText();
        assertTrue(objectKey.matches("0/[0-9a-f]{32}\\.png"), "objectKey 应为 0/{uuid32}.png 形态: " + objectKey);
        assertEquals("/api/v1/public/covers/" + objectKey, url, "url 应为封面公开访问相对路径");

        // 免登录 GET 相对 URL → 200 字节流 + Content-Type + 公开缓存头（真实 MinIO 回读）
        ResponseEntity<byte[]> image =
                restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), byte[].class);
        assertEquals(200, image.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, image.getHeaders().getContentType());
        String cacheControl = image.getHeaders().getCacheControl();
        assertNotNull(cacheControl);
        assertTrue(
                cacheControl.contains("max-age=86400") && cacheControl.contains("public"), "应携带公开缓存头: " + cacheControl);
        assertEquals(PNG_BYTES.length, image.getBody().length, "回读字节应与上传一致（内容不可变）");

        // 合法格式但对象不存在 → 404（NoSuchKey）
        String ghostKey = "0/" + "a".repeat(32) + ".png";
        ResponseEntity<String> ghost = getWithToken("/api/v1/public/covers/" + ghostKey, null);
        assertEquals(404, ghost.getStatusCode().value());

        // 白名单外键（19 位雪花 kbId 前缀，跨前缀读取）→ 404
        ResponseEntity<String> crossPrefix =
                getWithToken("/api/v1/public/covers/1948633200000000001/" + "b".repeat(32) + ".png", null);
        assertEquals(404, crossPrefix.getStatusCode().value());
    }

    @Test
    void 封面上传非法类型被拒400() throws Exception {
        String token = teacherToken();

        // 扩展名不在白名单（gif）→ 400，消息含文件名与允许清单
        ResponseEntity<String> badExt = uploadCover(token, "cover.gif", "image/gif", PNG_BYTES);
        assertEquals(400, badExt.getStatusCode().value());
        assertTrue(badExt.getBody() != null && badExt.getBody().contains("cover.gif"), "错误消息应含文件名");

        // MIME 与扩展名不匹配（.png + image/jpeg）→ 400
        ResponseEntity<String> badMime = uploadCover(token, "fake.png", "image/jpeg", PNG_BYTES);
        assertEquals(400, badMime.getStatusCode().value());
    }

    @Test
    void 未登录上传封面返回401() {
        // /api/v1/admin/** 受 AuthInterceptor 登录保护（契约 D.2.4 未登录 401）
        ResponseEntity<String> response = uploadCover(null, "cover.png", "image/png", PNG_BYTES);
        assertEquals(401, response.getStatusCode().value());
    }
}
