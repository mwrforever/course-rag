import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { createAppRouter, routes } from '@/router'
import { REFRESH_TOKEN_KEY, useAuthStore } from '@/stores/auth'

import type { LoginResponse, UserRole } from '@/lib/types'

/**
 * 路由配置与鉴权守卫测试（Task 16 核心 + UI 重构 2026-08-25 职责拆分）
 *
 * 覆盖契约（设计 §2.4 路由表 + §3.1 守卫）：
 * 1. 路由表完整性：全部页面齐备（含课程详情五子路由/学生/教师管理/404 兜底）；
 *    教师管理与审计页仅 SUPER_ADMIN；/users 重定向 /students
 * 2. 守卫三态：未登录 → 登录页携 redirect；已登录访问登录页 → 仪表盘；
 *    meta.roles 不匹配 → ForbiddenView
 * 3. 根路径重定向到仪表盘；未匹配路径兜底 /404
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

/** 递归拍平路由表（含布局壳子路由下的课程详情子路由） */
function flatten(records: typeof routes): Array<(typeof routes)[number]> {
  const out: Array<(typeof routes)[number]> = []
  const walk = (list: typeof routes) => {
    for (const r of list) {
      out.push(r)
      if (r.children?.length) {
        walk(r.children as typeof routes)
      }
    }
  }
  walk(records)
  return out
}

describe('路由配置', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('路由表覆盖设计页面清单（名称与路径齐备）', () => {
    const flat = flatten(routes)
    const byName = new Map(flat.map((r) => [r.name, r]))

    // 公开页：登录 + 403 + 404（顶层路由，path 带前导斜杠）
    expect(byName.get('login')?.path).toBe('/login')
    expect(byName.get('forbidden')?.path).toBe('/forbidden')
    expect(byName.get('not-found')?.path).toBe('/404')
    expect(byName.get('not-found')?.meta?.requiresAuth).toBe(false)

    // 两角色页面
    const expectPath = (name: string, path: string) => {
      const record = byName.get(name)
      expect(record, `缺少路由 ${name}`).toBeDefined()
      expect(record?.path).toBe(path)
    }
    expectPath('dashboard', 'dashboard')
    expectPath('knowledge-bases', 'knowledge-bases')
    expectPath('knowledge-documents', 'knowledge/documents')
    expectPath('knowledge-document-detail', 'knowledge/documents/:id')
    expectPath('knowledge-chunks', 'knowledge/chunks')
    expectPath('courses', 'courses')
    expectPath('course-new', 'courses/new')
    expectPath('course-detail', '')
    expectPath('course-content', 'content')
    expectPath('course-schedule', 'schedule')
    expectPath('course-teachers', 'teachers')
    expectPath('course-students', 'students')
    expectPath('students', 'students')
    expectPath('feedback', 'feedback')

    // 两角色页角色白名单
    for (const name of ['dashboard', 'courses', 'students', 'course-detail', 'feedback']) {
      expect(byName.get(name)?.meta?.roles).toEqual(['TEACHER', 'SUPER_ADMIN'])
    }

    // 超管专属页：教师管理 + 审计三页
    for (const name of ['teachers', 'sessions', 'login-records', 'token-blacklist']) {
      const record = byName.get(name)
      expect(record, `缺少超管路由 ${name}`).toBeDefined()
      expect(record?.meta?.roles).toEqual(['SUPER_ADMIN'])
    }
    expect(byName.get('login-records')?.path).toBe('security/login-records')
    expect(byName.get('token-blacklist')?.path).toBe('security/token-blacklist')

    // 课程详情壳：父路由承载 course-detail 五子路由
    const courseParent = flat.find(
      (r) => r.component?.name === 'CourseDetailLayout' || r.path === 'courses/:id',
    )
    expect(courseParent).toBeDefined()
  })

  it('/users 旧入口重定向到学生管理', () => {
    const users = routes
      .flatMap((r) => (r.children?.length ? r.children : [r]))
      .find((r) => r.path === 'users')
    expect(users?.redirect).toEqual({ name: 'students' })
  })

  it('未匹配路径兜底 /404（替代静默重定向仪表盘）', () => {
    const catchAll = routes.find((r) => r.path === '/:pathMatch(.*)*')
    expect(catchAll?.redirect).toBe('/404')
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

  it('TEACHER 访问超管页 /teachers：跳转 ForbiddenView（403 无权限）', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/teachers')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('TEACHER 访问超管页 /sessions：同样被拒绝', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/sessions')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('TEACHER 访问两角色页 /students：放行（学生管理两角色可见）', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('TEACHER'))

    await router.push('/students')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('students')
  })

  it('SUPER_ADMIN 访问超管页 /teachers：放行', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    await router.push('/teachers')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('teachers')
  })

  it('登录页（公开路由）未登录可直达', async () => {
    const router = createAppRouter()

    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
  })

  it('未知路径未登录：兜底 404 为公开页可直接到达', async () => {
    const router = createAppRouter()

    await router.push('/no-such-page')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('not-found')
  })

  it('全部业务路由可达：SUPER_ADMIN 逐页导航均放行并加载页面组件', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    // 设计 §2.4 全部受保护页面（含课程详情五子路由与学生/教师管理）
    const pages: Array<[string, string]> = [
      ['dashboard', '/dashboard'],
      ['knowledge-bases', '/knowledge-bases'],
      ['knowledge-documents', '/knowledge/documents'],
      ['knowledge-document-detail', '/knowledge/documents/d-1'],
      ['knowledge-chunks', '/knowledge/chunks'],
      ['courses', '/courses'],
      ['course-new', '/courses/new'],
      ['course-detail', '/courses/c-1'],
      ['course-content', '/courses/c-1/content'],
      ['course-schedule', '/courses/c-1/schedule'],
      ['course-teachers', '/courses/c-1/teachers'],
      ['course-students', '/courses/c-1/students'],
      ['students', '/students'],
      ['teachers', '/teachers'],
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

  it('/users 旧链接：重定向到 /students', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    await router.push('/users')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('students')
  })

  it('已登录访问未知路径：兜底 404 页', async () => {
    const router = createAppRouter()
    useAuthStore().setAuth(buildPayload('SUPER_ADMIN'))

    await router.push('/no-such-page')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('not-found')
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
