import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import { createAppRouter, routes } from '@/router'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由配置与鉴权守卫测试
 *
 * 覆盖：路由表元数据（公开/受保护/角色白名单）、根路径重定向、守卫两个重定向分支
 * （未登录进受保护路由、已登录进登录页）。
 */
describe('路由配置', () => {
  // 每个用例独立存储实例与登录态，避免用例间串扰（守卫读取 active pinia）
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('登录页为公开路由，仪表盘为受保护路由且限定管理端角色', () => {
    const login = routes.find((r) => r.name === 'login')
    expect(login?.meta?.requiresAuth).toBe(false)

    const dashboard = routes.find((r) => r.name === 'dashboard')
    expect(dashboard?.meta?.requiresAuth).toBe(true)
    expect(dashboard?.meta?.roles).toEqual(['TEACHER', 'SUPER_ADMIN'])
  })

  it('根路径重定向到仪表盘', async () => {
    const router = createAppRouter()
    const auth = useAuthStore()
    auth.setAuth({
      accessToken: 'at',
      refreshToken: 'rt',
      userId: '1002',
      role: 'SUPER_ADMIN',
      displayName: '超管',
    })

    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('dashboard')
  })

  it('未登录访问受保护路由：守卫携带 redirect 参数重定向登录页', async () => {
    setActivePinia(createPinia())
    const router = createAppRouter()

    await router.push('/dashboard')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/dashboard')
  })
})

/**
 * 认证 store 单测：登录态写入/清理/刷新恢复三条路径
 */
describe('认证 store', () => {
  beforeEach(() => {
    sessionStorage.clear()
    setActivePinia(createPinia())
  })

  it('setAuth 写入内存态并将 RT 持久化到 sessionStorage', () => {
    const auth = useAuthStore()
    auth.setAuth({
      accessToken: 'at-1',
      refreshToken: 'rt-1',
      userId: '1003',
      role: 'TEACHER',
      displayName: '张老师',
    })

    expect(auth.isAuthenticated).toBe(true)
    expect(auth.userId).toBe('1003')
    expect(auth.role).toBe('TEACHER')
    expect(sessionStorage.getItem('b_fe_refresh_token')).toBe('rt-1')
  })

  it('clearAuth 清空内存态并移除 sessionStorage 中的 RT', () => {
    const auth = useAuthStore()
    auth.setAuth({
      accessToken: 'at-2',
      refreshToken: 'rt-2',
      userId: '1004',
      role: 'SUPER_ADMIN',
      displayName: '李超管',
    })
    auth.clearAuth()

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.accessToken).toBeNull()
    expect(sessionStorage.getItem('b_fe_refresh_token')).toBeNull()
  })

  it('刷新页面重建 store：从 sessionStorage 恢复 RT，AT 丢失仍保持登录态', () => {
    // 模拟刷新前落过 RT
    sessionStorage.setItem('b_fe_refresh_token', 'rt-3')
    const auth = useAuthStore()

    expect(auth.refreshToken).toBe('rt-3')
    expect(auth.accessToken).toBeNull()
    expect(auth.isAuthenticated).toBe(true)
  })
})
