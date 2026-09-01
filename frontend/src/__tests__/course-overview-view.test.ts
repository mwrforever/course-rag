import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import { vReveal } from '@/directives/reveal'
import CourseOverviewView from '@/views/course/CourseOverviewView.vue'

/** api mock：courseApi/userApi + apiClient（image-upload 上传通道）+ ApiError */
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
  userApi: {
    list: vi.fn(),
  },
  apiClient: { post: vi.fn() },
  // 上传超时预算（与真实模块同值）：image-upload 引用，保持 mock 契约完整（BUG-03）
  UPLOAD_TIMEOUT_MS: 300_000,
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

/** 教师工厂（remote-select 选项载体） */
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

/** 课程工厂（时长为数字形态：表单 zod 校验要求课时数字或留空） */
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

/**
 * 挂载组件到指定路由
 *
 * @param path 目标路由（如 /courses/c-1）
 * @param queryClient 可选预构建查询客户端（warm-cache 用例传入预填缓存的客户端；
 *                    缺省每用例新建冷缓存客户端，staleTime 0 恒过期必重拉）
 */
async function mountAt(
  path: string,
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
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
      plugins: [[VueQueryPlugin, { queryClient }], pinia, router],
      directives: { reveal: vReveal },
    },
  })
  return { wrapper, router }
}

beforeEach(() => {
  vi.clearAllMocks()
  // 教师池缺省空（页面查询 + remote-select fetcher 共用）
  apiMock.userApi.list.mockResolvedValue(pageOf([]))
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
    // 错误内联于标题字段下方（Input 的 aria-describedby 关联红字）
    const titleInput = wrapper.find('[data-testid="field-title"]')
    const descId = titleInput.attributes('aria-describedby')
    expect(wrapper.find(`#${descId}`).text()).toBe('请输入课程标题')
    expect(apiMock.courseApi.update).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('价格校验：负数就地报错不发请求', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    // number 输入域非法字符由浏览器吞掉（值归空），此处以负数覆盖校验分支
    await wrapper.find('[data-testid="field-price"]').setValue('-5')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    const priceInput = wrapper.find('[data-testid="field-price"]')
    const descId = priceInput.attributes('aria-describedby')
    expect(wrapper.find(`#${descId}`).text()).toBe('价格须为非负数字')
    expect(apiMock.courseApi.update).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('保存编辑：update 全字段 + toast + 不跳转（无教师变动不调教师端点）', async () => {
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
    // 报名链接服务端管理：提交体不含 enrollmentLink（契约 A.2.3）
    expect(apiMock.courseApi.update.mock.calls[0][1]).not.toHaveProperty('enrollmentLink')
    expect(apiMock.courseApi.addTeachers).not.toHaveBeenCalled()
    expect(apiMock.courseApi.removeTeachers).not.toHaveBeenCalled()
    expect(showToast).toHaveBeenCalledWith('课程信息已保存', 'success')
    wrapper.unmount()
  })

  it('报名链接：只读展示 + 一键复制（clipboard）', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()

    const box = wrapper.find('[data-testid="enrollment-link-box"]')
    expect(box.exists()).toBe(true)
    const linkInput = wrapper.find('[data-testid="field-enrollment-link"]')
    // 只读展示：无 input 元素承载链接（p 文本展示）
    expect(linkInput.text()).toBe('https://apply.example.com')
    await wrapper.find('[data-testid="copy-link"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('https://apply.example.com')
    wrapper.unmount()
  })

  it('封面：image-upload 组件承载（回显已有 URL）', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-preview"]').attributes('src')).toBe(
      'https://cdn.example.com/cover.jpg',
    )
    wrapper.unmount()
  })

  it('分类：datalist 预置选项在场（允许自定义输入）', async () => {
    apiMock.courseApi.get.mockResolvedValue(course())
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    const category = wrapper.find('[data-testid="field-category"]')
    expect(category.attributes('list')).toBe('course-category-presets')
    expect(wrapper.findAll('#course-category-presets option').length).toBeGreaterThan(0)
    wrapper.unmount()
  })

  it('授课教师：编辑态回显 chip + 保存按差集调裸数组端点', async () => {
    apiMock.courseApi.get.mockResolvedValue(course({ teacherIds: ['t1', 't2'] }))
    apiMock.userApi.list.mockResolvedValue(
      pageOf([teacher('t1', '张老师'), teacher('t2', '李老师')]),
    )
    apiMock.courseApi.update.mockResolvedValue(undefined)
    apiMock.courseApi.addTeachers.mockResolvedValue(undefined)
    apiMock.courseApi.removeTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()

    // 回显：已分配教师以 chip 展示
    expect(wrapper.find('[data-testid="remote-chip-t1"]').text()).toContain('张老师')
    expect(wrapper.find('[data-testid="remote-chip-t2"]').text()).toContain('李老师')

    // 变更：移除 t1 → 保存时 DELETE [t1]（裸数组）
    await wrapper.find('[data-testid="remote-chip-remove-t1"]').trigger('click')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.removeTeachers).toHaveBeenCalledWith('c-1', ['t1'])
    expect(apiMock.courseApi.addTeachers).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('授课教师：讲师名为空时自动预填第一位教师姓名', async () => {
    apiMock.courseApi.get.mockResolvedValue(
      course({ instructorName: '', teacherIds: ['t1', 't2'] }),
    )
    apiMock.userApi.list.mockResolvedValue(
      pageOf([teacher('t1', '张老师'), teacher('t2', '李老师')]),
    )
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    expect(
      (wrapper.find('[data-testid="field-instructor"]').element as HTMLInputElement).value,
    ).toBe('张老师')
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

  it('保存成功后的后台重拉不回填覆盖用户新编辑（BUG-35 竞态回归，BUG-02 守卫核实）', async () => {
    // 首拉：课程数据回填表单；保存后 invalidate 触发的重拉挂起可控（模拟网络往返窗口期）
    apiMock.courseApi.get.mockResolvedValueOnce(course())
    let resolveRefetch: (c: CourseDTO) => void = () => {}
    apiMock.courseApi.get.mockImplementationOnce(
      () => new Promise<CourseDTO>((resolve) => (resolveRefetch = resolve)),
    )
    apiMock.courseApi.update.mockResolvedValue(undefined)
    const { wrapper } = await mountAt('/courses/c-1')
    await flushPromises()
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe(
      'RAG 实战营',
    )

    // 保存成功 → invalidate ['course-form'] → 后台重拉发出且挂起中（竞态窗口开启）
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.update).toHaveBeenCalledTimes(1)
    expect(showToast).toHaveBeenCalledWith('课程信息已保存', 'success')
    expect(apiMock.courseApi.get).toHaveBeenCalledTimes(2)

    // 重拉往返窗口期：用户立即开始下一轮编辑
    await wrapper.find('[data-testid="field-title"]').setValue('用户窗口期新标题')

    // 重拉完成：返回保存后的服务端快照。注意 vue-query 默认 structuralSharing 对
    // 深相等响应保引用（watch 不触发），须以差异字段（learningCount 变化）驱动
    // data 引用替换，才能复现真实网络往返的竞态窗口
    resolveRefetch(course({ title: 'RAG 实战营', learningCount: 5 }))
    await flushPromises()

    // 用户编辑保留，不被服务端快照覆盖（守卫失效时此断言会回到「RAG 实战营」）
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe(
      '用户窗口期新标题',
    )
    wrapper.unmount()
  })

  it('warm cache：命中 30s 未过期缓存不重拉，表单/标签/教师 chips 立即回填（BUG-02 回归）', async () => {
    // 场景：30s 内重进编辑页（同实体子路由往返），vue-query 命中未过期缓存，
    // data 在组件 watch 注册前已同步就位且不再变化——冷缓存用例（staleTime 0 恒重拉）
    // 无法覆盖该时序，须以预填缓存 + staleTime 30s 复现
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
    })
    queryClient.setQueryData(['course-form', 'c-1'], course({ teacherIds: ['t1'] }))
    queryClient.setQueryData(['teacher-pool'], [teacher('t1', '张老师')])
    const { wrapper } = await mountAt('/courses/c-1', queryClient)
    await flushPromises()

    // 未过期缓存不触发任何重拉（证明用例确处 warm-cache 路径，非冷缓存误绿）
    expect(apiMock.courseApi.get).not.toHaveBeenCalled()
    expect(apiMock.userApi.list).not.toHaveBeenCalled()
    // 表单已回填（无 immediate 时 watch 不消费初始值，表单全空）
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe(
      'RAG 实战营',
    )
    expect((wrapper.find('[data-testid="field-price"]').element as HTMLInputElement).value).toBe(
      '199',
    )
    expect(wrapper.find('[data-testid="tag-chip-RAG"]').exists()).toBe(true)
    // 教师选中集已回填，chips 回显
    expect(wrapper.find('[data-testid="remote-chip-t1"]').text()).toContain('张老师')
    wrapper.unmount()
  })
})

describe('课程概览（新建模式 /courses/new）', () => {
  it('新建：零加载请求，create 后跳转详情；报名链接提示保存后生成', async () => {
    apiMock.courseApi.create.mockResolvedValue(course({ id: 'c-9' }))
    const { wrapper, router } = await mountAt('/courses/new')
    await flushPromises()
    expect(apiMock.courseApi.get).not.toHaveBeenCalled()
    expect(apiMock.userApi.list).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('创建课程')
    // 新建态：报名链接只提示不出输入框
    expect(wrapper.find('[data-testid="enrollment-link-hint"]').text()).toBe('保存后自动生成')
    expect(wrapper.find('[data-testid="enrollment-link-box"]').exists()).toBe(false)

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

  it('新建含教师：create 后 POST 教师裸数组落库（契约 E.3）', async () => {
    const selected = [teacher('t1', '张老师'), teacher('t2', '李老师')]
    apiMock.courseApi.create.mockResolvedValue(course({ id: 'c-9' }))
    apiMock.courseApi.addTeachers.mockResolvedValue(undefined)
    const { wrapper } = await mountAt('/courses/new')
    await flushPromises()

    // 模拟 remote-select 回抛选中集（组件内部由用户交互触发，这里直调事件入口）
    const remote = wrapper.findComponent({ name: 'RemoteSelect' })
    expect(remote.exists()).toBe(true)
    remote.vm.$emit('update:modelValue', selected)
    await flushPromises()
    // 讲师名自动预填第一位教师
    expect(
      (wrapper.find('[data-testid="field-instructor"]').element as HTMLInputElement).value,
    ).toBe('张老师')

    await wrapper.find('[data-testid="field-title"]').setValue('新课程')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(apiMock.courseApi.addTeachers).toHaveBeenCalledWith('c-9', ['t1', 't2'])
    wrapper.unmount()
  })

  it('新建教师落库失败：区分提示「课程已创建」并仍跳转编辑页（BUG-07，防重复创建）', async () => {
    apiMock.courseApi.create.mockResolvedValue(course({ id: 'c-9' }))
    apiMock.courseApi.addTeachers.mockRejectedValue(new apiMock.ApiError(429, '请求过于频繁'))
    const { wrapper, router } = await mountAt('/courses/new')
    await flushPromises()

    const remote = wrapper.findComponent({ name: 'RemoteSelect' })
    remote.vm.$emit('update:modelValue', [teacher('t1', '张老师')])
    await flushPromises()
    await wrapper.find('[data-testid="field-title"]').setValue('新课程')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()

    expect(apiMock.courseApi.addTeachers).toHaveBeenCalledWith('c-9', ['t1'])
    // 课程已落库：区分提示（统一「保存失败」会诱导用户重试 create 产生重复课程）
    expect(showToast).toHaveBeenCalledWith('课程已创建，教师分配失败，可在编辑页重试分配', 'danger')
    expect(showToast).not.toHaveBeenCalledWith('课程创建成功', 'success')
    // 仍跳转编辑页：教师差集语义自然重试分配
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('course-detail')
    expect(router.currentRoute.value.params.id).toBe('c-9')
    wrapper.unmount()
  })

  it('新建 create 本身失败：维持「保存失败」提示且不跳转', async () => {
    apiMock.courseApi.create.mockRejectedValue(new Error('网络抖动'))
    const { wrapper, router } = await mountAt('/courses/new')
    await flushPromises()

    await wrapper.find('[data-testid="field-title"]').setValue('新课程')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()

    expect(showToast).toHaveBeenCalledWith('保存失败，请稍后重试', 'danger')
    expect(router.currentRoute.value.name).toBe('course-new')
    wrapper.unmount()
  })
})
