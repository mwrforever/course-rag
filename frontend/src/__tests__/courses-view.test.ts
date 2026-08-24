import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, courseApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import CoursesView from '@/views/CoursesView.vue'

import type { CourseDTO, PageResponse } from '@/lib/types'

/**
 * 课程列表页测试（Task 20 核心交付）
 *
 * 覆盖契约（设计 §2.4.4 课程列表 + task-20 brief）：
 * 1. 表格列：封面缩略 48px（无封面占位）/ 名称 / 讲师 / 价格 / 课时 / 学生数 /
 *    状态 Badge（ACTIVE emerald / ARCHIVED slate）/ 操作（编辑·删除）
 * 2. 编辑跳转 /courses/{id}、新建入口 /courses/new
 * 3. 删除：危险操作二次确认（danger 实底）→ remove → toast → 刷新；提交期间禁关闭
 * 4. 分页：共 N 条 + 上一页/下一页（页参数 page/size，total 为 Long 字符串）
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 契约要点：id/total/learningCount 为 Long 字符串铁律；价格/课时等数字域 tabular-nums。
 */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 课程工厂（默认 ACTIVE + 封面图，便于覆盖各表格列） */
function course(id: string, over: Partial<CourseDTO> = {}): CourseDTO {
  return {
    id,
    title: `课程-${id}`,
    description: '课程简介',
    coverImage: `https://cdn.example.com/${id}.jpg`,
    category: 'AI',
    instructorName: '王老师',
    price: 199,
    duration: '8 课时',
    tags: ['RAG'],
    rating: 0,
    learningCount: '42',
    enrollmentLink: '',
    status: 'ACTIVE',
    createdBy: '1001',
    createdAt: '2026-08-20T10:00:00',
    contents: null,
    schedules: null,
    teacherIds: null,
    ...over,
  }
}

/** 挂载课程列表：pinia + 路由（准备就绪至 /courses） */
async function mountCourses() {
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
  await router.push('/courses')
  await router.isReady()
  const wrapper = mount(CoursesView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('CoursesView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染封面缩略 48px / 名称 / 讲师 / 价格 / 课时 / 学生数 / 状态 / 操作', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(
      pageOf([course('c-1'), course('c-2', { status: 'ARCHIVED' })], '2'),
    )
    const { wrapper } = await mountCourses()

    // 封面缩略：48px（h-12 w-12）+ 原图直出
    const cover = wrapper.find('[data-testid="cover-c-1"]')
    expect(cover.exists()).toBe(true)
    expect(cover.attributes('src')).toBe('https://cdn.example.com/c-1.jpg')
    expect(cover.classes()).toContain('h-12')
    expect(cover.classes()).toContain('w-12')

    // 名称 / 讲师 / 课时 / 学生数
    expect(wrapper.find('[data-testid="row-c-1"]').text()).toContain('课程-c-1')
    expect(wrapper.find('[data-testid="row-c-1"]').text()).toContain('王老师')
    expect(wrapper.find('[data-testid="row-c-1"]').text()).toContain('8 课时')

    // 数字域 tabular-nums：价格与学生学习数（Long 字符串直出）
    const priceCell = wrapper.find('[data-testid="course-price-c-1"]')
    expect(priceCell.text()).toContain('¥199')
    expect(priceCell.classes()).toContain('tabular-nums')
    const learnersCell = wrapper.find('[data-testid="course-learners-c-1"]')
    expect(learnersCell.text()).toContain('42')
    expect(learnersCell.classes()).toContain('tabular-nums')

    // 状态 Badge：ACTIVE emerald / ARCHIVED 中性（设计 §2.5）
    const activeBadge = wrapper.find('[data-testid="course-status-c-1"]')
    expect(activeBadge.text()).toContain('ACTIVE')
    expect(activeBadge.classes()).toContain('bg-emerald-50')
    const archivedBadge = wrapper.find('[data-testid="course-status-c-2"]')
    expect(archivedBadge.text()).toContain('ARCHIVED')
    expect(archivedBadge.classes()).toContain('bg-slate-100')

    // 操作列：编辑 / 删除
    expect(wrapper.find('[data-testid="op-edit-c-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-delete-c-1"]').exists()).toBe(true)

    // 分页器总数
    expect(wrapper.text()).toContain('共 2 条')
    wrapper.unmount()
  })

  it('无封面兜底：渲染占位而非破图 img', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf([course('c-1', { coverImage: '' })], '1'))
    const { wrapper } = await mountCourses()

    expect(wrapper.find('[data-testid="cover-c-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="cover-fallback-c-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('编辑：点击操作列跳转 /courses/{id}；新建：页头跳转 /courses/new', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf([course('c-9')], '1'))
    const { wrapper, router } = await mountCourses()

    // 编辑 → course-detail 路由（同一编辑组件复用新建/详情两路由）；
    // 目标组件为懒加载 chunk，导航异步完成需 waitFor 轮询收敛（flushPromises 只清微任务）
    await wrapper.find('[data-testid="op-edit-c-9"]').trigger('click')
    await vi.waitFor(() => {
      expect(router.currentRoute.value.name).toBe('course-detail')
    })
    expect(router.currentRoute.value.params.id).toBe('c-9')

    // 重新挂载回列表（路由已离开，重新推进）
    const router2 = createAppRouter()
    await router2.push('/courses')
    await router2.isReady()
    const wrapper2 = mount(CoursesView, { global: { plugins: [createPinia(), router2] } })
    await flushPromises()
    await wrapper2.find('[data-testid="create-course"]').trigger('click')
    await vi.waitFor(() => {
      expect(router2.currentRoute.value.name).toBe('course-new')
    })
    wrapper.unmount()
    wrapper2.unmount()
  })
})

describe('CoursesView：分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('翻页携带 page 参数；上一页/下一页越界禁用；页码文本正确', async () => {
    const listSpy = vi.spyOn(courseApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([course(`c-${p}`)], '25', p)
    })
    const { wrapper } = await mountCourses()

    expect(wrapper.text()).toContain('第 1 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 1, size: 10 })

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.text()).toContain('第 2 / 3 页')
    expect(wrapper.find('[data-testid="row-c-2"]').exists()).toBe(true)
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      false,
    )

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect((wrapper.find('[data-testid="next-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )
    wrapper.unmount()
  })
})

describe('CoursesView：删除（二次确认）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('取消：不调接口，Dialog 关闭', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf([course('c-1')], '1'))
    const removeSpy = vi.spyOn(courseApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountCourses()

    const btn = wrapper.find('[data-testid="op-delete-c-1"]')
    expect(btn.classes()).toContain('bg-danger')
    await btn.trigger('click')
    const dialog = wrapper.find('[data-testid="course-del-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('删除课程')

    await wrapper.find('[data-testid="cancel-course-del"]').trigger('click')
    expect(removeSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="course-del-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('确认：remove(id) → toast → 关闭并刷新列表', async () => {
    const listSpy = vi
      .spyOn(courseApi, 'list')
      .mockResolvedValueOnce(pageOf([course('c-1'), course('c-2')], '2'))
      .mockResolvedValueOnce(pageOf([course('c-2')], '1'))
    const removeSpy = vi.spyOn(courseApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountCourses()

    await wrapper.find('[data-testid="op-delete-c-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-course-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('c-1')
    expect(document.body.textContent).toContain('课程已删除')
    expect(wrapper.find('[data-testid="course-del-dialog"]').exists()).toBe(false)
    // 刷新后行消失
    expect(wrapper.find('[data-testid="row-c-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="row-c-2"]').exists()).toBe(true)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('提交期间禁止取消/Esc/遮罩关闭（submitting 拦截），完成后正常关闭', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf([course('c-1')], '1'))
    let resolveDelete: () => void = () => {}
    const removeSpy = vi
      .spyOn(courseApi, 'remove')
      .mockImplementation(() => new Promise<void>((resolve) => (resolveDelete = resolve)))
    const { wrapper } = await mountCourses()

    await wrapper.find('[data-testid="op-delete-c-1"]').trigger('click')
    const dialog = wrapper.find('[data-testid="course-del-dialog"]')
    await dialog.find('[data-testid="confirm-course-del"]').trigger('click')
    await flushPromises()

    expect(
      (dialog.find('[data-testid="cancel-course-del"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
    await dialog.trigger('keydown', { key: 'Escape' })
    await dialog.trigger('click')
    expect(wrapper.find('[data-testid="course-del-dialog"]').exists()).toBe(true)
    expect(removeSpy).toHaveBeenCalledTimes(1)

    resolveDelete()
    await flushPromises()
    expect(wrapper.find('[data-testid="course-del-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('失败：danger toast 且 Dialog 保留可重试', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf([course('c-1')], '1'))
    vi.spyOn(courseApi, 'remove').mockRejectedValue(new ApiError(500, '删除失败', 500))
    const { wrapper } = await mountCourses()

    await wrapper.find('[data-testid="op-delete-c-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-course-del"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('删除失败')
    expect(wrapper.find('[data-testid="course-del-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('CoursesView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(courseApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountCourses()

    expect(wrapper.find('[data-testid="course-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="course-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(courseApi, 'list')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([course('c-1')], '1'))
    const { wrapper } = await mountCourses()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')

    await wrapper.find('[data-testid="retry-courses"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="course-table"]').text()).toContain('课程-c-1')
    wrapper.unmount()
  })

  it('empty：空态文案 + 新建入口（禁裸「暂无数据」）', async () => {
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf<CourseDTO>([], '0'))
    const { wrapper } = await mountCourses()

    expect(wrapper.text()).toContain('还没有课程')
    expect(wrapper.find('[data-testid="course-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="create-course-empty"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
