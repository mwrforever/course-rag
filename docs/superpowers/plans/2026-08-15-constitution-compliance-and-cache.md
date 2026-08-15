# 宪法合规全量修正 + Caffeine 缓存 + git 基线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 AGENTS.md 宪法存量违规（注入/Wrapper/全路径/手写转换/Entity 出边界/拼 SQL），引入 MapStruct + Caffeine 缓存，完成 backend git 基线提交。

**Architecture:** 分 10 个独立可测任务顺序推进：依赖与缓存基建 → 注入合规 → Wrapper/全路径机械合规 → MapStruct 转换器 → VO 化 → 两处 JdbcTemplate 迁移 XML → 两处查询缓存（课程/统计）→ 基线提交。每个任务独立提交、独立跑测。

**Tech Stack:** Java 17 / Spring Boot 3.5.8 / MyBatis-Plus 3.5.12 / MapStruct 1.6.3 + lombok-mapstruct-binding 0.2.0 / Caffeine（Boot parent 管理版本）/ Maven 3.9.16

## Global Constraints

（抄自 spec，每个任务隐式包含）
1. 注释/日志全中文；方法带中文 Javadoc；行级注释描述业务意图
2. 依赖注入统一 `private final` + Lombok `@RequiredArgsConstructor`，禁 @Autowired（DeviceKickService 手写构造器例外——Lua 加载合法场景）
3. Wrapper 一律 `Wrappers.lambdaQuery()/lambdaUpdate()` 链式，禁 `new LambdaQueryWrapper/LambdaUpdateWrapper`；禁止全路径类名（import 后短名）
4. 层间转换必须 MapStruct；**修改转换接口或 DTO 后必须 `mvn.cmd clean` 再编译**（增量编译不重新生成实现类）
5. Entity 禁止出 service 边界；controller 入参 DTO、出参 VO（`controller/vo` 包）；VO 不得含敏感列（DocumentVO 无 sourcePath、DocumentChunkVO 无 denseVector）
6. 禁止业务层拼接 SQL 字符串，复杂 SQL 走 mapper XML（只允许 select/insert/update/delete/where/if/foreach/set/choose 标签）
7. 缓存一致性铁律：先写 DB（事务内）→ 后失效缓存；缓存必须有失效时间
8. 查询必带分页（maxLimit 2000）；按需 select 字段，禁 SELECT *
9. 测试与实现同一次提交；因改动失效的旧测试直接删除，禁止留过渡
10. 提交纪律：每个任务只 `git add` 本任务涉及文件（禁 `git add -A`，**Task 10 基线提交是用户授权的唯一例外**）；提交信息中文语义化
11. 门禁：`cd backend && mvn.cmd test` 全绿 + `mvn.cmd spotless:apply`（或 spotless:check）+ `mvn.cmd checkstyle:check`
12. 本地仓库缺 mapstruct/caffeine jar，联网下载已授权（仓库 D:/code/envs/maven/3.9.16/repo）

---

### Task 1: 依赖与缓存基础设施（pom + CacheConfig）

**Files:**
- Modify: `backend/pom.xml`（依赖 + maven-compiler-plugin annotationProcessorPaths）
- Create: `backend/src/main/java/com/commerce/rag/config/CacheConfig.java`
- Test: `backend/src/test/java/com/commerce/rag/config/CacheConfigTest.java`

**Interfaces:**
- Produces: Spring bean `Cache<String, Object> courseQueryCache`（TTL 5min，max 512）；`Cache<String, Object> dashboardStatsCache`（TTL 60s，max 32）——Task 8/9 注入使用

- [ ] **Step 1: pom.xml 加 4 个依赖**（`<dependencies>` 内、Lombok 依赖之后）

```xml
        <!-- MapStruct 对象映射（层间转换，禁止手写；与 Lombok 经 binding 协同） -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>1.6.3</version>
        </dependency>

        <!-- Caffeine 本地缓存（课程查询 / Dashboard 统计，perf P2-2/P2-3） -->
        <dependency>
            <groupId>com.github.ben-manes.caffeine</groupId>
            <artifactId>caffeine</artifactId>
        </dependency>
```

- [ ] **Step 2: pom.xml 配置 annotationProcessorPaths**（`<build><plugins>` 内、spotless 插件之前加 maven-compiler-plugin；覆盖 Boot parent 默认配置，**三件套顺序不能乱**）

```xml
            <!-- 注解处理器路径：Lombok + MapStruct 协同（binding 必须，否则 MapStruct 读不到 Lombok getter） -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok-mapstruct-binding</artifactId>
                            <version>0.2.0</version>
                        </path>
                        <path>
                            <groupId>org.mapstruct</groupId>
                            <artifactId>mapstruct-processor</artifactId>
                            <version>1.6.3</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
```

- [ ] **Step 3: 验证依赖可解析**

Run: `cd backend && mvn.cmd dependency:resolve -q`
Expected: BUILD SUCCESS（联网下载 mapstruct/caffeine jar 到本地仓库）

- [ ] **Step 4: 新建 CacheConfig.java**

```java
package com.commerce.rag.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地缓存配置 —— 提供课程查询与 Dashboard 统计两个 Caffeine 缓存实例
 *
 * <p>课程查询缓存（perf P2-2）：CourseQueryService 查询结果，TTL 5 分钟，容量 512；
 * 失效钩子挂在课程/排期写方法（先写 DB 后失效，一致性铁律）。
 *
 * <p>Dashboard 统计缓存（perf P2-3）：三端点统计结果，TTL 60 秒兜底，
 * 文档上传/ETL 终态/反馈提交时由写方主动 invalidateAll。
 *
 * @author commerce-rag
 */
@Configuration
public class CacheConfig {

    /** 课程查询缓存：键格式 search:{keyword}:{page} / course:{id} / contents:{id} / schedule:{id} */
    @Bean
    public Cache<String, Object> courseQueryCache() {
        return Caffeine.newBuilder()
                .maximumSize(512)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }

    /** Dashboard 统计缓存：键格式 dashboardStats:{operatorId}:{isAdmin} / feedbackStats:{period}:{...} / feedbackTrend:{days} */
    @Bean
    public Cache<String, Object> dashboardStatsCache() {
        return Caffeine.newBuilder()
                .maximumSize(32)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }
}
```

- [ ] **Step 5: 新建 CacheConfigTest**

```java
package com.commerce.rag.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 本地缓存配置测试 —— 验证两个 Cache bean 可构建且 TTL/容量配置生效 */
@DisplayName("CacheConfig 缓存配置测试")
class CacheConfigTest {

    private final CacheConfig cacheConfig = new CacheConfig();

    @Test
    @DisplayName("课程查询缓存：可写入读取，5 分钟后过期")
    void courseQueryCache_putGetAndExpire() {
        Cache<String, Object> cache = cacheConfig.courseQueryCache();
        cache.put("course:1", "value");
        assertThat(cache.getIfPresent("course:1")).isEqualTo("value");
        assertThat(cache.getIfPresent("course:2")).isNull();
        // Caffeine 基于写入时间窗口过期：此处断言过期策略配置存在（expireAfterWrite=5min）
        assertThat(cache.policy().expireAfterWrite()).isPresent();
    }

    @Test
    @DisplayName("Dashboard 统计缓存：可写入读取，容量受限")
    void dashboardStatsCache_putGet() {
        Cache<String, Object> cache = cacheConfig.dashboardStatsCache();
        cache.put("stats:1", 42L);
        assertThat(cache.getIfPresent("stats:1")).isEqualTo(42L);
        assertThat(cache.policy().expireAfterWrite()).isPresent();
    }
}
```

- [ ] **Step 6: 跑测试**

Run: `cd backend && mvn.cmd test -Dtest=CacheConfigTest`
Expected: 2 个测试 PASS（首次编译会生成 mapstruct-processor，无转换器时为空转）

- [ ] **Step 7: 提交**

```bash
git add backend/pom.xml backend/src/main/java/com/commerce/rag/config/CacheConfig.java backend/src/test/java/com/commerce/rag/config/CacheConfigTest.java
git commit -m "chore: 引入 MapStruct/Caffeine 依赖与缓存配置（annotationProcessorPaths 三件套）"
```

---

### Task 2: 依赖注入合规（10 个 service → @RequiredArgsConstructor）

**Files:**
- Modify（9 个 @Autowired service）: `service/ChatSessionService.java`、`service/UserFeedbackService.java`、`service/ChatMessageService.java`、`service/KnowledgeBaseService.java`、`service/EnrollmentService.java`、`service/DocumentChunkService.java`、`service/CourseService.java`、`service/CourseScheduleService.java`、`service/DocumentService.java`
- Modify（手写构造器）: `service/SysUserService.java`
- Test: `test/service/CourseServiceTest.java`、`test/service/DocumentServiceTest.java`、`test/service/CourseScheduleServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 pom（无 bean 依赖）
- Produces: 10 个 service 全部变为"Lombok 生成全参构造器"——后续任务修改这些 service 时直接加 `private final` 字段即可

- [ ] **Step 1: 9 个 service 替换注入方式**

对每个文件（以 CourseService 为例，其余同规则）：
- 删除 `import org.springframework.beans.factory.annotation.Autowired;`
- 删除每个 `@Autowired` 注解行（含其上方的 @Autowired 与字段之间不留空行差异）
- 类上（`@Service` 下方）加 `import lombok.RequiredArgsConstructor;` 与 `@RequiredArgsConstructor` 注解
- 每个注入字段改 `private final Xxx xxx;`
- 保留其它非注入字段不变（如 `private static final Logger log`、`private static final ObjectMapper JSON_MAPPER`）

```java
@Service
@RequiredArgsConstructor
public class CourseService {
    private static final Logger log = LoggerFactory.getLogger(CourseService.class);
    private final CourseInfoMapper courseInfoMapper;
    private final CourseContentMapper courseContentMapper;
    // ... 其余 5 个 mapper / EtlPipeline 同理
}
```

DocumentService 特例：`@Qualifier("etlPool")` 必须保留在字段上（Lombok 会复制字段注解到构造器参数，Spring 按名称注入）：

```java
    @Qualifier("etlPool")
    private final ThreadPoolExecutor etlPool;
```

（import `org.springframework.beans.factory.annotation.Qualifier` 保留。）

- [ ] **Step 2: SysUserService 手写构造器 → @RequiredArgsConstructor**

删除 :45-54 手写构造器，类上加 `@RequiredArgsConstructor`，4 个字段（userMapper/passwordEncoder/deviceKickService/courseTeacherMapper）改 `private final`。无初始化逻辑，直接替换。

- [ ] **Step 3: 修改 3 个测试的注入方式**

`CourseServiceTest.java`（:62-76 区域）：`new CourseService()` + 反射按字段名注入 → 改为构造器传参：

```java
    @BeforeEach
    void setUp() {
        courseService = new CourseService(
                courseInfoMapper, courseContentMapper, courseScheduleMapper,
                courseTeacherMapper, courseEnrollmentMapper, documentChunkMapper, etlPipeline);
    }
```

`DocumentServiceTest.java`（:66 区域）：同法，按 DocumentService 构造器参数顺序传 6 个 mock（documentMapper/chunkMapper/knowledgeBaseMapper/minioStorageService/etlPipeline/etlPool——etlPool 是 ThreadPoolExecutor 类型，测试里用真实 `Executors.newFixedThreadPool(1)` 或 mock；若测试原用反射注入 mock ThreadPoolExecutor，则直接传该 mock）。

`CourseScheduleServiceTest.java`（:38 区域）：`new CourseScheduleService(scheduleMapper, courseService)`（两个 mock 字段名以测试内实际 @Mock 为准）。

注意：构造器参数顺序 = @RequiredArgsConstructor 按**字段声明顺序**生成；实施时对照各 service 当前字段声明顺序。

- [ ] **Step 4: 跑对应单测 + 全量编译**

Run: `cd backend && mvn.cmd test -Dtest=CourseServiceTest,DocumentServiceTest,CourseScheduleServiceTest,DocumentChunkServiceTest,KnowledgeBaseServiceTest,UserFeedbackServiceTest,SysUserServiceTest,EtlPipelineTest`
Expected: 全 PASS（@InjectMocks 的测试自动走构造器注入，无需改动）

- [ ] **Step 5: 验证 @Autowired 归零**

Run: `grep -rn "@Autowired" backend/src/main/java`
Expected: 仅剩 DeviceKickService 无 @Autowired（它本就不用）；输出为空或仅无关命中

- [ ] **Step 6: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/ backend/src/test/java/com/commerce/rag/service/
git commit -m "refactor: 10 个 service 依赖注入合规化（@RequiredArgsConstructor，DeviceKickService 合法例外保留）"
```

---

### Task 3: Wrapper 链式化 + 全路径类名（机械合规）

**Files:**
- Modify（12 个文件 79 处）: `service/CourseService.java`、`service/ChatSessionService.java`、`service/DocumentChunkService.java`、`service/SysUserService.java`、`service/SysLoginRecordService.java`、`service/EnrollmentService.java`、`service/KnowledgeBaseService.java`、`service/DocumentService.java`、`service/UserFeedbackService.java`、`service/CourseScheduleService.java`、`service/ChatMessageService.java`、`auth/AuthSessionService.java`
- Modify（全路径 10 个文件）: 上述涉及 + `controller/dto/PageResponse.java`、`controller/AdminDocumentController.java`、`etl/EtlPipeline.java`、`storage/MinioStorageService.java`、`auth/DeviceKickService.java`、`bot/hook/CustomSummarizationHook.java`、`bot/graph/PromptLoader.java`、`config/MilvusCollectionInitializer.java`

**Interfaces:** 无新接口。纯机械替换，公共方法签名零变化。

- [ ] **Step 1: 替换 79 处 Wrapper 实例化**

对每个文件：`import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;` 与 `...update.LambdaUpdateWrapper;` 删除；新增 `import com.baomidou.mybatisplus.core.toolkit.Wrappers;`（CourseService 已 import 则跳过）。

替换规则（保持链式语义逐字不变）：
- `new LambdaQueryWrapper<X>()` → `Wrappers.<X>lambdaQuery()`
- `new LambdaUpdateWrapper<X>()` → `Wrappers.<X>lambdaUpdate()`
- 无显式泛型的（如 `new LambdaQueryWrapper<>()`）→ `Wrappers.lambdaQuery()`

示例（CourseService:198）：

```java
// 改造前
LambdaUpdateWrapper<CourseInfo> wrapper = new LambdaUpdateWrapper<CourseInfo>().eq(CourseInfo::getId, courseId);
// 改造后
var wrapper = Wrappers.<CourseInfo>lambdaUpdate().eq(CourseInfo::getId, courseId);
```

注意：保留变量类型声明亦可（`LambdaUpdateWrapper<CourseInfo> wrapper = Wrappers.<CourseInfo>lambdaUpdate()...`），不强制 var。删除全部 `new LambdaQueryWrapper/new LambdaUpdateWrapper` 后，原 import 必须清理干净（spotless removeUnusedImports 会兜底，但手动删更稳）。

- [ ] **Step 2: 替换 12 处全路径类名**

逐处改为 import 短名：
- `service/CourseService.java:428,440` → `import com.commerce.rag.controller.dto.ScheduleDTO;` 后写 `List<ScheduleDTO>`（两处）
- `controller/dto/PageResponse.java:19` → `import com.baomidou.mybatisplus.core.metadata.IPage;`
- `controller/AdminDocumentController.java:154` → `import org.springframework.core.io.InputStreamResource;`
- `service/KnowledgeBaseService.java:171,172` → `import java.util.Objects; import java.util.stream.Collectors;`
- `service/DocumentService.java:183` → `import java.util.stream.Collectors;`
- `etl/EtlPipeline.java:453` → `import java.util.stream.Collectors;`
- `storage/MinioStorageService.java:151` → `import java.util.stream.Collectors;`
- `auth/DeviceKickService.java:286` → `import java.time.Duration;`（:464 的 java.sql.ResultSet/SQLException 在本任务先加 import 短名，Task 6 删 mapLoginRecord 时移除）
- `bot/hook/CustomSummarizationHook.java:286` → `import java.util.Collections;`
- `bot/graph/PromptLoader.java:122` → `import java.util.List;`
- `config/MilvusCollectionInitializer.java:318` → `import java.util.Map;`

- [ ] **Step 3: 验证归零**

Run: `grep -rn "new LambdaQueryWrapper\|new LambdaUpdateWrapper" backend/src/main/java`（期望空）
Run: `grep -rn "com\.baomidou\|java\.util\.\|java\.time\.\|java\.sql\.\|org\.springframework\.core\.io\.InputStreamResource" backend/src/main/java --include="*.java" | grep -v "^.*import "`（期望空——import 行外无全路径）

- [ ] **Step 4: 跑全量单测**

Run: `cd backend && mvn.cmd test`
Expected: 全 PASS（行为零变化；个别测试若 mock 了 `new LambdaQueryWrapper` 构造则同步改为 `any()`/`Wrappers.lambdaQuery()`）

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/
git commit -m "refactor: 79 处 Wrapper 链式化（Wrappers 静态工厂）+ 12 处全路径类名短名化"
```

---

### Task 4: MapStruct 转换器（SysUser/Course/Schedule）与接线

**Files:**
- Create: `service/SysUserConverter.java`、`service/CourseConverter.java`、`service/ScheduleConverter.java`
- Create Test: `test/service/SysUserConverterTest.java`、`test/service/CourseConverterTest.java`、`test/service/ScheduleConverterTest.java`
- Modify: `service/SysUserService.java`（toDTO 删除→注入转换器）、`service/CourseService.java`（toDTO 改调转换器）、`controller/AdminScheduleController.java`（toDTO 删除→注入转换器）、`controller/AdminCourseController.java:157`（内联→转换器）

**Interfaces:**
- Produces（Task 5 复用同模式）:
  - `SysUserConverter.toDTO(SysUser) → UserDTO`
  - `CourseConverter.toDTO(CourseInfo, List<CourseContent>, List<CourseSchedule>, List<Long>) → CourseDTO`；`CourseConverter.toContentDTO(CourseContent) → CourseDTO.CourseContentDTO`
  - `ScheduleConverter.toDTO(CourseSchedule) → ScheduleDTO`
- Consumes: 既有 `controller/dto` 的 CourseDTO/ScheduleDTO/UserDTO（位置不动，观察③）

- [ ] **Step 1: 新建 SysUserConverter**

```java
package com.commerce.rag.service;

import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import org.mapstruct.Mapper;

/**
 * 系统用户转换器 —— SysUser 实体 ↔ UserDTO
 *
 * <p>MapStruct 编译期生成实现，禁止手写转换（工程宪法）。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface SysUserConverter {

    /** 实体 → 用户 DTO（6 字段全同名，无需 @Mapping） */
    UserDTO toDTO(SysUser user);
}
```

- [ ] **Step 2: 新建 ScheduleConverter**

```java
package com.commerce.rag.service;

import com.commerce.rag.controller.dto.ScheduleDTO;
import com.commerce.rag.entity.CourseSchedule;
import org.mapstruct.Mapper;

/**
 * 课程排期转换器 —— CourseSchedule 实体 ↔ ScheduleDTO（11 字段全同名）
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface ScheduleConverter {

    /** 实体 → 排期 DTO */
    ScheduleDTO toDTO(CourseSchedule schedule);
}
```

- [ ] **Step 3: 新建 CourseConverter**

```java
package com.commerce.rag.service;

import com.commerce.rag.controller.dto.CourseDTO;
import com.commerce.rag.controller.dto.ScheduleDTO;
import com.commerce.rag.entity.CourseContent;
import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 课程转换器 —— CourseInfo + 关联数据 → CourseDTO
 *
 * <p>关联数据（内容/排期/教师）由 CourseService 查询后传入，转换器只做纯映射；
 * 嵌套 record 列表（CourseContentDTO/ScheduleDTO）按同名字段自动映射。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface CourseConverter {

    /** 课程实体 + 关联数据 → 课程 DTO（includeRelations=false 时传空 List） */
    @Mapping(target = "contents", source = "contents")
    @Mapping(target = "schedules", source = "schedules")
    @Mapping(target = "teacherIds", source = "teacherIds")
    CourseDTO toDTO(
            CourseInfo course,
            List<CourseContent> contents,
            List<CourseSchedule> schedules,
            List<Long> teacherIds);

    /** 内容实体 → 内容 Tab DTO（AdminCourseController 内联转换替代） */
    CourseDTO.CourseContentDTO toContentDTO(CourseContent content);
}
```

- [ ] **Step 4: 接线 SysUserService**

删除私有方法 `toDTO(SysUser)`（:352-359 区域），注入 `private final SysUserConverter sysUserConverter;`（新增 final 字段，@RequiredArgsConstructor 自动纳入），两处调用 `toDTO(user)` 改为 `sysUserConverter.toDTO(user)`（:106 create 返回处、:117 findById 返回处——以实际调用点为准，全文件搜索 `toDTO(` 替换）。

- [ ] **Step 5: 接线 CourseService**

保留公开方法 `toDTO(CourseInfo, boolean)`（controller 调用契约不变），方法体改为（关联数据查询留在 service，转换器只做纯映射）：

```java
    public CourseDTO toDTO(CourseInfo course, boolean includeRelations) {
        List<CourseContent> contents = List.of();
        List<CourseSchedule> schedules = List.of();
        List<Long> teacherIds = List.of();
        if (includeRelations) {
            contents = findContents(course.getId());
            schedules = findSchedules(course.getId());
            teacherIds = findTeacherIds(course.getId());
        }
        return courseConverter.toDTO(course, contents, schedules, teacherIds);
    }
```

删除原 16 字段手写 `new CourseDTO(...)` 与内联 `new CourseDTO.CourseContentDTO(...)`/`new com.commerce.rag.controller.dto.ScheduleDTO(...)`（Task 3 已将其短名化）。注入 `private final CourseConverter courseConverter;`。ScheduleDTO import 若不再直接引用类型（仅经转换器）可删——`List<ScheduleDTO>` 已不存在于方法体，删除 `import com.commerce.rag.controller.dto.ScheduleDTO;`（Task 3 加的）与 controller.dto 包引用（越层观察③范围缩小）。

- [ ] **Step 6: 接线 AdminScheduleController**

删除私有 `toDTO(CourseSchedule)`（:105-118），注入 `private final ScheduleConverter scheduleConverter;`（构造器加参数），3 处调用 `toDTO(schedule)` 改 `scheduleConverter.toDTO(schedule)`（:51/:64/:79）。

- [ ] **Step 7: 接线 AdminCourseController**

:156-158 内联改：

```java
        var dtos = contents.stream().map(courseConverter::toContentDTO).collect(Collectors.toList());
```

注入 `private final CourseConverter courseConverter;`（构造器参数）。

- [ ] **Step 8: 新建 3 个转换器测试**

`SysUserConverterTest`（核心 100% 覆盖要求：转换器是接口契约）：

```java
package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.controller.dto.UserDTO;
import com.commerce.rag.entity.SysUser;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** SysUserConverter 转换器测试 —— 实体到 DTO 字段映射正确性 */
@DisplayName("SysUserConverter 转换器测试")
class SysUserConverterTest {

    private final SysUserConverter converter = new SysUserConverterImpl();

    @Test
    @DisplayName("实体字段完整映射到 DTO")
    void toDTO_mapsAllFields() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("student1");
        user.setDisplayName("学生一");
        user.setRole("STUDENT");
        user.setStatus("ACTIVE");
        user.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        UserDTO dto = converter.toDTO(user);
        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.username()).isEqualTo("student1");
        assertThat(dto.displayName()).isEqualTo("学生一");
        assertThat(dto.role()).isEqualTo("STUDENT");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.createdAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0));
    }
}
```

`CourseConverterTest`：构造 CourseInfo + 内容/排期列表，断言 CourseDTO 16 字段映射 + 嵌套 contents/schedules 列表长度与字段；`toContentDTO` 断言。`ScheduleConverterTest`：11 字段映射断言。

注意：`new SysUserConverterImpl()` 需要 `mvn.cmd clean` 后编译生成实现类（Task 1 已配 annotationProcessorPaths）。测试运行前先 `mvn.cmd clean compile` 一次（或直接 `mvn.cmd test -Dtest=...`，test 阶段会自动编译 main 源码）。

- [ ] **Step 9: 跑测试（clean 强制）**

Run: `cd backend && mvn.cmd clean test -Dtest=SysUserConverterTest,CourseConverterTest,ScheduleConverterTest,SysUserServiceTest,CourseServiceTest,AdminCourseControllerTest,AdminScheduleControllerTest,CourseScheduleServiceTest`
Expected: 全 PASS

- [ ] **Step 10: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/ backend/src/main/java/com/commerce/rag/controller/AdminScheduleController.java backend/src/main/java/com/commerce/rag/controller/AdminCourseController.java backend/src/test/java/com/commerce/rag/service/
git commit -m "refactor: 手写转换替换为 MapStruct（SysUser/Course/Schedule 转换器接线）"
```

---

### Task 5: Entity 出边界 VO 化（4 controller + 4 service + 4 VO + 4 Converter）

**Files:**
- Create: `controller/vo/DocumentVO.java`、`controller/vo/KnowledgeBaseVO.java`、`controller/vo/DocumentChunkVO.java`、`controller/vo/UserFeedbackVO.java`
- Create: `service/DocumentConverter.java`、`service/KnowledgeBaseConverter.java`、`service/DocumentChunkConverter.java`、`service/UserFeedbackConverter.java`
- Create Test: `test/service/DocumentConverterTest.java`、`test/service/DocumentChunkConverterTest.java`、`test/service/KnowledgeBaseConverterTest.java`、`test/service/UserFeedbackConverterTest.java`
- Modify: `service/DocumentService.java`、`service/KnowledgeBaseService.java`、`service/DocumentChunkService.java`、`service/UserFeedbackService.java`
- Modify: `controller/AdminDocumentController.java`、`controller/AdminKnowledgeBaseController.java`、`controller/AdminChunkController.java`、`controller/AdminFeedbackController.java`
- Modify Test: `test/service/DocumentServiceTest.java`、`test/service/DocumentChunkServiceTest.java`、`test/service/KnowledgeBaseServiceTest.java`、`test/service/UserFeedbackServiceTest.java`、`test/controller/AdminDocumentControllerTest.java`、`test/controller/AdminChunkControllerTest.java`

**Interfaces:**
- Produces:
  - `DocumentConverter.toVO(Document) → DocumentVO`；`KnowledgeBaseConverter.toVO(KnowledgeBase) → KnowledgeBaseVO`；`DocumentChunkConverter.toVO(DocumentChunk) → DocumentChunkVO`；`UserFeedbackConverter.toVO(UserFeedback) → UserFeedbackVO`
  - service 签名变化：`DocumentService.upload/findById → DocumentVO`、`findPage → IPage<DocumentVO>`；`KnowledgeBaseService.create/findById → KnowledgeBaseVO`、`findPage → Page<KnowledgeBaseVO>`；`DocumentChunkService.findById(Long,Long,String)/findPage/findPending → VO/IPage<VO>`、`findContext → Map<String, DocumentChunkVO>`；`UserFeedbackService.findPage → IPage<UserFeedbackVO>`（create/findById(Long) 学生端方法保持 Entity——观察①/④）
- Consumes: Task 4 的 MapStruct 模式（@Mapper(componentModel="spring")）

- [ ] **Step 1: 新建 4 个 VO（record，字段=实体字段剔除敏感列）**

```java
package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;
import java.util.List;

/** 文档视图对象 —— controller 出参，不含内部存储路径 sourcePath */
public record DocumentVO(
        Long id,
        Long kbId,
        String title,
        String fileType,
        Long fileSize,
        String parseStatus,
        Integer chunkCount,
        String errorMessage,
        String courseId,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
```

```java
package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/** 知识库视图对象 —— controller 出参 */
public record KnowledgeBaseVO(
        Long id,
        String name,
        String description,
        String status,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {}
```

```java
package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/** 文档分片视图对象 —— controller 出参，不含向量密文 denseVector */
public record DocumentChunkVO(
        Long id,
        Long docId,
        Long kbId,
        Integer chunkIndex,
        String content,
        String headingPath,
        String parentTitle,
        Integer startPage,
        Integer endPage,
        Integer tokenCount,
        String collectionType,
        String courseId,
        String metadataJson,
        String milvusPk,
        Long parentChunkId,
        Long prevChunkId,
        Long nextChunkId,
        Integer charOffsetStart,
        Integer charOffsetEnd,
        String correctionStatus) {}
```

```java
package com.commerce.rag.controller.vo;

import java.time.LocalDateTime;

/** 用户反馈视图对象 —— controller 出参 */
public record UserFeedbackVO(
        Long id,
        Long sessionId,
        Long messageId,
        Long userId,
        Boolean isLiked,
        String intentType,
        LocalDateTime createdAt) {}
```

（DocumentChunk 实体还有 deleted 字段——@TableLogic 逻辑删除标记，不进 VO。Document 同。以实体当前字段为准，实施时对照 entity 源文件补全同名业务字段。）

- [ ] **Step 2: 新建 4 个转换器**（service 包，@Mapper(componentModel = "spring")，方法 `toVO(Xxx)`，同名自动映射，敏感字段因 VO 无此字段而自然忽略）

```java
package com.commerce.rag.service;

import com.commerce.rag.controller.vo.DocumentChunkVO;
import com.commerce.rag.entity.DocumentChunk;
import org.mapstruct.Mapper;

/**
 * 文档分片转换器 —— DocumentChunk 实体 → DocumentChunkVO（denseVector 不入 VO）
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface DocumentChunkConverter {
    DocumentChunkVO toVO(DocumentChunk chunk);
}
```

DocumentConverter / KnowledgeBaseConverter / UserFeedbackConverter 同构。

- [ ] **Step 3: DocumentService 改造**

注入 `private final DocumentConverter documentConverter;`。方法改造：
- `upload(...)`：返回 `documentConverter.toVO(doc)`（doc 为落库后实体）
- `findById(Long,Long,String)`：返回 `documentConverter.toVO(doc)`
- `findPage(...)`：返回 `IPage<DocumentVO>`——`Page<DocumentVO> voPage = new Page<>(page, size); voPage.setRecords(entityPage.getRecords().stream().map(documentConverter::toVO).toList()); voPage.setTotal(entityPage.getTotal());` 或构造 PageResponse（以现有 findPage 实现为准，保持分页语义：total/current/size 一致）
- download/downloadWithType/update/delete/reparse 不变
- **注意**：checkOwnership 等私有方法内部用 Entity，不受影响

- [ ] **Step 4: KnowledgeBaseService 改造**

注入 `private final KnowledgeBaseConverter knowledgeBaseConverter;`：
- `create → KnowledgeBaseVO`；`findById → KnowledgeBaseVO`；`findPage → Page<KnowledgeBaseVO>`（records 映射，total/current/size 保持）；update/delete 不变

- [ ] **Step 5: DocumentChunkService 改造**

注入 `private final DocumentChunkConverter chunkConverter;`：
- `findById(Long,Long,String) → DocumentChunkVO`（:78 版本）
- `findPage → IPage<DocumentChunkVO>`；`findPending → IPage<DocumentChunkVO>`；`findContext → Map<String, DocumentChunkVO>`（value 逐个转换，key 不变）
- `findById(Long)`（:66 学生端用）/ `findByCourseId` / `findByCourseIdDefault` 保持 Entity 不动

- [ ] **Step 6: UserFeedbackService 改造**

注入 `private final UserFeedbackConverter feedbackConverter;`：`findPage → IPage<UserFeedbackVO>`（records 映射）；create/delete/findStats 不变

- [ ] **Step 7: 4 个 controller 返回类型改造**

- AdminDocumentController：`ApiResponse<DocumentVO>`（:60 upload、:93 findById）、`ApiResponse<PageResponse<DocumentVO>>`（:106 findPage）
- AdminKnowledgeBaseController：`ApiResponse<KnowledgeBaseVO>`（:49/:58）、`ApiResponse<PageResponse<KnowledgeBaseVO>>`（:71）
- AdminChunkController：`ApiResponse<DocumentChunkVO>`（:49/:62）、`ApiResponse<Map<String, DocumentChunkVO>>`（:104）、`ApiResponse<PageResponse<DocumentChunkVO>>`（:130）
- AdminFeedbackController：`ApiResponse<PageResponse<UserFeedbackVO>>`（:43）
- 删对应 Entity import，加 VO import；`PageResponse.of(...)` 泛型自动适配

- [ ] **Step 8: 测试适配**

- DocumentServiceTest/DocumentChunkServiceTest/KnowledgeBaseServiceTest/UserFeedbackServiceTest：断言 `findById/upload/findPage` 返回值字段处改为 VO 断言（字段名同名，多为 `getXxx()` → `xxx()` record 访问器；分页断言 `records` 元素类型变化）
- AdminDocumentControllerTest/AdminChunkControllerTest：mock service 返回值类型改 VO，断言响应体字段同名字段
- 新增 4 个转换器测试（同 Task 4 模式）：**必须含敏感字段不泄露断言**——构造含 sourcePath/denseVector 的实体，断言 VO 无对应字段（编译期即保证，运行时断言 `DocumentVO` 无 `sourcePath()` 访问器编译不过，改为断言 VO 字段集合不含：用反射或直接断言可访问字段列表）

```java
    @Test
    @DisplayName("DocumentVO 不含内部路径 sourcePath（敏感字段不泄露）")
    void toVO_omitsSourcePath() {
        Document doc = new Document();
        doc.setSourcePath("/minio/internal/secret.pdf");
        DocumentVO vo = converter.toVO(doc);
        // record 编译期已固定字段集合，此处断言无泄露访问器
        assertThat(vo).isNotNull();
        assertThat(vo.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("sourcePath");
    }
```

- [ ] **Step 9: 跑测试（clean 强制，转换器新增）**

Run: `cd backend && mvn.cmd clean test -Dtest=DocumentConverterTest,DocumentChunkConverterTest,KnowledgeBaseConverterTest,UserFeedbackConverterTest,DocumentServiceTest,DocumentChunkServiceTest,KnowledgeBaseServiceTest,UserFeedbackServiceTest,AdminDocumentControllerTest,AdminChunkControllerTest,AdminDashboardControllerTest`
Expected: 全 PASS

- [ ] **Step 10: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/controller/vo/ backend/src/main/java/com/commerce/rag/service/ backend/src/main/java/com/commerce/rag/controller/AdminDocumentController.java backend/src/main/java/com/commerce/rag/controller/AdminKnowledgeBaseController.java backend/src/main/java/com/commerce/rag/controller/AdminChunkController.java backend/src/main/java/com/commerce/rag/controller/AdminFeedbackController.java backend/src/test/java/
git commit -m "refactor: Entity 出边界 VO 化（4 个 admin controller，敏感列不泄露）"
```

---

### Task 6: DeviceKickService JdbcTemplate → mapper XML

**Files:**
- Modify: `mapper/SysLoginRecordMapper.java`、`mapper/SysTokenBlacklistMapper.java`（加方法）
- Create: `resources/mapper/SysLoginRecordMapper.xml`、`resources/mapper/SysTokenBlacklistMapper.xml`
- Modify: `auth/DeviceKickService.java`（JdbcTemplate → mapper，删 mapLoginRecord）
- Modify Test: `test/auth/DeviceKickServiceTest.java`

**Interfaces:**
- Produces:
  - `SysTokenBlacklistMapper.countByJti(String jti) → Long`
  - `SysLoginRecordMapper.selectActiveForUpdate(Long userId, String deviceType) → List<SysLoginRecord>`
  - `SysLoginRecordMapper.updateStatusById(Long id) → int`
  - `SysLoginRecordMapper.updateStatusByIdIfActive(Long id) → int`
  - `SysLoginRecordMapper.updateStatusByUserAndJtiActive(Long userId, String jtiAt) → int`
  - `SysLoginRecordMapper.selectActiveByUserId(Long userId) → List<SysLoginRecord>`
- Consumes: 既有 `SysLoginRecord`/`SysTokenBlacklist` 实体

- [ ] **Step 1: 扩展两个 mapper 接口**

```java
@Mapper
public interface SysTokenBlacklistMapper extends BaseMapper<SysTokenBlacklist> {

    /** 按 jti 统计黑名单记录数（deleted=0 未删除），DeviceKickService PG 降级查询用 */
    Long countByJti(String jti);
}
```

```java
@Mapper
public interface SysLoginRecordMapper extends BaseMapper<SysLoginRecord> {

    /** 锁定某用户+设备类型的活跃登录记录（FOR UPDATE 行锁，PG 降级互踢用） */
    List<SysLoginRecord> selectActiveForUpdate(Long userId, String deviceType);

    /** 按 id 置 REVOKED（updated_at 数据库生成） */
    int updateStatusById(Long id);

    /** 按 id 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByIdIfActive(Long id);

    /** 按 user_id + jti_at 置 REVOKED（仅 ACTIVE 记录，幂等） */
    int updateStatusByUserAndJtiActive(Long userId, String jtiAt);

    /** 查询某用户全部活跃登录记录 */
    List<SysLoginRecord> selectActiveByUserId(Long userId);
}
```

- [ ] **Step 2: 新建 SysTokenBlacklistMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.commerce.rag.mapper.SysTokenBlacklistMapper">

    <!-- 按 jti 统计黑名单记录数（PG 降级查询，与 Redis 黑名单互补） -->
    <select id="countByJti" resultType="java.lang.Long">
        SELECT COUNT(*) FROM sys_token_blacklist WHERE jti = #{jti} AND deleted = 0
    </select>
</mapper>
```

- [ ] **Step 3: 新建 SysLoginRecordMapper.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.commerce.rag.mapper.SysLoginRecordMapper">

    <!-- 锁定用户+设备类型的活跃记录（FOR UPDATE 行锁，PG 降级互踢的原子性保障） -->
    <select id="selectActiveForUpdate" resultType="com.commerce.rag.entity.SysLoginRecord">
        SELECT id, user_id, jti_at, jti_rt, device_type, device_info, ip_address, expires_at, status
        FROM sys_login_record
        WHERE user_id = #{userId} AND device_type = #{deviceType} AND status = 'ACTIVE' AND deleted = 0
        FOR UPDATE
    </select>

    <!-- 按 id 置 REVOKED（updated_at 由数据库生成） -->
    <update id="updateStatusById">
        UPDATE sys_login_record SET status = 'REVOKED', updated_at = now() WHERE id = #{id}
    </update>

    <!-- 按 id 置 REVOKED（仅 ACTIVE，幂等） -->
    <update id="updateStatusByIdIfActive">
        UPDATE sys_login_record SET status = 'REVOKED', updated_at = now()
        WHERE id = #{id} AND status = 'ACTIVE'
    </update>

    <!-- 按 user_id + jti_at 置 REVOKED（仅 ACTIVE，幂等） -->
    <update id="updateStatusByUserAndJtiActive">
        UPDATE sys_login_record SET status = 'REVOKED', updated_at = now()
        WHERE user_id = #{userId} AND jti_at = #{jtiAt} AND status = 'ACTIVE'
    </update>

    <!-- 查询用户全部活跃登录记录（禁用用户时收集 jti） -->
    <select id="selectActiveByUserId" resultType="com.commerce.rag.entity.SysLoginRecord">
        SELECT id, user_id, jti_at, jti_rt, device_type, device_info, ip_address, expires_at, status
        FROM sys_login_record
        WHERE user_id = #{userId} AND status = 'ACTIVE' AND deleted = 0
    </select>
</mapper>
```

（MyBatis 全局 map-underscore-to-camel-case 开启时 resultType 自动映射下划线列；若 application.yml 未开启该配置，则改用 resultMap 显式映射——实施时以实际配置为准，若自动映射不生效则补 resultMap。）

- [ ] **Step 4: DeviceKickService 改造**

- 构造器参数 `JdbcTemplate jdbcTemplate` → `SysLoginRecordMapper loginRecordMapper`（已有同名字段！注意现有字段 `private final SysLoginRecordMapper loginRecordMapper;` 已存在——JdbcTemplate 与它并存。改造后：删除 `private final JdbcTemplate jdbcTemplate;` 字段与构造器参数，新增/复用 loginRecordMapper 字段即可，另加 `private final SysTokenBlacklistMapper tokenBlacklistMapper;` 字段——已存在（:53）！所以只需删除 JdbcTemplate 字段与参数，其余字段保留）
- 6 处调用点替换：
  - `:185-186` → `tokenBlacklistMapper.countByJti(jti)`，判空 `count != null && count > 0`
  - `:311-315` → `loginRecordMapper.selectActiveForUpdate(userId, deviceType)`
  - `:330-331` → `loginRecordMapper.updateStatusById(oldRecord.getId())`
  - `:355-357` → `loginRecordMapper.updateStatusById(record.getId())`
  - `:380-383` → `loginRecordMapper.updateStatusByIdIfActive(record.getId())`
  - `:406-410` → `loginRecordMapper.updateStatusByUserAndJtiActive(userId, result.oldJtiAt())`
  - `:458-461` → `loginRecordMapper.selectActiveByUserId(userId)`
- 删除私有方法 `mapLoginRecord`（:464-479）与 `import java.sql.ResultSet` / `import java.sql.SQLException`（Task 3 加的短名 import 一并删）
- 删除 `import org.springframework.jdbc.core.JdbcTemplate;`
- 手写构造器保留（Lua 加载），仅参数表变化；类级注释更新依赖说明（中文）

- [ ] **Step 5: DeviceKickServiceTest 适配**

现有测试 mock JdbcTemplate 的 stub（`when(jdbcTemplate.queryForObject(...))`、`when(jdbcTemplate.query(...))`、`verify(jdbcTemplate).update(...)`）全部改为对应 mapper 方法（countByJti/selectActiveForUpdate/updateStatusById/updateStatusByIdIfActive/updateStatusByUserAndJtiActive/selectActiveByUserId）。构造器传参相应调整（新增 tokenBlacklistMapper mock；若测试用反射注入则同步）。事务与降级语义断言保持不变。

- [ ] **Step 6: 跑测试**

Run: `cd backend && mvn.cmd test -Dtest=DeviceKickServiceTest,TokenServiceTest,AuthInterceptorTest,AuthSessionServiceTest`
Expected: 全 PASS（SQL 语义逐字等价）

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/mapper/SysLoginRecordMapper.java backend/src/main/java/com/commerce/rag/mapper/SysTokenBlacklistMapper.java backend/src/main/resources/mapper/SysLoginRecordMapper.xml backend/src/main/resources/mapper/SysTokenBlacklistMapper.xml backend/src/main/java/com/commerce/rag/auth/DeviceKickService.java backend/src/test/java/com/commerce/rag/auth/DeviceKickServiceTest.java
git commit -m "refactor: DeviceKickService JdbcTemplate 拼 SQL 迁移 mapper XML（FOR UPDATE 行锁语义保持）"
```

---

### Task 7: ChatMessageService/EnrollmentService 拼 SQL → mapper XML + EnrollmentConverter

**Files:**
- Modify: `mapper/ChatMessageMapper.java`、`mapper/SysUserMapper.java`（加方法）
- Create: `resources/mapper/ChatMessageMapper.xml`、`resources/mapper/SysUserMapper.xml`
- Modify: `service/ChatMessageService.java`、`service/EnrollmentService.java`
- Create: `service/EnrollmentConverter.java` + `test/service/EnrollmentConverterTest.java`
- Modify Test: 相关（ChatRequestWorkerTest 若断言 batchInsert 行为、EnrollmentService 相关测试）

**Interfaces:**
- Produces:
  - `ChatMessageMapper.batchInsert(List<ChatMessage> messages)`（XML foreach 多值插入）
  - `SysUserMapper.selectByIdsIn(List<Long> ids) → List<SysUser>`（仅 id/username/displayName 列）
  - `EnrollmentConverter.toDTO(SysUser user, CourseEnrollment enrollment) → StudentDTO`
- Consumes: 既有 ChatMessage/SysUser 实体

- [ ] **Step 1: ChatMessageMapper + XML**

```java
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

    /** 批量插入消息（run 结束后一次性写入，替代原 JdbcTemplate.batchUpdate 语义） */
    void batchInsert(List<ChatMessage> messages);
}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.commerce.rag.mapper.ChatMessageMapper">

    <!-- 批量插入消息：多值 INSERT，列与旧 BATCH_INSERT_SQL 逐字一致（deleted 固定 0，created_at 数据库生成） -->
    <insert id="batchInsert">
        INSERT INTO chat_message (id, session_id, role, content, intent_type, sources_json,
                                  token_count, run_id, seq, confidence, trace_id, message_type, deleted, created_at)
        VALUES
        <foreach collection="list" item="m" separator=",">
            (#{m.id}, #{m.sessionId}, #{m.role}, #{m.content}, #{m.intentType},
             #{m.sourcesJson}, #{m.tokenCount}, #{m.runId}, #{m.seq}, #{m.confidence},
             #{m.traceId}, #{m.messageType}, 0, now())
        </foreach>
    </insert>
</mapper>
```

- [ ] **Step 2: ChatMessageService 改造**

删除 BATCH_INSERT_SQL 常量（:30-33）与 `import org.springframework.jdbc.core.JdbcTemplate;`，删除 jdbcTemplate 字段（Task 2 已 final 化），`batchInsert` 方法体改：

```java
    public void batchInsert(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (ChatMessage msg : messages) {
            if (msg.getId() == null) {
                msg.setId(IdWorker.getId());
            }
        }
        log.info("批量插入消息: count={}", messages.size());
        messageMapper.batchInsert(messages);
    }
```

（sourcesJson 空值处理：原代码 `msg.getSourcesJson() != null ? ... : "[]"`——ChatMessage 实体若字段非 null 则无需处理；若可能为 null，实施时在循环内 `if (msg.getSourcesJson() == null) msg.setSourcesJson("[]");` 保持语义。）

- [ ] **Step 3: SysUserMapper + XML**

```java
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 按 ID 批量查询用户（仅 id/username/displayName 列，供选课学生列表组装） */
    List<SysUser> selectByIdsIn(List<Long> ids);
}
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.commerce.rag.mapper.SysUserMapper">

    <!-- 按 ID 批量查询用户（按需返回字段，避免 SELECT *） -->
    <select id="selectByIdsIn" resultType="com.commerce.rag.entity.SysUser">
        SELECT id, username, display_name FROM sys_user
        WHERE id IN
        <foreach collection="list" item="id" open="(" separator="," close=")">
            #{id}
        </foreach>
        AND deleted = 0
    </select>
</mapper>
```

- [ ] **Step 4: 新建 EnrollmentConverter**

```java
package com.commerce.rag.service;

import com.commerce.rag.controller.dto.StudentDTO;
import com.commerce.rag.entity.CourseEnrollment;
import com.commerce.rag.entity.SysUser;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 选课学生转换器 —— SysUser + CourseEnrollment → StudentDTO
 *
 * <p>多源映射：id/username/displayName 来自用户，enrolledAt/status 来自选课记录。
 *
 * @author commerce-rag
 */
@Mapper(componentModel = "spring")
public interface EnrollmentConverter {

    /** 用户实体 + 选课记录 → 学生 DTO */
    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "username", source = "user.username")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "enrolledAt", source = "enrollment.enrolledAt")
    @Mapping(target = "status", source = "enrollment.status")
    StudentDTO toDTO(SysUser user, CourseEnrollment enrollment);
}
```

- [ ] **Step 5: EnrollmentService.findStudents 改造**

```java
    public List<StudentDTO> findStudents(Long courseId, Long currentUserId, boolean isAdmin) {
        courseService.checkOwnership(courseId, currentUserId, isAdmin);
        List<CourseEnrollment> enrollments = enrollmentMapper.selectList(
                Wrappers.<CourseEnrollment>lambdaQuery()
                        .eq(CourseEnrollment::getCourseId, courseId)
                        .eq(CourseEnrollment::getStatus, "ACTIVE")
                        .orderByDesc(CourseEnrollment::getEnrolledAt));
        if (enrollments.isEmpty()) {
            return List.of();
        }
        List<Long> studentIds =
                enrollments.stream().map(CourseEnrollment::getStudentId).toList();
        List<SysUser> users = sysUserMapper.selectByIdsIn(studentIds);
        Map<Long, CourseEnrollment> enrollmentByUser =
                enrollments.stream().collect(Collectors.toMap(CourseEnrollment::getStudentId, e -> e));
        return users.stream()
                .map(user -> enrollmentConverter.toDTO(user, enrollmentByUser.get(user.getId())))
                .toList();
    }
```

注入：`private final SysUserMapper sysUserMapper;` + `private final EnrollmentConverter enrollmentConverter;`；删除 jdbcTemplate 字段与 import；删除 `import java.sql.ResultSet` 相关（若有）；删除原 ResultSet 行映射代码。

- [ ] **Step 6: EnrollmentConverterTest**

构造 SysUser（id/username/displayName）+ CourseEnrollment（studentId/enrolledAt/status），断言 StudentDTO 5 字段映射正确；enrollment 为 null 时 enrolledAt/status 为 null（可选断言）。

- [ ] **Step 7: 跑测试（clean）**

Run: `cd backend && mvn.cmd clean test -Dtest=EnrollmentConverterTest,ChatRequestWorkerTest,EnrollmentServiceTest`
Expected: 全 PASS（若 EnrollmentServiceTest 不存在则跳过该名；ChatRequestWorkerTest 若 mock 了 jdbcTemplate 改 mock messageMapper.batchInsert）

- [ ] **Step 8: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/mapper/ChatMessageMapper.java backend/src/main/java/com/commerce/rag/mapper/SysUserMapper.java backend/src/main/resources/mapper/ChatMessageMapper.xml backend/src/main/resources/mapper/SysUserMapper.xml backend/src/main/java/com/commerce/rag/service/ChatMessageService.java backend/src/main/java/com/commerce/rag/service/EnrollmentService.java backend/src/main/java/com/commerce/rag/service/EnrollmentConverter.java backend/src/test/java/com/commerce/rag/service/EnrollmentConverterTest.java
git commit -m "refactor: ChatMessage/Enrollment 拼 SQL 迁移 mapper XML（批量插入/IN 查询语义保持）"
```

---

### Task 8: CourseQueryService 缓存 + 失效钩子（perf P2-2）

**Files:**
- Modify: `service/CourseQueryService.java`、`service/CourseService.java`、`service/CourseScheduleService.java`
- Create Test: `test/service/CourseQueryServiceTest.java`
- Modify Test: `test/service/CourseServiceTest.java`（构造器加 courseQueryService mock）、`test/service/CourseScheduleServiceTest.java`

**Interfaces:**
- Consumes: Task 1 的 `courseQueryCache` bean
- Produces:
  - `CourseQueryService.evictCourse(Long courseId)`——精确失效 course/contents/schedule 键 + 清理 search:* 前缀键
  - 写方法失效钩子：CourseService.createCourse/updateCourse/deleteCourse/updateContent/batchUpdateContents 末尾、CourseScheduleService.create/update/delete 末尾调用 evictCourse

- [ ] **Step 1: CourseQueryService 加缓存**

类上加 Lombok `@RequiredArgsConstructor`（`import lombok.RequiredArgsConstructor;`），注入 `private final Cache<String, Object> courseQueryCache;`（`com.github.benmanes.caffeine.cache.Cache`）。4 个方法改为缓存读写：

```java
    public IPage<CourseInfo> searchCourses(String keyword, int page) {
        String key = "search:" + (keyword == null ? "" : keyword) + ":" + page;
        IPage<CourseInfo> cached = (IPage<CourseInfo>) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("搜索课程: keyword={}, page={}", keyword, page);
        IPage<CourseInfo> result = Db.lambdaQuery(CourseInfo.class)
                .select(CourseInfo::getId, CourseInfo::getTitle, CourseInfo::getCategory,
                        CourseInfo::getPrice, CourseInfo::getStatus, CourseInfo::getTags,
                        CourseInfo::getDuration, CourseInfo::getRating)
                .like(StringUtils.hasText(keyword), CourseInfo::getTitle, keyword)
                .eq(CourseInfo::getStatus, "ACTIVE")
                .orderByDesc(CourseInfo::getRating)
                .page(new Page<>(page, PAGE_SIZE));
        courseQueryCache.put(key, result);
        return result;
    }

    public CourseInfo findCourseById(String courseId) {
        String key = "course:" + courseId;
        CourseInfo cached = (CourseInfo) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        log.info("查询课程: courseId={}", courseId);
        CourseInfo result = Db.getById(Long.parseLong(courseId), CourseInfo.class);
        courseQueryCache.put(key, result);
        return result;
    }

    public List<CourseContent> findContentsByCourseId(String courseId) {
        String key = "contents:" + courseId;
        @SuppressWarnings("unchecked")
        List<CourseContent> cached = (List<CourseContent>) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // ... 原查询体不变 ...
        List<CourseContent> result = Db.lambdaQuery(CourseContent.class)
                .select(CourseContent::getId, CourseContent::getCourseId, CourseContent::getContentType,
                        CourseContent::getContent, CourseContent::getSortOrder)
                .eq(CourseContent::getCourseId, Long.parseLong(courseId))
                .orderByAsc(CourseContent::getSortOrder)
                .list();
        courseQueryCache.put(key, result);
        return result;
    }

    public CourseSchedule findNextSchedule(String courseId) {
        String key = "schedule:" + courseId;
        CourseSchedule cached = (CourseSchedule) courseQueryCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        // ... 原查询体不变 ...
        CourseSchedule result = Db.lambdaQuery(CourseSchedule.class)
                .select(CourseSchedule::getId, CourseSchedule::getCourseId,
                        CourseSchedule::getStartDate, CourseSchedule::getEndDate,
                        CourseSchedule::getScheduleType, CourseSchedule::getLocation,
                        CourseSchedule::getInstructorName, CourseSchedule::getCapacity,
                        CourseSchedule::getEnrolled, CourseSchedule::getStatus)
                .eq(CourseSchedule::getCourseId, Long.parseLong(courseId))
                .ge(CourseSchedule::getStartDate, LocalDate.now())
                .orderByAsc(CourseSchedule::getStartDate)
                .last("LIMIT 1")
                .one();
        courseQueryCache.put(key, result);
        return result;
    }
```

新增失效方法：

```java
    /**
     * 失效课程相关缓存（一致性铁律：写方先写 DB 后调用）
     *
     * <p>精确失效详情/内容/排期键（course/contents/schedule:{courseId}），
     * 并清理 search:* 前缀的列表键（课程数据变更影响列表可见性与排序）。
     *
     * @param courseId 发生变更的课程 ID
     */
    public void evictCourse(Long courseId) {
        String id = String.valueOf(courseId);
        courseQueryCache.invalidate("course:" + id);
        courseQueryCache.invalidate("contents:" + id);
        courseQueryCache.invalidate("schedule:" + id);
        courseQueryCache.asMap().keySet().removeIf(k -> k.startsWith("search:"));
    }
```

- [ ] **Step 2: CourseService 挂失效钩子**

注入 `private final CourseQueryService courseQueryService;`（CourseQueryService 不依赖 CourseService，无环）。5 个写方法末尾（DB 写完成后、log 之前）加一行：
- `createCourse`：`courseQueryService.evictCourse(course.getId());`
- `updateCourse`：`courseQueryService.evictCourse(courseId);`
- `deleteCourse`：`courseQueryService.evictCourse(courseId);`
- `updateContent`：`courseQueryService.evictCourse(courseId);`
- `batchUpdateContents`：`courseQueryService.evictCourse(courseId);`

- [ ] **Step 3: CourseScheduleService 挂失效钩子**

注入 `private final CourseQueryService courseQueryService;`。create/update/delete 末尾（DB 写后）：`courseQueryService.evictCourse(courseId);`（delete 方法有 courseId 参数；create 的 courseId 来自入参；以实际方法签名为准，排期变更影响该课程的 schedule 键）。

- [ ] **Step 4: 新建 CourseQueryServiceTest**

纯 Mockito 单测（不依赖 Spring 上下文，Db 静态调用会被真实执行——**注意**：CourseQueryService 用 MyBatis-Plus `Db` 静态工具直接查库，单测无法 mock `Db.lambdaQuery`。测试策略：缓存命中场景（预置 cache 内容后调用，断言不触库——但方法体无法注入 Db mock……）

**实施要点**：若 `Db.lambdaQuery` 无法在单测中 mock，则 CourseQueryServiceTest 只测缓存行为（命中/失效/键隔离）而**不测查库路径**——用 `ReflectionTestUtils.setField` 向 `courseQueryCache` 预置值后断言命中返回；evictCourse 断言失效。查库路径由 CourseApiToolTest 既有覆盖（真实链路间接验证）。测试代码：

```java
package com.commerce.rag.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.commerce.rag.entity.CourseInfo;
import com.commerce.rag.entity.CourseSchedule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CourseQueryService 缓存行为测试 —— 命中/键隔离/evictCourse 精确失效 */
@DisplayName("CourseQueryService 缓存测试")
class CourseQueryServiceTest {

    private final Cache<String, Object> courseQueryCache =
            Caffeine.newBuilder().maximumSize(512).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final CourseQueryService service = new CourseQueryService(courseQueryCache);

    @Test
    @DisplayName("findCourseById 命中缓存返回同一实例")
    void findCourseById_hitsCache() {
        CourseInfo info = new CourseInfo();
        info.setId(1L);
        info.setTitle("缓存课程");
        courseQueryCache.put("course:1", info);

        CourseInfo result = service.findCourseById("1");
        assertThat(result).isSameAs(info);
    }

    @Test
    @DisplayName("evictCourse 精确失效详情键并清理 search 列表键")
    void evictCourse_removesDetailAndSearchKeys() {
        courseQueryCache.put("course:1", new CourseInfo());
        courseQueryCache.put("contents:1", List.of());
        courseQueryCache.put("schedule:1", new CourseSchedule());
        courseQueryCache.put("search:java:1", new Object());
        courseQueryCache.put("course:2", new CourseInfo());

        service.evictCourse(1L);

        assertThat(courseQueryCache.getIfPresent("course:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("contents:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("search:java:1")).isNull();
        assertThat(courseQueryCache.getIfPresent("course:2")).isNotNull();
    }
}
```

（`new CourseQueryService()` 需要无参构造——CourseQueryService 原无构造器，@RequiredArgsConstructor 未加，保留无参构造即可；若 Task 2 未改它则天然无参。**注意**：CourseQueryService 不在 Task 2 的 10 个 service 清单中——它无 @Autowired，无需改动，保持无参构造。）

- [ ] **Step 5: 适配 CourseServiceTest/CourseScheduleServiceTest**

两个测试构造 service 时新增 `courseQueryService` 参数：`@Mock private CourseQueryService courseQueryService;`（@Mock 添加）+ 构造器传参。新增用例（可选）：写方法调用后 verify `courseQueryService.evictCourse(...)`。

- [ ] **Step 6: 跑测试（clean）**

Run: `cd backend && mvn.cmd clean test -Dtest=CourseQueryServiceTest,CourseServiceTest,CourseScheduleServiceTest,CourseApiToolTest`
Expected: 全 PASS

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/CourseQueryService.java backend/src/main/java/com/commerce/rag/service/CourseService.java backend/src/main/java/com/commerce/rag/service/CourseScheduleService.java backend/src/test/java/com/commerce/rag/service/CourseQueryServiceTest.java backend/src/test/java/com/commerce/rag/service/CourseServiceTest.java backend/src/test/java/com/commerce/rag/service/CourseScheduleServiceTest.java
git commit -m "feat: CourseQueryService 查询缓存（perf P2-2，TTL 5min，写方法按 courseId 精确失效）"
```

---

### Task 9: DashboardService 统计缓存 + 写方失效（perf P2-3）

**Files:**
- Modify: `service/DashboardService.java`、`service/DocumentService.java`、`etl/EtlPipeline.java`、`service/UserFeedbackService.java`
- Modify Test: `test/service/DashboardServiceTest.java`、`test/service/DocumentServiceTest.java`、`test/service/UserFeedbackServiceTest.java`、`test/etl/EtlPipelineTest.java`

**Interfaces:**
- Consumes: Task 1 的 `dashboardStatsCache` bean
- Produces: 写方失效——DocumentService.upload/update/delete/reparse、EtlPipeline.updateDocStatus、UserFeedbackService.create/delete 末尾 `dashboardStatsCache.invalidateAll()`

- [ ] **Step 1: DashboardService 加缓存**

注入 `private final Cache<String, Object> dashboardStatsCache;`（DashboardService 已是 @RequiredArgsConstructor——Task 2 未列它？检查：DashboardService 用 Lombok @RequiredArgsConstructor（调研 B.1 确认），加 final 字段自动纳入构造器）。

3 个方法包缓存：

```java
    public Map<String, Object> dashboardStats(Long operatorId, boolean isAdmin) {
        String key = "dashboardStats:" + operatorId + ":" + isAdmin;
        Map<String, Object> cached = (Map<String, Object>) dashboardStatsCache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> result = ...; // 原统计逻辑不变
        dashboardStatsCache.put(key, result);
        return result;
    }
```

`feedbackStats(String period, Long operatorId, boolean isAdmin)`：键 `"feedbackStats:" + period + ":" + operatorId + ":" + isAdmin`；`feedbackTrend(int days, Long operatorId, boolean isAdmin)`：键 `"feedbackTrend:" + days + ":" + operatorId + ":" + isAdmin`。返回类型以现有签名为准（Map<String,Object> / List<Map<String,Object>>），缓存泛型用 Object 存取。

- [ ] **Step 2: DocumentService 挂失效**

注入 `private final Cache<String, Object> dashboardStatsCache;`（类型 com.github.benmanes.caffeine.cache.Cache）。upload / update / delete / reparse 方法末尾（log 前）加 `dashboardStatsCache.invalidateAll();`

- [ ] **Step 3: EtlPipeline 挂失效**

注入 `private final Cache<String, Object> dashboardStatsCache;`。私有方法 `updateDocStatus`（统一状态写入点）末尾加 `dashboardStatsCache.invalidateAll();`（覆盖 SUCCESS/FAILED/INDEXED 终态；若 updateDocStatus 被高频调用且多次写状态，仍可接受——ETL 终态低频）。

- [ ] **Step 4: UserFeedbackService 挂失效**

注入 `private final Cache<String, Object> dashboardStatsCache;`。create / delete 末尾（DB 写后）加 `dashboardStatsCache.invalidateAll();`

- [ ] **Step 5: 测试适配**

- DashboardServiceTest：构造器传 dashboardStatsCache（真实 Caffeine 实例或 mock）。新增断言：`dashboardStats` 二次调用同一参数时 mapper 只查一次（mock mapper + verify 次数）；不同参数键隔离。
- DocumentServiceTest / UserFeedbackServiceTest / EtlPipelineTest：构造 service 时新增 dashboardStatsCache 参数（真实 Caffeine 实例即可），现有断言不变；可补 verify invalidateAll 用例（可选）。

- [ ] **Step 6: 跑测试（clean）**

Run: `cd backend && mvn.cmd clean test -Dtest=DashboardServiceTest,DocumentServiceTest,UserFeedbackServiceTest,EtlPipelineTest,AdminDashboardControllerTest`
Expected: 全 PASS

- [ ] **Step 7: 提交**

```bash
git add backend/src/main/java/com/commerce/rag/service/DashboardService.java backend/src/main/java/com/commerce/rag/service/DocumentService.java backend/src/main/java/com/commerce/rag/etl/EtlPipeline.java backend/src/main/java/com/commerce/rag/service/UserFeedbackService.java backend/src/test/java/
git commit -m "feat: DashboardService 统计缓存（perf P2-3，TTL 60s，上传/ETL 终态/反馈提交失效）"
```

---

### Task 10: 全量门禁 + P0-2 git 基线提交 + 进度文档更新

**Files:**
- Modify: `.gitignore`（加 `.superpowers/`——SDD 工作区目录，防污染基线提交）
- Modify: `docs/progress/2026-08-15-bug修正与新指示轮.md`（更新状态：P1-6 完成、缓存落地、基线已提交、观察项）
- Modify: 本 spec / plan 文件（若需要标注完成状态）

**Interfaces:** 无。收尾任务。

- [ ] **Step 1: .gitignore 加 SDD 目录**

`.gitignore` 末尾追加：

```
# ── Superpowers SDD 工作区 ──
.superpowers/
```

- [ ] **Step 2: 全量门禁**

Run: `cd backend && mvn.cmd clean test`
Expected: 全量测试全绿（275/275 基线 + 本次新增用例）
Run: `cd backend && mvn.cmd spotless:apply`
Run: `cd backend && mvn.cmd checkstyle:check`
Run: `cd backend && mvn.cmd spotbugs:check`（若 verify 绑定则随 test 阶段后的 verify 执行——以现有门禁命令为准：`mvn.cmd verify` 覆盖全部）
Expected: 全部通过；若有 spotless 格式问题先 `spotless:apply` 再重跑

- [ ] **Step 3: 全量合规复查**

Run: `grep -rn "@Autowired" backend/src/main/java`（期望空）
Run: `grep -rn "new LambdaQueryWrapper\|new LambdaUpdateWrapper" backend/src/main/java`（期望空）
Run: `grep -rn "JdbcTemplate" backend/src/main/java`（期望空——DeviceKickService/ChatMessageService/EnrollmentService 均已移除）
Run: `grep -rn "com\.baomidou\|java\.util\.\S*\.\|java\.time\.\S*\.\|java\.sql\." backend/src/main/java --include="*.java" | grep -v "import "`（期望空）
Expected: 全部归零

- [ ] **Step 4: 更新进度文档**

在 `docs/progress/2026-08-15-bug修正与新指示轮.md` 增加/更新：P1-6 宪法合规组 6 项完成状态、perf P2-2/P2-3 缓存落地、P0-2 基线提交完成、范围外观察项清单（StudentController/AuthController/DTO 包位置/FeedbackController）、主任务（§2.1 新指示轮四项）仍待用户确认的提示。中文记录。

- [ ] **Step 5: git 基线提交（用户授权的唯一 add -A 例外）**

```bash
git add -A
git commit -m "chore: backend 全量基线提交（117 个未跟踪文件入库）+ 宪法合规/缓存改造（2026-08-15）"
```

**注意**：基线提交会一并纳入主任务（§2.1 新指示轮四项）已落地的未提交改动——提交前向用户确认主任务代码状态（若用户已确认则直接入库；若方案被推翻则以推翻后代码为准）。

- [ ] **Step 6: 验证基线**

Run: `git ls-files backend/ | wc -l`（期望接近 backend 全量文件数）
Run: `git status --short`（期望干净或仅剩余无关项）
Run: `cd backend && mvn.cmd test -q`（fresh 状态编译验证——至少 `mvn.cmd compile` 通过）

---

## Self-Review 记录

**1. Spec 覆盖检查**：
- A 注入合规 → Task 2 ✓；B Wrapper → Task 3 ✓；C 全路径 → Task 3 ✓；D MapStruct（SysUser/Course/Schedule）→ Task 4 ✓、Enrollment → Task 7 ✓、4 个 VO 转换器 → Task 5 ✓；E VO 化 → Task 5 ✓；F DeviceKick → Task 6 ✓；G ChatMessage/Enrollment → Task 7 ✓；H 缓存 → Task 1（基建）+ Task 8/9 ✓；I 基线 → Task 10 ✓；pom 依赖 → Task 1 ✓；测试适配 → 各任务内置 ✓；门禁 → Task 10 ✓
- 决策点 1（G 纳入）→ Task 7 ✓；决策点 2（AuthController 例外）→ 无任务，spec 已记录 ✓；决策点 3（VO 位置 controller/vo）→ Task 5 ✓；决策点 4（失效策略）→ Task 8/9 ✓
- 观察项 ①-⑤ → 无任务，Task 10 Step 4 记录 ✓

**2. 占位符扫描**：无 TBD/TODO；各步骤含具体代码/命令。Task 5 Step 5 findContext 与 Task 8/9 的"原查询体不变"为引用现有实现的简写，附明确改造点说明。

**3. 类型一致性**：
- `Cache<String, Object>` bean 名 courseQueryCache/dashboardStatsCache 跨 Task 1/8/9 一致 ✓
- `evictCourse(Long)` 在 Task 8 定义，Task 8 Step 2/3 使用 ✓
- 转换器命名 SysUserConverter/CourseConverter/ScheduleConverter/EnrollmentConverter/DocumentConverter/KnowledgeBaseConverter/DocumentChunkConverter/UserFeedbackConverter 与 spec §4 一致 ✓
- `PageResponse.of()` 泛型适配 VO 在 Task 5 Step 7 说明 ✓
