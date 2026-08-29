import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { vReveal } from '@/directives/reveal'
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
    duration: '8',
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
      directives: { reveal: vReveal },
    },
  })
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
})

/**
 * 课程教师分配测试（2026-08-29 T2.4 重构：remote-select 多选差集保存）
 *
 * 覆盖：已分配 chip 回显 / 打开拉取教师池 / 新增 POST 裸数组 / 移除 DELETE 裸数组 /
 * 保存失败草稿保留 / 加载失败重试 / 页头刷新按钮。
 */
describe('课程教师分配（remote-select 多选差集保存）', () => {
  it('加载：已分配教师以 chip 回显，无变动时保存按钮禁用', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: ['t-1'] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王'), teacher('t-2', '小李')]))
    const { wrapper } = await mountAt()
    await flushPromises()

    expect(wrapper.find('[data-testid="remote-chip-t-1"]').text()).toContain('老王')
    expect(wrapper.text()).toContain('当前已分配 1 名')
    const save = wrapper.find('[data-testid="teacher-assign"]')
    expect(save.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('打开选择器：拉取教师池渲染选项，选中后保存走 POST 裸数组 + 重拉', async () => {
    apiMock.courseApi.get
      .mockResolvedValueOnce(course({ teacherIds: [] }))
      .mockResolvedValueOnce(course({ teacherIds: ['t-1'] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王'), teacher('t-2', '小李')]))
    apiMock.courseApi.addTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    // 打开 remote-select：focus 即以空关键字拉首屏候选（fetcher → userApi.list）
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await flushPromises()
    expect(apiMock.userApi.list).toHaveBeenCalledWith(expect.objectContaining({ role: 'TEACHER' }))
    expect(wrapper.find('[data-testid="remote-option-t-2"]').exists()).toBe(true)

    // 选中 t-1 → chip 入场，保存按钮解禁
    await wrapper.find('[data-testid="remote-option-t-1"]').trigger('click')
    expect(wrapper.find('[data-testid="remote-chip-t-1"]').exists()).toBe(true)
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()

    // 契约 E.3：body 为裸 JSON 数组（非 {teacherIds:[...]} 包装）
    expect(apiMock.courseApi.addTeachers).toHaveBeenCalledWith('c-1', ['t-1'])
    expect(showToast).toHaveBeenCalledWith('教师分配已保存', 'success')
    await vi.waitFor(() => expect(apiMock.courseApi.get).toHaveBeenCalledTimes(2))
    wrapper.unmount()
  })

  it('移除 chip 保存：DELETE 裸数组 body → toast → 重拉刷新基线', async () => {
    apiMock.courseApi.get
      .mockResolvedValueOnce(course({ teacherIds: ['t-1'] }))
      .mockResolvedValueOnce(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王')]))
    apiMock.courseApi.removeTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="remote-chip-remove-t-1"]').trigger('click')
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()

    expect(apiMock.courseApi.removeTeachers).toHaveBeenCalledWith('c-1', ['t-1'])
    expect(showToast).toHaveBeenCalledWith('教师分配已保存', 'success')
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="remote-chip-t-1"]').exists()).toBe(false)
    })
    wrapper.unmount()
  })

  it('保存失败：toast 提示且草稿保留可重试', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王')]))
    apiMock.courseApi.addTeachers.mockRejectedValue(new apiMock.ApiError(500, '分配接口异常'))
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await flushPromises()
    await wrapper.find('[data-testid="remote-option-t-1"]').trigger('click')
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()

    expect(showToast).toHaveBeenCalledWith('分配接口异常', 'danger')
    // 草稿保留：chip 仍在，可修正后重试
    expect(wrapper.find('[data-testid="remote-chip-t-1"]').exists()).toBe(true)
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
    expect(wrapper.find('[data-testid="remote-select"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('刷新按钮（T2.3）：点击触发页面查询重拉', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([]))
    const { wrapper } = await mountAt()
    await flushPromises()

    await wrapper.find('[data-testid="refresh-teachers"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.get).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
})
