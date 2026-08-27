import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, dashboardApi, feedbackApi, sessionApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import FeedbackView from '@/views/FeedbackView.vue'

import type {
  ChatMessageVO,
  ChatSessionDetailVO,
  FeedbackIntentStat,
  FeedbackTrendItem,
  PageResponse,
  UserFeedbackVO,
  UserRole,
} from '@/lib/types'

/**
 * 反馈报表页测试（Task 21 核心交付；2026-08-27 图表库移除适配）
 *
 * 覆盖契约（设计 §2.4.6 + task-21 brief）：
 * 1. KPI 4 卡：总反馈（列表计数）/ 点赞 / 点踩 / 点赞率（feedbacks/stats 求和）
 * 2. 图 1 柱状图（trend 每日反馈数，CSS 自绘）+ 图 2 意图×赞踩堆叠条（stats 三字段）
 * 3. intentType 筛选 → list 携带参数
 * 4. 回放入口角色差异：SUPER_ADMIN 可见「查看会话回放」→ Drawer 调 sessionApi.detail
 *    渲染 messages 只读流（role/content/intentType/seq）；TEACHER 无回放入口
 * 5. 删除（两角色均可，二次确认）→ remove → toast → 刷新
 * 6. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 图表策略（图表库移除后）：图表为 CSS 自绘共享组件，jsdom 直接渲染真实 DOM
 * （柱 div/SVG 扇区），无 canvas 桩；断言由 option 契约改写为 DOM 数据绑定契约。
 * 动效确定性：mock '@/lib/motion' 强制减少动效偏好，柱生长直接呈现终态。
 */
vi.mock('@/lib/motion', () => ({ prefersReducedMotion: () => true }))

/** 反馈工厂（isLiked true=赞 / false=踩；userId 为 Long 字符串，10 位供短格式断言） */
function feedback(id: string, over: Partial<UserFeedbackVO> = {}): UserFeedbackVO {
  return {
    id,
    sessionId: `s-${id}`,
    messageId: `m-${id}`,
    userId: `900000000${id}`,
    isLiked: true,
    intentType: 'knowledge_question',
    createdAt: '2026-08-24T10:00:00',
    ...over,
  }
}

function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 统计 mock：知识问答 12 赞 3 踩 + 闲聊 5 赞 7 踩 → 点赞 17 / 点踩 10 / 率 63% */
const STATS: FeedbackIntentStat[] = [
  { intentType: 'knowledge_question', likedCount: '12', dislikedCount: '3' },
  { intentType: 'chat', likedCount: '5', dislikedCount: '7' },
]

/** 近 7 日趋势 mock（count 为 Long 字符串） */
const TREND: FeedbackTrendItem[] = [
  { date: '2026-08-18', count: '3' },
  { date: '2026-08-19', count: '5' },
  { date: '2026-08-20', count: '0' },
  { date: '2026-08-21', count: '2' },
  { date: '2026-08-22', count: '4' },
  { date: '2026-08-23', count: '1' },
  { date: '2026-08-24', count: '6' },
]

const FEEDBACKS = [feedback('1'), feedback('2', { isLiked: false, intentType: 'chat' })]

/** 会话回放消息 mock（role/content/intentType/seq 只读流） */
const MESSAGES: ChatMessageVO[] = [
  {
    id: 'msg-1',
    role: 'user',
    content: 'RAG 是什么？',
    messageType: 'TEXT',
    intentType: 'chat',
    runId: 'run-1',
    seq: 1,
    createdAt: '2026-08-24T10:00:00',
  },
  {
    id: 'msg-2',
    role: 'assistant',
    content: 'RAG 是检索增强生成技术',
    messageType: null,
    intentType: 'knowledge_question',
    runId: 'run-1',
    seq: 2,
    createdAt: '2026-08-24T10:00:01',
  },
]

const DETAIL: ChatSessionDetailVO = {
  id: 's-1',
  userId: '10001',
  title: 'RAG 咨询',
  status: 'ACTIVE',
  lastMessageAt: '2026-08-24T10:00:01',
  model: 'qwen3-8b',
  createdAt: '2026-08-24T09:00:00',
  messages: MESSAGES,
}

/** 全部接口 spy 恢复为稳定 mock */
function mockResolvedData() {
  vi.spyOn(feedbackApi, 'list').mockResolvedValue(pageOf(FEEDBACKS, '180'))
  vi.spyOn(feedbackApi, 'stats').mockResolvedValue(STATS)
  vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
}

/** 挂载反馈报表页：登录态（角色可指定，供回放入口差异断言） */
async function mountFeedback(role: UserRole = 'SUPER_ADMIN') {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role,
    displayName: '管理员',
  })
  const router = createAppRouter()
  await router.push('/feedback')
  await router.isReady()
  const wrapper = mount(FeedbackView, {
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

describe('FeedbackView：KPI 4 卡（stats + 列表计数）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('总反馈取列表 total；点赞/点踩为 stats 求和；点赞率 correct 百分比', async () => {
    mockResolvedData()
    const { wrapper } = await mountFeedback()

    // 总反馈：列表计数（Long 字符串直出）
    const total = wrapper.find('[data-testid="kpi-total"]')
    expect(total.text()).toContain('180')
    expect(total.classes()).toContain('tabular-nums')
    // 点赞 12+5=17 / 点踩 3+7=10 / 点赞率 17/27 → 63%
    expect(wrapper.find('[data-testid="kpi-liked"]').text()).toContain('17')
    expect(wrapper.find('[data-testid="kpi-disliked"]').text()).toContain('10')
    expect(wrapper.find('[data-testid="kpi-rate"]').text()).toContain('63%')
    wrapper.unmount()
  })
})

describe('FeedbackView：图表挂载（CSS 自绘）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('挂载两个图表：柱状图（trend 每日反馈数）+ 意图×赞踩堆叠条（stats）', async () => {
    mockResolvedData()
    const { wrapper } = await mountFeedback()

    // 图 1：柱状图，x 轴 MM-DD 标签全量、柱数与序列一致、count 字符串转 number 换算柱高
    const chart = wrapper.find('[data-testid="trend-chart"]')
    expect(chart.exists()).toBe(true)
    expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(7)
    expect(chart.text()).toContain('08-18')
    expect(chart.text()).toContain('08-24')
    // 满刻度 ceil(6/4)*4=8：首柱 3/8=37.5%、峰值柱 6/8=75%
    const bars = wrapper.findAll('[data-testid^="trend-bar-"]')
    expect(bars[0].find('.bar').attributes('style')).toContain('--h: 37.5%')
    expect(bars[6].find('.bar').attributes('style')).toContain('--h: 75%')

    // 图 2：堆叠条，两意图行（知识问答 12 赞 3 踩 / 闲聊 5 赞 7 踩），值 string→number
    expect(wrapper.find('[data-testid="intent-like-bar"]').exists()).toBe(true)
    const kqRow = wrapper.find('[data-testid="intent-like-row-knowledge_question"]')
    expect(kqRow.text()).toContain('知识问答')
    expect(kqRow.text()).toContain('赞 12 · 踩 3')
    expect(kqRow.find('.bg-success').attributes('style')).toContain('width: 80%')
    const chatRow = wrapper.find('[data-testid="intent-like-row-chat"]')
    expect(chatRow.text()).toContain('赞 5 · 踩 7')
    wrapper.unmount()
  })

  it('趋势为空：柱状图区块降级空态；统计为空：堆叠条区块降级空态', async () => {
    vi.spyOn(feedbackApi, 'list').mockResolvedValue(pageOf(FEEDBACKS, '2'))
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue([])
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue([])
    const { wrapper } = await mountFeedback()

    expect(wrapper.find('[data-testid="trend-chart"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="intent-like-bar"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('近 7 日暂无反馈记录')
    expect(wrapper.text()).toContain('暂无意图统计')
    wrapper.unmount()
  })
})

describe('FeedbackView：列表与意图筛选', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染 #id / 用户短格式 / 意图 Badge / 赞踩图标 / 时间；意图 Badge 三类渲染', async () => {
    mockResolvedData()
    const { wrapper } = await mountFeedback()

    // #id 与用户 userId 短格式（Long 字符串截断展示，非全量）
    expect(wrapper.find('[data-testid="row-1"]').text()).toContain('#1')
    const userIdCell = wrapper.find('[data-testid="fb-user-1"]')
    // 10 位 userId 截断为前 8 位 + 省略号（G10 短格式），全量不在场
    expect(userIdCell.text()).toContain('90000000')
    expect(userIdCell.text()).not.toContain('9000000001')

    // 意图 Badge：knowledge_question / chat 双色在场
    expect(wrapper.find('[data-testid="fb-intent-1"]').text()).toContain('knowledge_question')
    expect(wrapper.find('[data-testid="fb-intent-2"]').text()).toContain('chat')

    // 赞踩图标：赞 ThumbsUp 在场 / 踩 ThumbsDown 在场（Phosphor 线性图标，非 emoji）
    expect(wrapper.find('[data-testid="fb-liked-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="fb-disliked-2"]').exists()).toBe(true)
    expect(wrapper.text()).not.toMatch(/👍|👎/)

    // 时间列短格式 + tabular-nums
    const timeCell = wrapper.find('[data-testid="fb-time-1"]')
    expect(timeCell.text()).toContain('08-24 10:00')
    expect(timeCell.classes()).toContain('tabular-nums')
    wrapper.unmount()
  })

  it('intentType 筛选：选择意图后 list 携带参数且回到第 1 页', async () => {
    const listSpy = vi.spyOn(feedbackApi, 'list').mockResolvedValue(pageOf(FEEDBACKS, '180'))
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(STATS)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
    const { wrapper } = await mountFeedback()

    await wrapper.find('[data-testid="filter-intent"]').setValue('chat')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ intentType: 'chat', page: 1 })
    wrapper.unmount()
  })
})

describe('FeedbackView：回放入口角色差异（权限矩阵）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('TEACHER：无「查看会话回放」入口；删除入口在场（全局可删）', async () => {
    mockResolvedData()
    const detailSpy = vi.spyOn(sessionApi, 'detail')
    const { wrapper } = await mountFeedback('TEACHER')

    expect(wrapper.find('[data-testid="op-replay-1"]').exists()).toBe(false)
    expect(detailSpy).not.toHaveBeenCalled()
    // 教师同样可删除反馈（后端全局口径 I3）
    expect(wrapper.find('[data-testid="op-delete-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('SUPER_ADMIN：查看会话回放 → Drawer 700px 渲染 messages 只读流（role/content/intentType/seq）', async () => {
    mockResolvedData()
    const detailSpy = vi.spyOn(sessionApi, 'detail').mockResolvedValue(DETAIL)
    const { wrapper } = await mountFeedback('SUPER_ADMIN')

    await wrapper.find('[data-testid="op-replay-1"]').trigger('click')
    await flushPromises()

    expect(detailSpy).toHaveBeenCalledWith('s-1')
    const drawer = wrapper.find('[data-testid="session-drawer"]')
    expect(drawer.exists()).toBe(true)
    // Drawer 700px 规范（设计 §2.6：回放 Drawer 700px）
    expect(drawer.classes()).toContain('w-[700px]')
    // 消息流只读：role / content / intentType / seq 全部在场
    expect(drawer.text()).toContain('RAG 是什么？')
    expect(drawer.text()).toContain('RAG 是检索增强生成技术')
    expect(drawer.text()).toContain('assistant')
    expect(drawer.text()).toContain('knowledge_question')
    expect(drawer.text()).toContain('1')
    expect(drawer.text()).toContain('2')

    // Esc 关闭 Drawer
    await drawer.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="session-drawer"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('回放 Drawer 空消息：空态文案兜底', async () => {
    mockResolvedData()
    vi.spyOn(sessionApi, 'detail').mockResolvedValue({ ...DETAIL, messages: [] })
    const { wrapper } = await mountFeedback('SUPER_ADMIN')

    await wrapper.find('[data-testid="op-replay-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="session-drawer"]').text()).toContain('该会话暂无消息记录')
    wrapper.unmount()
  })
})

describe('FeedbackView：删除（二次确认）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('确认删除：remove(id) → toast → 关闭确认框并刷新', async () => {
    const listSpy = vi
      .spyOn(feedbackApi, 'list')
      .mockResolvedValueOnce(pageOf(FEEDBACKS, '2'))
      .mockResolvedValueOnce(pageOf([FEEDBACKS[1]], '1'))
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(STATS)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
    const removeSpy = vi.spyOn(feedbackApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountFeedback('TEACHER')

    await wrapper.find('[data-testid="op-delete-1"]').trigger('click')
    expect(wrapper.find('[data-testid="fb-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-fb-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('1')
    expect(document.body.textContent).toContain('反馈已删除')
    expect(wrapper.find('[data-testid="fb-del-dialog"]').exists()).toBe(false)
    // 失效重拉为异步链：行消失与重拉次数以 waitFor 收敛
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="row-1"]').exists()).toBe(false)
      expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    })
    wrapper.unmount()
  })
})

describe('FeedbackView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：骨架屏在场（KPI 灰块 + 图表灰块 + 表格灰行）', async () => {
    const never = <T>() => new Promise<T>(() => {})
    vi.spyOn(feedbackApi, 'list').mockReturnValue(never())
    vi.spyOn(feedbackApi, 'stats').mockReturnValue(never())
    vi.spyOn(dashboardApi, 'feedbackTrend').mockReturnValue(never())
    const { wrapper } = await mountFeedback()

    expect(wrapper.find('[data-testid="feedback-skeleton"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('180')
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(feedbackApi, 'list').mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
    vi.spyOn(feedbackApi, 'stats').mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
    vi.spyOn(dashboardApi, 'feedbackTrend').mockRejectedValueOnce(
      new ApiError(503, '服务暂时不可用', 503),
    )
    mockResolvedData()
    const { wrapper } = await mountFeedback()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
    await wrapper.find('[data-testid="retry-feedback"]').trigger('click')
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="kpi-total"]').text()).toContain('180'),
    )
    wrapper.unmount()
  })

  it('empty：无反馈空态文案 + 无表格', async () => {
    vi.spyOn(feedbackApi, 'list').mockResolvedValue(pageOf<UserFeedbackVO>([], '0'))
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(STATS)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
    const { wrapper } = await mountFeedback()

    expect(wrapper.text()).toContain('还没有反馈记录')
    expect(wrapper.find('[data-testid="fb-table"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
