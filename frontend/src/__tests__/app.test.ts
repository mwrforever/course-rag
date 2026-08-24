import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'

import App from '@/App.vue'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'

import type { LoginResponse } from '@/lib/types'

/**
 * App 根组件冒烟测试
 *
 * 覆盖：未登录访问受保护路由被守卫重定向到登录页；登录后进入仪表盘
 * （渲染 AdminLayout 布局壳 + 仪表盘页）；已登录访问登录页被送回仪表盘。
 */
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
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })

    await router.push('/dashboard')
    await router.isReady()

    // 守卫生效：路由落在登录页，页面渲染品牌区文案
    expect(router.currentRoute.value.name).toBe('login')
    expect(wrapper.text()).toContain('知识库管理后台')
    wrapper.unmount()
  })

  it('登录后访问仪表盘：渲染布局壳与仪表盘基础组件', async () => {
    const pinia = createPinia()
    const router = createAppRouter()
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })

    // 写入登录态后导航到受保护路由
    const auth = useAuthStore()
    auth.setAuth(buildLoginPayload())
    await router.push('/dashboard')
    await router.isReady()

    // 仪表盘页面渲染在 AdminLayout 壳内（顶栏品牌 + 侧导航 + 页面按钮）
    expect(router.currentRoute.value.name).toBe('dashboard')
    expect(wrapper.text()).toContain('仪表盘')
    expect(wrapper.text()).toContain('知识库管理后台')
    expect(wrapper.text()).toContain('刷新')
    // 仪表盘「刷新」按钮为主 CTA（bg-brand 语义 token）
    const brandButton = wrapper.findAll('button').find((b) => b.text() === '刷新')
    expect(brandButton?.classes()).toContain('bg-brand')
    wrapper.unmount()
  })

  it('已登录访问登录页：守卫送回仪表盘', async () => {
    const pinia = createPinia()
    const router = createAppRouter()
    const wrapper = mount(App, { global: { plugins: [pinia, router] } })

    const auth = useAuthStore()
    auth.setAuth(buildLoginPayload())
    await router.push('/login')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('dashboard')
    wrapper.unmount()
  })
})
