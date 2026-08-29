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
import { z } from "zod";

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

/** J1 学生课程（StudentCourseVO；rating/price 为 BigDecimal→number，learningCount 为 Integer→number，不受 R0 Long 铁律影响） */
export interface StudentCourse {
  id: string;
  title: string;
  coverImage: string | null;
  category: string | null;
  instructorName: string | null;
  duration: string | null;
  rating: number | null;
  learningCount: number;
  /** 课程价格（单位元，≤2 位小数；0/null 为免费——契约 C 2026-08-29） */
  price: number | null;
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
  /** 课程价格（单位元，≤2 位小数；0/null 为免费——契约 C 2026-08-29） */
  price: number | null;
}

/**
 * 购买结果（CoursePurchaseVO，契约 B 2026-08-29）
 *
 * courseId 为 Long→string（R0 铁律）；status 购买后选课状态（恒 ACTIVE）；
 * purchased 恒 true（保留字段支撑未来支付态扩展）。
 */
export interface CoursePurchaseResult {
  courseId: string;
  status: string;
  purchased: boolean;
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

// ===== 时间轴节点体系（2026-08-28 时间线改版：SSE 事件按到达序归组的渲染模型） =====

/**
 * 查询计划载荷 zod 边界校验 schema（QUERY_PLAN SSE 事件 data 与历史 query_plan 行
 * content 同构，后端单一构造点保证一致）：intent 为意图 code 小写规范名
 * （knowledge_question / chat / unknown，QU 失败降级为 unknown + 原问题），
 * rewritten 为改写查询列表，filters.courseNames 为课程名收窄过滤（空数组 = 无过滤）。
 * 校验失败的事件整体忽略（脏数据不落时间轴）。
 */
export const queryPlanPayloadSchema = z.object({
  intent: z.string(),
  rewritten: z.array(z.string()),
  filters: z.object({
    courseNames: z.array(z.string()),
  }),
});

/** 查询计划载荷（zod 推导类型；SSE 事件与历史行共用） */
export type QueryPlanPayload = z.infer<typeof queryPlanPayloadSchema>;

/** 阶段节点（stage 事件按到达序建节点；同 stage 键去重保证 ring 回放幂等） */
export interface TimelineStageNode {
  kind: "stage";
  /** 阶段键（与 ChatStage.stage 同源） */
  stage: ChatStageKey;
  /** 后端中文文案（如「正在理解你的问题」） */
  label: string;
}

/** 查询计划节点（query_plan 事件建节点；每 run 语义唯一，二推原位替换） */
export interface TimelineQueryPlanNode {
  kind: "queryPlan";
  /** 意图 code 小写规范名（knowledge_question / chat / unknown） */
  intent: string;
  /** 改写查询列表（降级时为原问题） */
  rewritten: string[];
  /** 课程名收窄过滤（空数组 = 无过滤） */
  courseNames: string[];
}

/**
 * 思考节点（thinking 事件按 stage 归组：同 stage 多 delta 合并一节点，
 * lines 为按换行拆分的思考行、末行为进行中行；ended 由 thinking_end{stage} 置位）
 */
export interface TimelineThinkingNode {
  kind: "thinking";
  /** 思考来源阶段（understanding / attachments / generating；回放 null 降级 generating） */
  stage: ChatStageKey;
  /** 思考行列表（delta 按换行并入：首段续接末行，其余各起新行） */
  lines: string[];
  /** thinking_end 是否已到达（组件据此退出「思考中」状态） */
  ended: boolean;
}

/** 来源节点（sources 事件建节点；二推整体原位替换，保持首个到达位置） */
export interface TimelineSourcesNode {
  kind: "sources";
  /** 召回来源列表（与 StreamMessage.sources 同源快照） */
  sources: RetrievalSource[];
}

/** 工具节点（tool_call 建 pending 节点，tool_result 按 toolCallId 原位更新） */
export interface TimelineToolNode {
  kind: "tool";
  /** 工具调用标识（后端可发空串，空串按到达顺序兜底配对） */
  toolCallId: string;
  /** 工具名（如 searchKnowledge，UI 做人话映射） */
  toolName: string;
  /** tool_call 的 input 原文（JSON 任意结构） */
  input: unknown;
  /** pending=等待结果、success=完成、error=失败（后端恒 success，分支保留） */
  status: "pending" | "success" | "error";
  /** tool_result 的 output 原文（未配对前为 null） */
  output: unknown;
}

/**
 * 时间轴节点判别联合：SSE 事件按到达序 push/merge 进 StreamMessage.timeline，
 * 链式时间轴组件（ChainTimeline）逐节点渲染为链上步骤；delta 正文不入时间轴
 * （仍累积 StreamMessage.text，由答案区渲染）
 */
export type TimelineNode =
  | TimelineStageNode
  | TimelineQueryPlanNode
  | TimelineThinkingNode
  | TimelineSourcesNode
  | TimelineToolNode;

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

/**
 * R1 学生历史消息（StudentMessageVO；messageType: null=正文 / thinking / TOOL_CALL /
 * TOOL_RESULT / query_plan；intentType 存量可 null；thinkingStage 为 thinking 行的
 * 思考阶段键，历史存量行无该列值时为 null——前端降级按 generating 渲染）
 */
export interface StudentMessage {
  id: string;
  role: string;
  content: string;
  messageType: string | null;
  /** thinking 行的阶段键（understanding/attachments/generating）；非 thinking 行与存量旧行为 null */
  thinkingStage: string | null;
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
