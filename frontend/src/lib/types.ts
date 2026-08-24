/**
 * B 端接口契约类型（Schema 骨架）
 *
 * 字段以 Spring Boot 后端 VO/DTO 源码实测为准（backend/src/main/java/com/commerce/rag/vo、dto）。
 * 铁律（docs/backed/2026-08-24-后端功能调整.md §一 R0）：Jackson Long 全局序列化为字符串，
 * 一切 id/total/fileSize/计数类字段按 string 接收；Integer/double/BigDecimal 字段保持 number。
 * 时间一律 ISO-8601 无时区串（如 2026-08-24T10:15:30），前端 new Date(iso) 本地时区解析。
 * 本文件为骨架：业务接口类型细粒度定义与校验由 Task 16 及后续任务补齐。
 */

// ===== 通用响应 =====

/** 统一响应包装（后端 dto/ApiResponse）：code=0 成功；401 错误体无 data 键，故 data 可空 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data?: T
}

/** 分页响应（后端 dto/PageResponse）：total 为 Long 序列化后为 string，page/size 保持 number */
export interface PageResponse<T> {
  records: T[]
  total: string
  page: number
  size: number
}

// ===== 登录 =====

/** 用户角色（后端 enums/UserRole） */
export type UserRole = 'STUDENT' | 'TEACHER' | 'SUPER_ADMIN'

/** 登录请求（后端 dto/LoginRequest）：deviceType 缺省 WEB_DESKTOP */
export interface LoginRequest {
  username: string
  password: string
  deviceType?: string
}

/** 登录响应（后端 dto/LoginResponse）：userId 为 Long 序列化字符串；B 端仅 TEACHER/SUPER_ADMIN 可进 */
export interface LoginResponse {
  accessToken: string
  refreshToken: string
  userId: string
  role: UserRole
  displayName: string
}

/** 静默刷新请求（后端 dto/RefreshRequest）：RT 走响应体传递 */
export interface RefreshRequest {
  refreshToken: string
}

// ===== 知识库 =====

/** 知识库（后端 vo/KnowledgeBaseVO）：教师仅可见/可管自己创建（createdBy 比对） */
export interface KnowledgeBaseVO {
  id: string
  name: string
  description: string
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

// ===== 文档 =====

/** 文档状态（后端 Document 实体 parse_status，设计 §2.5 八态可视化体系） */
export type DocumentParseStatus =
  'PENDING' | 'PARSING' | 'PARSED' | 'CHUNKING' | 'CHUNKED' | 'EMBEDDING' | 'INDEXED' | 'FAILED'

/** 文档（后端 vo/DocumentVO）：fileSize 与 id 为 string；chunkCount 为 Integer 保持 number */
export interface DocumentVO {
  id: string
  kbId: string
  title: string
  fileType: string
  fileSize: string
  parseStatus: DocumentParseStatus
  chunkCount: number
  errorMessage: string
  metadataJson: string
  courseId: string | null
  createdBy: string
  createdAt: string
  updatedAt: string
}

// ===== 分片 =====

/** 修正状态（后端 DocumentChunk 实体 correction_status，设计 §2.5：PENDING amber / CORRECTED emerald） */
export type CorrectionStatus = 'PENDING' | 'CORRECTED'

/** 分片集合类型（TECHNICAL_QA 蓝 / COURSE_INFO 紫，设计 §2.4.3） */
export type CollectionType = 'TECHNICAL_QA' | 'COURSE_INFO'

/** 分片摘要（后端 vo/ChunkBriefVO），上下文抽屉节点卡使用 */
export interface ChunkBriefVO {
  id: string
  content: string
  headingPath: string
  chunkIndex: number
  parentTitle: string
}

/** 分片（后端 vo/ChunkVO，分片编辑抽屉展示用） */
export interface ChunkVO {
  id: string
  content: string
  headingPath: string
  chunkIndex: number
  parentTitle: string
  startPage: number
  endPage: number
}

/** 分片上下文（后端 vo/ChunkContextVO）：parent/prev/next 恒 null 时前端不渲染节点 */
export interface ChunkContextVO {
  id: string
  docId: string
  kbId: string
  content: string
  headingPath: string
  chunkIndex: number
  courseId: string | null
  parentChunkId: string | null
  prevChunkId: string | null
  nextChunkId: string | null
  parent: ChunkBriefVO | null
  prev: ChunkBriefVO | null
  next: ChunkBriefVO | null
}

/** 文档分片全量（后端 vo/DocumentChunkVO，分片修正工作台主表） */
export interface DocumentChunkVO {
  id: string
  docId: string
  kbId: string
  chunkIndex: number
  content: string
  headingPath: string
  parentTitle: string
  startPage: number
  endPage: number
  tokenCount: number
  collectionType: CollectionType | null
  courseId: string | null
  metadataJson: string
  milvusPk: string
  parentChunkId: string | null
  prevChunkId: string | null
  nextChunkId: string | null
  charOffsetStart: number
  charOffsetEnd: number
  correctionStatus: CorrectionStatus
  createdAt: string
  updatedAt: string
}

// ===== 课程 =====

/** 课程状态（ACTIVE emerald / ARCHIVED slate，设计 §2.5） */
export type CourseStatus = 'ACTIVE' | 'ARCHIVED'

/** 课程内容 Tab（后端 dto/CourseDTO.CourseContentDTO）：intro/syllabus/instructor/faq 四 Tab */
export interface CourseContentDTO {
  contentType: string
  content: string
  sortOrder: number
}

/** 排期（后端 vo/CourseScheduleVO）：capacity/enrolled 为 Integer 保持 number；startDate/endDate 为 LocalDate（YYYY-MM-DD） */
export interface CourseScheduleVO {
  id: string
  courseId: string
  startDate: string
  endDate: string
  scheduleType: string
  location: string
  instructorName: string
  capacity: number
  enrolled: number
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

/** 课程（后端 dto/CourseDTO）：price/rating 为 BigDecimal 按 number 接收；tags 可空数组 */
export interface CourseDTO {
  id: string
  title: string
  description: string
  coverImage: string
  category: string
  instructorName: string
  price: number
  duration: string
  tags: string[] | null
  rating: number
  learningCount: number
  enrollmentLink: string
  status: CourseStatus
  createdBy: string
  createdAt: string
  contents: CourseContentDTO[] | null
  schedules: CourseScheduleVO[] | null
  teacherIds: string[] | null
}

// ===== 用户 =====

/** 用户状态（ACTIVE emerald / DISABLED red，设计 §2.5） */
export type UserStatus = 'ACTIVE' | 'DISABLED'

/** 用户（后端 dto/UserDTO，用户管理页；教师仅可见自己创建的学生） */
export interface UserDTO {
  id: string
  username: string
  displayName: string
  role: UserRole
  status: UserStatus
  createdAt: string
}

// ===== 反馈 =====

/** 反馈（后端 vo/UserFeedbackVO）：isLiked 三态（NULL=未评，TRUE=赞，FALSE=踩）；userId/messageId 为 string */
export interface UserFeedbackVO {
  id: string
  sessionId: string
  messageId: string
  userId: string
  isLiked: boolean | null
  intentType: string | null
  createdAt: string
}

/** 反馈意图统计（后端 AdminUserFeedbackController feedbacks/stats：likedCount/dislikedCount 为 Long 字符串） */
export interface FeedbackIntentStat {
  intentType: string
  likedCount: string
  dislikedCount: string
}

// ===== 会话 =====

/** 会话状态（ACTIVE emerald / CLOSED slate，设计 §2.5） */
export type SessionStatus = 'ACTIVE' | 'CLOSED'

/** 会话（后端 vo/ChatSessionVO） */
export interface ChatSessionVO {
  id: string
  userId: string
  title: string
  status: SessionStatus
  lastMessageAt: string
  model: string
  createdAt: string
}

/** 会话消息（后端 vo/ChatMessageVO，会话回放 Drawer 只读流） */
export interface ChatMessageVO {
  id: string
  role: string
  content: string
  messageType: string | null
  intentType: string | null
  runId: string
  seq: number
  createdAt: string
}

// ===== 安全审计 =====

/** 登录记录状态（ACTIVE/REVOKED/EXPIRED，设计 §2.4.7） */
export type LoginRecordStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED'

/** 登录记录（后端 vo/SysLoginRecordVO，超管踢出设备） */
export interface SysLoginRecordVO {
  id: string
  userId: string
  jtiAt: string
  jtiRt: string
  deviceType: string
  deviceInfo: string
  ipAddress: string
  expiresAt: string
  status: LoginRecordStatus
  createdAt: string
  updatedAt: string
}

/** Token 黑名单项（后端 vo/SysTokenBlacklistVO，超管清理过期） */
export interface SysTokenBlacklistVO {
  id: string
  jti: string
  tokenType: string
  userId: string
  blacklistedBy: string
  reason: string
  expiresAt: string
  createdAt: string
}

// ===== 仪表盘 =====

/** 仪表盘统计（后端 AdminDashboardController dashboard/stats：各计数为 Long 字符串） */
export interface DashboardStats {
  documentCount: string
  pendingChunkCount: string
  knowledgeBaseCount: string
  /** feedback/stats：学生数/反馈数/点赞率（likeRate 浮点保持 number） */
  studentCount: string
  feedbackCount: string
  likeRate: number
}

/** 反馈趋势点（后端 feedback/trend：count 为 Long 字符串） */
export interface FeedbackTrendItem {
  date: string
  count: string
}

/** 反馈统计（后端 dashboard/feedback/stats：studentCount/feedbackCount 为 Long 字符串，likeRate 浮点） */
export interface FeedbackStats {
  studentCount: string
  feedbackCount: string
  likeRate: number
}

// ===== B 端请求 DTO（与后端 dto/ 一一对应，字段可空性照抄 Java record） =====

/** 知识库创建/更新（后端 dto/KnowledgeBaseRequest）：name 必填 */
export interface KnowledgeBaseRequest {
  name: string
  description?: string
}

/** 文档改标题（后端 dto/DocumentUpdateRequest） */
export interface DocumentUpdateRequest {
  title: string
}

/** 分片内容修正（后端 dto/ChunkContentUpdateRequest）：改 content 触发重新向量化 */
export interface ChunkContentUpdateRequest {
  content: string
}

/** 单片集合类型调整（后端 dto/ChunkCollectionTypeRequest）：不同步 Milvus（弱化入口） */
export interface ChunkCollectionTypeRequest {
  collectionType: CollectionType
  courseId?: string | null
}

/**
 * 批量修正（后端 dto/BatchChunkUpdateRequest，文档级 Milvus 同步可能慢）
 * collectionType/courseId 均可选：未选表示「不改」，后端 null 时不更新对应字段
 */
export interface BatchChunkUpdateRequest {
  ids: string[]
  collectionType?: CollectionType
  courseId?: string | null
}

/** 批量标记已修正（后端 dto/BatchCorrectedRequest，不可撤销） */
export interface BatchCorrectedRequest {
  ids: string[]
}

/** 创建课程（后端 dto/CreateCourseRequest） */
export interface CreateCourseRequest {
  title: string
  description?: string
  coverImage?: string
  category?: string
  instructorName?: string
  price?: number
  duration?: string
  tags?: string[] | null
  enrollmentLink?: string
}

/** 更新课程（后端 dto/UpdateCourseRequest）：所有字段可选，null 表示不更新 */
export interface UpdateCourseRequest {
  title?: string
  description?: string
  coverImage?: string
  category?: string
  instructorName?: string
  price?: number
  duration?: string
  tags?: string[] | null
  enrollmentLink?: string
  status?: CourseStatus
}

/** 创建排期（后端 dto/CreateScheduleRequest）：日期为 LocalDate 字符串 YYYY-MM-DD */
export interface CreateScheduleRequest {
  startDate: string
  endDate: string
  scheduleType: string
  location?: string
  instructorName?: string
  capacity?: number
}

/** 更新排期（后端 dto/UpdateScheduleRequest）：所有字段可选，null 表示不更新 */
export interface UpdateScheduleRequest {
  startDate?: string
  endDate?: string
  scheduleType?: string
  location?: string
  instructorName?: string
  capacity?: number
  enrolled?: number
  status?: string
}

/** 批量添加学生（后端 dto/EnrollmentRequest） */
export interface EnrollmentRequest {
  studentIds: string[]
}

/** 创建用户（后端 dto/CreateUserRequest）：role 白名单 TEACHER/STUDENT/SUPER_ADMIN */
export interface CreateUserRequest {
  username: string
  password: string
  displayName: string
  role: UserRole
}

/** 更新用户（后端 dto/UpdateUserRequest） */
export interface UpdateUserRequest {
  displayName?: string
}

/** 重置密码（后端 dto/ResetPasswordRequest） */
export interface ResetPasswordRequest {
  newPassword: string
}

/** 启用/禁用用户（后端 dto/UpdateStatusRequest）：ACTIVE / DISABLED */
export interface UpdateStatusRequest {
  status: UserStatus
}

/** 课程学生（后端 dto/StudentDTO）：enrolledAt 时间 ISO 串 */
export interface StudentDTO {
  id: string
  username: string
  displayName: string
  enrolledAt: string
  status: string
}

/** 会话详情（后端 vo/ChatSessionDetailVO，回放 Drawer 只读消息流） */
export interface ChatSessionDetailVO {
  id: string
  userId: string
  title: string
  status: SessionStatus
  model: string
  messages: ChatMessageVO[]
}
