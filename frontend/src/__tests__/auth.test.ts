import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/lib/api'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/stores/auth'

import type { LoginResponse } from '@/lib/types'

/**
 * 认证 store 测试（Task 16 核心）
 *
 * 覆盖契约（设计 §3.1 认证交互 + 任务铁律）：
 * 1. RT 生命周期：登录写 sessionStorage（key b_rt）、登出/失败清除、刷新重建后恢复
 * 2. login：成功写入登录态；STUDENT 角色登录提示无权限且不落凭据；接口失败仅上抛
 * 3. logout：调登出接口（幂等容错）+ 清凭据
 * 4. refreshOnce 单飞：并发调用仅发一次 refresh 请求，成功后旋转 RT
 * 5. fetchMe 启动恢复（M10）：单飞、无副作用（不动 AT/RT）、401/网络错误静默不清凭据
 */

// 完全 mock api 模块（不用 importOriginal：api.ts 与 auth store 存在模块循环引用，
// importOriginal 加载真实链会连带真实 store，导致 mock 失效）。
// ApiError 提供行为等价的轻量实现（真实实现由 api.test.ts 覆盖）。
vi.mock('@/lib/api', () => ({
  ApiError: class ApiError extends Error {
    readonly code: number
    readonly status?: number

    constructor(code: number, message: string, status?: number) {
      super(message)
      this.name = 'ApiError'
      this.code = code
      this.status = status
    }
  },
  authApi: {
    login: vi.fn(),
    refresh: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
  },
}))

import { authApi } from '@/lib/api'

/** 构造登录响应（Long 序列化铁律：userId 为 string） */
function buildPayload(
  role: LoginResponse['role'] = 'TEACHER',
  refreshToken = 'rt-1',
): LoginResponse {
  return {
    accessToken: 'at-1',
    refreshToken,
    userId: '1001',
    role,
    displayName: '测试用户',
  }
}

describe('认证 store：RT 生命周期', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('setAuth 写入内存态并将 RT 持久化到 sessionStorage（key=b_rt）', () => {
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.accessToken).toBe('at-1')
    expect(auth.userId).toBe('1001')
    expect(auth.role).toBe('TEACHER')
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt-1')
  })

  it('clearAuth 清空内存态与 sessionStorage 中的 RT', () => {
    const auth = useAuthStore()
    auth.setAuth(buildPayload())
    auth.clearAuth()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
    expect(auth.refreshToken).toBeNull()
    expect(auth.role).toBeNull()
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('刷新页面重建 store：从 sessionStorage 恢复 RT，AT 丢失仍保持登录态', () => {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, 'rt-3')
    const auth = useAuthStore()

    expect(auth.refreshToken).toBe('rt-3')
    expect(auth.accessToken).toBeNull()
    expect(auth.isAuthenticated).toBe(true)
  })

  it('用户信息字段在 setAuth 后完整可读（角色/显示名）', () => {
    const auth = useAuthStore()
    auth.setAuth(buildPayload('SUPER_ADMIN', 'rt-x'))

    expect(auth.role).toBe('SUPER_ADMIN')
    expect(auth.displayName).toBe('测试用户')
  })
})

describe('认证 store：login', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('TEACHER 角色登录成功：带 WEB_DESKTOP 设备类型调接口并写入登录态', async () => {
    vi.mocked(authApi.login).mockResolvedValue(buildPayload())
    const auth = useAuthStore()

    const result = await auth.login('teacher1', '123456')

    expect(authApi.login).toHaveBeenCalledWith({
      username: 'teacher1',
      password: '123456',
      deviceType: 'WEB_DESKTOP',
    })
    expect(result).toEqual(buildPayload())
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.accessToken).toBe('at-1')
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt-1')
  })

  it('SUPER_ADMIN 角色登录成功', async () => {
    vi.mocked(authApi.login).mockResolvedValue(buildPayload('SUPER_ADMIN'))
    const auth = useAuthStore()

    await auth.login('admin', '123456')

    expect(auth.role).toBe('SUPER_ADMIN')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('STUDENT 角色登录：提示无权限且不落任何凭据（不跳转的入口条件）', async () => {
    vi.mocked(authApi.login).mockResolvedValue(buildPayload('STUDENT'))
    const auth = useAuthStore()

    await expect(auth.login('stu1', '123456')).rejects.toMatchObject({
      code: 403,
      message: '当前账号无管理后台访问权限',
    })

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('登录接口失败（401 用户名或密码错误）：ApiError 上抛，本地状态不受影响', async () => {
    vi.mocked(authApi.login).mockRejectedValue(new ApiError(401, '用户名或密码错误', 401))
    const auth = useAuthStore()

    await expect(auth.login('teacher1', 'wrong-password')).rejects.toMatchObject({
      code: 401,
      message: '用户名或密码错误',
    })
    expect(auth.isAuthenticated).toBe(false)
  })
})

describe('认证 store：logout', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登出：调登出接口并清空全部凭据', async () => {
    vi.mocked(authApi.logout).mockResolvedValue(undefined)
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    await auth.logout()

    expect(authApi.logout).toHaveBeenCalledTimes(1)
    expect(auth.isAuthenticated).toBe(false)
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('登出接口异常（幂等容错）：仍清理本地凭据，不阻塞登出流程', async () => {
    vi.mocked(authApi.logout).mockRejectedValue(new ApiError(0, '网络连接失败，请检查网络'))
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    await auth.logout()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
  })
})

describe('认证 store：refreshOnce 单飞', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('并发调用仅发一次 refresh 请求，成功后写入新 AT/RT', async () => {
    vi.mocked(authApi.refresh).mockResolvedValue(buildPayload('TEACHER', 'rt-2'))
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    // 两个调用几乎同时发起：共享同一 promise（单飞去重）
    const [a, b] = await Promise.all([auth.refreshOnce(), auth.refreshOnce()])

    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(authApi.refresh).toHaveBeenCalledWith({ refreshToken: 'rt-1' })
    expect(a).toBe(b)
    expect(auth.accessToken).toBe('at-1')
    expect(auth.refreshToken).toBe('rt-2')
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt-2')
  })

  it('刷新失败：上抛 ApiError 且本地凭据保留（由 api 层统一执行失败登出）', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(
      new ApiError(401, 'Refresh Token 已被使用，请重新登录', 401),
    )
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    await expect(auth.refreshOnce()).rejects.toMatchObject({ code: 401 })
    // 单飞 promise 已清理：下次调用可重新发起
    expect(authApi.refresh).toHaveBeenCalledTimes(1)
  })

  it('无 RT 时直接抛错，不发起 refresh 请求', async () => {
    const auth = useAuthStore()
    auth.setAuth({ ...buildPayload(), refreshToken: '' })

    await expect(auth.refreshOnce()).rejects.toThrow()
    expect(authApi.refresh).not.toHaveBeenCalled()
  })
})

describe('认证 store：fetchMe 启动恢复（M10）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('refreshToken 在且 displayName 为空：恢复 userId/role/displayName，不动 AT/RT（无副作用）', async () => {
    const auth = useAuthStore()
    auth.refreshToken = 'rt-x'
    vi.mocked(authApi.me).mockResolvedValue({ userId: '1', role: 'TEACHER', displayName: '张老师' })

    await auth.fetchMe()

    expect(authApi.me).toHaveBeenCalledTimes(1)
    expect(auth.userId).toBe('1')
    expect(auth.role).toBe('TEACHER')
    expect(auth.displayName).toBe('张老师')
    // 无副作用：me 端点不签发 Token，前端不伪造 AT、不旋转 RT
    expect(auth.accessToken).toBeNull()
    expect(auth.refreshToken).toBe('rt-x')
  })

  it('并发调用共享单飞 promise（仅发一次 me 请求）；401/网络错误静默不清凭据', async () => {
    const auth = useAuthStore()
    auth.refreshToken = 'rt-x'
    vi.mocked(authApi.me).mockResolvedValue({ userId: '1', role: 'TEACHER', displayName: '张老师' })

    // 两个调用几乎同时发起：共享同一 promise（单飞去重，与 refreshOnce 同款模式）
    await Promise.all([auth.fetchMe(), auth.fetchMe()])

    expect(authApi.me).toHaveBeenCalledTimes(1)
    expect(auth.displayName).toBe('张老师')

    // 失败静默：模拟身份仍空的下次启动，me 401 → 不 reject、不登出、不清凭据
    auth.displayName = null
    vi.mocked(authApi.me).mockRejectedValueOnce(new ApiError(401, '未登录', 401))
    await expect(auth.fetchMe()).resolves.toBeUndefined()
    expect(auth.isAuthenticated).toBe(true)
    expect(auth.refreshToken).toBe('rt-x')
    expect(auth.displayName).toBeNull()
  })

  it('无 refreshToken 或 displayName 已恢复：直接跳过，不发 me 请求', async () => {
    const auth = useAuthStore()

    // 无 RT（未登录）：跳过
    await auth.fetchMe()
    expect(authApi.me).not.toHaveBeenCalled()

    // 身份已恢复（displayName 非空，如刚登录）：跳过
    auth.refreshToken = 'rt-x'
    auth.displayName = '张老师'
    await auth.fetchMe()
    expect(authApi.me).not.toHaveBeenCalled()
  })
})
