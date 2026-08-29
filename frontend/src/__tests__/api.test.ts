import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  ApiError,
  NETWORK_ERROR_MESSAGE,
  apiClient,
  authApi,
  chunkApi,
  courseApi,
  dashboardApi,
  documentApi,
  enrollmentApi,
  feedbackApi,
  knowledgeBaseApi,
  scheduleApi,
  securityApi,
  sessionApi,
  userApi,
  toProgressCallback,
} from '@/lib/api'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { AxiosError } from 'axios'

/**
 * api client 测试（Task 16 核心）
 *
 * 覆盖契约（docs/backed/2026-08-24-后端功能调整.md §五 + 设计 §3.2/§3.3）：
 * 1. 401 单飞刷新：并发 401 仅发一次 refresh，原请求携带新 AT 重放
 * 2. refresh 失败：清凭据 + 跳登录页 + toast「登录已失效，请重新登录」
 * 3. 登录/刷新端点 401 不触发刷新（登录失败文案就地展示）
 * 4. DELETE 带 body（removeCourseTeachers 透传数组）
 * 5. ApiError 分级（400/401/403/404/409/503/网络错误/2xx 业务错误码）
 * 6. 全部 B 端接口函数的方法/路径/参数拼装
 *
 * 传输层：axios 默认 XHR 在 jsdom 无网络，测试通过覆盖 apiClient.defaults.adapter
 * 注入内存路由表驱动拦截器与请求全链路（拦截器照常执行）。
 */

/** 内存路由表条目：命中即按 respond 结果回包（error 字段模拟网络异常） */
interface MockRoute {
  match: (config: InternalAxiosRequestConfig) => boolean
  respond: (config: InternalAxiosRequestConfig) => {
    status: number
    data?: unknown
    error?: unknown
  }
}

let routes: MockRoute[] = []
let calls: InternalAxiosRequestConfig[] = []

/** 注入内存 adapter：记录全部请求并按下发路由表应答（模拟 axios 内置 validateStatus：非 2xx 走错误通道） */
function installMockAdapter(routeList: MockRoute[]) {
  routes = routeList
  calls = []
  apiClient.defaults.adapter = async (
    config: InternalAxiosRequestConfig,
  ): Promise<AxiosResponse> => {
    calls.push(config)
    const route = routes.find((r) => r.match(config))
    if (!route) {
      throw new Error(`未 mock 的请求: ${config.method} ${config.url}`)
    }
    const result = route.respond(config)
    if (result.error) {
      throw result.error
    }
    const response = {
      data: result.data ?? { code: 0, data: null },
      status: result.status,
      statusText: String(result.status),
      headers: {},
      config,
      request: {},
    } as AxiosResponse
    // 模拟内置 adapter 的 settle+validateStatus：非 2xx 以 AxiosError 形态进入错误拦截器
    if (
      typeof config.validateStatus === 'function'
        ? !config.validateStatus(result.status)
        : result.status < 200 || result.status >= 300
    ) {
      throw new AxiosError(
        `Request failed with status code ${result.status}`,
        AxiosError.ERR_BAD_REQUEST,
        config,
        null,
        response,
      )
    }
    return response
  }
}

/** 成功响应包装（后端 code=0 成功） */
const ok = (data: unknown) => ({ status: 200, data: { code: 0, message: 'ok', data } })

/** 失败响应包装（code 与 HTTP 状态同值；401 特例无 data 键） */
const fail = (status: number, message: string) => ({ status, data: { code: status, message } })

/** 最小登录态（Long 序列化铁律：userId 为 string） */
function loginState(accessToken = 'at-old', refreshToken = 'rt-1') {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.setAuth({
    accessToken,
    refreshToken,
    userId: '1001',
    role: 'TEACHER',
    displayName: '测试教师',
  })
  return auth
}

/** 刷新接口返回的新登录态 */
const refreshPayload = {
  accessToken: 'at-new',
  refreshToken: 'rt-new',
  userId: '1001',
  role: 'TEACHER',
  displayName: '测试教师',
}

describe('api client：401 单飞刷新与重放', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  it('并发 401 仅发一次 refresh，两个原请求均携带新 AT 重放成功', async () => {
    loginState('at-old', 'rt-1')
    let refreshCalls = 0
    installMockAdapter([
      {
        match: (c) => c.url?.includes('/auth/refresh') ?? false,
        respond: () => {
          refreshCalls++
          return ok(refreshPayload)
        },
      },
      {
        // 首次带旧 AT 的请求回 401，重放携带新 AT 后成功
        match: (c) => c.url?.includes('/admin/dashboard/stats') ?? false,
        respond: (c) =>
          c.headers?.Authorization === 'Bearer at-new'
            ? ok({ documentCount: '3', pendingChunkCount: '1', knowledgeBaseCount: '2' })
            : fail(401, '认证令牌无效或已过期'),
      },
    ])

    // 两个并发请求同时触发 401：共享同一 refresh promise（单飞去重）
    const [a, b] = await Promise.all([dashboardApi.stats(), dashboardApi.stats()])

    expect(refreshCalls).toBe(1)
    expect(a.documentCount).toBe('3')
    expect(b.documentCount).toBe('3')
    // 刷新结果回写 store 与 sessionStorage（RT 旋转）
    const auth = useAuthStore()
    expect(auth.accessToken).toBe('at-new')
    expect(sessionStorage.getItem('b_rt')).toBe('rt-new')
    // 首次请求（旧 AT）与重放（新 AT）各 2 次：前两发为原始请求，后两发为重放
    const replayed = calls.filter((c) => c.url?.includes('/admin/dashboard/stats'))
    expect(replayed).toHaveLength(4)
    expect(replayed.slice(0, 2).every((c) => c.headers?.Authorization === 'Bearer at-old')).toBe(
      true,
    )
    expect(replayed.slice(2).every((c) => c.headers?.Authorization === 'Bearer at-new')).toBe(true)
  })

  it('refresh 失败（RT 已复用全量吊销）：清凭据、跳登录页并 toast「登录已失效，请重新登录」', async () => {
    loginState('at-old', 'rt-expired')
    installMockAdapter([
      {
        match: (c) => c.url?.includes('/auth/refresh') ?? false,
        respond: () => fail(401, 'Refresh Token 已被使用，请重新登录'),
      },
      {
        match: (c) => c.url?.includes('/admin/users') ?? false,
        respond: () => fail(401, '认证令牌无效或已过期'),
      },
    ])

    await expect(userApi.list({ page: 1, size: 20 })).rejects.toBeInstanceOf(ApiError)

    // 凭据清理：内存 + sessionStorage 的 RT 均清空
    const auth = useAuthStore()
    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem('b_rt')).toBeNull()
    // 全局登出流：跳登录页 + toast 统一文案
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(document.body.textContent).toContain('登录已失效，请重新登录')
  })

  it('无 RT 时 401 直接失败登出，不发起 refresh 请求', async () => {
    // 仅内存 AT、无 RT（模拟刷新后仅 cookie 兜底的边缘场景）
    setActivePinia(createPinia())
    useAuthStore().setAuth({
      accessToken: 'at-only',
      refreshToken: '',
      userId: '1001',
      role: 'TEACHER',
      displayName: '测试教师',
    })
    let refreshCalls = 0
    installMockAdapter([
      {
        match: (c) => c.url?.includes('/auth/refresh') ?? false,
        respond: () => {
          refreshCalls++
          return fail(401, 'Refresh Token 无效或已过期')
        },
      },
      {
        match: (c) => c.url?.includes('/admin/feedbacks') ?? false,
        respond: () => fail(401, '认证令牌无效或已过期'),
      },
    ])

    await expect(feedbackApi.list({ page: 1, size: 20 })).rejects.toBeInstanceOf(ApiError)
    expect(refreshCalls).toBe(0)
    expect(useAuthStore().isAuthenticated).toBe(false)
  })

  it('登录接口 401：不触发刷新直接抛 ApiError（登录页就地分级展示）', async () => {
    loginState()
    let refreshCalls = 0
    installMockAdapter([
      {
        match: (c) => c.url?.includes('/auth/login') ?? false,
        respond: () => fail(401, '用户名或密码错误'),
      },
      {
        match: (c) => c.url?.includes('/auth/refresh') ?? false,
        respond: () => {
          refreshCalls++
          return ok(refreshPayload)
        },
      },
    ])

    await expect(authApi.login({ username: 'u', password: '123456' })).rejects.toMatchObject({
      code: 401,
      message: '用户名或密码错误',
    })
    expect(refreshCalls).toBe(0)
  })

  it('refresh 端点自身 401：直抛不重试（避免刷新死循环）', async () => {
    loginState()
    installMockAdapter([
      {
        match: (c) => c.url?.includes('/auth/refresh') ?? false,
        respond: () => fail(401, 'Refresh Token 无效或已过期'),
      },
    ])

    await expect(authApi.refresh({ refreshToken: 'rt-bad' })).rejects.toBeInstanceOf(ApiError)
    expect(sessionStorage.getItem('b_rt')).toBe('rt-1')
  })
})

describe('api client：请求头与错误分级', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('存在 AT 时请求携带 Authorization: Bearer 头；无 AT 时不携带', async () => {
    useAuthStore().setAuth({
      accessToken: 'at-1',
      refreshToken: 'rt-1',
      userId: '1001',
      role: 'SUPER_ADMIN',
      displayName: '超管',
    })
    installMockAdapter([{ match: () => true, respond: () => ok(null) }])
    await knowledgeBaseApi.list({ page: 1, size: 20 })
    expect(calls[0].headers?.Authorization).toBe('Bearer at-1')

    // 清空登录态后请求不再携带 AT
    useAuthStore().clearAuth()
    await knowledgeBaseApi.list({ page: 1, size: 20 })
    expect(calls[1].headers?.Authorization).toBeUndefined()
  })

  it('404/409/503 等业务错误：包装为 ApiError 且携带后端 message', async () => {
    installMockAdapter([
      { match: (c) => c.url?.includes('kb-404') ?? false, respond: () => fail(404, '资源不存在') },
      {
        match: (c) => c.url?.includes('s-409') ?? false,
        respond: () => fail(409, '会话正在对话中，请稍后删除'),
      },
      {
        match: (c) => c.url?.includes('kb-503') ?? false,
        respond: () => fail(503, '服务暂时不可用'),
      },
    ])
    await expect(knowledgeBaseApi.get('kb-404')).rejects.toMatchObject({
      code: 404,
      message: '资源不存在',
    })
    await expect(sessionApi.remove('s-409')).rejects.toMatchObject({
      code: 409,
      message: '会话正在对话中，请稍后删除',
    })
    await expect(knowledgeBaseApi.get('kb-503')).rejects.toMatchObject({
      code: 503,
      message: '服务暂时不可用',
    })
  })

  it('网络错误：包装为 code=0 的 ApiError，message 用「网络连接失败，请检查网络」', async () => {
    installMockAdapter([
      {
        match: () => true,
        respond: () => {
          throw new Error('Network Error')
        },
      },
    ])

    await expect(userApi.list({ page: 1, size: 20 })).rejects.toMatchObject({
      code: 0,
      message: NETWORK_ERROR_MESSAGE,
    })
  })

  it('HTTP 200 但业务 code 非 0：视为业务错误抛出（防御后端契约变化）', async () => {
    installMockAdapter([
      {
        match: () => true,
        respond: () => ({ status: 200, data: { code: 500, message: '内部错误' } }),
      },
    ])

    await expect(courseApi.list({ page: 1, size: 20 })).rejects.toMatchObject({
      code: 500,
      message: '内部错误',
    })
  })

  it('服务端错误体缺 message 时回退「请求失败」默认文案', async () => {
    installMockAdapter([
      {
        match: () => true,
        respond: () => ({ status: 502, data: { code: 502 } }),
      },
    ])

    const err = await userApi.list({ page: 1, size: 20 }).catch((e) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err.message).toBe('请求失败')
    expect(err.code).toBe(502)
  })
})

describe('api client：接口函数拼装', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
    setActivePinia(createPinia())
    installMockAdapter([
      {
        match: () => true,
        respond: (c) => {
          // 会话/登录记录等读接口返回空分页，其余接口返回 null data
          const pageLike =
            c.url?.includes('/sessions') ||
            c.url?.includes('/login-records') ||
            c.url?.includes('/token-blacklist')
          return ok(pageLike ? { records: [], total: '0', page: 1, size: 20 } : null)
        },
      },
    ])
  })

  /** 读取指定 URL 的最后一次请求配置（断言方法/参数/请求体） */
  function lastCall(urlPart: string): InternalAxiosRequestConfig {
    const matched = calls.filter((c) => c.url?.includes(urlPart))
    return matched[matched.length - 1]
  }

  it('authApi：login 以 deviceType 缺省提交，logout 为 POST', async () => {
    await authApi.login({ username: 'teacher1', password: '123456' })
    let config = lastCall('/auth/login')
    expect(config.method).toBe('post')
    expect(JSON.parse(config.data)).toEqual({
      username: 'teacher1',
      password: '123456',
      deviceType: 'WEB_DESKTOP',
    })

    await authApi.logout()
    config = lastCall('/auth/logout')
    expect(config.method).toBe('post')
  })

  it('knowledgeBaseApi：CRUD 方法/路径/参数正确', async () => {
    await knowledgeBaseApi.list({ page: 2, size: 10, keyword: '课' })
    let config = lastCall('/admin/knowledge-bases')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({ page: 2, size: 10, keyword: '课' })

    await knowledgeBaseApi.create({ name: '新库', description: '描述' })
    config = lastCall('/admin/knowledge-bases')
    expect(config.method).toBe('post')
    expect(JSON.parse(config.data)).toEqual({ name: '新库', description: '描述' })

    await knowledgeBaseApi.get('kb-1')
    expect(lastCall('/admin/knowledge-bases/kb-1').method).toBe('get')

    await knowledgeBaseApi.update('kb-1', { name: '改名', description: '' })
    config = lastCall('/admin/knowledge-bases/kb-1')
    expect(config.method).toBe('put')
    expect(JSON.parse(config.data).name).toBe('改名')

    await knowledgeBaseApi.remove('kb-1')
    expect(lastCall('/admin/knowledge-bases/kb-1').method).toBe('delete')
  })

  it('documentApi：列表参数/上传 FormData/下载 Blob', async () => {
    await documentApi.list({
      kbId: 'kb-1',
      status: 'INDEXED',
      q: 'pdf',
      sort: 'created',
      page: 1,
      size: 5,
    })
    const config = lastCall('/admin/documents')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({
      kbId: 'kb-1',
      status: 'INDEXED',
      q: 'pdf',
      sort: 'created',
      page: 1,
      size: 5,
    })

    const form = new FormData()
    form.append('kbId', 'kb-1')
    form.append('title', '文档一')
    form.append('file', new File(['x'], 'a.pdf'))
    // 带进度回调：注册上传进度归一（内部回调执行一次验证百分比换算）
    const progress: number[] = []
    await documentApi.upload(form, (percent) => progress.push(percent))
    const uploadConfig = lastCall('/admin/documents')
    expect(uploadConfig.method).toBe('post')
    expect(uploadConfig.data).toBeInstanceOf(FormData)
    // 进度归一回调：total 缺省按 1 防除零（模拟 axios 进度事件）
    toProgressCallback((p) => progress.push(p))({ loaded: 50, total: 200 })
    expect(progress).toEqual([25])
    toProgressCallback((p) => progress.push(p))({ loaded: 30 })
    expect(progress).toEqual([25, 3000])

    await documentApi.get('d-1')
    expect(lastCall('/admin/documents/d-1').method).toBe('get')

    await documentApi.update('d-1', { title: '新标题' })
    expect(lastCall('/admin/documents/d-1').method).toBe('put')

    await documentApi.reparse('d-1')
    expect(lastCall('/admin/documents/d-1/reparse').method).toBe('post')

    await documentApi.remove('d-1')
    expect(lastCall('/admin/documents/d-1').method).toBe('delete')

    // 下载走 blob 响应（独立 adapter：返回真实 Blob，绕过通用 ok(null) 适配器）
    installMockAdapter([
      {
        match: () => true,
        respond: () => ({
          status: 200,
          data: new Blob(['pdf-bytes'], { type: 'application/pdf' }),
        }),
      },
    ])
    const blob = await documentApi.download('d-1')
    expect(blob).toBeInstanceOf(Blob)
    expect(lastCall('/admin/documents/d-1/download').method).toBe('get')
    expect(lastCall('/admin/documents/d-1/download').responseType).toBe('blob')
  })

  it('chunkApi：待修正列表/上下文/批量通道方法正确', async () => {
    await chunkApi.pending({ kbId: 'kb-1', docId: 'd-1', page: 1, size: 20 })
    let config = lastCall('/admin/chunks/pending')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({ kbId: 'kb-1', docId: 'd-1', page: 1, size: 20 })

    await chunkApi.list({ kbId: 'kb-1', page: 2 })
    config = lastCall('/admin/chunks')
    expect(config.method).toBe('get')
    expect(config.url).not.toContain('/pending')

    await chunkApi.get('c-1')
    expect(lastCall('/admin/chunks/c-1').method).toBe('get')

    await chunkApi.updateContent('c-1', { content: '修正后内容' })
    config = lastCall('/admin/chunks/c-1')
    expect(config.method).toBe('put')
    expect(JSON.parse(config.data).content).toBe('修正后内容')

    await chunkApi.updateCollectionType('c-1', { collectionType: 'COURSE_INFO', courseId: '9' })
    config = lastCall('/admin/chunks/c-1/collection-type')
    expect(config.method).toBe('patch')

    await chunkApi.context('c-1')
    expect(lastCall('/admin/chunks/c-1/context').method).toBe('get')

    await chunkApi.batchUpdate({
      ids: ['c-1', 'c-2'],
      collectionType: 'TECHNICAL_QA',
      courseId: '9',
    })
    config = lastCall('/admin/chunks/batch-update')
    expect(config.method).toBe('post')
    expect(JSON.parse(config.data).ids).toEqual(['c-1', 'c-2'])

    await chunkApi.batchCorrected({ ids: ['c-1'] })
    expect(lastCall('/admin/chunks/batch-corrected').method).toBe('post')

    await chunkApi.remove('c-1')
    expect(lastCall('/admin/chunks/c-1').method).toBe('delete')
  })

  it('courseApi：DELETE 带 body 移除教师（axios data 写法）', async () => {
    await courseApi.list({ page: 1, size: 20, category: 'AI', keyword: 'rag' })
    const listConfig = lastCall('/admin/courses')
    expect(listConfig.method).toBe('get')
    expect(listConfig.params).toEqual({ page: 1, size: 20, category: 'AI', keyword: 'rag' })

    await courseApi.create({
      title: '新课',
      description: '',
      coverImage: '',
      category: 'AI',
      instructorName: '王老师',
      price: 0,
      duration: '8 课时',
      tags: null,
      enrollmentLink: '',
    })
    expect(lastCall('/admin/courses').method).toBe('post')

    await courseApi.get('c-9')
    expect(lastCall('/admin/courses/c-9').method).toBe('get')

    await courseApi.update('c-9', { status: 'ARCHIVED' })
    expect(lastCall('/admin/courses/c-9').method).toBe('put')

    // DELETE 带 body：设计 §2.4.4「移除 DELETE /{id}/teachers 带 body（axios data 写法）」
    await courseApi.removeTeachers('c-9', ['t-7', 't-8'])
    const removeTeachers = lastCall('/admin/courses/c-9/teachers')
    expect(removeTeachers.method).toBe('delete')
    expect(JSON.parse(removeTeachers.data)).toEqual(['t-7', 't-8'])

    await courseApi.addTeachers('c-9', ['t-7'])
    expect(lastCall('/admin/courses/c-9/teachers').method).toBe('post')

    await courseApi.contents('c-9')
    expect(lastCall('/admin/courses/c-9/contents').method).toBe('get')

    // 内容单 Tab 保存：body 为裸 JSON 字符串，Content-Type 显式 application/json
    // （后端 @RequestBody String 接收：axios 字符串 data 原样透传，不带引号包裹）
    await courseApi.updateContent('c-9', 'intro', '## 课程介绍正文')
    const contentConfig = lastCall('/admin/courses/c-9/contents/intro')
    expect(contentConfig.method).toBe('put')
    expect(contentConfig.data).toBe('## 课程介绍正文')
    expect(contentConfig.headers?.['Content-Type']).toBe('application/json')

    await courseApi.batchContents('c-9', [{ contentType: 'intro', content: 'x', sortOrder: 1 }])
    expect(lastCall('/admin/courses/c-9/contents').method).toBe('put')

    await courseApi.remove('c-9')
    expect(lastCall('/admin/courses/c-9').method).toBe('delete')
  })

  it('courseApi.uploadCover：封面上传 multipart 路径拼装（契约 D.2.2）', async () => {
    const form = new FormData()
    form.set('file', new File(['x'], 'cover.png', { type: 'image/png' }))
    await courseApi.uploadCover(form)
    const uploadCall = lastCall('/admin/courses/cover')
    expect(uploadCall.method).toBe('post')
    expect(uploadCall.headers?.['Content-Type']).toContain('multipart/form-data')
  })

  it('scheduleApi/enrollmentApi：排期与报名的路径拼装', async () => {
    await scheduleApi.listByCourse('c-9')
    expect(lastCall('/admin/courses/c-9/schedules').method).toBe('get')

    await scheduleApi.create('c-9', {
      startDate: '2026-09-01',
      endDate: '2026-12-31',
      scheduleType: 'ONLINE',
      location: '线上',
      instructorName: '王老师',
      capacity: 50,
    })
    expect(lastCall('/admin/courses/c-9/schedules').method).toBe('post')

    await scheduleApi.get('s-1')
    expect(lastCall('/admin/schedules/s-1').method).toBe('get')

    await scheduleApi.update('s-1', { status: 'IN_PROGRESS' })
    expect(lastCall('/admin/schedules/s-1').method).toBe('put')

    await scheduleApi.remove('s-1')
    expect(lastCall('/admin/schedules/s-1').method).toBe('delete')

    await enrollmentApi.students('c-9')
    expect(lastCall('/admin/courses/c-9/students').method).toBe('get')

    await enrollmentApi.addStudents('c-9', { studentIds: ['u-1', 'u-2'] })
    expect(lastCall('/admin/courses/c-9/students').method).toBe('post')

    await enrollmentApi.removeStudent('c-9', 'u-1')
    expect(lastCall('/admin/courses/c-9/students/u-1').method).toBe('delete')

    await enrollmentApi.studentCourses('u-1')
    expect(lastCall('/admin/students/u-1/courses').method).toBe('get')
  })

  it('userApi：用户管理方法/参数/状态与密码接口', async () => {
    await userApi.list({ page: 1, size: 20, role: 'STUDENT', status: 'ACTIVE' })
    let config = lastCall('/admin/users')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({
      page: 1,
      size: 20,
      role: 'STUDENT',
      status: 'ACTIVE',
    })

    await userApi.create({
      username: 'stu1',
      password: '123456',
      displayName: '学生一',
      role: 'STUDENT',
    })
    expect(lastCall('/admin/users').method).toBe('post')

    await userApi.get('u-1')
    expect(lastCall('/admin/users/u-1').method).toBe('get')

    await userApi.update('u-1', { displayName: '改名' })
    expect(lastCall('/admin/users/u-1').method).toBe('put')

    await userApi.resetPassword('u-1', { newPassword: '654321' })
    config = lastCall('/admin/users/u-1/reset-password')
    expect(config.method).toBe('post')

    await userApi.updateStatus('u-1', { status: 'DISABLED' })
    config = lastCall('/admin/users/u-1/status')
    expect(config.method).toBe('patch')

    await userApi.remove('u-1')
    expect(lastCall('/admin/users/u-1').method).toBe('delete')
  })

  it('feedbackApi/sessionApi：反馈报表与会话审计', async () => {
    await feedbackApi.list({ page: 1, size: 20, intentType: 'TECHNICAL_QA' })
    let config = lastCall('/admin/feedbacks')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({ page: 1, size: 20, intentType: 'TECHNICAL_QA' })

    await feedbackApi.stats()
    expect(lastCall('/admin/feedbacks/stats').method).toBe('get')

    await feedbackApi.remove('f-1')
    expect(lastCall('/admin/feedbacks/f-1').method).toBe('delete')

    await sessionApi.list({ page: 1, size: 20 })
    config = lastCall('/admin/sessions')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({ page: 1, size: 20 })

    await sessionApi.detail('s-1')
    expect(lastCall('/admin/sessions/s-1').method).toBe('get')

    await sessionApi.close('s-1')
    expect(lastCall('/admin/sessions/s-1/close').method).toBe('patch')

    await sessionApi.remove('s-1')
    expect(lastCall('/admin/sessions/s-1').method).toBe('delete')
  })

  it('securityApi：登录记录与 Token 黑名单（手工加入走查询参数）', async () => {
    await securityApi.loginRecords({
      page: 1,
      size: 20,
      userId: 'u-1',
      deviceType: 'WEB_DESKTOP',
      status: 'ACTIVE',
    })
    let config = lastCall('/admin/login-records')
    expect(config.method).toBe('get')
    expect(config.params).toEqual({
      page: 1,
      size: 20,
      userId: 'u-1',
      deviceType: 'WEB_DESKTOP',
      status: 'ACTIVE',
    })

    await securityApi.loginRecord('lr-1')
    expect(lastCall('/admin/login-records/lr-1').method).toBe('get')

    await securityApi.revokeLoginRecord('lr-1')
    expect(lastCall('/admin/login-records/lr-1/revoke').method).toBe('post')

    await securityApi.blacklist({ page: 1, size: 20, tokenType: 'ACCESS' })
    config = lastCall('/admin/token-blacklist')
    expect(config.method).toBe('get')

    // 后端 addToBlacklist 全参数走 @RequestParam（查询参数）
    await securityApi.addBlacklist({
      jti: 'jti-1',
      tokenType: 'ACCESS',
      userId: 'u-1',
      reason: 'MANUAL_REVOKE',
    })
    config = lastCall('/admin/token-blacklist')
    expect(config.method).toBe('post')
    expect(config.params).toEqual({
      jti: 'jti-1',
      tokenType: 'ACCESS',
      userId: 'u-1',
      reason: 'MANUAL_REVOKE',
    })

    await securityApi.removeBlacklist('tb-1')
    expect(lastCall('/admin/token-blacklist/tb-1').method).toBe('delete')

    // 清理过期：POST 端点（返回 cleaned 数由页面任务消费，此处断言请求形态）
    await securityApi.cleanupBlacklist()
    expect(lastCall('/admin/token-blacklist/cleanup').method).toBe('post')
  })

  it('dashboardApi：三统计接口参数默认值与路径', async () => {
    await dashboardApi.stats()
    expect(lastCall('/admin/dashboard/stats').method).toBe('get')

    await dashboardApi.feedbackStats('week')
    const statsConfig = lastCall('/admin/feedback/stats')
    expect(statsConfig.method).toBe('get')
    expect(statsConfig.params).toEqual({ period: 'week' })

    await dashboardApi.feedbackTrend(7)
    const trendConfig = lastCall('/admin/feedback/trend')
    expect(trendConfig.method).toBe('get')
    expect(trendConfig.params).toEqual({ days: 7 })
  })
})
