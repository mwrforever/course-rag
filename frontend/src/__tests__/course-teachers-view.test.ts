import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseTeachersView from '@/views/course/CourseTeachersView.vue'

const apiMock = vi.hoisted(() => ({
  courseApi: {
    get: vi.fn(),
    addTeachers: vi.fn(),
    removeTeachers: vi.fn(),
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
import type { CourseDTO, PageResponse, UserDTO } from '@/lib/types'

function pageOf<T>(records: T[]): PageResponse<T> {
  return { records, total: String(records.length), page: 1, size: 100 }
}

function teacher(id: string, displayName: string): UserDTO {
  return {
    id,
    username: `u-${id}`,
    displayName,
    role: 'TEACHER',
    status: 'ACTIVE',
    createdAt: '2026-08-01T00:00:00Z',
  }
}

function course(over: Partial<CourseDTO> = {}): CourseDTO {
  return {
    id: 'c-1',
    title: 'RAG 实战营',
    description: '从零到一',
    coverImage: 'https://cdn.example.com/cover.jpg',
    category: 'AI',
    instructorName: '老王',
    price: 199,
    duration: '8 课时',
    tags: ['RAG'],
    rating: 0,
    learningCount: 0,
    enrollmentLink: 'https://apply.example.com',
    status: 'ACTIVE',
    createdBy: 'u1',
    createdAt: '2026-08-01T00:00:00Z',
    contents: null,
    schedules: null,
    teacherIds: [],
    ...over,
  }
}

async function mountAt(path = '/courses/c-1/teachers') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at',
    refreshToken: 'rt',
    userId: '1001',
    role: 'SUPER_ADMIN',
    displayName: '李超管',
  })
  const router = createAppRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(CourseTeachersView, {
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
  vi.clearAllMocks()
})

describe('课程教师分配（/courses/:id/teachers）', () => {
  it('加载双栏：已分配（课程 teacherIds）与可选（全量 TEACHER 剔除已分配）', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: ['t-1'] }))
    apiMock.userApi.list.mockResolvedValue(
      pageOf([teacher('t-1', '老王'), teacher('t-2', '小李'), teacher('t-3', '小张')]),
    )
    const { wrapper } = await mountAt()
    await flushPromises()

    expect(wrapper.find('[data-testid="teacher-assigned-t-1"]').text()).toContain('老王')
    expect(wrapper.find('[data-testid="teacher-available-t-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teacher-available-t-3"]').exists()).toBe(true)
    // 已分配教师不会出现在可选池
    expect(wrapper.find('[data-testid="teacher-available-t-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('搜索过滤：按显示名/用户名子串命中', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王'), teacher('t-2', '小李')]))
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="teacher-search"]').setValue('李')
    await flushPromises()
    expect(wrapper.find('[data-testid="teacher-available-t-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teacher-available-t-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('分配所选：POST [ids] → toast → 清空勾选 → 重拉课程刷新双栏', async () => {
    apiMock.courseApi.get
      .mockResolvedValueOnce(course({ teacherIds: [] }))
      .mockResolvedValueOnce(course({ teacherIds: ['t-1'] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王')]))
    apiMock.courseApi.addTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="teacher-check-t-1"]').setValue(true)
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.addTeachers).toHaveBeenCalledWith('c-1', ['t-1'])
    expect(showToast).toHaveBeenCalledWith('教师分配成功', 'success')
    // 失效重拉为异步链：重拉次数与双栏更新以 waitFor 收敛
    await vi.waitFor(() => expect(apiMock.courseApi.get).toHaveBeenCalledTimes(2))
    // 分配后已分配栏出现该教师
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="teacher-assigned-t-1"]').exists()).toBe(true)
    })
    wrapper.unmount()
  })

  it('分配成功后重拉失败：toast「课程刷新失败」提示（不静默）', async () => {
    apiMock.courseApi.get
      .mockResolvedValueOnce(course({ teacherIds: [] }))
      .mockRejectedValueOnce(new apiMock.ApiError(500, '课程接口异常'))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王')]))
    apiMock.courseApi.addTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="teacher-check-t-1"]').setValue(true)
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()

    expect(showToast).toHaveBeenCalledWith('教师分配成功', 'success')
    // 重拉失败 → 恢复原 refreshCourse 的失败提示交互
    await vi.waitFor(() =>
      expect(showToast).toHaveBeenCalledWith('课程刷新失败，请重试或刷新页面', 'danger'),
    )
    wrapper.unmount()
  })

  it('移除教师：DELETE [id] 带 body → toast → 重拉课程', async () => {
    apiMock.courseApi.get
      .mockResolvedValueOnce(course({ teacherIds: ['t-1'] }))
      .mockResolvedValueOnce(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王')]))
    apiMock.courseApi.removeTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="teacher-remove-t-1"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.removeTeachers).toHaveBeenCalledWith('c-1', ['t-1'])
    expect(showToast).toHaveBeenCalledWith('已移除教师', 'success')
    expect(wrapper.find('[data-testid="teacher-assigned-t-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('加载失败：横幅 + 重试恢复', async () => {
    apiMock.courseApi.get
      .mockRejectedValueOnce(new apiMock.ApiError(500, '教师接口异常'))
      .mockResolvedValueOnce(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([]))
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('教师接口异常')

    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="teacher-available-empty"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
