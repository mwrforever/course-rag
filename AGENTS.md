# RAG 课程助手(commerce-customer)

企业级多模态 RAG 课程助手:Spring AI Alibaba Agent(qwen3.8-max)+ Milvus 混合检索 + PG/Redis/MinIO。
C 端学生 AI 对话(意图体系:knowledge_question / chat / unknown,意图与检索解耦,元数据过滤收窄检索),B 端知识库/课程管理。

## 工程宪法(强制约束,任何实现不得违反)

### 分层依赖

- controller → service → mapper,禁止跨层调用
- agent 工具(@Tool)不直接访问数据层,一律经 Service 封装
- 检索链路:图节点 → ContextBuilder → Milvus/PG;图节点不得直接拼 SQL 或裸调 Milvus 客户端

### 对象通信约束

- **Entity**(数据库表映射对象,MP 实体)只存在于数据层(mapper/service),禁止出 service 边界、禁止直接返回给 controller/前端
- **DTO/VO 等传输对象各层独立定义,禁止跨层复用**——即使字段完全相同,只要不是数据库表映射对象,一律不复用;controller 入参走 DTO、出参走 VO(根目录 dto/vo 包),每个接口的请求/响应对象独立,保证接口契约独立演化
- 层间转换**必须使用 MapStruct**(Mapper 接口 + 编译期生成实现),禁止手写转换代码;转换器统一放 `convert/` 包,命名 `XxxConverter`
- **MapStruct 注意**:修改转换接口或相关 DTO 后,必须 `clean` 后重新编译(增量编译不重新生成实现类,不改干净会跑旧实现)

### 目录职责

```
backend/src/main/java/com/commerce/rag/
├── controller/    # 接口层:入参校验、调用 Service;禁止业务逻辑
├── dto/           # 接口传输对象(请求入参等,每接口独立定义,根目录下)
├── vo/            # controller 视图层对象(响应出参,controller 只操作 VO,根目录下)
├── service/       # 业务层:Service 接口(I 前缀)+ impl/ 实现类
├── convert/       # MapStruct 转换器(全部集中于此,命名 XxxConverter)
├── exception/     # 业务异常与自定义异常(BizException/ErrorCode)
├── record/        # 杂项对象(不隶属任何层/模块的对象)
├── properties/    # @ConfigurationProperties 属性绑定类(全部集中于此)
├── constants/     # 业务常量(接口定义静态常量,配置类数值归 properties/)
├── config/        # 配置与 Bean 注册(@Configuration/@Bean 全部集中于此)
├── mapper/        # 数据层:仅 MyBatis-Plus 数据访问,不含业务逻辑
├── entity/        # 数据库表映射对象(MP 实体,不出数据层)
├── bot/           # Agent 图编排:graph(图/节点)/tool(agent 工具)/rewrite/prompt/hook
├── retrieval/     # 检索链路:融合/rerank/context builder
├── etl/           # ETL 管道
├── auth/          # 认证与安全
├── storage/       # MinIO 存储
├── stream/        # SSE 流式
├── worker/        # 队列消费
└── enums/         # 枚举
```

禁止跨包职责:controller 不写业务、service 不写 SQL、mapper 不含业务逻辑、entity 不跨层传递;controller 入参走 DTO、出参走 VO,不直接操作 Entity

### Service 结构规范

- Service 一律「接口 + 实现」:接口命名 `IXxxService`(I 前缀),实现类 `XxxServiceImpl` 放 `service/impl/` 目录
- CRUD 型(有主表实体):接口 `extends IService<Entity>`,实现 `extends ServiceImpl<Mapper, Entity> implements IXxxService`;多 mapper 的 service 以主表 mapper 为 ServiceImpl 泛型,其余 mapper 在 impl 中注入
- 聚合查询型(无单一主表实体,如 CourseQueryService/DashboardService):接口不继承 IService,实现类注入所需 mapper
- 调用方(controller/worker/其它 service)一律注入接口类型(多态),`@Service` 注册在 impl 上

### 并发与异步

- **各业务独立线程池**(ETL/检索/偏好与记忆提取各一个),禁止共用一个池导致互相阻塞
- 并行场景用 `CompletableFuture`(多查询检索、附件批处理),注意超时控制与单点失败隔离
- SSE 流式用响应式(Flow/Flux),禁止阻塞请求线程
- 共享可变状态必须线程安全(ConcurrentHashMap/原子类),禁止裸 HashMap 跨线程共享

### Java 全局规范(所有 Java 代码强制)

- **禁止全路径类名**:任何 Java 代码禁止写全路径类名(如 `new com.baomidou...LambdaQueryWrapper`、`java.util.List`、`java.time.Duration`),一律 import 后使用短类名;全路径类名写法视为不合格
- **注解优先**:能用注解/框架声明式能力解决的,禁止手写样板代码(如 Lombok @Data/@Getter/@Setter/@Slf4j、Spring 注解);实体/DTO 用 Lombok 注解生成样板
- **依赖注入统一 `private final` + `@RequiredArgsConstructor`**(Lombok):禁止字段 @Autowired 注入、禁止手写样板构造器;仅构造器内有初始化逻辑等特殊场景才允许手写,且必须注释说明原因
- **禁止循环依赖**:service 之间依赖必须单向无环;出现 A→B→A 循环时,**拆层切断**——把环上交叉查询下沉为独立 service,双方只依赖下沉层;禁止用 @Lazy/ObjectProvider 等延迟注入掩盖循环
- **禁用弃用 API**:整个 Java 程序(JDK、Spring、MyBatis-Plus、Guava 等所有依赖)禁止使用任何被标记 `@Deprecated` 的方法/类,一律用当前依赖版本的非弃用 API 替代;编译期 deprecation 警告必须清零

### MyBatis-Plus 使用规范

- **按需取列**:查询必须按需 select 所需列(`lambdaQuery().select(列...)`),禁止 SELECT *、禁止全字段 `selectById/selectOne` 取回后丢弃;`getById` 仅用于确需完整实体的场景
- **拒绝 N+1 查询**:循环内单查改批量查询(如 `in` 批量),批量结果内存组装
- **本 service 操作自己的主表**:直接使用 ServiceImpl 内置链式 `this.lambdaQuery()/this.lambdaUpdate()`,终结方法(.list()/.one()/.count()/.page()/.update());或 IService 其它内置方法(getById/listByIds/save/saveBatch/updateById/removeById),**不构建 wrapper**
  ```java
  // 本 service 主表:this.lambdaQuery() 链式 + 按需取列
  List<User> users = this.lambdaQuery()
          .select(User::getId, User::getName)
          .like(User::getName, "J")
          .gt(User::getAge, 20)
          .orderByDesc(User::getAge)
          .list();
  ```
- **查询目标不是本 service 主表**(副表、跨模块条件查询)才使用 wrapper,推荐 `Wrappers.lambdaQuery()/lambdaUpdate()` 静态工厂链式构建
- **跨 service 复用查询**:经 service 依赖注入调用其公开方法(禁止直接操作他人 mapper、禁止复制查询逻辑);跨模块查数据优先用对方 service 接口的 `lambdaQuery()/lambdaUpdate()` 链式能力(wrapper 由对方实例产生,只查数据不建立 service 依赖)
- **mapper 调用传值不传 wrapper**:直接调用 BaseMapper 方法时传入具体值(如 `selectById(id)`、`deleteById(id)`),不要在调用点创建 wrapper 对象;确需条件查询的 wrapper 在 service 内构建
- **ID 生成**:实体 `@TableId(ASSIGN_ID)` 自动生成雪花 ID,禁止手动 `IdWorker`;批量插入用 `saveBatch`(JDBC 批处理,自动填充 ID,须在事务内调用)
- **禁止业务层拼接 SQL 字符串**:service/controller 中禁止 JdbcTemplate 拼 SQL、禁止字符串拼接 SQL(如占位符 join、to_char 等原生 SQL 写在 Java 字符串里)
- **复杂 SQL 必须走 mapper XML 映射文件**:连表查询、分组统计、聚合、复杂条件等无法用 lambda 链式清晰表达的 SQL,在 mapper 接口声明方法 + `src/main/resources/mapper/` 对应 XML 文件实现(类名.xml),由 MyBatis 解析执行;禁止在 service 里绕过 mapper 直接执行原生 SQL
- **XML 标签限制**:只用最常用标签(select/insert/update/delete/where/if/foreach/set/choose),禁止为省事滥用动态标签;每条 SQL 完整不分割,保证可读性与完整性
- 查询必带分页(PaginationInnerInterceptor maxLimit=2000)

### 异常规范

- 业务错误一律抛 `BizException(ErrorCode.XXX, 消息)`,禁止散落 `ResponseStatusException`
- `ErrorCode` 的 code 值与 HTTP 状态同值(保持 ApiResponse.code = HTTP 状态码的前端契约)
- 控制流异常(如 worker 取消信号)独立定义,统一放 `exception/`
- `GlobalExceptionHandler` 统一处理 BizException 与框架异常,禁止 controller 内局部 `@ExceptionHandler`(消除响应体双轨)

### 配置与注册规范

- 所有 `@Configuration` / `@Bean` 注册类一律放 `config/` 目录,禁止散落其它包
- 所有 `@ConfigurationProperties` 属性绑定类一律放 `properties/` 目录
- 业务常量(状态串、Redis key 前缀等)放 `constants/`,以接口定义静态常量;阈值等配置数值归 `properties/`(全配置化)
- 业务组件(`@Component`/`@Service`)留在各自模块包;只迁注册代码,不搬业务逻辑(如 LeadAgentGraph 保持业务类在 bot/graph/,仅以 @Component 注册)

### 缓存与一致性

- 常用查询项构建 Redis/本地缓存(Caffeine),缓存键命名规范:业务前缀 + 主键
- **一致性铁律**:先写 DB(事务内)→ 后失效缓存;禁止先改缓存后写库
- 涉及原子操作(设备互踢、黑名单等)用 **Lua 脚本**,禁止应用层读-改-写竞态
- 缓存必须有失效时间,禁止永久缓存

## 代码规范

- 注释/日志/文档全中文;工具方法必须带中文 `@Tool(description=...)`
- 测试与实现同一次提交;因改动失效的旧测试直接删除,禁止留过渡
- 前端 AI 对话/消息渲染/会话界面落地前**必须与用户沟通**;管理端界面按生产标准直接落地
