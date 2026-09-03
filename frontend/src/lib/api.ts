/**
 * B 端 API client（Task 16 核心交付）
 *
 * 契约来源：docs/backed/2026-08-24-后端功能调整.md §五（10 项核查）+ 设计 §3.1/§3.2/§3.3：
 * - baseURL /api/v1，dev 走 Vite proxy 转发 8080；withCredentials 打开 AT cookie 兜底通道
 * - 成功码是 0 不是 200：所有响应经统一解包（data?: T，401 错误体无 data 键）
 * - 401 全局拦截：单飞刷新（共享 store.refreshOnce 的 promise）→ 携带新 AT 重放原请求
 * - 刷新失败（RT 失效/复用全量吊销）→ 清凭据 → 跳登录页 → toast「登录已失效，请重新登录」
 * - 登录/刷新端点 401 直抛不刷（避免死循环），登录失败文案就地分级展示
 * - DELETE 带 body：axios data 写法（课程移除教师）
 * - 错误分级 ApiError：code 与 HTTP 同值（网络错误 code=0，message 统一网络文案）
 *
 * 线程安全注意：模块级 refreshPromise 由 axios 拦截器单线程调度，无并发竞争。
 */
import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'

import { showToast } from '@/lib/toast'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

import type {
  ApiResponse,
  BatchChunkUpdateRequest,
  BatchCorrectedRequest,
  ChatSessionDetailVO,
  ChatSessionVO,
  ChunkCollectionTypeRequest,
  ChunkContentUpdateRequest,
  CourseContentDTO,
  CourseCoverVO,
  CourseDTO,
  CourseScheduleVO,
  CreateCourseRequest,
  CreateScheduleRequest,
  CreateUserRequest,
  DashboardStats,
  DocumentChunkVO,
  DocumentUpdateRequest,
  DocumentVO,
  EnrollmentRequest,
  FeedbackIntentStat,
  FeedbackStats,
  FeedbackTrendItem,
  KnowledgeBaseRequest,
  KnowledgeBaseVO,
  LoginRequest,
  LoginResponse,
  PageResponse,
  ResetPasswordRequest,
  StudentDTO,
  SysLoginRecordVO,
  SysTokenBlacklistVO,
  UpdateCourseRequest,
  UpdateScheduleRequest,
  UpdateStatusRequest,
  UpdateUserRequest,
  UserDTO,
  UserFeedbackVO,
  UserRole,
  UserStatus,
} from '@/lib/types'

// ====================================================================
// 错误类型与常量
// ====================================================================

/** 网络错误统一文案（设计 §3.2：不跳登录，页内展示） */
export const NETWORK_ERROR_MESSAGE = '网络连接失败，请检查网络'

/**
 * 业务错误（设计 §3.2 分级）
 *
 * @param code 业务错误码：与 HTTP 状态同值（成功 0）；网络错误为 0 且无 status
 * @param message 后端 message（网络错误时为 NETWORK_ERROR_MESSAGE）
 * @param status HTTP 状态码：网络错误时为 undefined（调用方以此区分网络错误）
 */
export class ApiError extends Error {
  readonly code: number
  readonly status?: number

  constructor(code: number, message: string, status?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

/**
 * 401 重放标记：拦截器内扩展的请求配置字段
 * （_retried=true 表示已刷新重放过一次，防止新 AT 仍 401 时无限循环）
 */
interface RetriableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean
}

// ====================================================================
// axios 实例与拦截器
// ====================================================================

/** 统一 axios 实例：baseURL /api/v1 + cookie 兜底通道（设计 §3.3） */
export const apiClient = axios.create({
  baseURL: '/api/v1',
  withCredentials: true,
  timeout: 20_000,
})

/**
 * 上传类请求超时预算（毫秒，per-request 覆盖实例级 20s）
 *
 * axios 的 timeout 覆盖请求体发送与服务端响应等待全程：文档上传（≤100MB）在常见
 * 上行带宽下 30MB+ 发送即超 20s，超时后无响应体被归一为「网络连接失败」误导排查；
 * 封面上传（5MB）慢网络下同样风险。上传通道统一放宽至 300s，取消由用户导航离开承担。
 */
export const UPLOAD_TIMEOUT_MS = 300_000

/** 请求拦截器：内存 AT 存在时写入 Authorization 头（AT 丢失时基于 cookie 兜底） */
apiClient.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

/**
 * 401 单飞刷新并重放原请求
 *
 * @param config 触发 401 的原始请求配置（已标记 _retried）
 * @returns 重放后的响应（请求拦截器会自动携带新 AT）
 * @throws ApiError(401) 刷新失败时抛出；调用方已执行失败登出全局流
 */
async function handleUnauthorized(config: RetriableConfig): Promise<AxiosResponse> {
  const auth = useAuthStore()
  try {
    // 单飞：并发 401 共享同一 refresh promise（设计 §3.1 请求封装）
    await auth.refreshOnce()
  } catch {
    // 刷新失败（RT 失效/复用全量吊销）：清凭据 → 跳登录 → 统一文案 toast
    auth.clearAuth()
    if (router.currentRoute.value.name !== 'login') {
      router.push({ name: 'login' })
    }
    showToast('登录已失效，请重新登录', 'danger')
    throw new ApiError(401, '登录已失效，请重新登录', 401)
  }
  // 重放原请求：请求拦截器以最新 AT 重新注入 Authorization 头
  return apiClient.request(config)
}

/** 认证端点判定：登录/刷新/登出 401 不触发刷新（登录错误就地展示，刷新错误直抛防死循环） */
function isAuthRequestConfig(config: InternalAxiosRequestConfig): boolean {
  return config.url?.includes('/auth/') ?? false
}

/** 成功通道：解包 ApiResponse，code=0 拆出 data；非 0 视为业务错误抛出 */
function unwrapResponse(response: AxiosResponse): AxiosResponse {
  const body = response.data as ApiResponse<unknown> | undefined
  if (body && typeof body.code === 'number') {
    if (body.code === 0) {
      // 成功：data 可缺省（如登出接口），统一归一为 null
      response.data = body.data ?? null
      return response
    }
    throw new ApiError(body.code, body.message ?? '请求失败', body.code)
  }
  return response
}

/** 错误通道：401 单飞刷新重放；其余错误包装 ApiError（分级见类注释） */
async function handleErrorResponse(error: AxiosError): Promise<AxiosResponse> {
  const config = error.config as RetriableConfig | undefined
  const status = error.response?.status
  const body = error.response?.data as ApiResponse<unknown> | undefined

  if (status === 401 && config && !config._retried && !isAuthRequestConfig(config)) {
    config._retried = true
    return handleUnauthorized(config)
  }

  // 网络错误无响应体：统一文案，不跳登录（设计 §3.2）
  if (!body) {
    throw new ApiError(0, NETWORK_ERROR_MESSAGE)
  }
  throw new ApiError(body.code ?? status ?? 0, body.message ?? '请求失败', status)
}

apiClient.interceptors.response.use(unwrapResponse, handleErrorResponse)

// ====================================================================
// 业务接口函数（按设计 §2.4 页面分域导出）
// ====================================================================

/**
 * 统一业务请求入口：拦截器已完成 ApiResponse 解包，此处直接取业务数据
 *
 * @typeParam T 业务数据类型（ApiResponse.data）
 * @param config axios 请求配置（url 相对 baseURL）
 * @returns 解包后的业务数据（code=0 成功；否则抛 ApiError）
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await apiClient.request<T>(config)
  return response.data as T
}

/** /auth/me 响应（M10 启动恢复）：userId 为 string（Long 序列化铁律） */
export interface MeResponse {
  userId: string
  role: UserRole
  displayName: string
}

/** 认证域（设计 §3.1：login/refresh/logout 三端点 + M10 me 身份查询） */
export const authApi = {
  /** 登录：username+密码，deviceType 缺省 WEB_DESKTOP（后端实测缺省值兜底）；成功写 httpOnly cookie */
  login: (data: LoginRequest) =>
    request<LoginResponse>({
      method: 'post',
      url: '/auth/login',
      data: { ...data, deviceType: data.deviceType ?? 'WEB_DESKTOP' },
    }),
  /** 静默刷新：RT 一次性旋转（响应含新 AT+RT，前端 setAuth 后重放原请求） */
  refresh: (data: { refreshToken: string }) =>
    request<LoginResponse>({ method: 'post', url: '/auth/refresh', data }),
  /** 登出：幂等（失败不阻塞本地清理，store 层容错） */
  logout: () => request<void>({ method: 'post', url: '/auth/logout' }),
  /**
   * 查询当前登录用户身份（M10 启动恢复）：无副作用端点（不旋转 RT、不写库）。
   * isAuthRequestConfig 判定 url 含 '/auth/' → me 401 不触发刷新重放，静默语义正确
   */
  me: () => request<MeResponse>({ method: 'get', url: '/auth/me' }),
}

/** 知识库域（AdminKnowledgeBaseController）：两角色可进，教师限己建 */
export const knowledgeBaseApi = {
  list: (params?: { page?: number; size?: number; keyword?: string; signal?: AbortSignal }) => {
    // 解构剔除 signal：AbortSignal 对象不得进入查询串（会被序列化成垃圾参数），
    // 仅作为 axios 取消信号透传（契约 E 竞态防护）
    const { signal, ...query } = params ?? {}
    return request<PageResponse<KnowledgeBaseVO>>({
      method: 'get',
      url: '/admin/knowledge-bases',
      params: query,
      signal,
    })
  },
  get: (id: string) =>
    request<KnowledgeBaseVO>({ method: 'get', url: `/admin/knowledge-bases/${id}` }),
  create: (data: KnowledgeBaseRequest) =>
    request<KnowledgeBaseVO>({ method: 'post', url: '/admin/knowledge-bases', data }),
  update: (id: string, data: KnowledgeBaseRequest) =>
    request<void>({ method: 'put', url: `/admin/knowledge-bases/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/knowledge-bases/${id}` }),
}

/**
 * 上传进度归一回调（axios e.loaded/e.total → 0-100 百分比）
 *
 * @param onUploadProgress 页面传入的进度回调（设计 §2.4.2 上传进度条）
 * @returns axios 进度事件处理器（total 缺省按 1 防除零）
 */
export function toProgressCallback(onUploadProgress: (percent: number) => void) {
  return (e: { loaded: number; total?: number }) =>
    onUploadProgress(Math.round((e.loaded / (e.total ?? 1)) * 100))
}

/** 文档域（AdminDocumentController）：上传/列表/解析/下载，ETL 状态见设计 §2.5 */
export const documentApi = {
  /** 上传文档：multipart（kbId/title 必填，courseId 可选，file ≤100MB 白名单 pdf/docx/pptx/md/txt）；
   *  ≤100MB 传输远超实例级 20s，per-request 放宽超时（BUG-03） */
  upload: (form: FormData, onUploadProgress?: (percent: number) => void) =>
    request<DocumentVO>({
      method: 'post',
      url: '/admin/documents',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: UPLOAD_TIMEOUT_MS,
      // 上传进度回调（设计 §2.4.2 进度条；未传时不注册）
      onUploadProgress: onUploadProgress ? toProgressCallback(onUploadProgress) : undefined,
    }),
  list: (params?: {
    kbId?: string
    status?: string
    q?: string
    sort?: string
    page?: number
    size?: number
  }) => request<PageResponse<DocumentVO>>({ method: 'get', url: '/admin/documents', params }),
  get: (id: string) => request<DocumentVO>({ method: 'get', url: `/admin/documents/${id}` }),
  /** 改标题（DocumentUpdateRequest 仅 title 字段） */
  update: (id: string, data: DocumentUpdateRequest) =>
    request<void>({ method: 'put', url: `/admin/documents/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/documents/${id}` }),
  /** 重新解析（FAILED 终态行内「重新解析」入口） */
  reparse: (id: string) => request<void>({ method: 'post', url: `/admin/documents/${id}/reparse` }),
  /** 下载原始文件：blob 响应（FileSaver 由页面层负责落盘） */
  download: (id: string) =>
    request<Blob>({ method: 'get', url: `/admin/documents/${id}/download`, responseType: 'blob' }),
}

/** 分片域（AdminChunkController）：修正工作台 + 批量通道（文档级 Milvus 同步） */
export const chunkApi = {
  /** 待修正分片（correction_status=PENDING 固定，筛选仅 kbId/docId 两下拉） */
  pending: (params?: { kbId?: string; docId?: string; page?: number; size?: number }) =>
    request<PageResponse<DocumentChunkVO>>({ method: 'get', url: '/admin/chunks/pending', params }),
  list: (params?: { docId?: string; kbId?: string; page?: number; size?: number }) =>
    request<PageResponse<DocumentChunkVO>>({ method: 'get', url: '/admin/chunks', params }),
  get: (id: string) => request<DocumentChunkVO>({ method: 'get', url: `/admin/chunks/${id}` }),
  /** 编辑保存：改 content 触发重新向量化（保存 Dialog 提示文案） */
  updateContent: (id: string, data: ChunkContentUpdateRequest) =>
    request<void>({ method: 'put', url: `/admin/chunks/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/chunks/${id}` }),
  /** 单片集合类型调整：不同步 Milvus（弱化入口，统一引导批量通道） */
  updateCollectionType: (id: string, data: ChunkCollectionTypeRequest) =>
    request<void>({ method: 'patch', url: `/admin/chunks/${id}/collection-type`, data }),
  /** 上下文抽屉（parent/prev/current/next，可变节点为 null 不渲染） */
  context: (id: string) =>
    request<Record<string, DocumentChunkVO>>({ method: 'get', url: `/admin/chunks/${id}/context` }),
  /** 批量修正：collectionType + courseId（loading 态，Milvus 同步可能慢） */
  batchUpdate: (data: BatchChunkUpdateRequest) =>
    request<void>({ method: 'post', url: '/admin/chunks/batch-update', data }),
  /** 批量标记已修正：不可撤销（二次确认后调用） */
  batchCorrected: (data: BatchCorrectedRequest) =>
    request<void>({ method: 'post', url: '/admin/chunks/batch-corrected', data }),
}

/** 课程域（AdminCourseController + AdminScheduleController + AdminEnrollmentController 的课程侧） */
export const courseApi = {
  list: (params?: {
    page?: number
    size?: number
    category?: string
    keyword?: string
    signal?: AbortSignal
  }) => {
    // 解构剔除 signal：AbortSignal 对象不得进入查询串（会被序列化成垃圾参数），
    // 仅作为 axios 取消信号透传（契约 E 竞态防护）
    const { signal, ...query } = params ?? {}
    return request<PageResponse<CourseDTO>>({
      method: 'get',
      url: '/admin/courses',
      params: query,
      signal,
    })
  },
  get: (id: string) => request<CourseDTO>({ method: 'get', url: `/admin/courses/${id}` }),
  create: (data: CreateCourseRequest) =>
    request<CourseDTO>({ method: 'post', url: '/admin/courses', data }),
  update: (id: string, data: UpdateCourseRequest) =>
    request<void>({ method: 'put', url: `/admin/courses/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/courses/${id}` }),
  /**
   * 上传课程封面（契约 D.2.2：POST /admin/courses/cover，multipart 字段名 file）
   *
   * @param form 仅含 file 字段的 FormData（类型/大小白名单由后端二次校验）
   * @returns CourseCoverVO（objectKey + 相对 url，url 整串写入 coverImage 字段随课程提交）
   */
  uploadCover: (form: FormData) =>
    request<CourseCoverVO>({
      method: 'post',
      url: '/admin/courses/cover',
      data: form,
      headers: { 'Content-Type': 'multipart/form-data' },
      // 5MB 慢网络下同样会超实例级 20s，与文档上传同口径放宽（BUG-03）
      timeout: UPLOAD_TIMEOUT_MS,
    }),
  /** 教师分配（POST body 为 ID 数组，仅超管全量可选教师） */
  addTeachers: (id: string, teacherIds: string[]) =>
    request<void>({ method: 'post', url: `/admin/courses/${id}/teachers`, data: teacherIds }),
  /** 移除教师：DELETE 带 body（axios data 写法，设计 §2.4.4） */
  removeTeachers: (id: string, teacherIds: string[]) =>
    request<void>({ method: 'delete', url: `/admin/courses/${id}/teachers`, data: teacherIds }),
  contents: (id: string) =>
    request<CourseContentDTO[]>({ method: 'get', url: `/admin/courses/${id}/contents` }),
  /** 单 Tab 保存：body 为裸 JSON 字符串（设计 §2.4.4 逐 Tab 独立保存） */
  updateContent: (id: string, contentType: string, content: string) =>
    request<void>({
      method: 'put',
      url: `/admin/courses/${id}/contents/${contentType}`,
      data: content,
      // 裸字符串 body：显式声明 application/json（后端 @RequestBody String 接收）；
      // axios 默认 transformRequest 遇到 application/json 会把字符串 JSON.stringify
      // 成带引号形式，必须替换为该请求专属 transformRequest 原样透传（不加引号）
      headers: { 'Content-Type': 'application/json' },
      transformRequest: [(data) => data],
    }),
  batchContents: (id: string, contents: CourseContentDTO[]) =>
    request<void>({ method: 'put', url: `/admin/courses/${id}/contents`, data: contents }),
}

/** 排期域（AdminScheduleController）：课程排期增删改查 */
export const scheduleApi = {
  listByCourse: (courseId: string) =>
    request<CourseScheduleVO[]>({ method: 'get', url: `/admin/courses/${courseId}/schedules` }),
  create: (courseId: string, data: CreateScheduleRequest) =>
    request<CourseScheduleVO>({
      method: 'post',
      url: `/admin/courses/${courseId}/schedules`,
      data,
    }),
  get: (id: string) => request<CourseScheduleVO>({ method: 'get', url: `/admin/schedules/${id}` }),
  update: (id: string, data: UpdateScheduleRequest) =>
    request<void>({ method: 'put', url: `/admin/schedules/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/schedules/${id}` }),
}

/** 报名域（AdminEnrollmentController）：课程学生名单 */
export const enrollmentApi = {
  students: (courseId: string) =>
    request<StudentDTO[]>({ method: 'get', url: `/admin/courses/${courseId}/students` }),
  /** 批量添加学生：返回成功添加数（Integer 保持 number） */
  addStudents: (courseId: string, data: EnrollmentRequest) =>
    request<number>({ method: 'post', url: `/admin/courses/${courseId}/students`, data }),
  removeStudent: (courseId: string, studentId: string) =>
    request<void>({ method: 'delete', url: `/admin/courses/${courseId}/students/${studentId}` }),
  studentCourses: (studentId: string) =>
    request<CourseDTO[]>({ method: 'get', url: `/admin/students/${studentId}/courses` }),
}

/** 用户域（AdminUserController）：教师限己建学生，添加教师仅超管 */
export const userApi = {
  // 注：后端 AdminUserController.list 仅支持 page/size/role/status 四参（无 keyword，
  // 审核 Important-1 删除防静默失效）
  list: (params?: {
    page?: number
    size?: number
    role?: UserRole
    status?: UserStatus
    signal?: AbortSignal
  }) => {
    // 解构剔除 signal：AbortSignal 对象不得进入查询串（会被序列化成垃圾参数），
    // 仅作为 axios 取消信号透传（契约 E 竞态防护）
    const { signal, ...query } = params ?? {}
    return request<PageResponse<UserDTO>>({
      method: 'get',
      url: '/admin/users',
      params: query,
      signal,
    })
  },
  create: (data: CreateUserRequest) =>
    request<UserDTO>({ method: 'post', url: '/admin/users', data }),
  get: (id: string) => request<UserDTO>({ method: 'get', url: `/admin/users/${id}` }),
  update: (id: string, data: UpdateUserRequest) =>
    request<UserDTO>({ method: 'put', url: `/admin/users/${id}`, data }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/users/${id}` }),
  /** 重置密码（Dialog + 二次确认） */
  resetPassword: (id: string, data: ResetPasswordRequest) =>
    request<void>({ method: 'post', url: `/admin/users/${id}/reset-password`, data }),
  /** 启用/禁用（二次确认，danger） */
  updateStatus: (id: string, data: UpdateStatusRequest) =>
    request<void>({ method: 'patch', url: `/admin/users/${id}/status`, data }),
}

/** 反馈域（AdminFeedbackController）：报表 + 点赞率统计（回放仅超管） */
export const feedbackApi = {
  list: (params?: { page?: number; size?: number; intentType?: string }) =>
    request<PageResponse<UserFeedbackVO>>({ method: 'get', url: '/admin/feedbacks', params }),
  /** 意图 × 赞踩统计（likedCount/dislikedCount 为 Long 字符串） */
  stats: () => request<FeedbackIntentStat[]>({ method: 'get', url: '/admin/feedbacks/stats' }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/feedbacks/${id}` }),
}

/** 会话审计域（AdminSessionController）：超管专属 */
export const sessionApi = {
  list: (params?: { page?: number; size?: number }) =>
    request<PageResponse<ChatSessionVO>>({ method: 'get', url: '/admin/sessions', params }),
  /** 会话详情（回放 Drawer：messages 只读流） */
  detail: (id: string) =>
    request<ChatSessionDetailVO>({ method: 'get', url: `/admin/sessions/${id}` }),
  close: (id: string) => request<void>({ method: 'patch', url: `/admin/sessions/${id}/close` }),
  remove: (id: string) => request<void>({ method: 'delete', url: `/admin/sessions/${id}` }),
}

/** 安全审计域（AdminLoginRecordController）：登录记录 + Token 黑名单，超管专属 */
export const securityApi = {
  /** 登录记录列表（筛选 userId/deviceType/status） */
  loginRecords: (params?: {
    page?: number
    size?: number
    userId?: string
    deviceType?: string
    status?: string
  }) =>
    request<PageResponse<SysLoginRecordVO>>({ method: 'get', url: '/admin/login-records', params }),
  loginRecord: (id: string) =>
    request<SysLoginRecordVO>({ method: 'get', url: `/admin/login-records/${id}` }),
  /** 踢出设备（二次确认，danger） */
  revokeLoginRecord: (id: string) =>
    request<void>({ method: 'post', url: `/admin/login-records/${id}/revoke` }),
  blacklist: (params?: {
    page?: number
    size?: number
    userId?: string
    jti?: string
    tokenType?: string
  }) =>
    request<PageResponse<SysTokenBlacklistVO>>({
      method: 'get',
      url: '/admin/token-blacklist',
      params,
    }),
  /** 手工加入黑名单：后端全参数走 @RequestParam（查询参数传参） */
  addBlacklist: (params: {
    jti: string
    tokenType: string
    userId: string
    reason?: string
    expiresAt?: string
  }) => request<void>({ method: 'post', url: '/admin/token-blacklist', params }),
  removeBlacklist: (id: string) =>
    request<void>({ method: 'delete', url: `/admin/token-blacklist/${id}` }),
  /** 清理过期：返回 cleaned 数（Integer 保持 number） */
  cleanupBlacklist: () =>
    request<{ cleaned: number }>({ method: 'post', url: '/admin/token-blacklist/cleanup' }),
}

/** 仪表盘域（AdminDashboardController）：KPI + 趋势 */
export const dashboardApi = {
  /** 知识库 KPI：文档总数/待修正分片数/知识库数（Long 字符串） */
  stats: () => request<DashboardStats>({ method: 'get', url: '/admin/dashboard/stats' }),
  /** 学生/反馈 KPI（period: today/week/month，默认 today） */
  feedbackStats: (period = 'today') =>
    request<FeedbackStats>({ method: 'get', url: '/admin/feedback/stats', params: { period } }),
  /** 近 N 天每日反馈数（单折线图数据源，count 为 Long 字符串） */
  feedbackTrend: (days = 7) =>
    request<FeedbackTrendItem[]>({ method: 'get', url: '/admin/feedback/trend', params: { days } }),
}
