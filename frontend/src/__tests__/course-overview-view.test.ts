import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseOverviewView from '@/views/course/CourseOverviewView.vue'

/** api mock：courseApi 增删改查 + ApiError */
const apiMock = vi.hoisted(() => ({
  courseApi: {
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    contents: vi.fn(),
    updateContent: vi.fn(),
    addTeachers: vi.fn(),
    removeTeachers: vi.fn(),
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
import type { CourseDTO } from '@/lib/types'

/** 课程工厂 */
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

async function mountAt(path: string) {
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
  const wrapper = mount(CourseOverviewView, {
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

describe('课程概览（编辑模式 /courses/:id）', () => {
  it('加载课程并回填表单（标签/价格字符串化/状态）', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()

    const title = wrapper.find('[data-testid="field-title"]').element as HTMLInputElement
    expect(title.value).toBe('RAG 实战营')
    expect((wrapper.find('[data-testid="field-price"]').element as HTMLInputElement).value).toBe(
      '199',
    )
    expect(wrapper.text()).toContain('RAG')
    expect(wrapper.find('[data-testid="field-status"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('加载失败：横幅 + 重试恢复', async () => {
    apiMock.courseApi.get
      .mockRejectedValueOnce(new apiMock.ApiError(500, '服务器内部错误'))
      .mockResolvedValueOnce(course({ title: '恢复的课程' }))
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    expect(wrapper.text()).toContain('服务器内部错误')

    const retry = wrapper.findAll('button').find((b) => b.text().includes('重试'))
    await retry?.trigger('click')
    await flushPromises()
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe(
      '恢复的课程',
    )
    wrapper.unmount()
  })

  it('标题校验：空标题就地报错不发请求', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ title: '' }))
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="field-error"]').text()).toBe('请输入课程标题')
    expect(apiMock.courseApi.update).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存编辑：update 全字段 + toast + 不跳转', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    apiMock.courseApi.update.mockResolvedValue(undefined)
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.update).toHaveBeenCalledWith(
      'c-1',
      expect.objectContaining({ title: 'RAG 实战营', status: 'ACTIVE', price: 199 }),
    )
    expect(showToast).toHaveBeenCalledWith('课程信息已保存', 'success')
    wrapper.unmount()
  })

  it('标签 chips：回车添加（去重）+ X 删除', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ tags: ['RAG'] }))
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()

    const input = wrapper.find('[data-testid="tag-input"]')
    await input.setValue('LLM')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(wrapper.find('[data-testid="tag-chip-LLM"]').exists()).toBe(true)
    // 重复添加被去重
    await input.setValue('RAG')
    await input.trigger('keydown.enter')
    await flushPromises()
    expect(wrapper.findAll('[data-testid="tag-chip-RAG"]')).toHaveLength(1)
    // 删除
    await wrapper.find('[data-testid="tag-remove-RAG"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="tag-chip-RAG"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('课程概览（新建模式 /courses/new）', () => {
  it('新建：零加载请求，create 后跳转详情', async () => {
    apiMock.courseApi.create.mockResolvedValue(course({ id: 'c-9' }))
    const { wrapper, router } = await mountAt('/courses/new')
    await flushPromises()
    expect(apiMock.courseApi.get).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('创建课程')

    await wrapper.find('[data-testid="field-title"]').setValue('新课程')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.create).toHaveBeenCalledWith(
      expect.objectContaining({ title: '新课程' }),
    )
    expect(showToast).toHaveBeenCalledWith('课程创建成功', 'success')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('course-detail')
    expect(router.currentRoute.value.params.id).toBe('c-9')
    wrapper.unmount()
  })
})
