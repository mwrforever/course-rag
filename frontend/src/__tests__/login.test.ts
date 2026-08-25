import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import LoginView from '@/views/LoginView.vue'

import type { LoginResponse } from '@/lib/types'

/**
 * 登录页测试（Task 16 交付：左右分栏 + zod 校验 + 错误分级）
 *
 * 覆盖契约（设计 §2.4 /login 行与 §3.2 错误分级）：
 * 1. 左右分栏结构：左 40% slate-900 品牌区 + 右表单区
 * 2. zod 校验：用户名非空、密码 ≥6 位，校验不过不调登录接口
 * 3. 登录成功跳 redirect 或仪表盘
 * 4. 错误分级：401 用户名或密码错误 / 403 无权限 / 503 服务暂不可用 / 网络错误
 * 5. 提交中 loading 态 + 密码可见性切换
 */

/** 构造登录响应（Long 序列化铁律：userId 为 string） */
function buildPayload(role: LoginResponse['role'] = 'TEACHER'): LoginResponse {
  return {
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role,
    displayName: '测试教师',
  }
}

async function mountLogin() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createAppRouter()
  const wrapper = mount(LoginView, { global: { plugins: [pinia, router] } })
  await router.isReady()
  const store = useAuthStore()
  return { wrapper, router, store }
}

/** 填入用户名/密码并提交表单 */
async function submitLogin(wrapper: ReturnType<typeof mount>, username: string, password: string) {
  await wrapper.find('input[aria-label="用户名"]').setValue(username)
  await wrapper.find('input[aria-label="密码"]').setValue(password)
  await wrapper.find('form').trigger('submit')
}

describe('LoginView：结构与渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('左右分栏：左 40% slate-900 品牌区 + 右侧表单区', async () => {
    const { wrapper } = await mountLogin()

    // 品牌区：占 40% 宽 + slate-900 深底 + 品牌名
    const brand = wrapper.find('aside')
    expect(brand.classes()).toContain('w-[40%]')
    expect(brand.classes()).toContain('bg-slate-900')
    expect(brand.text()).toContain('课程助手管理后台')
    expect(wrapper.text()).toContain('知识运维与管理，一处完成')

    // 表单区：用户名/密码/登录按钮，无记住我复选框
    expect(wrapper.find('input[aria-label="用户名"]').exists()).toBe(true)
    expect(wrapper.find('input[aria-label="密码"]').attributes('type')).toBe('password')
    const submit = wrapper.find('button[type="submit"]')
    expect(submit.text()).toContain('登 录')
    expect(wrapper.find('input[type="checkbox"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('密码可见性切换：眼睛按钮切换 input 类型', async () => {
    const { wrapper } = await mountLogin()

    await wrapper.find('button[aria-label="显示密码"]').trigger('click')
    expect(wrapper.find('input[aria-label="密码"]').attributes('type')).toBe('text')

    await wrapper.find('button[aria-label="隐藏密码"]').trigger('click')
    expect(wrapper.find('input[aria-label="密码"]').attributes('type')).toBe('password')
    wrapper.unmount()
  })
})

describe('LoginView：zod 校验', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('用户名/密码为空：显示校验文案且不调登录接口', async () => {
    const { wrapper, store } = await mountLogin()
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue(buildPayload())

    await submitLogin(wrapper as never, '', '')
    await vi.waitFor(() => expect(wrapper.text()).toContain('请输入用户名'))
    expect(wrapper.text()).toContain('密码至少 6 位')
    expect(loginSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('密码不足 6 位：就地报错不提交', async () => {
    const { wrapper, store } = await mountLogin()
    const loginSpy = vi.spyOn(store, 'login').mockResolvedValue(buildPayload())

    await submitLogin(wrapper as never, 'teacher1', '12345')

    expect(wrapper.text()).toContain('密码至少 6 位')
    expect(loginSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})

describe('LoginView：登录流程与错误分级', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('登录成功：以用户名 + 密码提交并跳转仪表盘', async () => {
    const { wrapper, router, store } = await mountLogin()
    // 贴近真实登录流：spy 内部写入登录态（守卫依赖 isAuthenticated 放行目标页）
    const loginSpy = vi.spyOn(store, 'login').mockImplementation(async () => {
      store.setAuth(buildPayload())
      return buildPayload()
    })

    await submitLogin(wrapper as never, 'teacher1', '123456')
    // 目标页懒加载 + 守卫导航：全量并行 + 覆盖率插桩下放宽等待预算（默认 1s 偶发超时）
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('dashboard'), {
      timeout: 5000,
    })
    expect(loginSpy).toHaveBeenCalledWith('teacher1', '123456')
    wrapper.unmount()
  })

  it('带 redirect 参数登录成功：跳转回原目标页', async () => {
    const { wrapper, router, store } = await mountLogin()
    vi.spyOn(store, 'login').mockImplementation(async () => {
      store.setAuth(buildPayload())
      return buildPayload()
    })
    await router.push({ name: 'login', query: { redirect: '/knowledge/documents' } })

    await submitLogin(wrapper as never, 'teacher1', '123456')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/knowledge/documents'))
    wrapper.unmount()
  })

  it('401 用户名或密码错误：顶部 Alert 展示后端文案且停留登录页', async () => {
    const { wrapper, router, store } = await mountLogin()
    vi.spyOn(store, 'login').mockRejectedValue(new ApiError(401, '用户名或密码错误', 401))

    await submitLogin(wrapper as never, 'teacher1', 'wrong1')
    await vi.waitFor(() =>
      expect(wrapper.find('[role="alert"]').text()).toContain('用户名或密码错误'),
    )
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('403 无权限（STUDENT 登录）：展示无权限文案不跳转', async () => {
    const { wrapper, router, store } = await mountLogin()
    vi.spyOn(store, 'login').mockRejectedValue(new ApiError(403, '当前账号无管理后台访问权限', 403))

    await submitLogin(wrapper as never, 'stu1', '123456')
    await vi.waitFor(() =>
      expect(wrapper.find('[role="alert"]').text()).toContain('当前账号无管理后台访问权限'),
    )
    expect(router.currentRoute.value.name).toBe('login')
    wrapper.unmount()
  })

  it('503 服务暂不可用：展示统一降级文案', async () => {
    const { wrapper, store } = await mountLogin()
    vi.spyOn(store, 'login').mockRejectedValue(new ApiError(503, '服务暂时不可用', 503))

    await submitLogin(wrapper as never, 'teacher1', '123456')
    await vi.waitFor(() =>
      expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试'),
    )
    wrapper.unmount()
  })

  it('网络错误：展示「网络连接失败，请检查网络」', async () => {
    const { wrapper, store } = await mountLogin()
    vi.spyOn(store, 'login').mockRejectedValue(new ApiError(0, '网络连接失败，请检查网络'))

    await submitLogin(wrapper as never, 'teacher1', '123456')
    await vi.waitFor(() =>
      expect(wrapper.find('[role="alert"]').text()).toContain('网络连接失败，请检查网络'),
    )
    wrapper.unmount()
  })

  it('未知异常（非 ApiError）：展示兜底文案「登录失败，请稍后重试」', async () => {
    const { wrapper, store } = await mountLogin()
    vi.spyOn(store, 'login').mockRejectedValue(new Error('unexpected'))

    await submitLogin(wrapper as never, 'teacher1', '123456')
    await vi.waitFor(() =>
      expect(wrapper.find('[role="alert"]').text()).toContain('登录失败，请稍后重试'),
    )
    wrapper.unmount()
  })

  it('提交中：按钮进入 loading 禁用态，完成后恢复', async () => {
    const { wrapper, store } = await mountLogin()
    let resolveLogin!: (p: LoginResponse) => void
    vi.spyOn(store, 'login').mockReturnValue(new Promise((resolve) => (resolveLogin = resolve)))

    await wrapper.find('input[aria-label="用户名"]').setValue('teacher1')
    await wrapper.find('input[aria-label="密码"]').setValue('123456')
    await wrapper.find('form').trigger('submit')
    await vi.waitFor(() =>
      expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined(),
    )
    expect(wrapper.text()).toContain('登录中')

    resolveLogin(buildPayload())
    await vi.waitFor(() =>
      expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined(),
    )
    wrapper.unmount()
  })
})
