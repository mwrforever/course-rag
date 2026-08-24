import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { createAppRouter, routes } from '@/router'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/stores/auth'

import type { LoginResponse, UserRole } from '@/lib/types'

/**
 * 路由配置与鉴权守卫测试（Task 16 核心）
 *
 * 覆盖契约（设计 §2.4 路由表 + §3.1 守卫）：
 * 1. 路由表完整性：设计 §2.4 全部页面齐备；会话/安全审计仅 SUPER_ADMIN
 * 2. 守卫三态：未登录 → 登录页携 redirect；已登录访问登录页 → 仪表盘；
 *    meta.roles 不匹配 → ForbiddenView
 * 3. 根路径重定向到仪表盘
 */

/** 构造登录态（Long 序列化铁律：userId 为 string） */
function buildPayload(role: UserRole = 'TEACHER'): LoginResponse {
  return {
    accessToken: 'at-test',
    refreshToken: 'rt-test',
    userId: '1001',
    role,
    displayName: '测试用户',
  }
}

describe('路由配置', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('路由表覆盖设计 §2.4 全部页面（名称与路径齐备）', () => {
    const flat = routes.flatMap((r) => (r.children?.length ? r.children : [r]))
    const byName = new Map(flat.map((r) => [r.name, r]))

    // 公开页：登录 + 403 无权限页（顶层路由，path 带前导斜杠）
    expect(byName.get('login')?.path).toBe('/login')
    expect(byName.get('login')?.meta?.requiresAuth).toBe(false)
    expect(byName.get('forbidden')?.path).toBe('/forbidden')
    expect(byName.get('forbidden')?.meta?.requiresAuth).toBe(false)

    // 两角色页面
    const expectPath = (name: string, path: string) => {
      const record = byName.get(name)
      expect(record, `缺少路由 ${name}`).toBeDefined()
      expect(record?.path).toBe(path)
      expect(record?.meta?.requiresAuth).toBe(true)
      expect(record?.meta?.roles).toEqual(['TEACHER', 'SUPER_ADMIN'])
    }
    expectPath('dashboard', 'dashboard')
    expectPath('knowledge-bases', 'knowledge-bases')
    expectPath('knowledge-documents', 'knowledge/documents')
    expectPath('knowledge-document-detail', 'knowledge/documents/:id')
    expectPath('knowledge-chunks', 'knowledge/chunks')
    expectPath('courses', 'courses')
    expectPath('course-new', 'courses/new')
    expectPath('course-detail', 'courses/:id')
    expectPath('users', 'users')
    expectPath('feedback', 'feedback')

    // 超管专属页：仅 SUPER_ADMIN
    const superAdminOnly = ['sessions', 'login-records', 'token-blacklist']
    for (const name of superAdminOnly) {
      const record = byName.get(name)
      expect(record, `缺少超管路由 ${name}`).toBeDefined()
      expect(record?.meta?.roles).toEqual(['SUPER_ADMIN'])
    }
    expect(byName.get('sessions')?.path).toBe('sessions')
    expect(byName.get('login-records')?.path).toBe('security/login-records')
    expect(byName.get('token-blacklist')?.path).toBe('security/token-blacklist')

    // 业务路由全部挂载在布局壳父路由下
    const layout = routes.find((r) => r.name === 'admin-layout')
    expect(layout).toBeDefined()
    expect(flat).not.toContain(layout)
  })

  it('根路径重定向到仪表盘', async () => {
    const router = createAppRouter()
    const auth = useAuthStore()
    auth.setAuth(buildPayload('SUPER_ADMIN'))

    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('dashboard')
  })
})

describe('路由守卫（三态）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('未登录访问受保护路由：守卫携带 redirect 参数重定向登录页', async () => {
    const router = createAppRouter()

    await router.push('/dashboard')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/dashboard')
  })

  it('已登录访问登录页：送回仪表盘', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload())

    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('TEACHER 访问超管页 /sessions：跳转 ForbiddenView（403 无权限）', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/sessions')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('TEACHER 访问超管页 /security/login-records：同样被拒绝', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/security/login-records')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('SUPER_ADMIN 访问超管页 /sessions：放行', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    await router.push('/sessions')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('sessions')
  })

  it('TEACHER 访问两角色页 /feedback：放行', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/feedback')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('feedback')
  })

  it('登录页（公开路由）未登录可直达', async () => {
    const router = createAppRouter()

    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('全部业务路由可达：SUPER_ADMIN 逐页导航均放行并加载页面组件', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    // 设计 §2.4 全部受保护页面（含占位视图；课程编辑新建/详情共用组件）
    const pages: Array<[string, string]> = [
      ['dashboard', '/dashboard'],
      ['knowledge-bases', '/knowledge-bases'],
      ['knowledge-documents', '/knowledge/documents'],
      ['knowledge-document-detail', '/knowledge/documents/d-1'],
      ['knowledge-chunks', '/knowledge/chunks'],
      ['courses', '/courses'],
      ['course-new', '/courses/new'],
      ['course-detail', '/courses/c-1'],
      ['users', '/users'],
      ['feedback', '/feedback'],
      ['sessions', '/sessions'],
      ['login-records', '/security/login-records'],
      ['token-blacklist', '/security/token-blacklist'],
    ]
    for (const [name, path] of pages) {
      await router.push(path)
      await router.isReady()
      expect(router.currentRoute.value.name, `路由 ${path} 应可达`).toBe(name)
    }
  })
})

/**
 * 认证 store 单测（RT 生命周期：随 Task 16 独立文件同测，此处保留关键三条防回归）
 */
describe('认证 store：RT 生命周期', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('setAuth 写入内存态并将 RT 持久化到 sessionStorage（key=b_rt）', () => {
    const auth = useAuthStore()
    auth.setAuth(buildPayload())

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.userId).toBe('1001')
    expect(auth.role).toBe('TEACHER')
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rt-test')
  })

  it('clearAuth 清空内存态并移除 sessionStorage 中的 RT', () => {
    const auth = useAuthStore()
    auth.setAuth(buildPayload())
    auth.clearAuth()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
    expect(sessionStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull()
  })

  it('刷新页面重建 store：从 sessionStorage 恢复 RT，AT 丢失仍保持登录态', () => {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, 'rt-3')
    const auth = useAuthStore()

    expect(auth.refreshToken).toBe('rt-3')
    expect(auth.accessToken).toBeNull()
    expect(auth.isAuthenticated).toBe(true)
  })
})
