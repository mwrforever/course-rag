import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, sessionApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import SessionsAdminView from '@/views/SessionsAdminView.vue'

import type { ChatMessageVO, ChatSessionDetailVO, ChatSessionVO, PageResponse } from '@/lib/types'

/**
 * 会话审计页测试（Task 21 核心交付，超管专属：设计 §2.4.7）
 *
 * 覆盖契约：
 * 1. 列表：#id / 用户 / 标题 / model / 状态 Badge（ACTIVE emerald / CLOSED slate）/
 *    最后消息时间 / 创建时间
 * 2. 详情 Drawer 700px：sessionApi.detail 渲染 messages 只读流（role/content/intentType/seq）
 * 3. 关闭：仅 ACTIVE 行展示入口 → patch close → toast → 刷新
 * 4. 删除：二次确认 → remove → toast → 刷新
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；seq 为 Integer 保持 number。
 */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 会话工厂（默认 ACTIVE） */
function session(id: string, over: Partial<ChatSessionVO> = {}): ChatSessionVO {
  return {
    id,
    userId: `2000${id}`,
    title: `会话-${id}`,
    status: 'ACTIVE',
    lastMessageAt: '2026-08-24T09:30:00',
    model: 'qwen3-8b',
    createdAt: '2026-08-24T08:00:00',
    ...over,
  }
}

const MESSAGES: ChatMessageVO[] = [
  {
    id: 'msg-1',
    role: 'user',
    content: '课程资料在哪？',
    messageType: 'TEXT',
    intentType: 'chat',
    runId: 'run-1',
    seq: 1,
    createdAt: '2026-08-24T09:00:00',
  },
  {
    id: 'msg-2',
    role: 'assistant',
    content: '在课程工作台中可以查看',
    messageType: null,
    intentType: 'knowledge_question',
    runId: 'run-1',
    seq: 2,
    createdAt: '2026-08-24T09:00:05',
  },
]

function detailOf(s: ChatSessionVO, messages: ChatMessageVO[] = MESSAGES): ChatSessionDetailVO {
  return {
    id: s.id,
    userId: s.userId,
    title: s.title,
    status: s.status,
    lastMessageAt: s.lastMessageAt,
    model: s.model,
    createdAt: s.createdAt,
    messages,
  }
}

/** 挂载会话审计页（仅超管可进，mount 前固定 SUPER_ADMIN 登录态） */
async function mountSessions() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role: 'SUPER_ADMIN',
    displayName: '超管',
  })
  const router = createAppRouter()
  await router.push('/sessions')
  await router.isReady()
  const wrapper = mount(SessionsAdminView, {
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

describe('SessionsAdminView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染 #id/用户/标题/model/状态/最后消息/创建时间，状态 Badge ACTIVE emerald / CLOSED slate', async () => {
    vi.spyOn(sessionApi, 'list').mockResolvedValue(
      pageOf([session('s-1'), session('s-2', { status: 'CLOSED' })], '2'),
    )
    const { wrapper } = await mountSessions()

    // 行内容：id / 用户 / 标题 / model / 两类时间
    const row1 = wrapper.find('[data-testid="row-s-1"]')
    expect(row1.text()).toContain('#s-1')
    expect(row1.text()).toContain('2000s-1')
    expect(row1.text()).toContain('会话-s-1')
    expect(row1.text()).toContain('qwen3-8b')
    expect(row1.text()).toContain('08-24 09:30')
    expect(row1.text()).toContain('08-24 08:00')

    // 状态 Badge：ACTIVE emerald / CLOSED 中性（设计 §2.5）
    expect(wrapper.find('[data-testid="session-status-s-1"]').classes()).toContain('bg-emerald-50')
    expect(wrapper.find('[data-testid="session-status-s-2"]').classes()).toContain('bg-slate-100')

    // 时间列 tabular-nums
    expect(row1.findAll('.tabular-nums').length).toBeGreaterThan(0)

    // 操作：详情常驻；关闭仅 ACTIVE 行；删除全场
    expect(wrapper.find('[data-testid="op-detail-s-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-close-s-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-close-s-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="op-delete-s-2"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('SessionsAdminView：详情回放 Drawer', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('点击详情：sessionApi.detail(id) → Drawer 700px 回放 messages 只读流', async () => {
    vi.spyOn(sessionApi, 'list').mockResolvedValue(pageOf([session('s-1')], '1'))
    const detailSpy = vi.spyOn(sessionApi, 'detail').mockResolvedValue(detailOf(session('s-1')))
    const { wrapper } = await mountSessions()

    await wrapper.find('[data-testid="op-detail-s-1"]').trigger('click')
    await flushPromises()

    expect(detailSpy).toHaveBeenCalledWith('s-1')
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.exists()).toBe(true)
    expect(drawer.classes()).toContain('w-[700px]')

    // 消息流：role / content / intentType / seq 只读在场
    expect(drawer.text()).toContain('课程资料在哪？')
    expect(drawer.text()).toContain('在课程工作台中可以查看')
    expect(drawer.text()).toContain('user')
    expect(drawer.text()).toContain('assistant')
    expect(drawer.text()).toContain('knowledge_question')
    expect(drawer.text()).toContain('1')
    expect(drawer.text()).toContain('2')

    // Esc 关闭 Drawer
    await drawer.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="session-drawer"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Drawer 标题与状态头部在场；空消息空态兜底', async () => {
    vi.spyOn(sessionApi, 'list').mockResolvedValue(pageOf([session('s-1')], '1'))
    vi.spyOn(sessionApi, 'detail').mockResolvedValue(
      detailOf(session('s-1', { status: 'CLOSED' }), []),
    )
    const { wrapper } = await mountSessions()

    await wrapper.find('[data-testid="op-detail-s-1"]').trigger('click')
    await flushPromises()
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.text()).toContain('会话-s-1')
    expect(drawer.text()).toContain('CLOSED')
    expect(drawer.text()).toContain('该会话暂无消息记录')
    wrapper.unmount()
  })

  it('详情加载失败：danger toast 且 Drawer 关闭可重开', async () => {
    vi.spyOn(sessionApi, 'list').mockResolvedValue(pageOf([session('s-1')], '1'))
    vi.spyOn(sessionApi, 'detail').mockRejectedValue(new ApiError(500, '详情加载失败', 500))
    const { wrapper } = await mountSessions()

    await wrapper.find('[data-testid="op-detail-s-1"]').trigger('click')
    await flushPromises()
    expect(document.body.textContent).toContain('详情加载失败')
    expect(wrapper.find('[data-testid="session-drawer"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('SessionsAdminView：关闭与删除', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('关闭会话：close(id) → toast → 刷新（仅 ACTIVE 行入口）', async () => {
    const listSpy = vi
      .spyOn(sessionApi, 'list')
      .mockResolvedValueOnce(pageOf([session('s-1')], '1'))
      .mockResolvedValueOnce(pageOf([session('s-1', { status: 'CLOSED' })], '1'))
    const closeSpy = vi.spyOn(sessionApi, 'close').mockResolvedValue()
    const { wrapper } = await mountSessions()

    await wrapper.find('[data-testid="op-close-s-1"]').trigger('click')
    await flushPromises()

    expect(closeSpy).toHaveBeenCalledWith('s-1')
    expect(document.body.textContent).toContain('会话已关闭')
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    // 刷新后行变 CLOSED：关闭入口消失
    expect(wrapper.find('[data-testid="op-close-s-1"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('删除会话：二次确认（danger）→ remove(id) → toast → 刷新', async () => {
    const listSpy = vi
      .spyOn(sessionApi, 'list')
      .mockResolvedValueOnce(pageOf([session('s-1'), session('s-2')], '2'))
      .mockResolvedValueOnce(pageOf([session('s-2')], '1'))
    const removeSpy = vi.spyOn(sessionApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountSessions()

    const deleteBtn = wrapper.find('[data-testid="op-delete-s-1"]')
    expect(deleteBtn.classes()).toContain('bg-danger')
    await deleteBtn.trigger('click')
    expect(wrapper.find('[data-testid="session-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-session-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('s-1')
    expect(document.body.textContent).toContain('会话已删除')
    expect(wrapper.find('[data-testid="row-s-1"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('SessionsAdminView：四态与分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(sessionApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountSessions()

    expect(wrapper.find('[data-testid="session-skeleton"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(sessionApi, 'list')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([session('s-1')], '1'))
    const { wrapper } = await mountSessions()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
    await wrapper.find('[data-testid="retry-sessions"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="row-s-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('empty：无会话空态文案', async () => {
    vi.spyOn(sessionApi, 'list').mockResolvedValue(pageOf<ChatSessionVO>([], '0'))
    const { wrapper } = await mountSessions()

    expect(wrapper.text()).toContain('还没有会话')
    expect(wrapper.find('[data-testid="session-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('分页：共 N 条 + 翻页携带 page 参数', async () => {
    const listSpy = vi.spyOn(sessionApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([session(`s-${p}`)], '25', p)
    })
    const { wrapper } = await mountSessions()

    expect(wrapper.text()).toContain('共 25 条')
    expect(wrapper.text()).toContain('第 1 / 3 页')
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.find('[data-testid="row-s-2"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
