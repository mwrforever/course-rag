/**
 * C 端统一 api client（任务 7 核心）
 *
 * 职责（设计文档 §3.1/§3.2/§3.3）：
 * - 统一 baseURL /api/v1（相对路径，dev 经 Next rewrite 同源代理转发 8080，规避 CORS 与 cookie domain）
 * - AT（Access Token）内存变量为主 + 后端 httpOnly cookie 兜底（每次请求 credentials: include）
 * - RT（Refresh Token）存 localStorage（key `c_rt`，端前缀区分 B 端；R-4 决策接受 XSS 面）
 * - 401 单飞刷新：并发 401 共享同一个 refresh promise（去重）→ RT 一次性旋转 → 重放原请求
 * - 刷新认证性失败 → 清凭据 + 触发登出回调（AuthProvider 注册，清空用户态）且不重放
 * - 网络错误抛类型化 NetworkError，不清凭据不登出
 *
 * 线程安全说明：模块级单飞 promise 保证并发 401 只发一次 refresh；
 * 登出回调为单一注册槽（AuthProvider 挂载注册/卸载注销），非线程安全问题场景。
 */
import type {
  ApiResponse,
  AttachmentRecord,
  ChatRequest,
  ChunkBrief,
  ChunkContext,
  CoursePurchaseResult,
  FeedbackRequest,
  LoginResponse,
  MaterialChunk,
  PageResponse,
  PublicCourse,
  SessionItem,
  StudentCourse,
  StudentMessage,
} from "./types";

/** API 基础路径（dev 经 next.config.ts rewrite 同源代理后端 8080） */
const BASE_URL = "/api/v1";
/** RT 的 localStorage 键（c_ 前缀区分 B 端 sessionStorage 方案，设计 §3.1） */
const REFRESH_TOKEN_KEY = "c_rt";
/** RT 存在性提示 cookie 名：RT 落 localStorage 服务端不可见，以此非 httpOnly cookie 向
 *  middleware 提供「持有凭证」迹象——AT cookie 过期但 RT 有效的窗口由它放行走静默续期 */
const RT_LIVE_COOKIE = "c_rt_live";
/** RT 提示 cookie 有效期（秒）：与后端 RT 有效期对齐（7 天，application.yml refresh-token-expiry） */
const RT_LIVE_COOKIE_MAX_AGE = 604800;

/** 自身即认证语义的端点：401 不触发单飞刷新（防递归） */
const SELF_AUTH_PATHS = ["/auth/login", "/auth/refresh", "/auth/logout"];

/** API 业务错误：code 与 HTTP 状态同值（契约 §1），前端按 code 分级处理而非依赖文案 */
export class ApiError extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.code = code;
  }
}

/** 网络错误：请求未抵达服务器（断网/DNS/代理不可达），不清凭据不登出（设计 §3.2） */
export class NetworkError extends Error {
  constructor() {
    super("网络连接失败，请检查网络");
    this.name = "NetworkError";
  }
}

// ===== 凭据存取：AT 模块内存变量（刷新页面即失，由 AuthProvider 静默续期恢复） =====

/** 内存 Access Token（登录/刷新后写入；不清 Storage，规避 XSS 直读） */
let accessToken: string | null = null;
/** 401 刷新失败登出回调（AuthProvider 挂载时注册，卸载时注销） */
let unauthorizedHandler: (() => void) | null = null;
/** 单飞 refresh promise：并发 401 去重共享 */
let refreshPromise: Promise<LoginResponse> | null = null;

/** 写入内存 AT（传 null 仅清内存值，不动 RT） */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/** 读取内存 AT（测试与 SSE 手工请求头组装用） */
export function getAccessToken(): string | null {
  return accessToken;
}

/** 同步 RT 存在性提示 cookie（有 RT 写入 7 天有效期，无 RT 即刻清除） */
function syncRtLiveCookie(hasRt: boolean): void {
  document.cookie = hasRt
    ? `${RT_LIVE_COOKIE}=1; Path=/; Max-Age=${RT_LIVE_COOKIE_MAX_AGE}; SameSite=Lax`
    : `${RT_LIVE_COOKIE}=; Path=/; Max-Age=0; SameSite=Lax`;
}

/** 写入 RT 到 localStorage（传 null 移除），并同步 c_rt_live 提示 cookie（middleware 放行依据） */
export function setRefreshToken(token: string | null): void {
  if (token === null) {
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    syncRtLiveCookie(false);
  } else {
    localStorage.setItem(REFRESH_TOKEN_KEY, token);
    syncRtLiveCookie(true);
  }
}

/** 读取 localStorage 的 RT（无则 null） */
export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

/** 清空全部本地凭据（内存 AT + localStorage RT + c_rt_live 提示 cookie），登出与刷新失败时调用 */
export function clearCredentials(): void {
  accessToken = null;
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  syncRtLiveCookie(false);
}

/** 注册/注销 401 刷新失败的登出回调（AuthProvider 用；传 null 注销） */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

// ===== 响应解析 =====

/** 解析响应体：空体与非 JSON 容错为 null（cancel 端点为 ResponseEntity<Void> 空体；网关错误页为 HTML） */
async function parseBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** 统一业务校验：ApiResponse.code!==0 抛 ApiError；非 ApiResponse 结构按 HTTP 状态兜底 */
function assertOk(response: Response, body: unknown): void {
  if (body !== null && typeof body === "object" && "code" in body) {
    const { code, message } = body as { code: number; message?: string };
    // 成功码是 0 而非 200（契约 §1）
    if (code !== 0) {
      throw new ApiError(code, message ?? "请求失败");
    }
    return;
  }
  if (!response.ok) {
    throw new ApiError(response.status, `请求失败（HTTP ${response.status}）`);
  }
}

// ===== 刷新（RT 一次性旋转 + 单飞去重） =====

/** 执行一次 refresh 请求：成功后新 AT 入内存、新 RT 回写存储（旋转） */
async function doRefresh(rt?: string): Promise<LoginResponse> {
  const token = rt ?? getRefreshToken();
  if (!token) {
    throw new ApiError(401, "登录已失效，请重新登录");
  }
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify({ refreshToken: token }),
    });
  } catch {
    throw new NetworkError();
  }
  const body = await parseBody(response);
  assertOk(response, body);
  const data = (body as ApiResponse<LoginResponse> | null)?.data;
  if (!data) {
    throw new ApiError(401, "登录已失效，请重新登录");
  }
  // RT 一次性旋转：新 AT 入内存、新 RT 回写存储
  accessToken = data.accessToken;
  setRefreshToken(data.refreshToken);
  return data;
}

/**
 * 单飞刷新入口：并发调用共享同一 promise（仅首个调用真正发请求）
 *
 * 认证性失败（ApiError，如 RT 无效/过期/复用全量吊销）→ 清凭据 + 触发登出回调；
 * 网络失败（NetworkError）→ 不动凭据（设计 §3.2 网络错误不登出）。
 */
function refreshOnce(rt?: string): Promise<LoginResponse> {
  if (!refreshPromise) {
    refreshPromise = doRefresh(rt)
      .catch((error: unknown) => {
        if (error instanceof ApiError) {
          clearCredentials();
          unauthorizedHandler?.();
        }
        throw error;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

// ===== 请求核心 =====

/**
 * 带认证的 fetch：组装 Bearer 头与 credentials，401 时单飞刷新后重放一次
 *
 * 返回原始 Response（postChat 的 SSE 流式消费场景）；业务解包用 apiFetch。
 * retried 标记防二次 401 死循环（重放后仍 401 交由调用方按 ApiError 处理）。
 */
async function authedFetch(path: string, init?: RequestInit, retried = false): Promise<Response> {
  const headers = new Headers(init?.headers);
  // 内存 AT 优先，httpOnly cookie 由 credentials 通道兜底（双通道，设计 §3.1）
  if (accessToken && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  // JSON 体补 Content-Type；FormData 交由浏览器生成 multipart 边界
  if (init?.body != null && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  let response: Response;
  try {
    response = await fetch(`${BASE_URL}${path}`, { ...init, headers, credentials: "include" });
  } catch {
    // 网络层失败：类型化抛出且不动凭据（设计 §3.2）
    throw new NetworkError();
  }
  // 401 → 单飞刷新成功后重放一次；刷新失败时 refreshOnce 内已清凭据+回调并抛出，不重放
  if (response.status === 401 && !retried && !SELF_AUTH_PATHS.includes(path)) {
    await refreshOnce();
    return authedFetch(path, init, true);
  }
  return response;
}

/**
 * 统一请求入口：带认证发起请求并解包 ApiResponse.data
 *
 * @param path 相对路径（如 "/student/courses"，自动拼 baseURL /api/v1）
 * @param init fetch 初始化（body 为 FormData 时不强设 JSON Content-Type）
 * @returns 解包后的 data（ApiResponse&lt;Void&gt; 空数据场景为 undefined）
 * @throws ApiError code!==0 或 HTTP 错误；NetworkError 网络层失败
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await authedFetch(path, init);
  const body = await parseBody(response);
  assertOk(response, body);
  return (body as ApiResponse<T> | null)?.data as T;
}

// ===== 认证端点 =====

/**
 * 登录：username + password（非邮箱），deviceType 固定 WEB_DESKTOP
 *
 * 成功后 AT 入内存、RT 入 localStorage；失败抛 ApiError（401 凭证错 / 403 已禁用）。
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const data = await apiFetch<LoginResponse>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password, deviceType: "WEB_DESKTOP" }),
  });
  accessToken = data.accessToken;
  setRefreshToken(data.refreshToken);
  return data;
}

/**
 * 发送注册验证码（HTML 邮件，6 位数字，15 分钟有效；重发间隔与防爆破阈值由后端频控）
 *
 * 失败抛 ApiError：409 邮箱已注册或重发间隔未到 / 503 SMTP 故障。
 */
export async function sendRegisterCode(email: string): Promise<void> {
  await apiFetch("/auth/register/code", {
    method: "POST",
    body: JSON.stringify({ email }),
  });
}

/**
 * 注册并自动登录：邮箱 + 验证码 + 密码（昵称可选，缺省回退邮箱前缀）
 *
 * 成功后 AT 入内存、RT 入 localStorage（注册即登录契约，与 /login 响应同构）；
 * 失败抛 ApiError：400 验证码过期/错误/锁定 / 409 并发抢注。
 */
export async function registerAndLogin(input: {
  email: string;
  code: string;
  password: string;
  nickname?: string;
}): Promise<LoginResponse> {
  const data = await apiFetch<LoginResponse>("/auth/register", {
    method: "POST",
    body: JSON.stringify({
      email: input.email,
      code: input.code,
      password: input.password,
      ...(input.nickname ? { nickname: input.nickname } : {}),
    }),
  });
  accessToken = data.accessToken;
  setRefreshToken(data.refreshToken);
  return data;
}

/**
 * 主动刷新（AuthProvider 挂载静默续期入口；与 401 拦截共享单飞去重）
 *
 * @param rt 显式 RT（缺省读 localStorage 存储）
 */
export function refresh(rt?: string): Promise<LoginResponse> {
  return refreshOnce(rt);
}

/** 登出：后端吊销尽力而为（网络失败不阻断），无论成败都清本地凭据（本地登出必须成功） */
export async function logout(): Promise<void> {
  try {
    await authedFetch("/auth/logout", { method: "POST" });
  } catch {
    // 后端吊销尽力而为：断网/令牌过期均不阻断本地登出（清理照常执行）
  } finally {
    clearCredentials();
  }
}

// ===== 业务端点（J1-J8 + R1/R3 补口） =====

/** J1: 我的课程全量列表（后端无分页，前端内存过滤排序） */
export function getMyCourses(): Promise<StudentCourse[]> {
  return apiFetch<StudentCourse[]>("/student/courses");
}

/** 公开课程列表（未登录可访问：首页/课堂页数据源，仅对外信息字段） */
export function getPublicCourses(): Promise<PublicCourse[]> {
  return apiFetch<PublicCourse[]>("/public/courses");
}

/** J2: 课程专属资料分片列表（未选课 403 → 课程页专属引导态） */
export function getMaterials(courseId: string): Promise<MaterialChunk[]> {
  return apiFetch<MaterialChunk[]>(`/student/courses/${courseId}/materials`);
}

/**
 * 学生自助购买课程（契约 B 2026-08-29：POST /student/courses/{courseId}/purchase）
 *
 * 幂等语义：已购（ACTIVE）再购返回与首次相同的成功结构，不报 409；
 * DROPPED 记录后端重激活；购买为 dev 直通（无支付校验）。
 * 失败抛 ApiError：404 课程不存在或已下架 / 403 非 STUDENT 角色。
 */
export function purchaseCourse(courseId: string): Promise<CoursePurchaseResult> {
  return apiFetch<CoursePurchaseResult>(`/student/courses/${courseId}/purchase`, {
    method: "POST",
  });
}

/** J3: 通用资料库分片分页（courseId=DEFAULT） */
export function getKbChunks(page: number, size: number): Promise<PageResponse<ChunkBrief>> {
  return apiFetch<PageResponse<ChunkBrief>>(`/student/knowledge-bases?page=${page}&size=${size}`);
}

/** J4: 分片上下文（父/前/后关联，可空恒 null） */
export function getChunkContext(chunkId: string): Promise<ChunkContext> {
  return apiFetch<ChunkContext>(`/student/chunks/${chunkId}/context`);
}

/** J6: 我的会话分页（keyword 可选：标题模糊搜索，空/缺省 = 全量列表） */
export function getSessions(
  page: number,
  size: number,
  keyword?: string,
): Promise<PageResponse<SessionItem>> {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  if (keyword) {
    query.set("keyword", keyword);
  }
  return apiFetch<PageResponse<SessionItem>>(`/student/sessions?${query.toString()}`);
}

/** J7: 创建会话（title 缺省后端补「新对话」） */
export function createSession(title?: string): Promise<SessionItem> {
  return apiFetch<SessionItem>("/student/sessions", {
    method: "POST",
    body: JSON.stringify({ title }),
  });
}

/** 重命名会话（PATCH /student/sessions/{id}；404/403 守卫与删除端点一致） */
export function updateSessionTitle(sessionId: string, title: string): Promise<SessionItem> {
  return apiFetch<SessionItem>(`/student/sessions/${sessionId}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
}

/** R3: 删除会话（级联软删；活跃 run 时后端 409） */
export function deleteSession(sessionId: string): Promise<void> {
  return apiFetch<void>(`/student/sessions/${sessionId}`, { method: "DELETE" });
}

/** R1: 会话历史消息分页（升序最旧一页，默认 size=200） */
export function getSessionMessages(
  sessionId: string,
  page: number,
  size: number,
): Promise<PageResponse<StudentMessage>> {
  return apiFetch<PageResponse<StudentMessage>>(
    `/student/sessions/${sessionId}/messages?page=${page}&size=${size}`,
  );
}

/**
 * J8: 发起 SSE 对话（POST /student/chat，与 /chat/stream 等价但角色门禁更宽，前端统一用此路径）
 *
 * 返回原始 Response 交由上层 ReadableStream 手写 SSE 解析器消费（设计 §1.5.4）；
 * 401 时自动单飞刷新后重放（SSE 建立前 AT 过期场景，设计 §3.1）。
 */
export function postChat(req: ChatRequest): Promise<Response> {
  return authedFetch("/student/chat", { method: "POST", body: JSON.stringify(req) });
}

/** 取消正在生成的 run（run 终态后后端 409，由调用方静默吞） */
export function cancelRun(runId: string): Promise<void> {
  return apiFetch<void>(`/student/chat/${runId}/cancel`, { method: "POST" });
}

/**
 * 断流重连（设计 §1.5.4 传输层）：GET /student/chat/{runId}/reconnect
 *
 * lastEventId 为断流前最后消费事件的 SSE id 行（seq）；null 表示全量回放（不带查询参数）。
 * 返回原始 Response 交由上层 ReadableStream 消费；401 时自动单飞刷新后重放；
 * 降级路径（回放窗口过期）以 error 事件 code=REPLAY_FAILED 呈现，由上层错误分级。
 */
export function reconnectChat(runId: string, lastEventId: number | null): Promise<Response> {
  const query = lastEventId === null ? "" : `?lastEventId=${lastEventId}`;
  return authedFetch(`/student/chat/${runId}/reconnect${query}`, { method: "GET" });
}

/** 附件上传（multipart 字段名 files）：返回附件记录（url 为 objectKey，预览用本地 blob） */
export function uploadAttachments(files: File[]): Promise<AttachmentRecord[]> {
  const form = new FormData();
  for (const file of files) {
    form.append("files", file);
  }
  return apiFetch<AttachmentRecord[]>("/student/chat/attachments", { method: "POST", body: form });
}

/** J5: 提交 AI 回答反馈（messageId 来自 SSE end 事件） */
export function postFeedback(req: FeedbackRequest): Promise<void> {
  return apiFetch<void>("/student/feedbacks", { method: "POST", body: JSON.stringify(req) });
}
