/**
 * C 端接口类型系统（任务 7 地基）
 *
 * 类型铁律（docs/contracts/2026-08-16-接口契约定稿.md §7 / 后端 R0 Jackson Long→String）：
 * - 后端一切 Long 来源字段（id / total / fileSize / runId / userId 等）按 string 接收，
 *   防止雪花 ID（19 位）超出 JS Number.MAX_SAFE_INTEGER 精度丢失
 * - Integer 来源字段（learningCount / page / size / seq / chunkIndex / startPage / endPage）
 *   与浮点（rating / score）保持 number（R0 仅序列化 Long，Integer 不受影响）
 * - 可空对象字段恒输出 null（契约 §2），集合字段恒输出 []
 * - LocalDateTime → ISO-8601 无时区字符串（"2026-08-24T10:15:30"）
 */

/** 后端统一响应包装：code 与 HTTP 状态同值，成功码为 0（非 200）；401 特例响应无 data 键，故 data 可选 */
export interface ApiResponse<T> {
  code: number;
  message: string;
  data?: T;
}

/** 分页包装：total 为 Long→string；page/size 为 int→number */
export interface PageResponse<T> {
  records: T[];
  total: string;
  page: number;
  size: number;
}

/** 登录/刷新响应（AuthController LoginResponse；userId 为 Long→string） */
export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  userId: string;
  role: string;
  displayName: string;
}

/** J1 学生课程（StudentCourseVO；rating 为 BigDecimal→number，learningCount 为 Integer→number，不受 R0 Long 铁律影响） */
export interface StudentCourse {
  id: string;
  title: string;
  coverImage: string | null;
  category: string | null;
  instructorName: string | null;
  duration: string | null;
  rating: number | null;
  learningCount: number;
}

/** 公开课程（PublicCourseVO；未登录可浏览，比 StudentCourse 多 description 供详情页展示） */
export interface PublicCourse {
  id: string;
  title: string;
  description: string | null;
  coverImage: string | null;
  category: string | null;
  instructorName: string | null;
  duration: string | null;
  rating: number | null;
  learningCount: number;
}

/** J2 课程资料分片（ChunkVO；含页码区间 badge 所需 startPage/endPage） */
export interface MaterialChunk {
  id: string;
  content: string;
  headingPath: string | null;
  chunkIndex: number;
  parentTitle: string | null;
  startPage: number | null;
  endPage: number | null;
}

/** J3/J4 资料分片简报（ChunkBriefVO） */
export interface ChunkBrief {
  id: string;
  content: string;
  headingPath: string | null;
  chunkIndex: number;
  parentTitle: string | null;
}

/** J4 分片上下文（ChunkContextVO；parent/prev/next 可空关联恒为 null，前端 if (x) 判断） */
export interface ChunkContext {
  id: string;
  docId: string;
  kbId: string;
  content: string;
  headingPath: string | null;
  chunkIndex: number;
  courseId: string | null;
  parentChunkId: string | null;
  prevChunkId: string | null;
  nextChunkId: string | null;
  parent: ChunkBrief | null;
  prev: ChunkBrief | null;
  next: ChunkBrief | null;
}

/** 检索来源（SSE SOURCES 事件与历史 sources 数组同构；score 为 double→number 不受 R0 影响；
 *  content 为片段正文截断预览（2026-08-27 召回抽屉），存量 sources_json 无该字段按可缺省容错） */
export interface RetrievalSource {
  chunkId: string;
  docTitle: string;
  headingPath: string;
  score: number;
  content?: string;
}

/** STAGE 阶段事件键（后端 SseEventTransformer.STAGE_* 同值契约）：附件解析→意图理解→知识库检索→生成回答 */
export type ChatStageKey = "attachments" | "understanding" | "retrieving" | "generating";

/** STAGE 阶段条目（SSE stage 事件载荷；label 为后端中文文案直接展示） */
export interface ChatStage {
  stage: ChatStageKey;
  label: string;
}

/** 附件记录（上传返回/历史回显统一载体；url 为 MinIO objectKey 非可直接访问 URL；size 为 Long→string） */
export interface AttachmentRecord {
  type: "image" | "document";
  url: string;
  name: string;
  size: string;
}

/** 对话附件提交项：结构同 AttachmentRecord，随 ChatRequest 提交（来自 uploadAttachments 返回值） */
export type ChatAttachment = AttachmentRecord;

/** J6/J7 会话条目（SessionVO；lastMessageAt 新建会话时为 null） */
export interface SessionItem {
  id: string;
  title: string;
  status: string;
  lastMessageAt: string | null;
  createdAt: string;
}

/** R1 学生历史消息（StudentMessageVO；messageType: null=正文 / thinking / TOOL_CALL / TOOL_RESULT；intentType 存量可 null） */
export interface StudentMessage {
  id: string;
  role: string;
  content: string;
  messageType: string | null;
  intentType: string | null;
  runId: string;
  seq: number;
  createdAt: string;
  sources: RetrievalSource[];
  attachments: AttachmentRecord[];
}

/** J8 对话请求（ChatRequest；sessionId null=新建会话，query 必填非空，attachments 可 null） */
export interface ChatRequest {
  sessionId: string | null;
  query: string;
  attachments: ChatAttachment[] | null;
}

/** J5 反馈请求（FeedbackRequest；messageId 来自 SSE end 事件；intentType 可选） */
export interface FeedbackRequest {
  sessionId: string;
  messageId: string;
  isLiked: boolean;
  intentType?: string;
}
