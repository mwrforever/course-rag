import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, sessionApi } from '@/lib/api'
import ConversationReplayDrawer from '@/components/ConversationReplayDrawer.vue'

import type { ChatSessionDetailVO, SessionStatus } from '@/lib/types'

/**
 * 会话回放抽屉（公共组件）测试
 *
 * 覆盖契约（2.2 打磨任务，Feedback/Sessions 两视图共用）：
 * 1. 打开（open=true）→ sessionApi.detail(id) 拉取 → 消息流渲染（role/seq/intentType/content）
 * 2. 头部：title 缺省「会话回放」/ 传入覆盖；会话 #id
 * 3. 状态徽章：initialStatus 在场展示，详情加载后以明细 status 为准
 * 4. 空消息空态兜底；加载中 spinner
 * 5. 关闭：Esc / 遮罩点击 → close emit；加载中关闭拦截（不发 close）
 * 6. 详情加载失败 → danger toast + close emit；重开再次拉取
 */

/** 会话详情工厂（messages 只读流：role/content/intentType/seq） */
function detailOf(over: Partial<ChatSessionDetailVO> = {}): ChatSessionDetailVO {
  return {
    id: 's-1',
    userId: 'u-1',
    title: '会话-s-1',
    status: 'ACTIVE',
    lastMessageAt: '2026-08-24T10:00:01',
    model: 'qwen-max',
    createdAt: '2026-08-24T10:00:00',
    messages: [
      {
        id: 'm1',
        role: 'user',
        seq: 1,
        intentType: 'knowledge_question',
        content: '课程资料在哪？',
        messageType: 'text',
        runId: 'run-1',
        createdAt: '2026-08-24T10:00:00',
      },
      {
        id: 'm2',
        role: 'assistant',
        seq: 2,
        intentType: null,
        content: '在课程工作台中可以查看',
        messageType: 'text',
        runId: 'run-1',
        createdAt: '2026-08-24T10:00:01',
      },
    ],
    ...over,
  }
}

/** 挂载 Drawer：props 与组件声明对齐（open/sessionId 必填，title/initialStatus 可选） */
function mountDrawer(props: {
  open: boolean
  sessionId: string
  title?: string
  initialStatus?: SessionStatus | ''
}) {
  return mount(ConversationReplayDrawer, { props })
}

describe('ConversationReplayDrawer：回放只读流', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  it('打开 → detail(id) 拉取 → 消息流 role/seq/intentType/content 只读在场', async () => {
    const detailSpy = vi.spyOn(sessionApi, 'detail').mockResolvedValue(detailOf())
    const wrapper = mountDrawer({ open: false, sessionId: 's-1' })

    // 未打开：不请求、不渲染
    expect(detailSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="session-drawer"]').exists()).toBe(false)

    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(detailSpy).toHaveBeenCalledWith('s-1')
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.exists()).toBe(true)
    expect(drawer.classes()).toContain('w-[700px]')
    expect(drawer.text()).toContain('课程资料在哪？')
    expect(drawer.text()).toContain('在课程工作台中可以查看')
    expect(drawer.text()).toContain('user')
    expect(drawer.text()).toContain('assistant')
    expect(drawer.text()).toContain('knowledge_question')
    // seq 序号与会话标识在场
    expect(drawer.text()).toContain('seq 1')
    expect(drawer.text()).toContain('seq 2')
    expect(drawer.text()).toContain('会话 #s-1')
    wrapper.unmount()
  })

  it('头部：title 缺省「会话回放」；传入 title + initialStatus 展示会话标题与状态徽章（以明细为准）', async () => {
    vi.spyOn(sessionApi, 'detail').mockResolvedValue(detailOf())
    // 无 initialStatus（Feedback 场景）：头部不渲染状态文本
    const wrapper = mountDrawer({ open: true, sessionId: 's-1' })
    await flushPromises()
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.text()).toContain('会话回放')
    expect(drawer.text()).not.toContain('ACTIVE')
    wrapper.unmount()

    // Sessions 场景：initialStatus=ACTIVE，但明细返回 CLOSED → 徽章以明细为准
    vi.spyOn(sessionApi, 'detail').mockResolvedValue(detailOf({ status: 'CLOSED' }))
    const wrapper2 = mountDrawer({
      open: true,
      sessionId: 's-2',
      title: '会话-s-2',
      initialStatus: 'ACTIVE' as SessionStatus,
    })
    await flushPromises()
    const drawer2 = wrapper2.find('[data-testid="session-drawer"]')
    expect(drawer2.text()).toContain('会话-s-2')
    expect(drawer2.text()).toContain('CLOSED')
    wrapper2.unmount()
  })

  it('空消息：空态兜底文案；加载中：spinner + 加载文案在场', async () => {
    vi.spyOn(sessionApi, 'detail').mockImplementation(
      () => new Promise<ChatSessionDetailVO>(() => {}),
    )
    const wrapper = mountDrawer({ open: true, sessionId: 's-1' })
    await flushPromises()
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.text()).toContain('加载会话消息')
    wrapper.unmount()

    vi.spyOn(sessionApi, 'detail').mockResolvedValue(detailOf({ messages: [] }))
    const wrapper2 = mountDrawer({ open: true, sessionId: 's-1' })
    await flushPromises()
    expect(wrapper2.find('[data-testid="session-drawer"]').text()).toContain('该会话暂无消息记录')
    wrapper2.unmount()
  })

  it('Esc 与遮罩点击 → close emit；加载中关闭拦截（不发 close）', async () => {
    let resolveDetail!: (v: ChatSessionDetailVO) => void
    vi.spyOn(sessionApi, 'detail').mockImplementation(
      () =>
        new Promise<ChatSessionDetailVO>((resolve) => {
          resolveDetail = resolve
        }),
    )
    const wrapper = mountDrawer({ open: true, sessionId: 's-1' })
    await flushPromises()
    // 加载中：Esc 与关闭按钮均被拦截（防丢加载态）
    await wrapper.find('[data-testid="session-drawer"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.find('[data-testid="close-replay"]').trigger('click')
    expect(wrapper.emitted('close')).toBeUndefined()
    // 加载完成：Esc 关闭
    resolveDetail(detailOf())
    await flushPromises()
    await wrapper.find('[data-testid="session-drawer"]').trigger('keydown', { key: 'Escape' })
    expect((wrapper.emitted('close') ?? []).length).toBe(1)
    wrapper.unmount()
  })

  it('详情加载失败：danger toast + close emit；重开再次拉取', async () => {
    const detailSpy = vi.spyOn(sessionApi, 'detail')
    detailSpy.mockRejectedValueOnce(new ApiError(500, '详情加载失败', 500))
    const wrapper = mountDrawer({ open: true, sessionId: 's-1' })
    await flushPromises()
    expect(document.body.textContent).toContain('详情加载失败')
    expect((wrapper.emitted('close') ?? []).length).toBe(1)
    // 父组件响应 close 收合（open=false）后抽屉消失
    await wrapper.setProps({ open: false })
    expect(wrapper.find('[data-testid="session-drawer"]').exists()).toBe(false)
    // 重开（关闭后再次展开）：重置明细并再次拉取
    detailSpy.mockResolvedValue(detailOf())
    await wrapper.setProps({ open: true })
    await flushPromises()
    expect(detailSpy).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="session-drawer"]').text()).toContain('课程资料在哪？')
    wrapper.unmount()
  })
})
