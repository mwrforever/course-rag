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

async function mountAt(
  path = '/courses/c-1/teachers',
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
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
      plugins: [[VueQueryPlugin, { queryClient }], pinia, router],
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

  it('warm cache：命中 30s 未过期缓存不重拉，草稿 chips 立即回填（BUG-02 回归）', async () => {
    // 场景：30s 内重进教师分配页命中未过期缓存，data 在 watch 注册前已同步就位——
    // 无 immediate 时 draftInitialized 永不为 true，已分配教师 chips 空白。
    // PERF-11：缓存按统一键预填——['course', id] 为详情壳/概览同键的原始 CourseDTO
    // （模拟 Tab 首访切换：前一个视图已填充共享缓存，本视图二次挂载 0 请求）
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
    })
    queryClient.setQueryData(['course', 'c-1'], course({ teacherIds: ['t-1'] }))
    queryClient.setQueryData(['user-pool', 'TEACHER'], [teacher('t-1', '老王')])
    const { wrapper } = await mountAt('/courses/c-1/teachers', queryClient)
    await flushPromises()

    // 未过期缓存不触发任何重拉（证明用例确处 warm-cache 路径，非冷缓存误绿）
    expect(apiMock.courseApi.get).not.toHaveBeenCalled()
    expect(apiMock.userApi.list).not.toHaveBeenCalled()
    // 草稿已回填：已分配教师以 chip 回显
    expect(wrapper.find('[data-testid="remote-chip-t-1"]').text()).toContain('老王')
    expect(wrapper.text()).toContain('当前已分配 1 名')
    wrapper.unmount()
  })

  it('PERF-09：池 fetcher 命中 QueryClient 缓存——页面池查询后打开下拉/搜索 0 重复请求', async () => {
    // 30s staleTime 对齐生产全局默认：页面池查询首拉后，remote-select fetcher 的
    // ensureQueryData 同键命中缓存，空关键字首屏 + 关键字搜索均纯本地过滤
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
    })
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: [] }))
    apiMock.userApi.list.mockResolvedValue(pageOf([teacher('t-1', '老王'), teacher('t-2', '小李')]))
    const { wrapper } = await mountAt('/courses/c-1/teachers', queryClient)
    await flushPromises()
    // 页面教师池查询首拉一次
    expect(apiMock.userApi.list).toHaveBeenCalledTimes(1)

    // 打开下拉（空关键字首屏，无防抖立即拉）：fetcher 命中缓存 0 新请求，选项照常渲染
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await flushPromises()
    expect(apiMock.userApi.list).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="remote-option-t-2"]').exists()).toBe(true)

    // 关键字搜索（防抖 300ms 后触发）：仍命中缓存本地过滤
    await wrapper.find('[data-testid="remote-input"]').setValue('老王')
    await new Promise((resolve) => setTimeout(resolve, 400))
    await flushPromises()
    expect(apiMock.userApi.list).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="remote-option-t-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="remote-option-t-2"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
