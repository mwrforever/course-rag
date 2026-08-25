import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseDetailLayout from '@/views/course/CourseDetailLayout.vue'

const apiMock = vi.hoisted(() => ({
  courseApi: {
    get: vi.fn(),
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

import type { CourseDTO } from '@/lib/types'

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

async function mountAt(path = '/courses/c-1') {
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
  const wrapper = mount(CourseDetailLayout, { global: { plugins: [pinia, router] } })
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('课程详情壳（子导航 + 元数据）', () => {
  it('加载课程标题并渲染五个分区子导航', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt()
    await flushPromises()

    expect(wrapper.find('[data-testid="course-detail-title"]').text()).toBe('RAG 实战营')
    // 子导航：概览/内容/排期/教师/学生（RouterLink 激活态交由路由名匹配）
    for (const label of ['概览', '内容', '排期', '教师', '学生']) {
      expect(wrapper.text()).toContain(label)
    }
    expect(wrapper.find('[data-testid="course-nav-course-detail"]').classes()).toContain(
      'bg-brand-soft',
    )
    wrapper.unmount()
  })

  it('课程 404：渲染不存在态（课程不存在或已下架）', async () => {
    apiMock.courseApi.get.mockRejectedValue(new apiMock.ApiError(404, '课程不存在'))
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('课程不存在或已下架')
    wrapper.unmount()
  })

  it('加载失败：横幅 + 重试恢复', async () => {
    apiMock.courseApi.get
      .mockRejectedValueOnce(new apiMock.ApiError(500, '课程接口异常'))
      .mockResolvedValueOnce(course())
    const { wrapper } = await mountAt()
    await flushPromises()
    expect(wrapper.text()).toContain('课程接口异常')

    await wrapper.find('[data-testid="retry-course"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="course-detail-title"]').text()).toBe('RAG 实战营')
    wrapper.unmount()
  })

  it('返回课程列表按钮触发跳转 /courses', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper, router } = await mountAt()
    await flushPromises()
    const pushSpy = vi.spyOn(router, 'push')
    await wrapper.find('[data-testid="back-to-courses"]').trigger('click')
    await flushPromises()
    expect(pushSpy).toHaveBeenCalledWith({ name: 'courses' })
    pushSpy.mockRestore()
    wrapper.unmount()
  })
})
