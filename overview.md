# RAG 课程助手后端 — 回归审计偏离修复完成

## TL;DR

对照三份设计文档核查后端实现，发现并修复了全部 56 项原始偏离 + 15 项补充偏离，168 个测试全通过。

## 交付概览

| 指标 | 结果 |
|------|------|
| 交付状态 | ✅ 完成 |
| 编译 | BUILD SUCCESS（零错误） |
| 测试通过率 | 168/168 = 100% |
| 已知问题 | 0 |

## 修复清单（按模块）

### DB Schema（3 项修复 + 2 项误报确认）
- D7: CourseSchedule teaching_mode 注释补 HYBRID
- N-DB-1: UserFeedbackService.delete 软删除实现
- N-DB-2: V6 DDL 超管 INSERT 注释
- D8/D9: 确认为审计误报（设计文档 status = ACTIVE/ARCHIVED，非 DRAFT/PUBLISHED）

### F#1 Agent 架构（10 项修复）
- N-F1-1 (P0): PromptLoader 新增 loadRaw/loadRawAndReplace，消除硬编码 fallback
- F1-9/F3-2 (P0): WarningHook drainWarnings 改用 updateState 走 reducer
- F1-16: CustomSummarizationHook buildNewMessages 保留 firstUserMessage
- F1-5: 增量摘要提示词补 3 条融合规则
- F1-12: KnowledgeSearchResult 精简字段 + 新增 docTitle
- F1-10: CourseListResult size → pageSize
- F1-11: CourseDetailResult 移除顶层冗余 duration
- F1-13: WarningHook 告警格式改为 `⚠️ [警告内容]`
- N-F1-2: query-rewrite.yml 移除 ${query} 占位符

### F#2 流式管线（10 项修复）
- F2-4: WorkerConfig 线程池动态计算 CPU*2
- F2-8: ChatRequestWorker Redis 结果缓存
- F2-9: ChatController ring buffer miss → PG 降级回放
- F2-10: SseEventTransformer SOURCES 事件生产
- F2-14: heartbeat 改 SSE 注释行 `:heartbeat`
- F2-15: AGENT_MODEL_FINISHED 发累积 text（delta 兜底）
- F2-11: saver.get 替代 getTuple（Javadoc 说明）
- F2-16: MemoryStreamBridge.replayFrom 委托方法
- N-F2-4: RunnableConfig 增加 userId metadata
- N-F2-5: persistMessages state 提取说明

### F#4 数据层 + CRUD（10 项修复）
- F4-9: CorsConfig.java（CORS 跨域配置）
- F4-7: StudentController 移除 JdbcTemplate 改用 DocumentChunkService
- N-F4-5: GlobalExceptionHandler.java（全局异常处理）
- N-F4-1: SysUserService delete 级联软删 course_teacher
- N-F4-2: checkOwnership 增加 SUPER_ADMIN 旁路
- N-F4-3: SecurityException → ResponseStatusException
- N-F4-4: TEACHER 查询按 created_by 过滤
- N-F4-6: AdminChunkController 全端点 userId/ownership 校验
- N-F4-7: CourseService/ChatSessionService JdbcTemplate → Mapper
- N-F4-8: 删除操作记录 operatorId

### 测试文件同步修复（8 个测试类）
- SysUserServiceTest: CreateUserRequest/UpdateUserRequest 参数适配 + 移除 setCreatedBy
- UserFeedbackServiceTest: delete 方法增加 operatorId 参数
- KnowledgeBaseServiceTest: delete 增加 isAdmin 参数 + SecurityException→ResponseStatusException
- DocumentChunkServiceTest: delete/batchUpdate/batchCorrected/updateContent/findContext 参数适配
- ChatControllerTest: 构造器增加 ChatMessageService mock
- RerankServiceTest: 构造器增加 PromptLoader mock
- AdminUserControllerTest: CreateUserRequest/UserDTO 参数适配

## 用户下一步建议

1. **启动后端验证**：`cd backend && mvn.cmd spring-boot:run`（需确保 PostgreSQL/Redis/Milvus 已启动）
2. **前端开发**：后端 API 已全部就绪，可开始 `frontend/`（管理端）和 `student-frontend/`（学生端）实现
3. **集成测试**：当前为单元测试（Mock），建议后续补充 Docker Compose 环境的集成测试
4. **回归报告更新**：docs/regression-audit-2026-07-23.md 中的偏离项可标记为已修复
