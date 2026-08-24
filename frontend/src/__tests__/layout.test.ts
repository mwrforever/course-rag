import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import AdminLayout, { createNavGroups, navGroups } from '@/layouts/AdminLayout.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'

import type { UserRole } from '@/lib/types'

/**
 * 布局壳测试（Task 16 核心：角色过滤逻辑）
 *
 * 覆盖契约（设计 §2.3 布局骨架）：
 * 1. createNavGroups 角色过滤：TEACHER 隐藏会话审计/安全审计，SUPER_ADMIN 全量可见
 * 2. 顶栏 56px slate-900 + 品牌名；侧栏 220px 分组；内容区 max-w-[1400px]
 * 3. 头像下拉：显示名/角色展示 + 退出登录（调登出接口并跳登录页）
 * 4. 页头标题来自当前路由 meta.title
 *
 * 仪表盘子页于 Task 17 落地（KPI 卡）：此处 mock api 层返回稳定数据，
 * 断言仪表盘 KPI 与快捷入口在布局壳内正常渲染（避免真实 axios 网络调用）。
 */

/** 仪表盘接口 mock：稳定 KPI 数据（计数全 string，likeRate 浮点） */
vi.mock('@/lib/api', () => ({
  ApiError: class ApiError extends Error {},
  dashboardApi: {
    stats: () =>
      Promise.resolve({
        documentCount: '12',
        pendingChunkCount: '3',
        knowledgeBaseCount: '4',
      }),
    feedbackStats: () =>
      Promise.resolve({ studentCount: '30', feedbackCount: '90', likeRate: 0.5 }),
    feedbackTrend: () => Promise.resolve([]),
  },
  documentApi: {
    list: () => Promise.resolve({ records: [], total: '0', page: 1, size: 5 }),
  },
}))

/** 写入指定角色的登录态 */
function setLoginRole(role: UserRole) {
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role,
    displayName: role === 'SUPER_ADMIN' ? '李超管' : '张老师',
  })
}

describe('createNavGroups：按角色过滤超管分组', () => {
  it('TEACHER：仅基础分组，隐藏会话审计与安全审计', () => {
    const groups = createNavGroups('TEACHER')
    const labels = groups.flatMap((g) => [g.label, ...g.items.map((i) => i.label)])

    expect(labels).toContain('仪表盘')
    expect(labels).toContain('文档')
    expect(labels).toContain('分片')
    expect(labels).toContain('课程')
    expect(labels).toContain('用户')
    expect(labels).toContain('反馈')
    expect(labels).not.toContain('会话审计')
    expect(labels).not.toContain('安全审计')
  })

  it('SUPER_ADMIN：全部 7 个分组可见', () => {
    const groups = createNavGroups('SUPER_ADMIN')
    const labels = groups.flatMap((g) => [g.label, ...g.items.map((i) => i.label)])

    expect(labels).toContain('会话审计')
    expect(labels).toContain('安全审计')
    expect(groups).toHaveLength(7)
  })

  it('登录态未恢复（role 为 null）：按最低权限渲染，超管分组不可见', () => {
    const groups = createNavGroups(null)
    const labels = groups.flatMap((g) => g.label)

    expect(labels).not.toContain('会话审计')
    expect(createNavGroups(null)).toEqual(createNavGroups('TEACHER'))
  })

  it('导航静态结构与路由路径对应（供路由表与布局一致性校验）', () => {
    expect(navGroups).toHaveLength(7)
    const allTos = navGroups.flatMap((g) => g.items.map((i) => i.to))
    expect(allTos).toEqual([
      '/dashboard',
      '/knowledge/documents',
      '/knowledge/chunks',
      '/courses',
      '/users',
      '/feedback',
      '/sessions',
      '/security/login-records',
    ])
  })
})

describe('AdminLayout 渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  async function mountLayout(role: UserRole, initialPath = '/dashboard') {
    const pinia = createPinia()
    setActivePinia(pinia)
    setLoginRole(role)
    const router = createAppRouter()
    await router.push(initialPath)
    await router.isReady()
    // 内容区经 RouterView 渲染当前子路由页面（仪表盘页含「刷新」主按钮）
    const wrapper = mount(AdminLayout, { global: { plugins: [pinia, router] } })
    return { wrapper, router, pinia }
  }

  it('TEACHER：顶栏 56px slate-900 品牌区与侧栏渲染，无超管导航', async () => {
    const { wrapper } = await mountLayout('TEACHER')

    // 顶栏：56px + slate-900 + 品牌名
    const header = wrapper.find('header')
    expect(header.classes()).toContain('h-14')
    expect(header.classes()).toContain('bg-slate-900')
    expect(wrapper.text()).toContain('知识库管理后台')

    // 侧栏：220px + 基础导航项
    const aside = wrapper.find('aside')
    expect(aside.classes()).toContain('w-[220px]')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('文档')
    expect(wrapper.text()).toContain('分片')
    expect(wrapper.text()).toContain('课程')
    expect(wrapper.text()).toContain('用户')
    expect(wrapper.text()).toContain('反馈')

    // 超管分组不可见
    expect(wrapper.text()).not.toContain('会话审计')
    expect(wrapper.text()).not.toContain('安全审计')

    // 内容区限宽，子路由页面经 RouterView 渲染（仪表盘 KPI 卡在场）
    expect(wrapper.find('.max-w-\\[1400px\\]').exists()).toBe(true)
    await vi.waitFor(() => expect(wrapper.text()).toContain('文档总数'))
    wrapper.unmount()
  })

  it('SUPER_ADMIN：会话审计与安全审计导航可见', async () => {
    const { wrapper } = await mountLayout('SUPER_ADMIN')

    expect(wrapper.text()).toContain('会话审计')
    expect(wrapper.text()).toContain('安全审计')
    wrapper.unmount()
  })

  it('页头标题来自当前路由 meta.title', async () => {
    const { wrapper } = await mountLayout('TEACHER', '/knowledge/documents')

    expect(wrapper.find('main h1').text()).toContain('文档管理')
    wrapper.unmount()
  })

  it('导航激活态：当前路由对应项高亮（激活类与指示条）', async () => {
    const { wrapper } = await mountLayout('TEACHER', '/knowledge/chunks')

    const links = wrapper.findAll('a')
    const active = links.find((l) => l.classes().includes('bg-brand-soft'))
    // 分片项激活：当前路由 /knowledge/chunks
    expect(active?.text()).toContain('分片')
    wrapper.unmount()
  })

  it('头像下拉：显示名与角色展示，退出登录清凭据并跳登录页', async () => {
    const { wrapper, router } = await mountLayout('SUPER_ADMIN')

    // 默认收起；点击头像展开菜单
    expect(wrapper.text()).not.toContain('退出登录')
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    expect(wrapper.text()).toContain('李超管')
    expect(wrapper.text()).toContain('SUPER_ADMIN')
    expect(wrapper.text()).toContain('退出登录')

    // 再次点击收起
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    expect(wrapper.text()).not.toContain('退出登录')

    // 展开后执行退出：登出接口被调 + 清凭据 + 跳转登录页
    await wrapper.find('button[aria-label="用户菜单"]').trigger('click')
    const store = useAuthStore()
    const logoutSpy = vi.spyOn(store, 'logout').mockImplementation(async () => {
      // 模拟真实登出流程的本地清理（守卫依赖 isAuthenticated 放行登录页）
      store.clearAuth()
    })
    await wrapper.find('button[aria-label="退出登录"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('login'))
    expect(logoutSpy).toHaveBeenCalledTimes(1)
    expect(store.isAuthenticated).toBe(false)
    wrapper.unmount()
  })
})
