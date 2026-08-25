import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseScheduleView from '@/views/course/CourseScheduleView.vue'

const apiMock = vi.hoisted(() => ({
  scheduleApi: {
    listByCourse: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
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
import type { CourseScheduleVO } from '@/lib/types'

function schedule(over: Partial<CourseScheduleVO> = {}): CourseScheduleVO {
  return {
    id: 's-1',
    courseId: 'c-1',
    startDate: '2026-09-01',
    endDate: '2026-09-30',
    scheduleType: 'ONLINE',
    location: '腾讯会议',
    instructorName: '老王',
    capacity: 100,
    enrolled: 30,
    status: 'ACTIVE',
    createdBy: 'u1',
    createdAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-01T00:00:00Z',
    ...over,
  }
}

async function mountAt(path = '/courses/c-1/schedule') {
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
  const wrapper = mount(CourseScheduleView, { global: { plugins: [pinia, router] } })
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('课程排期（/courses/:id/schedule）', () => {
  it('加载排期表格：起止/类型/地点/讲师/容量/已报', async () => {
    apiMock.scheduleApi.listByCourse.mockResolvedValue([schedule()])
    const { wrapper } = await mountAt()
    await flushPromises()
    const row = wrapper.find('[data-testid="schedule-row-s-1"]')
    expect(row.text()).toContain('2026-09-01')
    expect(row.text()).toContain('ONLINE')
    expect(row.text()).toContain('腾讯会议')
    expect(row.text()).toContain('100')
    expect(row.text()).toContain('30')
    wrapper.unmount()
  })

  it('空排期：引导文案', async () => {
    apiMock.scheduleApi.listByCourse.mockResolvedValue([])
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('还没有排期，点击新增排期添加课程安排')
    wrapper.unmount()
  })

  it('新增排期：日期必填校验 → create → toast → 刷新列表', async () => {
    apiMock.scheduleApi.listByCourse.mockResolvedValue([])
    apiMock.scheduleApi.create.mockResolvedValue(schedule())
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="add-schedule"]').trigger('click')
    expect(wrapper.find('[data-testid="schedule-dialog"]').exists()).toBe(true)
    // 校验：缺结束日期就地报错
    await wrapper.find('[data-testid="schedule-start"]').setValue('2026-10-01')
    await wrapper.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-error"]').text()).toBe('请输入结束日期')
    expect(apiMock.scheduleApi.create).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="schedule-end"]').setValue('2026-10-31')
    await wrapper.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()
    expect(apiMock.scheduleApi.create).toHaveBeenCalledWith(
      'c-1',
      expect.objectContaining({ startDate: '2026-10-01', capacity: undefined }),
    )
    expect(showToast).toHaveBeenCalledWith('排期已保存', 'success')
    // 关闭 Dialog 并刷新列表
    expect(wrapper.find('[data-testid="schedule-dialog"]').exists()).toBe(false)
    expect(apiMock.scheduleApi.listByCourse).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('行内编辑：回填 → update 全字段', async () => {
    apiMock.scheduleApi.listByCourse.mockResolvedValue([schedule()])
    apiMock.scheduleApi.update.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="op-schedule-edit-s-1"]').trigger('click')
    expect(wrapper.text()).toContain('编辑排期')
    await wrapper.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()
    expect(apiMock.scheduleApi.update).toHaveBeenCalledWith(
      's-1',
      expect.objectContaining({ scheduleType: 'ONLINE', capacity: 100 }),
    )
    wrapper.unmount()
  })

  it('删除排期：二次确认 → remove → toast → 刷新', async () => {
    apiMock.scheduleApi.listByCourse.mockResolvedValue([schedule()])
    apiMock.scheduleApi.remove.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="op-schedule-del-s-1"]').trigger('click')
    expect(wrapper.find('[data-testid="schedule-del-dialog"]').exists()).toBe(true)
    // 取消不调用
    await wrapper.find('[data-testid="cancel-schedule-del"]').trigger('click')
    await flushPromises()
    expect(apiMock.scheduleApi.remove).not.toHaveBeenCalled()
    // 确认删除
    await wrapper.find('[data-testid="op-schedule-del-s-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-schedule-del"]').trigger('click')
    await flushPromises()
    expect(apiMock.scheduleApi.remove).toHaveBeenCalledWith('s-1')
    expect(showToast).toHaveBeenCalledWith('排期已删除', 'success')
    wrapper.unmount()
  })
})
