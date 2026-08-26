import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import StudentsView from '@/views/StudentsView.vue'

const apiMock = vi.hoisted(() => ({
  userApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    resetPassword: vi.fn(),
    updateStatus: vi.fn(),
    remove: vi.fn(),
  },
  ApiError: class ApiError extends Error {
    code: number
    constructor(code: number, message: string) {
      super(message)
      this.code = code
    }
  },
}))
vi.mock('@/lib/api', () => apiMock)
vi.mock('@/lib/toast', () => ({ showToast: vi.fn() }))

import { showToast } from '@/lib/toast'
import type { PageResponse, UserDTO } from '@/lib/types'

function pageOf<T>(records: T[], total = String(records.length)): PageResponse<T> {
  return { records, total, page: 1, size: 10 }
}

function student(id: string, over: Partial<UserDTO> = {}): UserDTO {
  return {
    id,
    username: `stu-${id}`,
    displayName: `学生${id}`,
    role: 'STUDENT',
    status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z',
    ...over,
  }
}

async function mountAt(path = '/students') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at',
    refreshToken: 'rt',
    userId: '1001',
    role: 'TEACHER',
    displayName: '张老师',
  })
  const router = createAppRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(StudentsView, {
    global: {
      plugins: [
        [
          VueQueryPlugin,
          { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
        ],
        pinia,
        router,
      ],
    },
  })
  return { wrapper, router }
}

beforeEach(() => {
  // 与其他视图测试对齐：restoreAllMocks 同时清 mock 实现（clearAllMocks 遗留实现跨用例泄漏实证）
  vi.restoreAllMocks()
})

describe('学生管理（/students，两角色可见）', () => {
  it('加载学生分页列表：role=STUDENT 过滤 + 分页器', async () => {
    apiMock.userApi.list.mockResolvedValue(pageOf([student('s-1')], '11'))
    const { wrapper } = await mountAt()
    await flushPromises()

    expect(apiMock.userApi.list).toHaveBeenCalledWith(
      expect.objectContaining({ role: 'STUDENT', page: 1, size: 10 }),
    )
    expect(wrapper.find('[data-testid="student-table"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="row-s-1"]').text()).toContain('学生s-1')
    expect(wrapper.text()).toContain('11 条')
    // 分页：下一页 → page=2
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    wrapper.unmount()
  })

  it('空列表：语义空态 + 添加学生入口', async () => {
    apiMock.userApi.list.mockResolvedValue(pageOf([]))
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('还没有学生')
    wrapper.unmount()
  })

  it('添加学生：zod 校验失败就地报错；合法提交 create 固定 STUDENT 角色', async () => {
    apiMock.userApi.list.mockResolvedValue(pageOf([]))
    apiMock.userApi.create.mockResolvedValue(student('s-new'))
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="add-student"]').trigger('click')
    // 空表单提交：就地报错且不发请求
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()
    expect(wrapper.find('[data-testid="add-error-username"]').exists()).toBe(true)
    expect(apiMock.userApi.create).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="add-username"]').setValue('stuliu')
    await wrapper.find('[data-testid="add-password"]').setValue('123456')
    await wrapper.find('[data-testid="add-displayname"]').setValue('小明')
    await wrapper.find('[data-testid="add-form"]').trigger('submit')
    await flushPromises()
    expect(apiMock.userApi.create).toHaveBeenCalledWith({
      username: 'stuliu',
      password: '123456',
      displayName: '小明',
      role: 'STUDENT',
    })
    expect(showToast).toHaveBeenCalledWith('学生账号已创建', 'success')
    wrapper.unmount()
  })

  it('重置密码：两次输入不一致就地报错；一致调用 resetPassword', async () => {
    apiMock.userApi.list.mockResolvedValue(pageOf([student('s-1')]))
    apiMock.userApi.resetPassword.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="op-reset-s-1"]').trigger('click')
    await wrapper.find('[data-testid="reset-password"]').setValue('654321')
    await wrapper.find('[data-testid="reset-confirm"]').setValue('654322')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="reset-error"]').text()).toBe('两次输入的密码不一致')
    expect(apiMock.userApi.resetPassword).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="reset-confirm"]').setValue('654321')
    await wrapper.find('[data-testid="submit-reset"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.resetPassword).toHaveBeenCalledWith('s-1', { newPassword: '654321' })
    wrapper.unmount()
  })

  it('禁用/启用二次确认与删除二次确认', async () => {
    apiMock.userApi.list.mockResolvedValue(pageOf([student('s-1')]))
    apiMock.userApi.updateStatus.mockResolvedValue(undefined)
    apiMock.userApi.remove.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    // 禁用：确认后调用 updateStatus
    await wrapper.find('[data-testid="op-disable-s-1"]').trigger('click')
    expect(wrapper.find('[data-testid="status-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="submit-status"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.updateStatus).toHaveBeenCalledWith('s-1', { status: 'DISABLED' })
    expect(showToast).toHaveBeenCalledWith('已禁用该学生', 'success')

    // 删除：确认后调用 remove
    await wrapper.find('[data-testid="op-delete-s-1"]').trigger('click')
    expect(wrapper.find('[data-testid="user-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-user-del"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.remove).toHaveBeenCalledWith('s-1')
    expect(showToast).toHaveBeenCalledWith('学生已删除', 'success')
    wrapper.unmount()
  })

  it('删除末页最后一条：回退上一页防空页（页码变化自动重拉）', async () => {
    // 第 1 页 1 条共 11（2 页）→ 翻第 2 页 1 条 → 删除后回退第 1 页
    apiMock.userApi.list
      .mockResolvedValueOnce(pageOf([student('s-1')], '11'))
      .mockResolvedValueOnce(pageOf([student('s-9')], '11'))
      .mockResolvedValueOnce(pageOf([student('s-1')], '10'))
    apiMock.userApi.remove.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    // 翻到第 2 页（末页仅剩 1 条）
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    expect(wrapper.text()).toContain('第 2 / 2 页')
    expect(wrapper.find('[data-testid="row-s-9"]').exists()).toBe(true)

    // 删除唯一行：回退到第 1 页（不展示空页）
    await wrapper.find('[data-testid="op-delete-s-9"]').trigger('click')
    await wrapper.find('[data-testid="confirm-user-del"]').trigger('click')
    await flushPromises()
    expect(apiMock.userApi.remove).toHaveBeenCalledWith('s-9')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('第 1 / 1 页')
      expect(wrapper.find('[data-testid="row-s-1"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="row-s-9"]').exists()).toBe(false)
    })
    wrapper.unmount()
  })

  it('加载失败：横幅 + 重试恢复', async () => {
    apiMock.userApi.list
      .mockRejectedValueOnce(new apiMock.ApiError(500, '列表接口异常'))
      .mockResolvedValueOnce(pageOf([student('s-1')]))
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('列表接口异常')
    await wrapper.find('[data-testid="retry-students"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="student-table"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
