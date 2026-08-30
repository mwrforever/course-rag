import { flushPromises, mount } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { vReveal } from '@/directives/reveal'
import { ApiError, chunkApi, courseApi, knowledgeBaseApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import ChunksView from '@/views/ChunksView.vue'

import type { PageResponse } from '@/lib/types'
import type { CourseDTO, DocumentChunkVO, KnowledgeBaseVO } from '@/lib/types'

/**
 * 分片修正工作台测试（Task 19 核心交付）
 *
 * 覆盖契约（设计 §2.4.3 + task-19 brief）：
 * 1. 默认 pending 列表：内容预览 2 行截断+全文 tooltip / 所属文档（docId 短格式+kbId 原文）/
 *    collection_type Badge（TECHNICAL_QA 蓝 / COURSE_INFO 紫 / null 未分类）/
 *    courseId（「通用」灰 Badge 或 id 短格式）/ 操作（上下文/编辑）
 * 2. 筛选与分页：kbId 下拉 + docId 输入，仅后端两参；变更重置页码
 * 3. 勾选状态管理：行勾选/全选/部分选择/批量按钮出现条件与计数
 * 4. 批量修正 Dialog：表单校验（全「不改」禁提交）/ 课程远程搜索选择器 /
 *    提交体 {ids, collectionType?, courseId?}（不改省略、通用 DEFAULT 显式 'DEFAULT'）/ loading 态
 * 5. 标记已修正：二次确认（危险按钮 + 不可撤销文案）→ POST batch-corrected → 行消失
 * 6. 编辑 Drawer：mono textarea 全文 + 元数据只读区 + 保存 PUT 体 + 重向量化提示
 * 7. 上下文 Drawer：parent/prev/current/next 时间线，null 节点不渲染，失败可重试
 * 8. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 数据源为 vue-query：独立 QueryClient（retry:false）+ 接口层 vi.spyOn 内存 mock。
 */

/** 分页响应构造（Long total 为 string，page/size 为 number） */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 待修正分片工厂（默认 docId 后 6 位呈短格式 #123456） */
function chunk(id: string, over: Partial<DocumentChunkVO> = {}): DocumentChunkVO {
  return {
    id,
    docId: 'doc-123456',
    kbId: 'kb-1',
    chunkIndex: 3,
    content: `分片内容-${id}，用于预览与编辑`,
    headingPath: '第一章 · RAG 概述',
    parentTitle: '',
    startPage: 2,
    endPage: 3,
    tokenCount: 128,
    collectionType: null,
    courseId: null,
    metadataJson: '',
    milvusPk: '',
    parentChunkId: null,
    prevChunkId: null,
    nextChunkId: null,
    charOffsetStart: 1024,
    charOffsetEnd: 2048,
    correctionStatus: 'PENDING',
    createdAt: '2026-08-24T10:00:00',
    updatedAt: '2026-08-24T10:00:00',
    ...over,
  }
}

/** 知识库选项 mock（筛选下拉来源） */
function kb(id: string, name: string): KnowledgeBaseVO {
  return {
    id,
    name,
    description: '',
    status: 'ACTIVE',
    createdBy: '1001',
    createdAt: '2026-08-20T10:00:00',
    updatedAt: '2026-08-20T10:00:00',
  }
}

/** 挂载分片工作台：独立 QueryClient + pinia + 路由（TEACHER 登录态） */
async function mountChunks() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  const wrapper = mount(ChunksView, {
    // reveal 指令：main.ts 全局注册，直挂视图的测试需显式提供（滚动入场指令）
    global: {
      plugins: [[VueQueryPlugin, { queryClient }], pinia, router],
      directives: { reveal: vReveal },
    },
  })
  await router.isReady()
  await flushPromises()
  return { wrapper, router, queryClient }
}

describe('ChunksView：列表渲染与四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染内容预览（2 行截断 + 全文 tooltip）/ 所属文档 / collectionType Badge 三态 / courseId（null 与 DEFAULT 双形态）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(
      pageOf(
        [
          chunk('c-1', { collectionType: 'TECHNICAL_QA', courseId: 'course-100001' }),
          chunk('c-2', { collectionType: 'COURSE_INFO' }),
          chunk('c-3', { content: '未分类长内容'.repeat(30) }),
          // 后端实测形态：通用资料库 course_id 为字符串 'DEFAULT'（非 null）
          chunk('c-4', { courseId: 'DEFAULT' }),
        ],
        '4',
      ),
    )
    const { wrapper } = await mountChunks()

    expect(wrapper.find('[data-testid="chunk-skeleton"]').exists()).toBe(false)
    const table = wrapper.find('[data-testid="chunk-table"]')
    expect(table.exists()).toBe(true)

    // 内容预览：line-clamp-2 截断 + title 全文 tooltip
    const contentCell = wrapper.find('[data-testid="chunk-content-c-3"]')
    expect(contentCell.classes()).toContain('line-clamp-2')
    expect(contentCell.attributes('title')).toBe('未分类长内容'.repeat(30))

    // 所属文档：docId 短格式（title 全文）+ kbId 原文
    const docCell = wrapper.find('[data-testid="chunk-doc-c-1"]')
    expect(docCell.text()).toContain('#123456')
    expect(docCell.text()).toContain('kb-1')
    expect(docCell.attributes('title')).toBe('doc-123456')

    // collection_type Badge：TECHNICAL_QA 蓝 / COURSE_INFO 紫 / null 灰
    const badgeQa = wrapper.find('[data-testid="chunk-collection-c-1"]')
    expect(badgeQa.text()).toContain('TECHNICAL_QA')
    expect(badgeQa.classes()).toContain('bg-brand-soft')
    const badgeInfo = wrapper.find('[data-testid="chunk-collection-c-2"]')
    expect(badgeInfo.text()).toContain('COURSE_INFO')
    expect(badgeInfo.classes()).toContain('bg-violet-50')
    expect(wrapper.find('[data-testid="chunk-collection-c-3"]').text()).toContain('未分类')

    // courseId 非空：显示 id 短格式（title 全文）
    const courseCell = wrapper.find('[data-testid="chunk-course-c-1"]')
    expect(courseCell.text()).toContain('#100001')
    expect(courseCell.attributes('title')).toBe('course-100001')
    // 既有 null 形态：显示「通用」灰 Badge
    expect(wrapper.find('[data-testid="chunk-course-c-2"]').text()).toContain('通用')
    // 后端实测 'DEFAULT' 字符串形态：同样显示「通用」灰 Badge，绝不渲染成短格式 '#EFAULT'
    const defaultCell = wrapper.find('[data-testid="chunk-course-c-4"]')
    expect(defaultCell.text()).toContain('通用')
    expect(defaultCell.text()).not.toContain('#EFAULT')
    expect(wrapper.find('[data-testid="chunk-course-c-4"]').classes()).toContain('bg-slate-100')

    // 操作列：上下文 / 编辑
    expect(wrapper.find('[data-testid="op-context-c-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-edit-c-1"]').exists()).toBe(true)

    // 分页器总数
    expect(wrapper.text()).toContain('共 4 条')
    wrapper.unmount()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountChunks()

    expect(wrapper.find('[data-testid="chunk-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="chunk-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const { wrapper } = await mountChunks()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')

    await wrapper.find('[data-testid="retry-chunks"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="chunk-table"]').text()).toContain('c-1')
    wrapper.unmount()
  })

  it('empty：空态文案（禁裸「暂无数据」）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountChunks()

    expect(wrapper.text()).toContain('还没有待修正分片')
    expect(wrapper.find('[data-testid="chunk-table"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('ChunksView：筛选与分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('kbId/docId 仅两参：变更携带参数并重置页码；docId 提交后生效', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(
      pageOf([kb('kb-1', 'RAG 知识库'), kb('kb-2', '课程知识库')], '2'),
    )
    // total 25 保证「下一页」可用（totalPages=3），验证翻页后筛选重置页码
    const pendingSpy = vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '25'))
    const { wrapper } = await mountChunks()

    // kbId 下拉变更 → 查询参数 kbId 到位
    await wrapper.find('[data-testid="filter-kb"]').setValue('kb-2')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).toMatchObject({ kbId: 'kb-2' })

    // docId 输入未提交前不带参数
    await wrapper.find('[data-testid="filter-doc"]').setValue('doc-99')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).not.toHaveProperty('docId')

    // 提交 docId → 参数到位
    await wrapper.find('[data-testid="apply-doc"]').trigger('click')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).toMatchObject({ docId: 'doc-99' })

    // 翻页到 2 后筛选变更 → 页码重置回 1
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    await wrapper.find('[data-testid="filter-kb"]').setValue('kb-1')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).toMatchObject({ kbId: 'kb-1', page: 1 })
    wrapper.unmount()
  })

  it('分页：上一页/下一页越界禁用，页码文本正确', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    const pendingSpy = vi.spyOn(chunkApi, 'pending').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([chunk(`c-${p}`)], '25', p)
    })
    const { wrapper } = await mountChunks()

    expect(wrapper.text()).toContain('第 1 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(pendingSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.text()).toContain('第 2 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      false,
    )
    wrapper.unmount()
  })
})

describe('ChunksView：勾选状态管理', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('行勾选/全选/部分选择驱动批量按钮出现条件与计数', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(
      pageOf([chunk('c-1'), chunk('c-2'), chunk('c-3')], '3'),
    )
    const { wrapper } = await mountChunks()

    // 未勾选：批量按钮不出现
    expect(wrapper.find('[data-testid="batch-update"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="batch-corrected"]').exists()).toBe(false)

    // 行勾选 c-1：部分选择态（全选框不勾选），批量按钮计数 1
    await wrapper.find('[data-testid="select-c-1"]').setValue(true)
    expect((wrapper.find('[data-testid="select-all"]').element as HTMLInputElement).checked).toBe(
      false,
    )
    expect(wrapper.find('[data-testid="batch-update"]').text()).toContain('1')
    expect(wrapper.find('[data-testid="batch-corrected"]').text()).toContain('1')

    // 全选：整页 3 行选中，全选框勾选
    await wrapper.find('[data-testid="select-all"]').setValue(true)
    expect((wrapper.find('[data-testid="select-all"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    expect(wrapper.find('[data-testid="batch-update"]').text()).toContain('3')

    // 再点全选取消：全部取消 → 批量按钮消失
    await wrapper.find('[data-testid="select-all"]').setValue(false)
    expect(wrapper.find('[data-testid="batch-update"]').exists()).toBe(false)

    // 全部行手动勾选后全选框自动为勾选态（部分选择计算驱动）
    await wrapper.find('[data-testid="select-c-1"]').setValue(true)
    await wrapper.find('[data-testid="select-c-2"]').setValue(true)
    await wrapper.find('[data-testid="select-c-3"]').setValue(true)
    expect((wrapper.find('[data-testid="select-all"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    wrapper.unmount()
  })
})

describe('ChunksView：批量修正 Dialog（表单校验 + 提交体 + loading 态）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  /** 勾选 c-1 并打开批量修正 Dialog */
  async function openBatchDialog() {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1'), chunk('c-2')], '2'))
    const { wrapper } = await mountChunks()
    await wrapper.find('[data-testid="select-c-1"]').setValue(true)
    await wrapper.find('[data-testid="batch-update"]').trigger('click')
    const dialog = wrapper.find('[data-testid="batch-dialog"]')
    expect(dialog.exists()).toBe(true)
    return { wrapper, dialog }
  }

  it('表单校验：collectionType 与 courseId 均为「不改」时提交禁用', async () => {
    const { wrapper, dialog } = await openBatchDialog()
    const batchSpy = vi.spyOn(chunkApi, 'batchUpdate').mockResolvedValue()

    // 默认不改/不改：提交禁用
    expect(
      (dialog.find('[data-testid="submit-batch"]').element as HTMLButtonElement).disabled,
    ).toBe(true)

    // 仅选 collectionType：可提交
    await dialog.find('[data-testid="batch-collection-type"]').setValue('TECHNICAL_QA')
    expect(
      (dialog.find('[data-testid="submit-batch"]').element as HTMLButtonElement).disabled,
    ).toBe(false)
    expect(batchSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('课程搜索选择器：远程搜索 keyword，选中课程后提交体 {ids, collectionType, courseId}', async () => {
    const { wrapper, dialog } = await openBatchDialog()
    const batchSpy = vi.spyOn(chunkApi, 'batchUpdate').mockResolvedValue()
    // 课程选择器仅消费 id/title，其余字段以断言转换补齐
    vi.spyOn(courseApi, 'list').mockResolvedValue(
      pageOf([{ id: 'c-9', title: 'RAG 实战营' }] as unknown as CourseDTO[], '1'),
    )

    await dialog.find('[data-testid="batch-collection-type"]').setValue('TECHNICAL_QA')
    const courseInput = dialog.find('[data-testid="batch-course"] [data-testid="remote-input"]')
    await courseInput.trigger('focus')
    await flushPromises()
    // 输入关键字：防抖 300ms 后发请求，收敛目标 = 结果 option 渲染
    await courseInput.setValue('实战')
    await vi.waitFor(
      () =>
        expect(
          dialog.find('[data-testid="batch-course"] [data-testid="remote-option-c-9"]').exists(),
        ).toBe(true),
      { timeout: 5000 },
    )
    expect(courseApi.list).toHaveBeenCalledWith(
      expect.objectContaining({ keyword: '实战', size: 10 }),
    )
    await dialog
      .find('[data-testid="batch-course"] [data-testid="remote-option-c-9"]')
      .trigger('click')
    // 单选选中：输入框回显课程标题
    expect(
      (
        dialog.find('[data-testid="batch-course"] [data-testid="remote-input"]')
          .element as HTMLInputElement
      ).value,
    ).toBe('RAG 实战营')

    await dialog.find('[data-testid="submit-batch"]').trigger('click')
    await flushPromises()
    expect(batchSpy).toHaveBeenCalledWith({
      ids: ['c-1'],
      collectionType: 'TECHNICAL_QA',
      courseId: 'c-9',
    })
    wrapper.unmount()
  })

  it('通用(DEFAULT)提交值：courseId 显式传 "DEFAULT"（后端写库并同步 Milvus）', async () => {
    const { wrapper, dialog } = await openBatchDialog()
    const batchSpy = vi.spyOn(chunkApi, 'batchUpdate').mockResolvedValue()
    vi.spyOn(courseApi, 'list').mockResolvedValue(pageOf<CourseDTO>([], '0'))

    // 「设为通用」入口在「不改」态直接可用（remote-select 未选课程）
    await dialog.find('[data-testid="batch-course-default"]').trigger('click')

    await dialog.find('[data-testid="submit-batch"]').trigger('click')
    await flushPromises()
    // 后端 if (courseId != null) 判定：'DEFAULT' 非 null，会实际写库并同步 Milvus
    expect(batchSpy).toHaveBeenCalledWith({ ids: ['c-1'], courseId: 'DEFAULT' })
    wrapper.unmount()
  })

  it('loading 态与成功流：提交中禁用 → toast 成功 → 关闭清空勾选并刷新', async () => {
    const { wrapper, dialog } = await openBatchDialog()
    let resolveBatch: () => void = () => {}
    const batchSpy = vi
      .spyOn(chunkApi, 'batchUpdate')
      .mockImplementation(() => new Promise<void>((resolve) => (resolveBatch = resolve)))
    const listSpy = vi.spyOn(chunkApi, 'pending')

    await dialog.find('[data-testid="batch-collection-type"]').setValue('COURSE_INFO')
    await dialog.find('[data-testid="submit-batch"]').trigger('click')
    await flushPromises()

    // loading 态：提交按钮禁用 + 文案切换
    const submitBtn = dialog.find('[data-testid="submit-batch"]')
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(true)
    expect(submitBtn.text()).toContain('提交中')

    // 完成：toast + Dialog 关闭 + 勾选清空（批量按钮消失）+ 列表刷新
    resolveBatch()
    await flushPromises()
    expect(batchSpy).toHaveBeenCalledWith({ ids: ['c-1'], collectionType: 'COURSE_INFO' })
    expect(document.body.textContent).toContain('批量修正完成')
    expect(wrapper.find('[data-testid="batch-dialog"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="batch-update"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('失败：toast danger 且 Dialog 保留可重试，loading 态恢复', async () => {
    const { wrapper, dialog } = await openBatchDialog()
    const batchSpy = vi
      .spyOn(chunkApi, 'batchUpdate')
      .mockRejectedValue(new ApiError(500, '批量修正失败', 500))
    const listSpy = vi.spyOn(chunkApi, 'pending')

    await dialog.find('[data-testid="batch-collection-type"]').setValue('TECHNICAL_QA')
    await dialog.find('[data-testid="submit-batch"]').trigger('click')
    await flushPromises()

    expect(document.body.textContent).toContain('批量修正失败')
    expect(wrapper.find('[data-testid="batch-dialog"]').exists()).toBe(true)
    expect(batchSpy).toHaveBeenCalledTimes(1)
    // 失败不刷新列表
    expect(listSpy.mock.calls.length).toBe(1)
    // loading 态恢复：可再次提交
    expect(
      (dialog.find('[data-testid="submit-batch"]').element as HTMLButtonElement).disabled,
    ).toBe(false)
    wrapper.unmount()
  })
})

describe('ChunksView：标记已修正（二次确认，不可撤销）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('危险操作二次确认：不可撤销文案；取消不调接口', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const correctedSpy = vi.spyOn(chunkApi, 'batchCorrected').mockResolvedValue()
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="select-c-1"]').setValue(true)
    const btn = wrapper.find('[data-testid="batch-corrected"]')
    expect(btn.classes()).toContain('bg-danger')

    await btn.trigger('click')
    const dialog = wrapper.find('[data-testid="corrected-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('不可撤销')

    await wrapper.find('[data-testid="cancel-corrected"]').trigger('click')
    expect(correctedSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="corrected-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('确认：POST {ids} → toast → 行消失（刷新后列表移除）→ 勾选清空', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    const listSpy = vi
      .spyOn(chunkApi, 'pending')
      .mockResolvedValueOnce(pageOf([chunk('c-1'), chunk('c-2')], '2'))
      // 标记已修正后刷新：c-2 恢复为非 pending（行消失）
      .mockResolvedValueOnce(pageOf([chunk('c-1')], '1'))
    const correctedSpy = vi.spyOn(chunkApi, 'batchCorrected').mockResolvedValue()
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="select-c-2"]').setValue(true)
    await wrapper.find('[data-testid="batch-corrected"]').trigger('click')
    await wrapper.find('[data-testid="confirm-corrected"]').trigger('click')
    await flushPromises()

    expect(correctedSpy).toHaveBeenCalledWith({ ids: ['c-2'] })
    expect(document.body.textContent).toContain('已标记')
    expect(wrapper.find('[data-testid="corrected-dialog"]').exists()).toBe(false)
    // c-2 行消失、c-1 保留
    expect(wrapper.find('[data-testid="row-c-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="row-c-1"]').exists()).toBe(true)
    // 勾选清空 → 批量按钮消失
    expect(wrapper.find('[data-testid="batch-corrected"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('提交期间禁止取消/Esc/遮罩关闭（与批量 Dialog submitting 拦截一致）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    // 提交挂起可控：断言期间 Dialog 不可被任何途径关闭
    let resolveCorrected: () => void = () => {}
    const correctedSpy = vi
      .spyOn(chunkApi, 'batchCorrected')
      .mockImplementation(() => new Promise<void>((resolve) => (resolveCorrected = resolve)))
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="select-c-1"]').setValue(true)
    await wrapper.find('[data-testid="batch-corrected"]').trigger('click')
    const dialog = wrapper.find('[data-testid="corrected-dialog"]')
    await dialog.find('[data-testid="confirm-corrected"]').trigger('click')
    await flushPromises()

    // 提交中：取消按钮禁用
    expect(
      (dialog.find('[data-testid="cancel-corrected"]').element as HTMLButtonElement).disabled,
    ).toBe(true)
    // Esc 与遮罩点击均被 submitting 拦截，Dialog 保持在场
    await dialog.trigger('keydown', { key: 'Escape' })
    await dialog.trigger('click')
    expect(wrapper.find('[data-testid="corrected-dialog"]').exists()).toBe(true)
    expect(correctedSpy).toHaveBeenCalledTimes(1)

    // 提交完成：Dialog 正常关闭
    resolveCorrected()
    await flushPromises()
    expect(wrapper.find('[data-testid="corrected-dialog"]').exists()).toBe(false)
    expect(correctedSpy).toHaveBeenCalledWith({ ids: ['c-1'] })
    wrapper.unmount()
  })
})

describe('ChunksView：编辑 Drawer（mono 全文 + 元数据只读 + 重向量化提示）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('打开：全文 textarea + 元数据只读区（headingPath/charOffset 起止/tokenCount）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-edit-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="edit-drawer"]')
    expect(drawer.exists()).toBe(true)

    // mono textarea 全文回显
    const textarea = drawer.find('[data-testid="edit-content"]')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('分片内容-c-1，用于预览与编辑')
    expect(textarea.classes()).toContain('font-mono')

    // 元数据只读区：headingPath / charOffset 起止 / tokenCount（tabular-nums）
    const meta = drawer.find('[data-testid="edit-meta"]')
    expect(meta.text()).toContain('第一章 · RAG 概述')
    expect(meta.text()).toContain('1024 - 2048')
    expect(meta.text()).toContain('128')
    expect(drawer.find('[data-testid="edit-meta"] input').exists()).toBe(false)
    wrapper.unmount()
  })

  it('空内容校验：清空后保存就地报错且不调接口', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const updateSpy = vi.spyOn(chunkApi, 'updateContent').mockResolvedValue()
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-edit-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="edit-drawer"]')
    await drawer.find('[data-testid="edit-content"]').setValue('   ')
    await drawer.find('[data-testid="submit-edit"]').trigger('click')
    await flushPromises()

    expect(updateSpy).not.toHaveBeenCalled()
    expect(drawer.find('[data-testid="edit-error"]').text()).toContain('请输入分片内容')
    wrapper.unmount()
  })

  it('保存：PUT {content} → 重向量化提示 toast → 关闭 Drawer 并刷新', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    const listSpy = vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const updateSpy = vi.spyOn(chunkApi, 'updateContent').mockResolvedValue()
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-edit-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="edit-drawer"]')
    await drawer.find('[data-testid="edit-content"]').setValue('修正后的分片内容')
    await drawer.find('[data-testid="submit-edit"]').trigger('click')
    await flushPromises()

    // 保存体：{content}（PUT /admin/chunks/{id}）
    expect(updateSpy).toHaveBeenCalledWith('c-1', { content: '修正后的分片内容' })
    // 重新向量化提示文案
    expect(document.body.textContent).toContain('内容已更新，正在重新向量化')
    // 关闭 Drawer + 列表刷新
    expect(wrapper.find('[data-testid="edit-drawer"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('ChunksView：上下文 Drawer（四节点时间线，null 不渲染）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('打开：调 context 接口，parent/prev/current/next 四节点渲染', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const contextSpy = vi.spyOn(chunkApi, 'context').mockResolvedValue({
      parent: chunk('p-1', { content: '父分片内容' }),
      prev: chunk('v-1', { content: '前一分片内容' }),
      current: chunk('c-1', { content: '当前分片内容' }),
      next: chunk('n-1', { content: '下一分片内容' }),
    })
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-context-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="context-drawer"]')
    expect(drawer.exists()).toBe(true)
    // 查询为异步调度（enabled 翻转触发），接口调用与节点渲染以 waitFor 收敛
    await vi.waitFor(() => expect(contextSpy).toHaveBeenCalledWith('c-1'))

    // 四节点时间线（含对应中文标签与内容）
    await vi.waitFor(() => {
      expect(drawer.text()).toContain('父分片')
      expect(drawer.text()).toContain('前一分片')
      expect(drawer.text()).toContain('当前分片')
      expect(drawer.text()).toContain('下一分片')
    })
    expect(drawer.find('[data-testid="ctx-parent"]').text()).toContain('父分片内容')
    expect(drawer.find('[data-testid="ctx-current"]').text()).toContain('当前分片内容')
    // 节点卡元数据：分片序号 + id 短格式 + 页码区间
    expect(wrapper.find('[data-testid="ctx-prev"]').text()).toContain('#v-1')
    wrapper.unmount()
  })

  it('null 节点不渲染：仅 current/next 在场', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    // 后端 findContext 允许 parent/prev 为 null（value 空节点），类型收敛按运行时形态断言
    vi.spyOn(chunkApi, 'context').mockResolvedValue({
      parent: null,
      prev: null,
      current: chunk('c-1'),
      next: chunk('n-1'),
    } as unknown as Record<string, DocumentChunkVO>)
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-context-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="context-drawer"]')
    await vi.waitFor(() => {
      expect(drawer.find('[data-testid="ctx-parent"]').exists()).toBe(false)
      expect(drawer.find('[data-testid="ctx-prev"]').exists()).toBe(false)
      expect(drawer.find('[data-testid="ctx-current"]').exists()).toBe(true)
      expect(drawer.find('[data-testid="ctx-next"]').exists()).toBe(true)
    })
    wrapper.unmount()
  })

  it('加载失败：错误文案 + 重试恢复', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1')], '1'))
    const contextSpy = vi
      .spyOn(chunkApi, 'context')
      .mockRejectedValueOnce(new ApiError(500, '上下文加载失败', 500))
      .mockResolvedValue({ current: chunk('c-1') })
    const { wrapper } = await mountChunks()

    await wrapper.find('[data-testid="op-context-c-1"]').trigger('click')
    const drawer = wrapper.find('[data-testid="context-drawer"]')
    await vi.waitFor(() => {
      expect(drawer.find('[data-testid="ctx-error"]').text()).toContain('上下文加载失败')
    })

    await drawer.find('[data-testid="retry-ctx"]').trigger('click')
    await vi.waitFor(() => {
      expect(contextSpy).toHaveBeenCalledTimes(2)
      expect(drawer.find('[data-testid="ctx-current"]').exists()).toBe(true)
    })
    wrapper.unmount()
  })

  it('竞态守卫：开 A→关→开 B，A 晚到响应不覆盖 B（快速开关乱序）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', 'RAG 知识库')], '1'))
    vi.spyOn(chunkApi, 'pending').mockResolvedValue(pageOf([chunk('c-1'), chunk('c-2')], '2'))
    // A（c-1）请求挂起可控：响应在 B 加载完成之后才 resolve（模拟真实网络乱序）
    let resolveA: (v: Record<string, DocumentChunkVO>) => void = () => {}
    const contextSpy = vi.spyOn(chunkApi, 'context').mockImplementation((id: string) => {
      if (id === 'c-1') {
        return new Promise<Record<string, DocumentChunkVO>>((resolve) => (resolveA = resolve))
      }
      return Promise.resolve({ current: chunk('c-2', { content: 'B 的当前分片' }) })
    })
    const { wrapper } = await mountChunks()

    // 开 A（c-1 请求在途，Drawer 处于加载态）
    await wrapper.find('[data-testid="op-context-c-1"]').trigger('click')
    expect(contextSpy).toHaveBeenCalledWith('c-1')
    // 关 A
    await wrapper.find('[data-testid="close-context"]').trigger('click')
    expect(wrapper.find('[data-testid="context-drawer"]').exists()).toBe(false)
    // 开 B（c-2 请求即回）：B 的节点正常渲染
    await wrapper.find('[data-testid="op-context-c-2"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="ctx-current"]').text()).toContain('B 的当前分片')
    // A 迟到响应到达：不得回填覆盖 B（序号已过期），loading 态也不被 A 的收尾干扰
    resolveA({ current: chunk('c-1', { content: 'A 的当前分片' }) })
    await flushPromises()
    expect(wrapper.find('[data-testid="ctx-current"]').text()).toContain('B 的当前分片')
    expect(wrapper.find('[data-testid="ctx-current"]').text()).not.toContain('A 的当前分片')
    expect(wrapper.find('[data-testid="ctx-loading"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
