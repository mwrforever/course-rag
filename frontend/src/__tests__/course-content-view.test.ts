import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { vReveal } from '@/directives/reveal'
import CourseContentView from '@/views/course/CourseContentView.vue'

const apiMock = vi.hoisted(() => ({
  courseApi: {
    contents: vi.fn(),
    updateContent: vi.fn(),
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

/** md-editor-v3 mock：textarea 桥接 modelValue ↔ update:modelValue/onChange */
vi.mock('md-editor-v3', async () => {
  const { defineComponent, h } = await import('vue')
  const MdEditor = defineComponent({
    name: 'MdEditor',
    props: { modelValue: { type: String, default: '' } },
    emits: ['update:modelValue', 'onChange'],
    setup(props, { emit }) {
      return () =>
        h('textarea', {
          'data-testid': 'md-editor-stub',
          value: props.modelValue,
          onInput: (e: Event) => {
            const next = (e.target as HTMLTextAreaElement).value
            emit('update:modelValue', next)
            emit('onChange', next)
          },
        })
    },
  })
  return { MdEditor }
})

import { showToast } from '@/lib/toast'

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
  const wrapper = mount(CourseContentView, {
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

describe('课程内容（/courses/:id/content）', () => {
  it('按 sortOrder 加载 Tab 顺序，编辑器回写当前 Tab', async () => {
    apiMock.courseApi.contents.mockResolvedValue([
      { contentType: 'faq', content: 'FAQ 正文', sortOrder: 4 },
      { contentType: 'intro', content: '介绍正文', sortOrder: 1 },
      { contentType: 'syllabus', content: '大纲正文', sortOrder: 2 },
      { contentType: 'instructor', content: '讲师正文', sortOrder: 3 },
    ])
    const { wrapper } = await mountAt('/courses/c-1/content')
    await flushPromises()

    // 激活 Tab = intro（sortOrder 最小）
    const tabs = wrapper.findAll('[data-testid^="tab-"]')
    expect(tabs.map((t) => t.text())).toEqual(['课程介绍', '教学大纲', '讲师信息', '常见问题'])
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('介绍正文')
    // 切 Tab 不丢内容
    await tabs[2].trigger('click')
    await flushPromises()
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('讲师正文')
    wrapper.unmount()
  })

  it('列表为空：回退四个常量 Tab 均可直接开写', async () => {
    apiMock.courseApi.contents.mockResolvedValue([])
    const { wrapper } = await mountAt('/courses/c-1/content')
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="tab-"]')).toHaveLength(4)
    wrapper.unmount()
  })

  it('逐 Tab 独立保存：PUT 携带当前 Tab 正文，toast 区分文案', async () => {
    apiMock.courseApi.contents.mockResolvedValue([])
    apiMock.courseApi.updateContent.mockResolvedValue(undefined)
    const { wrapper } = await mountAt('/courses/c-1/content')
    await flushPromises()

    const editor = wrapper.find('[data-testid="md-editor-stub"]')
    await editor.setValue('新的介绍内容')
    await wrapper.find('[data-testid="save-content"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.updateContent).toHaveBeenCalledWith('c-1', 'intro', '新的介绍内容')
    expect(showToast).toHaveBeenCalledWith('课程介绍已保存', 'success')
    wrapper.unmount()
  })

  it('内容加载失败：横幅 + 重试恢复', async () => {
    apiMock.courseApi.contents
      .mockRejectedValueOnce(new apiMock.ApiError(500, '内容接口异常'))
      .mockResolvedValueOnce([{ contentType: 'intro', content: '恢复正文', sortOrder: 1 }])
    const { wrapper } = await mountAt('/courses/c-1/content')
    await flushPromises()
    expect(wrapper.find('[data-testid="contents-error"]').text()).toContain('内容接口异常')

    await wrapper.find('[data-testid="retry-contents"]').trigger('click')
    await flushPromises()
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('恢复正文')
    wrapper.unmount()
  })
})
