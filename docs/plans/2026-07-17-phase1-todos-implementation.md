# Phase 1 剩余 TODO 实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. 同时加载 spring-ai-alibaba-best-practices 技能和 mybatis-plus-best-practise 技能。

**Goal:** 完成 Phase 1 F#1 Agent 核心架构的 4 个外部集成 TODO，使检索管道、Rerank、摘要压缩和课程查询全部接入真实服务。

**Architecture:** 4 个 TODO 按依赖顺序逐个实现，每个走完整 TDD 循环（先写测试 → 验证失败 → 实现代码 → 验证通过 → 编译验证）。测试策略为 Mock 单元测试（Mockito mock 外部依赖），不启动真实基础设施。

**Tech Stack:** Spring AI 1.1.2 + SAA 1.1.2.0 + Milvus SDK 2.4.8 + MyBatis-Plus 3.5.12 + JUnit 5 + Mockito

---

## API 速查（已通过 javap 实证）

### RerankModel（SAA DashScope 自动装配）
```java
// 接口: com.alibaba.cloud.ai.model.RerankModel
// 实现: com.alibaba.cloud.ai.dashscope.rerank.DashScopeRerankModel
// Spring Bean: spring-ai-alibaba-starter-dashscope 自动装配
RerankRequest request = new RerankRequest(query, documents);  // List<Document>
RerankResponse response = rerankModel.call(request);
List<DocumentWithScore> results = response.getResults();
// DocumentWithScore.getScore() → Double
// DocumentWithScore.getOutput() → Document
// Document.getText() → String
// Document.getMetadata() → Map<String, Object>
```

### EmbeddingModel（Spring AI 标准接口）
```java
// 接口: org.springframework.ai.embedding.EmbeddingModel
// 实现: DashScopeEmbeddingModel（自动装配）
float[] vector = embeddingModel.embed(queryText);  // 默认方法
```

### ChatModel（Spring AI 标准接口）
```java
// 接口: org.springframework.ai.chat.model.ChatModel
// 实现: DashScopeChatModel（自动装配，主模型 qwen3.7-max）
// 指定小模型: 用 Prompt + DashScopeChatOptions.builder().withModel("qwen-turbo").build()
String response = chatModel.call(prompt);  // Prompt 含 options 指定 qwen-turbo
```

### MilvusClient（Milvus SDK 2.4.8 v1 API）
```java
// 创建: new MilvusServiceClient(ConnectParam)
ConnectParam connectParam = ConnectParam.newBuilder()
    .withHost(milvusHost).withPort(milvusPort).build();
MilvusServiceClient client = new MilvusServiceClient(connectParam);

// 搜索:
SearchParam searchParam = SearchParam.newBuilder()
    .withCollectionName("knowledge_chunks")
    .withVectorFieldName("embedding")        // 向量字段名
    .withFloatVectors(List.of(floatList))    // List<List<Float>>
    .withTopK(20)
    .withExpr("collection_type == \"TECHNICAL_QA\"")  // 标量过滤
    .withOutFields(List.of("chunk_id", "content", "source", "heading_path",
                           "collection_type", "course_id"))
    .withMetricType(MetricType.COSINE)
    .withParams("{\"ef\": 64}")               // HNSW 参数
    .build();
R<SearchResults> result = client.search(searchParam);
SearchResultsWrapper wrapper = new SearchResultsWrapper(result.getData().getResults());
List<IDScore> idScores = wrapper.getIDScore(0);  // queryIndex=0
// idScore.getLongID() / idScore.getScore()
// wrapper.getFieldData("content", 0) → List<?>
```

### MyBatis-Plus（参见 mybatis-plus-best-practise 技能）
```java
// Entity: @TableName + 继承 Serializable
// Mapper: extends BaseMapper<T>
// Service: extends ServiceImpl<Mapper, Entity> 或 IService<T>
// 单表 CRUD: lambdaQuery() / lambdaUpdate()
// 多表 JOIN: XML 映射文件
```

---

## Task 1: RerankService — DashScopeRerankModel 集成

**依赖注入：** `RerankModel`（Spring 自动装配 Bean）

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/retrieval/RerankService.java`
- Test: `backend/src/test/java/com/commerce/rag/retrieval/RerankServiceTest.java`

### Step 1: 写失败测试

```java
@ExtendWith(MockitoExtension.class)
class RerankServiceTest {

    @Mock RerankModel rerankModel;
    @InjectMocks RerankService rerankService;
    // 注意: RerankService 构造函数需要改为注入 RerankModel

    @Test
    @DisplayName("rerank 正常调用 — 返回按分数降序、过滤低于阈值的结果")
    void rerank_normalCall_returnsSortedFilteredResults() {
        // Given: 3 个 chunk，其中 1 个 score < threshold(0.30)
        List<KnowledgeChunk> chunks = List.of(
            chunk("c1", "content1", 0.0),
            chunk("c2", "content2", 0.0),
            chunk("c3", "content3", 0.0)
        );
        // Mock rerankModel 返回 3 个 DocumentWithScore
        when(rerankModel.call(any(RerankRequest.class)))
            .thenReturn(new RerankResponse(List.of(
                dws("c1", "content1", 0.95),
                dws("c2", "content2", 0.45),
                dws("c3", "content3", 0.15)  // 低于 0.30 → 过滤
            )));
        // When
        List<KnowledgeChunk> result = rerankService.rerank("query", chunks);
        // Then
        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).chunkId());
        assertEquals(0.95, result.get(0).score(), 0.001);
    }

    @Test
    @DisplayName("rerank 空输入 — 返回空列表")
    void rerank_emptyInput_returnsEmptyList() {
        List<KnowledgeChunk> result = rerankService.rerank("query", Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("rerank 异常 — 降级返回原列表前10")
    void rerank_exception_fallbackToOriginalList() {
        List<KnowledgeChunk> chunks = List.of(chunk("c1", "content1", 0.0));
        when(rerankModel.call(any())).thenThrow(new RuntimeException("API超时"));
        List<KnowledgeChunk> result = rerankService.rerank("query", chunks);
        assertEquals(1, result.size());  // 降级返回原列表
    }
}
```

### Step 2: 验证测试失败
```bash
cd backend && mvn test -Dtest=RerankServiceTest -q
# 预期: FAIL — RerankService 构造函数未注入 RerankModel
```

### Step 3: 实现代码

修改 `RerankService.java`：
1. 构造函数注入 `RerankModel`
2. `rerank()` 方法：
   - 空 → 返回空列表
   - 将 `List<KnowledgeChunk>` 转为 `List<Document>`（content + metadata 带 chunkId）
   - 调用 `rerankModel.call(new RerankRequest(query, documents))`
   - 遍历 `response.getResults()`，过滤 `score < threshold`
   - 映射回 `KnowledgeChunk`（带 rerank score）
   - try-catch 降级：异常时返回原列表前 10

### Step 4: 验证测试通过
```bash
cd backend && mvn test -Dtest=RerankServiceTest -q
# 预期: PASS
```

### Step 5: 全量编译验证
```bash
cd backend && mvn compile -q
# 预期: BUILD SUCCESS
```

---

## Task 2: CustomSummarizationHook — LLM 摘要生成

**依赖注入：** `ChatModel`（Spring 自动装配，主模型 qwen3.7-max）+ 通过 `DashScopeChatOptions` 指定 qwen-turbo

**Files:**
- Modify: `backend/src/main/java/com/commerce/rag/bot/hook/CustomSummarizationHook.java`
- Test: `backend/src/test/java/com/commerce/rag/bot/hook/CustomSummarizationHookTest.java`

### Step 1: 写失败测试

```java
@ExtendWith(MockitoExtension.class)
class CustomSummarizationHookTest {

    @Mock ChatModel chatModel;
    @Mock RunnableConfig config;

    // 注意: CustomSummarizationHook 需改为注入 ChatModel
    // 构造: CustomSummarizationHook(ChatModel chatModel, @Value threshold, @Value keepRecent)

    @Test
    @DisplayName("generateSummary 首次摘要 — 无旧摘要，生成新摘要")
    void generateSummary_noPrevious_generatesNewSummary() {
        when(chatModel.call(any(Prompt.class)))
            .thenReturn(new ChatResponse(...));  // 返回含摘要文本的响应
        // 验证: 返回的文本以 "## 对话摘要:" 开头
    }

    @Test
    @DisplayName("generateSummary 增量摘要 — 有旧摘要，融合生成")
    void generateSummary_withPrevious_mergesSummaries() {
        // 验证: Prompt 中包含旧摘要文本
    }

    @Test
    @DisplayName("generateSummary 异常 — 降级返回占位摘要")
    void generateSummary_exception_fallbackPlaceholder() {
        when(chatModel.call(any())).thenThrow(new RuntimeException("LLM超时"));
        // 验证: 返回不含 "(摘要待 LLM 生成)" 的降级文本
    }
}
```

### Step 2: 验证测试失败
```bash
cd backend && mvn test -Dtest=CustomSummarizationHookTest -q
# 预期: FAIL — 构造函数未注入 ChatModel
```

### Step 3: 实现代码

修改 `CustomSummarizationHook.java`：
1. 构造函数增加 `ChatModel` 参数 + `@Value("${context.summary-model:qwen-turbo}") String summaryModel`
2. `generateSummary()` 方法：
   - 构建 `DashScopeChatOptions` 指定 `summaryModel`（qwen-turbo）
   - 构建 Prompt：SystemMessage（摘要指令）+ UserMessage（待摘要消息 + 旧摘要）
   - 调用 `chatModel.call(prompt)` 获取摘要文本
   - 返回 `SUMMARY_PREFIX + summaryText`
   - try-catch 降级：异常时返回 `SUMMARY_PREFIX + "(摘要生成失败，请参考最近消息)"`
3. 摘要 prompt 模板：
   - 无旧摘要：`请将以下对话内容压缩为简洁摘要...`
   - 有旧摘要：`请将以下旧摘要与新对话内容融合为一份更新后的摘要...`

### Step 4: 验证测试通过
### Step 5: 全量编译验证

---

## Task 3: SearchKnowledgeTool — Milvus 向量检索

**依赖注入：** `EmbeddingModel`（自动装配）+ `MilvusClient`（需新建配置类）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/config/MilvusConfig.java`
- Modify: `backend/src/main/java/com/commerce/rag/bot/tool/SearchKnowledgeTool.java`
- Test: `backend/src/test/java/com/commerce/rag/bot/tool/SearchKnowledgeToolTest.java`

### Step 1: 写失败测试

```java
@ExtendWith(MockitoExtension.class)
class SearchKnowledgeToolTest {

    @Mock EmbeddingModel embeddingModel;
    @Mock MilvusClient milvusClient;
    @Mock FusionService fusionService;
    @Mock RerankService rerankService;
    @InjectMocks SearchKnowledgeTool tool;

    @Test
    @DisplayName("searchSingle 正常检索 — 返回 KnowledgeChunk 列表")
    void searchSingle_normal_returnsChunks() {
        TypedQuery query = new TypedQuery(IntentType.TECHNICAL_QA, "如何配置Redis", null);
        // Mock embedding
        when(embeddingModel.embed("如何配置Redis")).thenReturn(new float[]{0.1f, 0.2f, ...});
        // Mock Milvus search
        when(milvusClient.search(any(SearchParam.class))).thenReturn(mockSearchResult());
        // 验证: 返回非空 chunk 列表，chunkId/content/score 正确
    }

    @Test
    @DisplayName("searchSingle 带 courseId — 过滤表达式包含 course_id")
    void searchSingle_withCourseId_filterIncludesCourseId() {
        TypedQuery query = new TypedQuery(IntentType.COURSE_INFO, "课程大纲", "COURSE_123");
        // 验证: SearchParam 的 expr 包含 course_id == "COURSE_123"
    }

    @Test
    @DisplayName("searchSingle Milvus异常 — 降级返回空列表")
    void searchSingle_milvusException_returnsEmptyList() {
        when(embeddingModel.embed(any())).thenReturn(new float[]{0.1f});
        when(milvusClient.search(any())).thenThrow(new RuntimeException("连接超时"));
        // 验证: 返回空列表，不抛异常
    }
}
```

### Step 2: 验证测试失败

### Step 3: 实现代码

**3a. 创建 `MilvusConfig.java`：**
```java
@Configuration
public class MilvusConfig {
    @Bean
    public MilvusClient milvusClient(
            @Value("${milvus.host}") String host,
            @Value("${milvus.port}") int port) {
        ConnectParam connectParam = ConnectParam.newBuilder()
                .withHost(host).withPort(port).build();
        return new MilvusServiceClient(connectParam);
    }
}
```

**3b. 修改 `SearchKnowledgeTool.java`：**
1. 构造函数增加 `EmbeddingModel` + `MilvusClient` 参数
2. `searchSingle()` 方法：
   - `float[] vector = embeddingModel.embed(query.queryText())`
   - 转为 `List<List<Float>>` 供 Milvus 使用
   - 构建过滤表达式：`collection_type == "${query.collectionType()}"` + 可选 `&& course_id == "${query.courseId()}"`
   - 构建 `SearchParam`（collection/vectorField/topK/expr/outFields/metricType/params）
   - 调用 `milvusClient.search(searchParam)`
   - 用 `SearchResultsWrapper` 解析结果
   - 映射为 `List<KnowledgeChunk>`
   - try-catch 降级返回空列表

### Step 4: 验证测试通过
### Step 5: 全量编译验证

---

## Task 4: CourseApiTool — MyBatis-Plus 数据层

**依赖注入：** `CourseQueryService`（新建，封装 MyBatis-Plus 查询）

**Files:**
- Create: `backend/src/main/java/com/commerce/rag/entity/CourseInfo.java`
- Create: `backend/src/main/java/com/commerce/rag/entity/CourseContent.java`
- Create: `backend/src/main/java/com/commerce/rag/entity/CourseSchedule.java`
- Create: `backend/src/main/java/com/commerce/rag/mapper/CourseInfoMapper.java`
- Create: `backend/src/main/java/com/commerce/rag/mapper/CourseContentMapper.java`
- Create: `backend/src/main/java/com/commerce/rag/mapper/CourseScheduleMapper.java`
- Create: `backend/src/main/java/com/commerce/rag/service/CourseQueryService.java`
- Modify: `backend/src/main/java/com/commerce/rag/bot/tool/CourseApiTool.java`
- Test: `backend/src/test/java/com/commerce/rag/bot/tool/CourseApiToolTest.java`
- Modify: `backend/src/main/java/com/commerce/rag/CommerceRagApplication.java`（加 `@MapperScan`）

### DB 表结构速查

```
course_info: id, title, description, cover_image, category, instructor_name,
             price, duration, tags(JSONB), rating, learning_count,
             enrollment_link, status, created_by, deleted, created_at, updated_at

course_content: id, course_id, content_type, content(TEXT), sort_order, deleted
  -- content_type 枚举: SYLLABUS / DESCRIPTION / PREREQUISITES / TARGET_AUDIENCE

course_schedule: id, course_id, start_date, end_date, schedule_type,
                 location, instructor_name, capacity, enrolled, status, ...
```

### Step 1: 写失败测试

```java
@ExtendWith(MockitoExtension.class)
class CourseApiToolTest {

    @Mock CourseQueryService courseQueryService;
    @InjectMocks CourseApiTool tool;

    @Test
    @DisplayName("listCourses 关键词搜索 — 返回分页结果")
    void listCourses_withKeyword_returnsPagedResult() {
        when(courseQueryService.searchCourses("Java", 1))
            .thenReturn(new PageResult<>(List.of(mockCourseInfo()), 1, 10, 1));
        CourseListResult result = tool.listCourses("Java", 1);
        assertEquals(1, result.total());
        assertEquals(1, result.courses().size());
    }

    @Test
    @DisplayName("queryCourseDetail — 聚合 header + content + schedule")
    void queryCourseDetail_aggregatesAllData() {
        when(courseQueryService.findCourseById("123"))
            .thenReturn(mockCourseInfo());
        when(courseQueryService.findContentsByCourseId("123"))
            .thenReturn(List.of(mockContent("SYLLABUS", "大纲...")));
        when(courseQueryService.findNextSchedule("123"))
            .thenReturn(mockSchedule());
        CourseDetailResult result = tool.queryCourseDetail("123");
        assertNotNull(result.summary());
        assertEquals("大纲...", result.syllabus());
    }

    @Test
    @DisplayName("queryEnrollment — 返回价格 + 报名链接 + 排期")
    void queryEnrollment_returnsEnrollmentInfo() {
        when(courseQueryService.findCourseById("123")).thenReturn(mockCourseInfo());
        when(courseQueryService.findNextSchedule("123")).thenReturn(mockSchedule());
        EnrollmentResult result = tool.queryEnrollment("123");
        assertNotNull(result.enrollmentUrl());
    }
}
```

### Step 2: 验证测试失败

### Step 3: 实现代码

**3a. Entity 类（3 个）：**
- `CourseInfo` — `@TableName("course_info")`，字段与 DB 对应，`tags` 用 `String` 或 `TypeHandler` 处理 JSONB
- `CourseContent` — `@TableName("course_content")`
- `CourseSchedule` — `@TableName("course_schedule")`
- 所有 Entity 继承序列化接口，`deleted` 字段配 `@TableLogic`

**3b. Mapper 接口（3 个）：**
- `CourseInfoMapper extends BaseMapper<CourseInfo>`
- `CourseContentMapper extends BaseMapper<CourseContent>`
- `CourseScheduleMapper extends BaseMapper<CourseSchedule>`

**3c. `CourseQueryService`：**
- `searchCourses(keyword, page)` → `lambdaQuery().like(title, keyword).page(...)`
- `findCourseById(courseId)` → `getById(courseId)`
- `findContentsByCourseId(courseId)` → `lambdaQuery().eq(course_id).orderByAsc(sort_order).list()`
- `findNextSchedule(courseId)` → `lambdaQuery().eq(course_id).ge(start_date, now).orderByAsc(start_date).last("LIMIT 1").one()`

**3d. 修改 `CourseApiTool`：**
- 注入 `CourseQueryService`
- `listCourses()` → 调用 `courseQueryService.searchCourses()`，映射为 `CourseListResult`
- `queryCourseDetail()` → 聚合 header + content + schedule，映射为 `CourseDetailResult`
- `queryEnrollment()` → 调用 `courseQueryService`，映射为 `EnrollmentResult`

**3e. 修改 `CommerceRagApplication`：**
- 添加 `@MapperScan("com.commerce.rag.mapper")`

### Step 4: 验证测试通过
### Step 5: 全量编译验证

---

## 实现顺序与依赖

```
Task 1 (RerankService) ──────────────┐
                                      ↓
Task 3 (SearchKnowledgeTool) ← 依赖 RerankService（已注入）

Task 2 (CustomSummarizationHook) ─── 独立

Task 4 (CourseApiTool) ───────────── 独立
```

推荐执行顺序：Task 1 → Task 2 → Task 3 → Task 4

---

## 编译命令（Windows Maven 环境）

```bash
# 单元测试
cd /d/code/py/project/commerce-customer/backend && \
"/d/code/java/jdk/jdk17/bin/java.exe" \
  -classpath "D:\code\java\maven\apache-maven-3.9.16\boot\plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=D:\code\java\maven\apache-maven-3.9.16\bin\m2.conf" \
  "-Dmaven.home=D:\code\java\maven\apache-maven-3.9.16" \
  "-Dmaven.multiModuleProjectDirectory=D:\code\py\project\commerce-customer\backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher test -q

# 仅编译
cd /d/code/py/project/commerce-customer/backend && \
"/d/code/java/jdk/jdk17/bin/java.exe" \
  -classpath "D:\code\java\maven\apache-maven-3.9.16\boot\plexus-classworlds-2.11.0.jar" \
  "-Dclassworlds.conf=D:\code\java\maven\apache-maven-3.9.16\bin\m2.conf" \
  "-Dmaven.home=D:\code\java\maven\apache-maven-3.9.16" \
  "-Dmaven.multiModuleProjectDirectory=D:\code\py\project\commerce-customer\backend" \
  org.codehaus.plexus.classworlds.launcher.Launcher compile -q
```

---

## 共享约定

1. **所有注释、日志使用中文**（项目规范）
2. **降级策略**：每个外部调用都必须 try-catch，异常时返回安全默认值（空列表/占位文本），不中断主流程
3. **日志规范**：`log.info()` 记录关键节点，`log.warn()` 记录降级，`log.debug()` 记录详细数据
4. **DTO 映射**：Entity → DTO 的转换逻辑放在 Tool 类中，不放在 Service 层
5. **MyBatis-Plus 约定**：单表用 `lambdaQuery()`，多表 JOIN 用 XML（本 Task 全是单表查询）
6. **Milvus 向量格式**：`float[]` → `List<List<Float>>` 转换（Milvus v1 API 要求）
7. **RerankModel 调用**：将 `KnowledgeChunk.content` 作为 `Document.text`，`chunkId` 放 metadata
