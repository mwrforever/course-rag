import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'

import type { LoginResponse } from '@/lib/types'

/**
 * App 根组件冒烟测试
 *
 * 覆盖：未登录访问受保护路由被守卫重定向到登录页；登录后进入仪表盘
 * （渲染 AdminLayout 布局壳 + 仪表盘页）；已登录访问登录页被送回仪表盘。
 *
 * 仪表盘子页于 Task 17 落地（KPI 卡）：此处 mock api 层返回稳定数据，
 * 断言 KPI 卡与快捷入口在壳内渲染（避免真实 axios 网络调用污染冒烟）。
 */

/** 仪表盘接口 mock：稳定 KPI 数据（文档总数 12 由布局冒烟直接断言） */
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
  // 意图统计（2026-08-27 仪表盘重构新增消费）：空数组 → donut/堆叠条区块空态
  feedbackApi: {
    stats: () => Promise.resolve([]),
  },
  documentApi: {
    list: () => Promise.resolve({ records: [], total: '0', page: 1, size: 5 }),
  },
}))
describe('App 冒烟', () => {
  beforeEach(() => {
    // 每个用例独立存储实例，避免登录态串扰
    sessionStorage.clear()
  })

  // 构造最小登录态（userId 为 string，Long 序列化铁律）
  function buildLoginPayload(): LoginResponse {
    return {
      accessToken: 'at-test',
      refreshToken: 'rt-test',
      userId: '1001',
      role: 'TEACHER',
      displayName: '测试教师',
    }
  }

  it('未登录访问受保护路由：重定向到登录页并渲染品牌区', async () => {
    const pinia = createPinia()
    const router = createAppRouter()
    const wrapper = mount(App, {
      global: {
        plugins: [
          pinia,
          router,
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
        ],
      },
    })

    await router.push('/dashboard')
    await router.isReady()

    // 守卫生效：路由落在登录页，页面渲染品牌区文案
    expect(router.currentRoute.value.name).toBe('login')
    expect(wrapper.text()).toContain('课程助手管理后台')
    wrapper.unmount()
  })

  it('登录后访问仪表盘：渲染布局壳与仪表盘基础组件', async () => {
    const pinia = createPinia()
    const router = createAppRouter()
    const wrapper = mount(App, {
      global: {
        plugins: [
          pinia,
          router,
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
        ],
      },
    })

    // 写入登录态后导航到受保护路由
    const auth = useAuthStore()
    auth.setAuth(buildLoginPayload())
    await router.push('/dashboard')
    await router.isReady()

    // 仪表盘页面渲染在 AdminLayout 壳内（顶栏品牌 + 侧导航 + KPI 卡）
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('课程助手管理后台')
    await vi.waitFor(() => expect(wrapper.text()).toContain('文档总数'))
    // KPI 数值（mock 文档总数 12）与快捷入口在壳内渲染
    await vi.waitFor(() => expect(wrapper.text()).toContain('12'))
    expect(wrapper.text()).toContain('上传文档')
    wrapper.unmount()
  })

  it('已登录访问登录页：守卫送回仪表盘', async () => {
    const pinia = createPinia()
    const router = createAppRouter()
    const wrapper = mount(App, {
      global: {
        plugins: [
          pinia,
          router,
          [
            VueQueryPlugin,
            { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
          ],
        ],
      },
    })

    const auth = useAuthStore()
    auth.setAuth(buildLoginPayload())
    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('dashboard')
    wrapper.unmount()
  })
})
