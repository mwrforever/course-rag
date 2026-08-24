import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, courseApi, enrollmentApi, scheduleApi, userApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CourseEditView from '@/views/CourseEditView.vue'

/**
 * md-editor-v3 模块级 mock：真实导出组件未声明 name 选项（VTU stubs 按名匹配失效），
 * 且内置 CodeMirror 依赖 jsdom 缺失的布局 API（getClientRects）会抛错；
 * 以同契约 textarea 桥接替代（modelValue ↔ update:modelValue/onChange），
 * 交互契约与真实编辑器一致（异步工厂：内部动态 import vue 规避 hoisting 限制）。
 */
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

import type {
  CourseContentDTO,
  CourseDTO,
  CourseScheduleVO,
  PageResponse,
  StudentDTO,
  UserDTO,
} from '@/lib/types'

/**
 * 课程编辑页测试（Task 20 核心交付，设计 §2.4.4）
 *
 * 覆盖契约：
 * 1. 新建/编辑同组件复用：/courses/new 无加载请求 → create → 跳转 /courses/{id}；
 *    /courses/:id 全量加载（课程 + 4 Tab 内容 + 排期 + 教师 + 学生）
 * 2. 基础表单：封面 URL 输入实时预览 + onError 兜底 / 标题* zod / 标签 chips /
 *    状态下拉 / 保存 PUT 提交体（全字段 + status）
 * 3. 内容 4 Tab：intro/syllabus/instructor/faq 按 sortOrder 加载；逐 Tab 独立保存，
 *    PUT body 为裸字符串（api 层已断言裸 JSON 串 + Content-Type，此处断言透传参数）
 * 4. 排期：表格渲染 + 新增 Dialog + 行内编辑 Dialog + 删除二次确认 + 提交期拦截
 * 5. 教师分配：双栏（可选=全量 TEACHER 剔除已分配）+ 搜索过滤 + POST [ids] +
 *    移除 DELETE 带 body
 * 6. 学生名单：已选列表 + 添加 Dialog（搜索多选 → 返回成功数提示「成功添加 N 名」）+
 *    行移除二次确认
 * 7. 四态：loading 骨架 / error 横幅重试 / 正常
 *
 * md-editor-v3 为重量级编辑器：测试以同签名 Stub（textarea 桥接 modelValue/
 * update:modelValue）替换渲染，交互契约与真实组件一致。
 */

/** 分页响应构造（Long total 为 string） */
function pageOf<T>(records: T[], total: string): PageResponse<T> {
  return { records, total, page: 1, size: 100 }
}

/** 课程工厂（teacherIds 可空数组，Null 为后端未返回） */
function course(id: string, over: Partial<CourseDTO> = {}): CourseDTO {
  return {
    id,
    title: 'RAG 实战营',
    description: '从零到一掌握 RAG',
    coverImage: 'https://cdn.example.com/cover.jpg',
    category: 'AI',
    instructorName: '王老师',
    price: 199,
    duration: '8 课时',
    tags: ['RAG', '入门'],
    rating: 0,
    learningCount: '42',
    enrollmentLink: 'https://apply.example.com/rag',
    status: 'ACTIVE',
    createdBy: '1001',
    createdAt: '2026-08-20T10:00:00',
    contents: null,
    schedules: null,
    teacherIds: ['t-1'],
    ...over,
  }
}

/** 内容 Tab 工厂（sortOrder 为后端序） */
function content(type: string, text: string, sortOrder: number): CourseContentDTO {
  return { contentType: type, content: text, sortOrder }
}

/** 排期工厂 */
function schedule(id: string, over: Partial<CourseScheduleVO> = {}): CourseScheduleVO {
  return {
    id,
    courseId: 'c-9',
    startDate: '2026-09-01',
    endDate: '2026-12-31',
    scheduleType: 'ONLINE',
    location: '腾讯会议',
    instructorName: '王老师',
    capacity: 50,
    enrolled: 12,
    status: 'NOT_STARTED',
    createdBy: '1001',
    createdAt: '2026-08-21T10:00:00',
    updatedAt: '2026-08-21T10:00:00',
    ...over,
  }
}

/** 学生工厂 */
function student(id: string, over: Partial<StudentDTO> = {}): StudentDTO {
  return {
    id,
    username: `stu_${id}`,
    displayName: `学生${id}`,
    enrolledAt: '2026-08-22T09:00:00',
    status: 'ACTIVE',
    ...over,
  }
}

/** 用户工厂（角色白名单 TEACHER/STUDENT/SUPER_ADMIN） */
function user(id: string, displayName: string, role: 'TEACHER' | 'STUDENT'): UserDTO {
  return {
    id,
    username: `user_${id}`,
    displayName,
    role,
    status: 'ACTIVE',
    createdAt: '2026-08-10T10:00:00',
  }
}

/** 默认编辑态接口 mock（可被用例覆写）：课程 + 4 Tab + 1 排期 + 1 学生 + 3 教师 */
function mockEditData() {
  vi.spyOn(courseApi, 'get').mockResolvedValue(course('c-9', { teacherIds: ['t-1'] }))
  vi.spyOn(courseApi, 'contents').mockResolvedValue([
    content('intro', '## 课程介绍', 1),
    content('syllabus', '## 教学大纲', 2),
    content('instructor', '## 讲师信息', 3),
    content('faq', '## 常见问题', 4),
  ])
  vi.spyOn(scheduleApi, 'listByCourse').mockResolvedValue([schedule('s-1')])
  vi.spyOn(enrollmentApi, 'students').mockResolvedValue([student('u-1')])
  vi.spyOn(userApi, 'list').mockResolvedValue(
    pageOf(
      [
        user('t-1', '王老师', 'TEACHER'),
        user('t-2', '张老师', 'TEACHER'),
        user('t-3', '李老师', 'TEACHER'),
      ],
      '3',
    ),
  )
}

/**
 * 挂载编辑页（path 可指定 /courses/new 或 /courses/:id）
 * 返回 wrapper 与 router，便于断言跳转与各接口调用次数
 */
async function mountEdit(path = '/courses/c-9') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role: 'TEACHER',
    displayName: '测试教师',
  })
  const router = createAppRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(CourseEditView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

/** 触发 md-editor 替身输入：等效真实编辑器 onChange → modelValue 更新 */
async function setEditorValue(wrapper: ReturnType<typeof mount>, text: string) {
  await wrapper.find('[data-testid="md-editor-stub"]').setValue(text)
}

describe('CourseEditView：新建模式（/courses/new 与编辑复用同组件）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('新建：不发起任何加载请求，标题为空；标题* zod 校验拦截提交', async () => {
    const getSpy = vi.spyOn(courseApi, 'get')
    const createSpy = vi.spyOn(courseApi, 'create').mockResolvedValue(course('c-new'))
    const { wrapper } = await mountEdit('/courses/new')

    // 新建模式：课程详情/内容/排期/学生/教师全部不请求
    expect(getSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="md-editor-stub"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="schedule-table"]').exists()).toBe(false)

    // 标题必填：空表单提交 → 就地报错且不发请求
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe('')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    expect(wrapper.find('[data-testid="field-error"]').text()).toContain('请输入课程标题')
    expect(createSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('新建：create 提交体（title 必填 + 可选项 + tags 数组）→ 跳转 /courses/{id} 继续编辑', async () => {
    const createSpy = vi
      .spyOn(courseApi, 'create')
      .mockResolvedValue(course('c-100', { title: '新课标题' }))
    const { wrapper, router } = await mountEdit('/courses/new')

    await wrapper.find('[data-testid="field-title"]').setValue('新课标题')
    await wrapper.find('[data-testid="field-description"]').setValue('新课简介')
    await wrapper.find('[data-testid="field-category"]').setValue('LLM')
    await wrapper.find('[data-testid="field-instructor"]').setValue('赵老师')
    await wrapper.find('[data-testid="field-price"]').setValue('399')
    await wrapper
      .find('[data-testid="field-enrollment-link"]')
      .setValue('https://apply.example.com/x')

    // 标签：回车添加 chip
    const tagInput = wrapper.find('[data-testid="tag-input"]')
    await tagInput.setValue('实战')
    await tagInput.trigger('keydown.enter')
    await tagInput.setValue('进阶')
    await tagInput.trigger('keydown.enter')
    expect(wrapper.find('[data-testid="tag-chip-实战"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tag-chip-进阶"]').exists()).toBe(true)

    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()

    // 提交体：CreateCourseRequest（tags 数组化；新建不含 status 字段）
    expect(createSpy).toHaveBeenCalledWith({
      title: '新课标题',
      description: '新课简介',
      coverImage: '',
      category: 'LLM',
      instructorName: '赵老师',
      price: 399,
      duration: '',
      tags: ['实战', '进阶'],
      enrollmentLink: 'https://apply.example.com/x',
    })
    expect(createSpy.mock.calls[0]?.[0]).not.toHaveProperty('status')
    // toast + 跳转详情路由继续编辑
    expect(document.body.textContent).toContain('课程创建成功')
    expect(router.currentRoute.value.name).toBe('course-detail')
    expect(router.currentRoute.value.params.id).toBe('c-100')
    wrapper.unmount()
  })

  it('新建：保存按钮 loading 态（提交挂起时禁用 + 文案切换）', async () => {
    let resolveCreate: (c: CourseDTO) => void = () => {}
    vi.spyOn(courseApi, 'create').mockImplementation(
      () => new Promise<CourseDTO>((resolve) => (resolveCreate = resolve)),
    )
    const { wrapper } = await mountEdit('/courses/new')

    await wrapper.find('[data-testid="field-title"]').setValue('新课')
    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()

    const saveBtn = wrapper.find('[data-testid="save-basic"]')
    expect((saveBtn.element as HTMLButtonElement).disabled).toBe(true)
    expect(saveBtn.text()).toContain('创建中')

    resolveCreate(course('c-100', { title: '新课' }))
    await flushPromises()
    wrapper.unmount()
  })
})

describe('CourseEditView：编辑加载与基础表单', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('编辑加载：课程字段回填 + 4 Tab 内容按 sortOrder + 排期/学生/教师数据', async () => {
    mockEditData()
    const { wrapper } = await mountEdit('/courses/c-9')

    // 基础字段回填
    expect((wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value).toBe(
      'RAG 实战营',
    )
    expect(
      (wrapper.find('[data-testid="field-description"]').element as HTMLTextAreaElement).value,
    ).toBe('从零到一掌握 RAG')
    expect((wrapper.find('[data-testid="field-cover"]').element as HTMLInputElement).value).toBe(
      'https://cdn.example.com/cover.jpg',
    )
    expect((wrapper.find('[data-testid="field-price"]').element as HTMLInputElement).value).toBe(
      '199',
    )

    // 4 Tab 内容：intro 默认激活，编辑器回显对应正文
    expect(wrapper.find('[data-testid="md-editor-stub"]').exists()).toBe(true)
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('## 课程介绍')
    // 切换 Tab：syllabus 回显各自内容
    await wrapper.find('[data-testid="tab-syllabus"]').trigger('click')
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('## 教学大纲')

    // 排期表格（起止/类型/地点/讲师/容量/已报）
    const scheduleRow = wrapper.find('[data-testid="schedule-row-s-1"]')
    expect(scheduleRow.exists()).toBe(true)
    expect(scheduleRow.text()).toContain('2026-09-01')
    expect(scheduleRow.text()).toContain('2026-12-31')
    expect(scheduleRow.text()).toContain('ONLINE')
    expect(scheduleRow.text()).toContain('腾讯会议')
    expect(scheduleRow.text()).toContain('王老师')
    expect(scheduleRow.text()).toContain('50')
    expect(scheduleRow.text()).toContain('12')

    // 学生名单（username/displayName/enrolledAt）
    const studentRow = wrapper.find('[data-testid="student-row-u-1"]')
    expect(studentRow.text()).toContain('stu_u-1')
    expect(studentRow.text()).toContain('学生u-1')

    // 教师双栏：已分配（t-1）与可选（t-2/t-3 剔除 t-1）
    expect(wrapper.find('[data-testid="teacher-assigned-t-1"]').text()).toContain('王老师')
    expect(wrapper.find('[data-testid="teacher-assigned-t-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="teacher-available-t-2"]').text()).toContain('张老师')
    expect(wrapper.find('[data-testid="teacher-available-t-3"]').text()).toContain('李老师')
    expect(wrapper.find('[data-testid="teacher-available-t-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('封面 URL：实时预览 + onError 兜底 + 改 URL 恢复', async () => {
    mockEditData()
    const { wrapper } = await mountEdit('/courses/c-9')

    // 初始 URL 预览
    const preview = wrapper.find('[data-testid="cover-preview"]')
    expect(preview.exists()).toBe(true)
    expect(preview.attributes('src')).toBe('https://cdn.example.com/cover.jpg')
    expect(wrapper.find('[data-testid="cover-fallback"]').exists()).toBe(false)

    // onError 兜底：破图切换占位（无上传接口 G11）
    await preview.trigger('error')
    expect(wrapper.find('[data-testid="cover-preview"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cover-fallback"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="cover-fallback"]').text()).toContain('封面预览')

    // 修改 URL：错误态复位，预览新图
    await wrapper.find('[data-testid="field-cover"]').setValue('https://cdn.example.com/new.jpg')
    expect(wrapper.find('[data-testid="cover-fallback"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cover-preview"]').attributes('src')).toBe(
      'https://cdn.example.com/new.jpg',
    )
    wrapper.unmount()
  })

  it('基础信息保存：PUT 提交体全字段 + 状态下拉切 ARCHIVED + toast', async () => {
    mockEditData()
    const updateSpy = vi.spyOn(courseApi, 'update').mockResolvedValue()
    const { wrapper } = await mountEdit('/courses/c-9')

    // 状态下拉：默认回填 ACTIVE，切换 ARCHIVED
    expect((wrapper.find('[data-testid="field-status"]').element as HTMLSelectElement).value).toBe(
      'ACTIVE',
    )
    await wrapper.find('[data-testid="field-status"]').setValue('ARCHIVED')
    await wrapper.find('[data-testid="field-price"]').setValue('259.5')

    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()

    // UpdateCourseRequest：全字段透传（tags 保持数组；price 数值化）
    expect(updateSpy).toHaveBeenCalledWith('c-9', {
      title: 'RAG 实战营',
      description: '从零到一掌握 RAG',
      coverImage: 'https://cdn.example.com/cover.jpg',
      category: 'AI',
      instructorName: '王老师',
      price: 259.5,
      duration: '8 课时',
      tags: ['RAG', '入门'],
      enrollmentLink: 'https://apply.example.com/rag',
      status: 'ARCHIVED',
    })
    expect(document.body.textContent).toContain('课程信息已保存')
    wrapper.unmount()
  })

  it('标签：X 删除 chip 后提交体同步移除', async () => {
    mockEditData()
    const updateSpy = vi.spyOn(courseApi, 'update').mockResolvedValue()
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="tag-remove-入门"]').trigger('click')
    expect(wrapper.find('[data-testid="tag-chip-入门"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="tag-chip-RAG"]').exists()).toBe(true)

    await wrapper.find('[data-testid="save-basic"]').trigger('click')
    await flushPromises()
    expect(updateSpy.mock.calls[0]?.[1]?.tags).toEqual(['RAG'])
    wrapper.unmount()
  })

  it('四态：loading 骨架；error 横幅 + 重试恢复', async () => {
    // loading：课程接口挂起 → 骨架在场，基础表单不可见
    vi.spyOn(courseApi, 'get').mockReturnValue(new Promise(() => {}))
    const first = await mountEdit('/courses/c-9')
    expect(first.wrapper.find('[data-testid="edit-skeleton"]').exists()).toBe(true)
    expect(first.wrapper.find('[data-testid="save-basic"]').exists()).toBe(false)
    first.wrapper.unmount()

    // error：503 统一降级 → 重试恢复
    vi.restoreAllMocks()
    mockEditData()
    vi.spyOn(courseApi, 'get')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(course('c-9', { teacherIds: ['t-1'] }))
    const second = await mountEdit('/courses/c-9')
    expect(second.wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')

    await second.wrapper.find('[data-testid="retry-course"]').trigger('click')
    await flushPromises()
    expect(second.wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(
      (second.wrapper.find('[data-testid="field-title"]').element as HTMLInputElement).value,
    ).toBe('RAG 实战营')
    second.wrapper.unmount()
  })
})

describe('CourseEditView：内容 4 Tab 独立保存', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('逐 Tab 独立保存：PUT /contents/{contentType} 携带裸字符串正文 + 各自保存 toast', async () => {
    mockEditData()
    const updateContentSpy = vi.spyOn(courseApi, 'updateContent').mockResolvedValue()
    const { wrapper } = await mountEdit('/courses/c-9')

    // intro Tab：改写后保存 → 仅 intro 请求（裸字符串，无对象包裹）
    await setEditorValue(wrapper, '# 新版课程介绍')
    await wrapper.find('[data-testid="save-content"]').trigger('click')
    await flushPromises()
    expect(updateContentSpy).toHaveBeenCalledWith('c-9', 'intro', '# 新版课程介绍')
    expect(document.body.textContent).toContain('课程介绍已保存')

    // 切 syllabus Tab：各自内容互不串写，保存走对应 contentType
    await wrapper.find('[data-testid="tab-syllabus"]').trigger('click')
    await setEditorValue(wrapper, '## 第一章 向量化')
    await wrapper.find('[data-testid="save-content"]').trigger('click')
    await flushPromises()
    expect(updateContentSpy).toHaveBeenLastCalledWith('c-9', 'syllabus', '## 第一章 向量化')
    expect(updateContentSpy).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('内容加载失败态：正文区错误横幅 + 重试恢复', async () => {
    mockEditData()
    vi.spyOn(courseApi, 'contents')
      .mockRejectedValueOnce(new ApiError(500, '内容加载失败', 500))
      .mockResolvedValue([content('intro', '## 重试后的内容', 1)])
    const { wrapper } = await mountEdit('/courses/c-9')

    expect(wrapper.find('[data-testid="contents-error"]').text()).toContain('内容加载失败')
    expect(wrapper.find('[data-testid="md-editor-stub"]').exists()).toBe(false)

    await wrapper.find('[data-testid="retry-contents"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="contents-error"]').exists()).toBe(false)
    expect(
      (wrapper.find('[data-testid="md-editor-stub"]').element as HTMLTextAreaElement).value,
    ).toBe('## 重试后的内容')
    wrapper.unmount()
  })
})

describe('CourseEditView：排期 Section（新增/编辑/删除）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('新增 Dialog：提交体 CreateScheduleRequest → toast → 关闭并刷新', async () => {
    mockEditData()
    const createSpy = vi
      .spyOn(scheduleApi, 'create')
      .mockResolvedValue(schedule('s-2', { startDate: '2027-01-01' }))
    const listSpy = vi.mocked(scheduleApi.listByCourse)
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-schedule"]').trigger('click')
    const dialog = wrapper.find('[data-testid="schedule-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('新增排期')

    await dialog.find('[data-testid="schedule-start"]').setValue('2027-01-01')
    await dialog.find('[data-testid="schedule-end"]').setValue('2027-03-31')
    await dialog.find('[data-testid="schedule-type"]').setValue('OFFLINE')
    await dialog.find('[data-testid="schedule-location"]').setValue('上海教室')
    await dialog.find('[data-testid="schedule-instructor"]').setValue('刘老师')
    await dialog.find('[data-testid="schedule-capacity"]').setValue('30')

    await dialog.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith('c-9', {
      startDate: '2027-01-01',
      endDate: '2027-03-31',
      scheduleType: 'OFFLINE',
      location: '上海教室',
      instructorName: '刘老师',
      capacity: 30,
    })
    expect(document.body.textContent).toContain('排期已保存')
    expect(wrapper.find('[data-testid="schedule-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('新增 Dialog 校验：起止日期必填，就地报错不发请求', async () => {
    mockEditData()
    const createSpy = vi.spyOn(scheduleApi, 'create')
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-schedule"]').trigger('click')
    const dialog = wrapper.find('[data-testid="schedule-dialog"]')
    await dialog.find('[data-testid="submit-schedule"]').trigger('click')

    expect(dialog.find('[data-testid="schedule-error"]').text()).toContain('请输入开始日期')
    expect(createSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('行内编辑 Dialog：回填 → update 提交体（可选字段仅带表单值）→ 刷新', async () => {
    mockEditData()
    const updateSpy = vi.spyOn(scheduleApi, 'update').mockResolvedValue()
    const listSpy = vi.mocked(scheduleApi.listByCourse)
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="op-schedule-edit-s-1"]').trigger('click')
    const dialog = wrapper.find('[data-testid="schedule-dialog"]')
    expect(dialog.text()).toContain('编辑排期')
    expect((dialog.find('[data-testid="schedule-start"]').element as HTMLInputElement).value).toBe(
      '2026-09-01',
    )
    expect((dialog.find('[data-testid="schedule-type"]').element as HTMLSelectElement).value).toBe(
      'ONLINE',
    )

    await dialog.find('[data-testid="schedule-end"]').setValue('2027-02-28')
    await dialog.find('[data-testid="schedule-location"]').setValue('新地点')
    await dialog.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()

    expect(updateSpy).toHaveBeenCalledWith('s-1', {
      startDate: '2026-09-01',
      endDate: '2027-02-28',
      scheduleType: 'ONLINE',
      location: '新地点',
      instructorName: '王老师',
      capacity: 50,
    })
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('删除二次确认：取消不调接口；确认 remove → toast → 刷新', async () => {
    mockEditData()
    const removeSpy = vi.spyOn(scheduleApi, 'remove').mockResolvedValue()
    const listSpy = vi.mocked(scheduleApi.listByCourse)
    const { wrapper } = await mountEdit('/courses/c-9')

    // 取消路径
    await wrapper.find('[data-testid="op-schedule-del-s-1"]').trigger('click')
    expect(wrapper.find('[data-testid="schedule-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="cancel-schedule-del"]').trigger('click')
    expect(removeSpy).not.toHaveBeenCalled()

    // 确认路径
    await wrapper.find('[data-testid="op-schedule-del-s-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-schedule-del"]').trigger('click')
    await flushPromises()
    expect(removeSpy).toHaveBeenCalledWith('s-1')
    expect(document.body.textContent).toContain('排期已删除')
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('新增 Dialog：提交期间 Esc/遮罩/取消全拦截，提交完成后关闭（carry 交互规范）', async () => {
    mockEditData()
    let resolveCreate: (s: CourseScheduleVO) => void = () => {}
    const createSpy = vi
      .spyOn(scheduleApi, 'create')
      .mockImplementation(
        () => new Promise<CourseScheduleVO>((resolve) => (resolveCreate = resolve)),
      )
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-schedule"]').trigger('click')
    const dialog = wrapper.find('[data-testid="schedule-dialog"]')
    await dialog.find('[data-testid="schedule-start"]').setValue('2027-01-01')
    await dialog.find('[data-testid="schedule-end"]').setValue('2027-03-31')
    await dialog.find('[data-testid="submit-schedule"]').trigger('click')
    await flushPromises()

    // 提交中：取消禁用 + Esc/遮罩点击被拦截
    expect(
      (dialog.find('[data-testid="cancel-schedule"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
    await dialog.trigger('keydown', { key: 'Escape' })
    await dialog.trigger('click')
    expect(wrapper.find('[data-testid="schedule-dialog"]').exists()).toBe(true)
    expect(createSpy).toHaveBeenCalledTimes(1)

    // 完成：Dialog 关闭
    resolveCreate(schedule('s-2'))
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('CourseEditView：教师分配双栏', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('可选教师搜索过滤：按显示名/用户名客户端过滤（GET /users 无 keyword 参数）', async () => {
    mockEditData()
    const listSpy = vi.mocked(userApi.list)
    const { wrapper } = await mountEdit('/courses/c-9')

    // 教师池一次拉取：role=TEACHER + size=100（选择器只列 TEACHER 兜底 R18）
    expect(listSpy).toHaveBeenCalledWith({ role: 'TEACHER', size: 100 })

    expect(wrapper.find('[data-testid="teacher-available-t-2"]').exists()).toBe(true)
    await wrapper.find('[data-testid="teacher-search"]').setValue('张')
    expect(wrapper.find('[data-testid="teacher-available-t-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="teacher-available-t-3"]').exists()).toBe(false)

    await wrapper.find('[data-testid="teacher-search"]').setValue('error')
    expect(wrapper.find('[data-testid="teacher-available-t-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="teacher-available-empty"]').text()).toContain(
      '没有匹配的教师',
    )
    wrapper.unmount()
  })

  it('分配所选：POST {ids} 数组 → toast → 重新拉取课程刷新双栏', async () => {
    mockEditData()
    const addSpy = vi.spyOn(courseApi, 'addTeachers').mockResolvedValue()
    const getSpy = vi.mocked(courseApi.get)
    const { wrapper } = await mountEdit('/courses/c-9')

    // 勾选 t-2 + t-3 分配
    await wrapper.find('[data-testid="teacher-check-t-2"]').setValue(true)
    await wrapper.find('[data-testid="teacher-check-t-3"]').setValue(true)
    await wrapper.find('[data-testid="teacher-assign"]').trigger('click')
    await flushPromises()

    expect(addSpy).toHaveBeenCalledWith('c-9', ['t-2', 't-3'])
    expect(document.body.textContent).toContain('教师分配成功')
    // 分配后重新拉取课程（teacherIds 更新驱动双栏重排）
    expect(getSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('移除教师：DELETE 带 body [id]（axios data 写法）→ toast → 刷新', async () => {
    mockEditData()
    const removeSpy = vi.spyOn(courseApi, 'removeTeachers').mockResolvedValue()
    const getSpy = vi.mocked(courseApi.get)
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="teacher-remove-t-1"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('c-9', ['t-1'])
    expect(document.body.textContent).toContain('已移除教师')
    expect(getSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('CourseEditView：学生名单', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('添加学生 Dialog：搜索过滤 + 多选 + POST {studentIds} → 「成功添加 N 名」', async () => {
    mockEditData()
    // 候选池：仅 STUDENT 角色（教师过滤走 R18 兜底）且剔除已报名的 u-1
    vi.spyOn(userApi, 'list').mockResolvedValue(
      pageOf(
        [
          user('t-1', '王老师', 'TEACHER'),
          user('s-2', '小明', 'STUDENT'),
          user('s-3', '小红', 'STUDENT'),
        ],
        '3',
      ),
    )
    const addSpy = vi.spyOn(enrollmentApi, 'addStudents').mockResolvedValue(2)
    const studentsSpy = vi.mocked(enrollmentApi.students)
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-students"]').trigger('click')
    const dialog = wrapper.find('[data-testid="student-dialog"]')
    expect(dialog.exists()).toBe(true)

    // 选择器只列 STUDENT 且剔除已报名（u-1）：教师与已报名学生均不出现
    expect(dialog.find('[data-testid="student-option-s-2"]').text()).toContain('小明')
    expect(dialog.find('[data-testid="student-option-s-3"]').text()).toContain('小红')
    expect(dialog.find('[data-testid="student-option-u-1"]').exists()).toBe(false)
    expect(dialog.find('[data-testid="student-option-t-1"]').exists()).toBe(false)

    // 搜索过滤（客户端过滤：后端 /users 无 keyword 参数）
    await dialog.find('[data-testid="student-search"]').setValue('小红')
    expect(dialog.find('[data-testid="student-option-s-2"]').exists()).toBe(false)
    expect(dialog.find('[data-testid="student-option-s-3"]').exists()).toBe(true)
    await dialog.find('[data-testid="student-search"]').setValue('')

    // 多选提交：按钮计数 + POST {studentIds}
    await dialog.find('[data-testid="student-option-s-2"]').trigger('click')
    await dialog.find('[data-testid="student-option-s-3"]').trigger('click')
    expect(dialog.find('[data-testid="submit-students"]').text()).toContain('2')
    await dialog.find('[data-testid="submit-students"]').trigger('click')
    await flushPromises()

    expect(addSpy).toHaveBeenCalledWith('c-9', { studentIds: ['s-2', 's-3'] })
    // 成功数提示（后端返回 Integer 成功数，可能部分成功）
    expect(document.body.textContent).toContain('成功添加 2 名')
    expect(wrapper.find('[data-testid="student-dialog"]').exists()).toBe(false)
    expect(studentsSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('部分成功：选中 2 名实际成功 1 名 → 按返回数提示「成功添加 1 名」', async () => {
    mockEditData()
    vi.spyOn(userApi, 'list').mockResolvedValue(
      pageOf(
        [
          user('t-1', '王老师', 'TEACHER'),
          user('s-2', '小明', 'STUDENT'),
          user('s-3', '小红', 'STUDENT'),
        ],
        '3',
      ),
    )
    vi.spyOn(enrollmentApi, 'addStudents').mockResolvedValue(1)
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-students"]').trigger('click')
    const dialog = wrapper.find('[data-testid="student-dialog"]')
    await dialog.find('[data-testid="student-option-s-2"]').trigger('click')
    await dialog.find('[data-testid="student-option-s-3"]').trigger('click')
    await dialog.find('[data-testid="submit-students"]').trigger('click')
    await flushPromises()

    // 成功数提示：以本次操作的最后一条 toast 断言（body 可能残留前序用例 toast，
    // 禁止 not.toContain 依赖全量文本，契约是本次返回数驱动文案）
    const toasts = document.querySelectorAll('[data-toast]')
    expect(toasts[toasts.length - 1]?.textContent).toBe('成功添加 1 名')
    wrapper.unmount()
  })

  it('行移除：二次确认（danger）→ removeStudent → toast → 刷新', async () => {
    mockEditData()
    const removeSpy = vi.spyOn(enrollmentApi, 'removeStudent').mockResolvedValue()
    const studentsSpy = vi.mocked(enrollmentApi.students)
    const { wrapper } = await mountEdit('/courses/c-9')

    // 取消路径
    await wrapper.find('[data-testid="student-remove-u-1"]').trigger('click')
    expect(wrapper.find('[data-testid="student-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="cancel-student-del"]').trigger('click')
    expect(removeSpy).not.toHaveBeenCalled()

    // 确认路径
    await wrapper.find('[data-testid="student-remove-u-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-student-del"]').trigger('click')
    await flushPromises()
    expect(removeSpy).toHaveBeenCalledWith('c-9', 'u-1')
    expect(document.body.textContent).toContain('已移除学生')
    expect(studentsSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('添加学生 Dialog：提交中禁止关闭（Esc/遮罩拦截），完成后关闭', async () => {
    mockEditData()
    vi.spyOn(userApi, 'list').mockResolvedValue(
      pageOf([user('t-1', '王老师', 'TEACHER'), user('s-2', '小明', 'STUDENT')], '2'),
    )
    let resolveAdd: (n: number) => void = () => {}
    const addSpy = vi
      .spyOn(enrollmentApi, 'addStudents')
      .mockImplementation(() => new Promise<number>((resolve) => (resolveAdd = resolve)))
    const { wrapper } = await mountEdit('/courses/c-9')

    await wrapper.find('[data-testid="add-students"]').trigger('click')
    const dialog = wrapper.find('[data-testid="student-dialog"]')
    await dialog.find('[data-testid="student-option-s-2"]').trigger('click')
    await dialog.find('[data-testid="submit-students"]').trigger('click')
    await flushPromises()

    expect(
      (dialog.find('[data-testid="cancel-students"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
    await dialog.trigger('keydown', { key: 'Escape' })
    await dialog.trigger('click')
    expect(wrapper.find('[data-testid="student-dialog"]').exists()).toBe(true)
    expect(addSpy).toHaveBeenCalledTimes(1)

    resolveAdd(1)
    await flushPromises()
    expect(wrapper.find('[data-testid="student-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
