# AGENTS.md — RAG 课程助手(commerce-customer)

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
- 层间转换**必须使用 MapStruct**(Mapper 接口 + 编译期生成实现),禁止手写转换代码;转换器接口与使用它的 Service 同包,命名 `XxxConverter`
- **MapStruct 注意**:修改转换接口或相关 DTO 后,必须 `clean` 后重新编译(增量编译不重新生成实现类,不改干净会跑旧实现)

### 目录职责

```
backend/src/main/java/com/commerce/rag/
├── controller/    # 接口层:入参校验、调用 Service;禁止业务逻辑
├── dto/           # 接口传输对象(请求入参等,每接口独立定义,根目录下)
├── vo/            # controller 视图层对象(响应出参,controller 只操作 VO,根目录下)
├── service/       # 业务层:业务编排、事务、Entity↔DTO/VO 转换(MapStruct 转换器)
├── mapper/        # 数据层:仅 MyBatis-Plus 数据访问,不含业务逻辑
├── entity/        # 数据库表映射对象(MP 实体,不出数据层)
├── bot/           # Agent 图编排:graph(图/节点)/tool(agent 工具)/rewrite/prompt/hook
├── retrieval/     # 检索链路:融合/rerank/context builder
├── etl/           # ETL 管道
├── config/        # 配置类
├── auth/          # 认证与安全
├── storage/       # MinIO 存储
├── stream/        # SSE 流式
├── worker/        # 队列消费
└── enums/         # 枚举
```

禁止跨包职责:controller 不写业务、service 不写 SQL、mapper 不含业务逻辑、entity 不跨层传递;controller 入参走 DTO、出参走 VO,不直接操作 Entity

### 并发与异步

- **各业务独立线程池**(ETL/检索/偏好与记忆提取各一个),禁止共用一个池导致互相阻塞
- 并行场景用 `CompletableFuture`(多查询检索、附件批处理),注意超时控制与单点失败隔离
- SSE 流式用响应式(Flow/Flux),禁止阻塞请求线程
- 共享可变状态必须线程安全(ConcurrentHashMap/原子类),禁止裸 HashMap 跨线程共享

### Java 全局规范（所有 Java 代码强制）

- **禁止全路径类名**:任何 Java 代码禁止写全路径类名(如 `new com.baomidou...LambdaQueryWrapper`、`java.util.List`、`java.time.Duration`),一律 import 后使用短类名;全路径类名写法视为不合格
- **注解优先**:能用注解/框架声明式能力解决的,禁止手写样板代码(如 Lombok @Data/@Getter/@Setter/@Slf4j、Spring 注解);实体/DTO 用 Lombok 注解生成样板
- **依赖注入统一 `private final` + `@RequiredArgsConstructor`**(Lombok):禁止字段 @Autowired 注入、禁止手写样板构造器;仅构造器内有初始化逻辑等特殊场景才允许手写,且必须注释说明原因
- **禁止循环依赖**:service 之间依赖必须单向无环;出现 A→B→A 循环时,通过注入其 wrapper 对象(IService.lambdaQuery()/lambdaUpdate() 链式包装)或拆层解耦,严禁循环依赖

### MyBatis-Plus 使用规范

- **按需返回字段**:查询只 select 所需列,禁止 SELECT *
- **拒绝 N+1 查询**:循环内单查改批量查询(如 `in` 批量),批量结果内存组装
- **Wrapper 一律 lambda 链式构建,禁止 new**:必须 `import com.baomidou.mybatisplus.core.toolkit.Wrappers;` 后使用 `Wrappers.lambdaQuery()/lambdaUpdate()` 静态工厂链式构建;禁止 `new LambdaQueryWrapper/LambdaUpdateWrapper` 对象(即使已 import),禁止在方法内写全路径类名 `new com.baomidou...LambdaQueryWrapper`
  ```java
  // ServiceImpl 内部:this.lambdaQuery() 链式,终结方法(.list()/.one()/.count()/.page())返回结果
  List<User> users = this.lambdaQuery()
          .like(User::getName, "J")
          .gt(User::getAge, 20)
          .orderByDesc(User::getAge)
          .list();
  ```
- **跨 service 复用查询**:不同 service 需要引用其它 service 的查询能力时,经 service 依赖注入调用其公开方法(禁止直接操作他人 mapper、禁止复制查询逻辑);出现循环依赖时通过注入其 wrapper 对象解耦(严禁循环依赖,见 Java 全局规范)
- **mapper 调用传值不传 wrapper**:直接调用 BaseMapper 方法时传入具体值(如 `selectById(id)`、`deleteById(id)`),不要在调用点创建 wrapper 对象;确需条件查询的 wrapper 在 service 内构建
- **禁止业务层拼接 SQL 字符串**:service/controller 中禁止 JdbcTemplate 拼 SQL、禁止字符串拼接 SQL(如占位符 join、to_char 等原生 SQL 写在 Java 字符串里)
- **复杂 SQL 必须走 mapper XML 映射文件**:连表查询、分组统计、聚合、复杂条件等无法用 lambda 链式清晰表达的 SQL,在 mapper 接口声明方法 + `src/main/resources/mapper/` 对应 XML 文件实现(类名.xml),由 MyBatis 解析执行;禁止在 service 里绕过 mapper 直接执行原生 SQL
- **XML 标签限制**:只用最常用标签(select/insert/update/delete/where/if/foreach/set/choose),禁止为省事滥用动态标签;每条 SQL 完整不分割,保证可读性与完整性
- 查询必带分页(PaginationInnerInterceptor maxLimit=2000)

### 缓存与一致性

- 常用查询项构建 Redis/本地缓存(Caffeine),缓存键命名规范:业务前缀 + 主键
- **一致性铁律**:先写 DB(事务内)→ 后失效缓存;禁止先改缓存后写库
- 涉及原子操作(设备互踢、黑名单等)用 **Lua 脚本**,禁止应用层读-改-写竞态
- 缓存必须有失效时间,禁止永久缓存

## 代码规范

- 注释/日志/文档全中文;工具方法必须带中文 `@Tool(description=...)`
- 测试与实现同一次提交;因改动失效的旧测试直接删除,禁止留过渡
- 前端 AI 对话/消息渲染/会话界面落地前**必须与用户沟通**;管理端界面按生产标准直接落地
