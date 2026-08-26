import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, chunkApi, documentApi } from '@/lib/api'
import { formatDateTime, formatFileSize } from '@/lib/utils'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import DocumentDetailView from '@/views/DocumentDetailView.vue'

import type { DocumentChunkVO, DocumentParseStatus, DocumentVO, PageResponse } from '@/lib/types'

/**
 * 文档详情页测试（Task 18 核心交付）
 *
 * 覆盖契约（设计 §2.4.2 详情 + G14 静态状态机示意）：
 * 1. 信息卡：标题/类型 Badge/大小/分片数/上传时间/状态（含 FAILED 错误信息）
 * 2. 分片列表：content 2 行截断 + headingPath + 分页（chunks?docId=）
 * 3. 状态时间线：当前态高亮（brand）/ 前序已完成（emerald）/ 后续待处理（中性），FAILED 终态分支
 * 4. FAILED：errorMessage 展开 + [重新解析] 调接口
 * 5. 四态：loading/empty（暂无分片）/error 重试/正常
 */

/** 分页响应构造（Long total 为 string） */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 文档工厂 */
function ddoc(status: DocumentParseStatus, over: Partial<DocumentVO> = {}): DocumentVO {
  return {
    id: 'd-1',
    kbId: 'kb-1',
    title: 'RAG 架构入门.pdf',
    fileType: 'pdf',
    fileSize: '2048',
    parseStatus: status,
    chunkCount: 42,
    errorMessage: '',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-24T09:30:00',
    updatedAt: '2026-08-24T10:00:00',
    ...over,
  }
}

/** 分片工厂 */
function chunkOf(
  id: string,
  index: number,
  content: string,
  headingPath = '第一章 概述',
): DocumentChunkVO {
  return {
    id,
    docId: 'd-1',
    kbId: 'kb-1',
    chunkIndex: index,
    content,
    headingPath,
    parentTitle: '第一章 概述',
    startPage: 1,
    endPage: 2,
    tokenCount: 128,
    collectionType: 'TECHNICAL_QA',
    courseId: null,
    metadataJson: '',
    milvusPk: `milvus-${id}`,
    parentChunkId: null,
    prevChunkId: null,
    nextChunkId: null,
    charOffsetStart: 0,
    charOffsetEnd: 100,
    correctionStatus: 'PENDING',
    createdAt: '2026-08-24T09:31:00',
    updatedAt: '2026-08-24T09:31:00',
  }
}

/** 挂载详情页：TEACHER 登录态 + 真实路由推入文档详情路由 */
async function mountDetail(docId = 'd-1') {
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
  await router.push(`/knowledge/documents/${docId}`)
  await router.isReady()
  const wrapper = mount(DocumentDetailView, {
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
  await flushPromises()
  return { wrapper, router }
}

describe('DocumentDetailView：信息卡渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染标题/类型 Badge/大小/分片数/上传更新时间/状态 Badge', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('INDEXED'))
    vi.spyOn(chunkApi, 'list').mockResolvedValue(
      pageOf([chunkOf('c-1', 1, 'RAG 检索流程说明，用于回答知识性问题。')], '1'),
    )
    const { wrapper } = await mountDetail()

    const info = wrapper.find('[data-testid="doc-info"]')
    expect(info.text()).toContain('RAG 架构入门.pdf')
    expect(info.text()).toContain('PDF')
    expect(info.text()).toContain(formatFileSize('2048')) // 2.0 KB
    expect(info.text()).toContain('42') // 分片数
    expect(info.text()).toContain(formatDateTime('2026-08-24T09:30:00'))
    expect(info.text()).toContain(formatDateTime('2026-08-24T10:00:00'))
    // 状态 Badge：INDEXED → emerald
    const badge = wrapper.find('[data-testid="detail-status"] [data-testid="etl-badge"]')
    expect(badge.classes()).toContain('bg-emerald-50')
    expect(badge.text()).toContain('INDEXED')
    wrapper.unmount()
  })

  it('FAILED 文档：错误信息展示在信息卡', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(
      ddoc('FAILED', { errorMessage: '解析失败：文件损坏' }),
    )
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-testid="doc-info"]').text()).toContain('解析失败：文件损坏')
    wrapper.unmount()
  })
})

describe('DocumentDetailView：分片列表与分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染分片内容（2 行截断）与 headingPath，翻页携带分页参数', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('INDEXED'))
    const chunkSpy = vi.spyOn(chunkApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return p === 1
        ? pageOf(
            [
              chunkOf('c-1', 1, '第一页分片内容：RAG 混合检索'),
              chunkOf('c-2', 2, '第二页分片内容'),
            ],
            '23',
            1,
          )
        : pageOf([chunkOf('c-11', 11, '第二页分片内容')], '23', 2)
    })
    const { wrapper } = await mountDetail()

    // 分片表：headingPath + 内容（line-clamp-2 两行截断）
    const list = wrapper.find('[data-testid="chunk-list"]')
    expect(list.text()).toContain('第一章 概述')
    expect(list.text()).toContain('第一页分片内容：RAG 混合检索')
    const content = wrapper.find('[data-testid="chunk-content-c-1"]')
    expect(content.classes()).toContain('line-clamp-2')
    expect(wrapper.text()).toContain('共 23 条')

    // 翻页：chunks?docId=d-1 + page=2
    await wrapper.find('[data-testid="chunk-next"]').trigger('click')
    await flushPromises()
    expect(chunkSpy.mock.calls.at(-1)?.[0]).toMatchObject({ docId: 'd-1', page: 2 })
    expect(wrapper.text()).toContain('第二页分片内容')
    expect(wrapper.text()).toContain('第 2 / 3 页')
    wrapper.unmount()
  })

  it('无分片（INDEXED 但 chunkCount 0）：空态提示', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('INDEXED', { chunkCount: 0 }))
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDetail()

    expect(wrapper.text()).toContain('暂无分片')
    wrapper.unmount()
  })

  it('分片加载失败：错误横幅 + 重试恢复', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('INDEXED'))
    vi.spyOn(chunkApi, 'list')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([chunkOf('c-1', 1, '恢复后的分片')], '1'))
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-testid="chunk-error"]').text()).toContain('服务暂时不可用')
    await wrapper.find('[data-testid="retry-chunks"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="chunk-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="chunk-list"]').text()).toContain('恢复后的分片')
    wrapper.unmount()
  })
})

describe('DocumentDetailView：状态时间线（G14 静态状态机示意）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('CHUNKING 当前态高亮：前序 emerald 完成、当前 brand 高亮、后续中性待处理', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('CHUNKING'))
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDetail()

    const timeline = wrapper.find('[data-testid="status-timeline"]')
    expect(timeline.exists()).toBe(true)

    // 前序三步（PENDING/PARSING/PARSED）已完成：emerald 节点
    for (const st of ['PENDING', 'PARSING', 'PARSED']) {
      const step = wrapper.find(`[data-testid="timeline-${st}"]`)
      expect(step.classes()).toContain('done')
    }
    // 当前 CHUNKING：brand 高亮 + 工作态
    const current = wrapper.find('[data-testid="timeline-CHUNKING"]')
    expect(current.classes()).toContain('current')
    // 后续（CHUNKED/EMBEDDING/INDEXED）待处理：中性
    for (const st of ['CHUNKED', 'EMBEDDING', 'INDEXED']) {
      expect(wrapper.find(`[data-testid="timeline-${st}"]`).classes()).toContain('pending')
    }
    // FAILED 分支不渲染（非失败态）
    expect(wrapper.find('[data-testid="timeline-failed"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('INDEXED 终态：全部步骤完成态，无 FAILED 分支', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(ddoc('INDEXED'))
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountDetail()

    for (const st of [
      'PENDING',
      'PARSING',
      'PARSED',
      'CHUNKING',
      'CHUNKED',
      'EMBEDDING',
      'INDEXED',
    ]) {
      expect(wrapper.find(`[data-testid="timeline-${st}"]`).classes()).toContain('done')
    }
    expect(wrapper.find('[data-testid="timeline-failed"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('FAILED：终态分支红色高亮 + errorMessage 展开 + [重新解析] 调接口', async () => {
    vi.spyOn(documentApi, 'get').mockResolvedValue(
      ddoc('FAILED', { errorMessage: '解析失败：文件损坏' }),
    )
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([], '0'))
    const reparseSpy = vi.spyOn(documentApi, 'reparse').mockResolvedValue()
    const getSpy = vi.spyOn(documentApi, 'get')
    const { wrapper } = await mountDetail()

    // 失败分支在场：错误详情 mono 展开 + 重新解析按钮
    const failed = wrapper.find('[data-testid="timeline-failed"]')
    expect(failed.classes()).toContain('failed')
    expect(failed.text()).toContain('解析失败：文件损坏')
    expect(failed.find('[data-testid="detail-reparse"]').exists()).toBe(true)

    // 点击重新解析：接口 + toast + 详情/分片重新加载（invalidate 失效重拉为异步链，waitFor 轮询收敛）
    await failed.find('[data-testid="detail-reparse"]').trigger('click')
    await flushPromises()
    expect(reparseSpy).toHaveBeenCalledWith('d-1')
    expect(document.body.textContent).toContain('重新解析')
    await vi.waitFor(() => {
      expect(getSpy.mock.calls.length).toBeGreaterThan(1)
    })
    wrapper.unmount()
  })
})

describe('DocumentDetailView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：信息卡与分片区骨架在场', async () => {
    vi.spyOn(documentApi, 'get').mockReturnValue(new Promise(() => {}))
    vi.spyOn(chunkApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-testid="detail-skeleton"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('error：文档加载失败展示错误横幅 + 重试恢复', async () => {
    vi.spyOn(documentApi, 'get')
      .mockRejectedValueOnce(new ApiError(404, '文档不存在', 404))
      .mockResolvedValue(ddoc('INDEXED'))
    vi.spyOn(chunkApi, 'list').mockResolvedValue(pageOf([chunkOf('c-1', 1, '内容')], '1'))
    const { wrapper } = await mountDetail()

    expect(wrapper.find('[data-testid="detail-error"]').text()).toContain('文档不存在')
    await wrapper.find('[data-testid="retry-detail"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="doc-info"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
