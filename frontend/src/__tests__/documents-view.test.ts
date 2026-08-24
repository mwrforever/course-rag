import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, courseApi, documentApi, knowledgeBaseApi } from '@/lib/api'
import { formatDateTime } from '@/lib/utils'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import DocumentsView from '@/views/DocumentsView.vue'

import type { PageResponse } from '@/lib/types'
import type { CourseDTO, DocumentParseStatus, DocumentVO, KnowledgeBaseVO } from '@/lib/types'

/**
 * 文档管理页测试（Task 18 核心交付）
 *
 * 覆盖契约（设计 §2.4.2 + 任务 brief）：
 * 1. 列表渲染：☐/文件名+所属库小字/类型 Badge/状态 Badge/分片数/上传时间（相对+绝对 tooltip）/操作
 * 2. 筛选参数：kbId/status/q → 查询参数（q 仅「搜索」提交），翻页重置 page=1
 * 3. 排序仅 created/updated 两列（后端实测），排序指示器只在这两列
 * 4. 8 态 Badge 全分支（设计 §2.5 映射经共享组件渲染）
 * 5. 上传 Dialog：kbId 必选 / 类型白名单 / size 上限（纯函数） / XHR 进度条 / 完成关刷新
 * 6. 批量 allSettled：循环单条 + 聚合 toast「成功 n / 失败 m」
 * 7. 删除二次确认；重新解析；下载；改标题；查看分片跳详情
 * 8. ETL 轮询接线：非终态行 5s 自动刷新 / 全终态停
 * 9. 四态：loading/empty/error/正常
 *
 * 页面数据源为 vue-query：测试注入独立 QueryClient（retry:false），接口层 vi.spyOn 内存 mock。
 */

/** 分页响应构造（Long total 为 string，page/size 为 number） */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 文档工厂（createdAt 默认取当前时间戳，相对时间断言如需确定性可覆盖） */
function doc(id: string, status: DocumentParseStatus, over: Partial<DocumentVO> = {}): DocumentVO {
  return {
    id,
    kbId: 'kb-1',
    title: `文档-${id}.md`,
    fileType: 'md',
    fileSize: '2048',
    parseStatus: status,
    chunkCount: 0,
    errorMessage: '',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-24T09:30:00',
    updatedAt: '2026-08-24T09:30:00',
    ...over,
  }
}

/** 知识库 mock（上传下拉与筛选选项来源） */
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

/** 八态样本（8 条记录，覆盖设计 §2.5 全部 Badge 分支） */
const EIGHT_STATE_DOCS: DocumentVO[] = [
  doc('d-pending', 'PENDING'),
  doc('d-parsing', 'PARSING', { title: '解析中.pdf', fileType: 'pdf' }),
  doc('d-parsed', 'PARSED'),
  doc('d-chunking', 'CHUNKING', { title: '分片中.pptx', fileType: 'pptx' }),
  doc('d-chunked', 'CHUNKED'),
  doc('d-embedding', 'EMBEDDING'),
  doc('d-indexed', 'INDEXED', { title: '已入库.docx', fileType: 'docx', chunkCount: 42 }),
  doc('d-failed', 'FAILED', { title: '失败.txt', fileType: 'txt' }),
]

/** 八态期望 Badge 底色（设计 §2.5 明细，与共享组件映射一致） */
const STATUS_BG: Record<DocumentParseStatus, string> = {
  PENDING: 'bg-slate-100',
  PARSING: 'bg-brand-soft',
  PARSED: 'bg-brand-soft',
  CHUNKING: 'bg-violet-50',
  CHUNKED: 'bg-violet-50',
  EMBEDDING: 'bg-amber-50',
  INDEXED: 'bg-emerald-50',
  FAILED: 'bg-red-50',
}

/** 挂载文档页：独立 QueryClient + pinia + 路由（TEACHER 登录态过守卫） */
async function mountDocuments() {
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
  const wrapper = mount(DocumentsView, {
    global: { plugins: [[VueQueryPlugin, { queryClient }], pinia, router] },
  })
  await router.isReady()
  await flushPromises()
  return { wrapper, router, queryClient }
}

/** jsdom 文件选择模拟：注入 input.files 后触发 change（jsdom 不实现 File 赋值） */
async function pickFile(wrapper: VueWrapper, file: File) {
  const input = wrapper.find('[data-testid="file-input"]')
  Object.defineProperty(input.element, 'files', {
    value: [file],
    configurable: true,
  })
  await input.trigger('change')
}

describe('DocumentsView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('渲染文件名+所属库小字/类型 Badge/状态 Badge 8 分支/分片数/时间与总数', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf(EIGHT_STATE_DOCS, '8'))
    const { wrapper } = await mountDocuments()

    expect(wrapper.find('[data-testid="doc-skeleton"]').exists()).toBe(false)
    const table = wrapper.find('[data-testid="doc-table"]')
    expect(table.exists()).toBe(true)

    // 文件名 + 所属库小字（大小写类型 Badge 大写展示；d-indexed 行标题被 factory 覆盖为中文名）
    expect(table.text()).toContain('已入库.docx')
    expect(table.text()).toContain('前端知识库')
    expect(table.text()).toContain('PDF')
    expect(table.text()).toContain('PPTX')

    // 8 态 Badge 全分支：行内徽章底色与设计 §2.5 映射一致
    for (const d of EIGHT_STATE_DOCS) {
      const row = table.find(`[data-testid="row-${d.id}"]`)
      const badge = row.find('[data-testid="etl-badge"]')
      expect(badge.exists()).toBe(true)
      expect(badge.classes()).toContain(STATUS_BG[d.parseStatus])
      expect(badge.text()).toContain(d.parseStatus)
    }

    // 分片数 tabular-nums（INDEXED 行 42）
    expect(table.text()).toContain('42')
    // 上传时间绝对串在 tooltip（title 属性），列内相对时间
    const timeCell = wrapper.find('[data-testid="doc-time-d-indexed"]')
    expect(timeCell.attributes('title')).toBe(formatDateTime('2026-08-24T09:30:00'))
    // 分页器「共 8 条」
    expect(wrapper.text()).toContain('共 8 条')
    wrapper.unmount()
  })

  it('上传时间列显示相对时间（N 分钟前）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(
      pageOf(
        [
          doc('d-recent', 'INDEXED', {
            createdAt: new Date(Date.now() - 3 * 60 * 1000).toISOString(),
          }),
        ],
        '1',
      ),
    )
    const { wrapper } = await mountDocuments()

    const timeCell = wrapper.find('[data-testid="doc-time-d-recent"]')
    expect(timeCell.text()).toContain('分钟前')
    // 绝对时间保留在 title tooltip（设计 §2.4.2：相对 + 绝对）
    expect(timeCell.attributes('title')).toBe(
      formatDateTime(new Date(Date.now() - 3 * 60 * 1000).toISOString()),
    )
    wrapper.unmount()
  })

  it('上传时间/更新时间两表头带排序指示器，其余列无指示器（排序仅两列）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([], '0'))
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const { wrapper } = await mountDocuments()

    // 默认 sort=created：指示器落在上传时间列
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ sort: 'created' })
    expect(wrapper.find('[data-testid="sort-created"] svg').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sort-updated"] svg').exists()).toBe(false)

    // 点击更新时间列：sort 切到 updated，指示器迁移
    await wrapper.find('[data-testid="sort-updated"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ sort: 'updated' })
    expect(wrapper.find('[data-testid="sort-updated"] svg').exists()).toBe(true)
    expect(wrapper.find('[data-testid="sort-created"] svg').exists()).toBe(false)

    // 指示器仅存在于这两列：表格其余表头无 svg 指示
    const otherHeaders = wrapper.findAll(
      '[data-testid="doc-table"] thead th:not(:nth-child(6)):not(:nth-child(7))',
    )
    for (const th of otherHeaders) {
      expect(th.find('svg').exists()).toBe(false)
    }
    wrapper.unmount()
  })

  it('筛选：kbId/status 变更携带参数并重置页码，q 仅搜索按钮提交', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(
      pageOf([kb('kb-1', '前端知识库'), kb('kb-2', '后端知识库')], '2'),
    )
    // total 25 保证「下一页」可用（totalPages=3），验证翻页后筛选重置页码
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '25'))
    const { wrapper } = await mountDocuments()

    // kbId 筛选：下拉变更 → 查询参数 kbId 到位
    await wrapper.find('[data-testid="filter-kb"]').setValue('kb-2')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ kbId: 'kb-2' })

    // status 筛选
    await wrapper.find('[data-testid="filter-status"]').setValue('FAILED')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ status: 'FAILED' })

    // q 输入不即时提交（Enter 前无 q 参数）
    await wrapper.find('[data-testid="filter-q"]').setValue('rag 检索')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).not.toHaveProperty('q')

    // 搜索按钮提交 q
    await wrapper.find('[data-testid="apply-q"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ q: 'rag 检索' })

    // 翻页到 2 后筛选变更 → 页码重置回 1
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    await wrapper.find('[data-testid="filter-kb"]').setValue('kb-1')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ kbId: 'kb-1', page: 1 })
    wrapper.unmount()
  })

  it('分页：上一页/下一页越界禁用，翻页参数与页码正确', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const listSpy = vi.spyOn(documentApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([doc(`d-${p}`, 'INDEXED')], '25', p)
    })
    const { wrapper } = await mountDocuments()

    expect(wrapper.text()).toContain('第 1 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )

    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.text()).toContain('第 2 / 3 页')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      false,
    )
    wrapper.unmount()
  })

  it('操作菜单：查看分片跳详情页 / 重新解析调接口', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const reparseSpy = vi.spyOn(documentApi, 'reparse').mockResolvedValue()
    const { wrapper, router } = await mountDocuments()

    // 打开菜单 → 查看分片 → 路由跳详情
    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-view"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/knowledge/documents/d-1'))

    // 重新解析：接口 + toast + 列表刷新
    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-reparse"]').trigger('click')
    await flushPromises()
    expect(reparseSpy).toHaveBeenCalledWith('d-1')
    expect(document.body.textContent).toContain('重新解析')
    wrapper.unmount()
  })

  it('操作菜单：下载走 blob + 锚点落盘', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const downloadSpy = vi
      .spyOn(documentApi, 'download')
      .mockResolvedValue(new Blob(['pdf-content'], { type: 'application/pdf' }))
    // jsdom 无 URL.createObjectURL：桩掉并断言调用
    const createObjectURL = vi.fn(() => 'blob:mock')
    const revokeObjectURL = vi.fn()
    vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
    const { wrapper } = await mountDocuments()

    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-download"]').trigger('click')
    await flushPromises()

    expect(downloadSpy).toHaveBeenCalledWith('d-1')
    expect(createObjectURL).toHaveBeenCalled()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
    expect(document.body.textContent).toContain('下载')
    vi.unstubAllGlobals()
    wrapper.unmount()
  })

  it('操作菜单：改标题 Dialog 保存调 update 并刷新', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const updateSpy = vi.spyOn(documentApi, 'update').mockResolvedValue()
    const { wrapper } = await mountDocuments()

    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-rename"]').trigger('click')
    const dialog = wrapper.find('[data-testid="rename-dialog"]')
    expect(dialog.exists()).toBe(true)

    await dialog.find('[data-testid="rename-input"]').setValue('新标题.md')
    await dialog.find('[data-testid="submit-rename"]').trigger('click')
    await flushPromises()

    expect(updateSpy).toHaveBeenCalledWith('d-1', { title: '新标题.md' })
    expect(document.body.textContent).toContain('标题已更新')
    expect(wrapper.find('[data-testid="rename-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('删除二次确认：取消不调接口，确认后 remove + toast + 刷新', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const removeSpy = vi.spyOn(documentApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountDocuments()

    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-delete"]').trigger('click')
    const dialog = wrapper.find('[data-testid="delete-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('不可恢复')

    await wrapper.find('[data-testid="cancel-delete"]').trigger('click')
    expect(removeSpy).not.toHaveBeenCalled()

    await wrapper.find('[data-testid="doc-menu-d-1"]').trigger('click')
    await wrapper.find('[data-testid="menu-delete"]').trigger('click')
    await wrapper.find('[data-testid="confirm-delete"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('d-1')
    expect(document.body.textContent).toContain('文档已删除')
    expect(wrapper.find('[data-testid="delete-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('DocumentsView：批量删除（allSettled 聚合）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('勾选 2 行批量删除：循环单条 + 聚合 toast「成功 1 / 失败 1」+ 清空勾选刷新', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(
      pageOf([doc('d-1', 'INDEXED'), doc('d-2', 'INDEXED'), doc('d-3', 'INDEXED')], '3'),
    )
    const removeSpy = vi
      .spyOn(documentApi, 'remove')
      .mockResolvedValueOnce()
      .mockRejectedValueOnce(new ApiError(500, '删除失败', 500))
    const { wrapper } = await mountDocuments()

    // 未勾选时无批量入口
    expect(wrapper.find('[data-testid="batch-delete"]').exists()).toBe(false)

    // 勾选 d-1 与 d-3（跳过 d-2）
    await wrapper.find('[data-testid="select-d-1"]').setValue(true)
    await wrapper.find('[data-testid="select-d-3"]').setValue(true)
    expect(wrapper.find('[data-testid="batch-delete"]').text()).toContain('2')

    // 二次确认
    await wrapper.find('[data-testid="batch-delete"]').trigger('click')
    const dialog = wrapper.find('[data-testid="batch-dialog"]')
    expect(dialog.text()).toContain('批量删除')
    await wrapper.find('[data-testid="confirm-batch"]').trigger('click')
    await flushPromises()

    // 循环单条：仅勾选行被调
    expect(removeSpy).toHaveBeenCalledTimes(2)
    expect(removeSpy).toHaveBeenCalledWith('d-1')
    expect(removeSpy).toHaveBeenCalledWith('d-3')
    expect(removeSpy).not.toHaveBeenCalledWith('d-2')

    // allSettled 聚合文案：成功 1 / 失败 1（danger 类型由文案载体体现）
    expect(document.body.textContent).toContain('成功 1 / 失败 1')
    // 勾选清空 + Dialog 关闭
    expect(wrapper.find('[data-testid="batch-dialog"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="batch-delete"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('全选按钮勾选整页，批量删除全部成功文案「成功 3 / 失败 0」', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(
      pageOf([doc('d-1', 'INDEXED'), doc('d-2', 'INDEXED'), doc('d-3', 'INDEXED')], '3'),
    )
    const removeSpy = vi.spyOn(documentApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountDocuments()

    await wrapper.find('[data-testid="select-all"]').setValue(true)
    expect(wrapper.find('[data-testid="batch-delete"]').text()).toContain('3')

    await wrapper.find('[data-testid="batch-delete"]').trigger('click')
    await wrapper.find('[data-testid="confirm-batch"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledTimes(3)
    expect(document.body.textContent).toContain('成功 3 / 失败 0')
    wrapper.unmount()
  })
})

describe('DocumentsView：上传 Dialog（校验 + 进度条）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  /** 挂载并打开上传 Dialog（知识库下拉选项就绪） */
  async function openUpload() {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(
      pageOf([kb('kb-1', '前端知识库'), kb('kb-2', '后端知识库')], '2'),
    )
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDocuments()
    await wrapper.find('[data-testid="upload-doc"]').trigger('click')
    const dialog = wrapper.find('[data-testid="upload-dialog"]')
    expect(dialog.exists()).toBe(true)
    return { wrapper, dialog }
  }

  it('kbId 必选：未选知识库提交就地报错且不调上传接口', async () => {
    const { wrapper, dialog } = await openUpload()
    const uploadSpy = vi.spyOn(documentApi, 'upload').mockResolvedValue(doc('d-new', 'PENDING'))

    await dialog.find('form[data-testid="upload-form"]').trigger('submit')
    await flushPromises()

    expect(dialog.find('[data-testid="upload-error"]').text()).toContain('请选择知识库')
    expect(uploadSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('类型白名单：exe 文件拒绝（仅 pdf/docx/pptx/md/txt）', async () => {
    const { wrapper, dialog } = await openUpload()
    const uploadSpy = vi.spyOn(documentApi, 'upload').mockResolvedValue(doc('d-new', 'PENDING'))

    await dialog.find('[data-testid="upload-kb"]').setValue('kb-1')
    await dialog.find('[data-testid="upload-title"]').setValue('恶意脚本')
    await pickFile(wrapper, new File(['x'], 'virus.exe'))
    await dialog.find('form[data-testid="upload-form"]').trigger('submit')
    await flushPromises()

    expect(dialog.find('[data-testid="upload-error"]').text()).toContain('不支持的文件类型')
    expect(uploadSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('大小上限：>100MB 拒绝（validateUploadFile 纯函数超限分支，避免 100MB 内存构造）', async () => {
    // 挂载 + 打开上传 Dialog
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDocuments()
    await wrapper.find('[data-testid="upload-doc"]').trigger('click')
    const dialog = wrapper.find('[data-testid="upload-dialog"]')
    const uploadSpy = vi.spyOn(documentApi, 'upload').mockResolvedValue(doc('d-new', 'PENDING'))

    // 以对象字面量模拟 101MB 文件的 name/size（仅校验字段，不真实分配内存）
    await pickFile(wrapper, { name: 'big.pdf', size: 101 * 1024 * 1024 } as File)
    await dialog.find('[data-testid="upload-kb"]').setValue('kb-1')
    await dialog.find('[data-testid="upload-title"]').setValue('大文件课件')
    await dialog.find('form[data-testid="upload-form"]').trigger('submit')
    await flushPromises()

    // 校验顺序：kbId → 标题 → 文件 → 类型/大小白名单，超限文案展示且不调上传接口
    expect(dialog.find('[data-testid="upload-error"]').text()).toContain('100MB')
    expect(uploadSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('XHR 进度条：onUploadProgress 回调驱动宽度百分比，完成关闭 Dialog 并刷新', async () => {
    const { wrapper, dialog } = await openUpload()
    let progressCb: ((p: number) => void) | undefined
    // 可控 promise：上传挂起时进度条可见（mock 立即 resolve 会导致 Dialog 提前关闭）；
    // 初始化为 no-op 占位，mock 首次调用时被真实 resolve 函数覆盖（TS 避免未赋值报错）
    let resolveUpload: (doc: DocumentVO) => void = () => {}
    const listSpy = vi.spyOn(documentApi, 'list')
    const uploadSpy = vi.spyOn(documentApi, 'upload').mockImplementation(async (_form, cb) => {
      progressCb = cb
      return new Promise<DocumentVO>((resolve) => {
        resolveUpload = resolve
      })
    })

    await dialog.find('[data-testid="upload-kb"]').setValue('kb-1')
    await dialog.find('[data-testid="upload-title"]').setValue('新课件')
    await pickFile(wrapper, new File(['pdf'], '新课件.pdf', { type: 'application/pdf' }))
    await dialog.find('form[data-testid="upload-form"]').trigger('submit')

    // FormData 契约：kbId/title/file 必传；courseId 未选不携带
    expect(uploadSpy).toHaveBeenCalledTimes(1)
    const form = uploadSpy.mock.calls[0][0] as FormData
    expect(form.get('kbId')).toBe('kb-1')
    expect(form.get('title')).toBe('新课件')
    expect(form.get('file')).toBeInstanceOf(File)
    expect(form.get('courseId')).toBeNull()

    // 进度回调 → 进度条宽度（XHR onUploadProgress 驱动，上传挂起时可见）
    expect(progressCb).toBeDefined()
    progressCb?.(50)
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-progress"]').attributes('style')).toContain('50%')
    progressCb?.(100)
    await flushPromises()
    expect(wrapper.find('[data-testid="upload-progress"]').attributes('style')).toContain('100%')

    // 完成：toast + Dialog 关闭 + 列表刷新（新文档 PENDING 进入轮询）
    resolveUpload(doc('d-new', 'PENDING', { title: '新课件.pdf', fileType: 'pdf' }))
    await flushPromises()
    expect(document.body.textContent).toContain('上传成功')
    expect(wrapper.find('[data-testid="upload-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })

  it('课程搜索选择器：关键字搜索课程并选中携带 courseId', async () => {
    const { wrapper, dialog } = await openUpload()
    const courseSpy = vi.spyOn(courseApi, 'list').mockResolvedValue(
      // 课程选择器仅消费 id/title，其余字段以断言转换补齐（avoid 全字段工厂噪音）
      pageOf([{ id: 'c-9', title: 'RAG 实战营' }] as unknown as CourseDTO[], '1'),
    )
    const uploadSpy = vi.spyOn(documentApi, 'upload').mockResolvedValue(doc('d-new', 'PENDING'))

    await dialog.find('[data-testid="course-search"]').setValue('RAG')
    await flushPromises()
    expect(courseSpy).toHaveBeenCalledWith(expect.objectContaining({ keyword: 'RAG' }))
    await wrapper.find('[data-testid="course-option-c-9"]').trigger('click')
    expect(dialog.find('[data-testid="selected-course"]').text()).toContain('RAG 实战营')

    await dialog.find('[data-testid="upload-kb"]').setValue('kb-1')
    await dialog.find('[data-testid="upload-title"]').setValue('课件')
    await pickFile(wrapper, new File(['md'], 'note.md', { type: 'text/markdown' }))
    await dialog.find('form[data-testid="upload-form"]').trigger('submit')
    await flushPromises()

    const form = uploadSpy.mock.calls[0][0] as FormData
    expect(form.get('courseId')).toBe('c-9')
    wrapper.unmount()
  })
})

describe('DocumentsView：ETL 轮询接线（vue-query refetchInterval）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('存在非终态行：5 秒后自动重新拉取列表', async () => {
    vi.useFakeTimers()
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'PENDING')], '1'))
    await mountDocuments()
    const callsAfterMount = listSpy.mock.calls.length
    expect(callsAfterMount).toBeGreaterThanOrEqual(1)

    // 前进 5s（ETL 轮询间隔）→ 触发自动刷新
    await vi.advanceTimersByTimeAsync(5000)
    expect(listSpy.mock.calls.length).toBeGreaterThan(callsAfterMount)
    vi.useRealTimers()
  })

  it('全终态：轮询停止（推进 10s 不产生新请求）', async () => {
    vi.useFakeTimers()
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const listSpy = vi
      .spyOn(documentApi, 'list')
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    await mountDocuments()
    const callsAfterMount = listSpy.mock.calls.length

    await vi.advanceTimersByTimeAsync(10_000)
    expect(listSpy.mock.calls.length).toBe(callsAfterMount)
    vi.useRealTimers()
  })
})

describe('DocumentsView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountDocuments()

    expect(wrapper.find('[data-testid="doc-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="doc-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([doc('d-1', 'INDEXED')], '1'))
    const { wrapper } = await mountDocuments()

    const banner = wrapper.find('[role="alert"]')
    expect(banner.text()).toContain('服务暂时不可用，请稍后重试')

    await wrapper.find('[data-testid="retry-docs"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="doc-table"]').text()).toContain('d-1')
    wrapper.unmount()
  })

  it('empty：空态含行动入口（上传文档按钮），禁止裸「暂无数据」', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    vi.spyOn(documentApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDocuments()

    expect(wrapper.text()).toContain('还没有文档')
    await wrapper.find('[data-testid="upload-doc-empty"]').trigger('click')
    expect(wrapper.find('[data-testid="upload-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
