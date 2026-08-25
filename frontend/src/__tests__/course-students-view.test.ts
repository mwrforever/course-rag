import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseStudentsView from '@/views/course/CourseStudentsView.vue'

const apiMock = vi.hoisted(() => ({
  enrollmentApi: {
    students: vi.fn(),
    addStudents: vi.fn(),
    removeStudent: vi.fn(),
  },
  userApi: {
    list: vi.fn(),
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
import type { PageResponse, StudentDTO, UserDTO } from '@/lib/types'

function pageOf<T>(records: T[]): PageResponse<T> {
  return { records, total: String(records.length), page: 1, size: 100 }
}

function student(id: string, displayName: string): StudentDTO {
  return {
    id,
    username: `stu-${id}`,
    displayName,
    enrolledAt: '2026-08-02T00:00:00Z',
    status: 'ACTIVE',
  }
}

function candidate(id: string, displayName: string): UserDTO {
  return {
    id,
    username: `u-${id}`,
    displayName,
    role: 'STUDENT',
    status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z',
  }
}

async function mountAt(path = '/courses/c-1/students') {
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
  const wrapper = mount(CourseStudentsView, { global: { plugins: [pinia, router] } })
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('课程学生名单（/courses/:id/students）', () => {
  it('加载名单：username/displayName/报名时间 + 行移除入口', async () => {
    apiMock.enrollmentApi.students.mockResolvedValue([student('s-1', '小明')])
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.find('[data-testid="student-row-s-1"]').text()).toContain('小明')
    expect(wrapper.find('[data-testid="student-remove-s-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('空名单：引导文案 + 添加学生入口', async () => {
    apiMock.enrollmentApi.students.mockResolvedValue([])
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('还没有学生报名，点击添加学生开通名额')
    wrapper.unmount()
  })

  it('添加学生：搜索多选 → POST {studentIds} → 成功数 toast → 名单刷新', async () => {
    apiMock.enrollmentApi.students
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([student('s-1', '小明')])
    apiMock.userApi.list.mockResolvedValue(
      pageOf([candidate('s-1', '小明'), candidate('s-2', '小红')]),
    )
    apiMock.enrollmentApi.addStudents.mockResolvedValue(1)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="add-students"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="student-dialog"]').exists()).toBe(true)

    await wrapper.find('[data-testid="student-search"]').setValue('小')
    await flushPromises()
    // 整行点击切换勾选
    await wrapper.find('[data-testid="student-option-s-1"]').trigger('click')
    await wrapper.find('[data-testid="submit-students"]').trigger('click')
    await flushPromises()
    expect(apiMock.enrollmentApi.addStudents).toHaveBeenCalledWith('c-1', {
      studentIds: ['s-1'],
    })
    expect(showToast).toHaveBeenCalledWith('成功添加 1 名', 'success')
    expect(wrapper.find('[data-testid="student-dialog"]').exists()).toBe(false)
    expect(apiMock.enrollmentApi.students).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('移除学生：二次确认 → removeStudent → toast → 名单刷新', async () => {
    apiMock.enrollmentApi.students
      .mockResolvedValueOnce([student('s-1', '小明')])
      .mockResolvedValueOnce([])
    apiMock.enrollmentApi.removeStudent.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="student-remove-s-1"]').trigger('click')
    expect(wrapper.find('[data-testid="student-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-student-del"]').trigger('click')
    await flushPromises()
    expect(apiMock.enrollmentApi.removeStudent).toHaveBeenCalledWith('c-1', 's-1')
    expect(showToast).toHaveBeenCalledWith('已移除学生', 'success')
    wrapper.unmount()
  })

  it('加载失败：横幅 + 重试恢复', async () => {
    apiMock.enrollmentApi.students
      .mockRejectedValueOnce(new apiMock.ApiError(500, '名单接口异常'))
      .mockResolvedValueOnce([student('s-1', '小明')])
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('名单接口异常')
    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="student-row-s-1"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
