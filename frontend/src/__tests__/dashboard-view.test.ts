import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, dashboardApi, documentApi, feedbackApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'

import type {
  DashboardStats,
  DocumentVO,
  FeedbackIntentStat,
  FeedbackStats,
  FeedbackTrendItem,
} from '@/lib/types'

/**
 * 仪表盘页测试（2026-08-27 紫系换肤重构适配）
 *
 * 覆盖契约（设计稿 Edukors Dashboard 映射 + 任务 brief）：
 * 1. KPI 4 卡（StatCard + count-up）：文档总数/待修正分片[amber 图标警示]/
 *    学生总数/点赞率，值 Long 字符串转 number 滚动至终值
 * 2. 反馈趋势 CSS 柱状图：柱数/x 轴 MM-DD 标签/hover tooltip/7-30 天范围切换重拉
 * 3. 反馈意图 donut：三意图扇区/图例占比/hover tooltip/中心总量
 * 4. 意图×赞踩堆叠条：三意图行/赞踩计数/行内赞段宽度
 * 5. 最近上传文档 5 行小表 + eye 按钮跳文档详情路由
 * 6. 快捷入口 5 项跳转 + 待修正 KPI 卡点击跳分片页
 * 7. 四态：loading skeleton / empty 分区块空态 / error 横幅重试 / 正常
 * 8. 无环比：断言「↗/↑/环比」类元素不存在（后端无历史对比，禁止假数据）
 * 9. core/trend 查询拆键（PERF-12）：范围切换仅重拉趋势接口（core 四接口零重拉）；
 *    趋势失败仅趋势区报错（页面其余区块正常、无整页横幅）；
 *    keepPreviousData 保证切换期间旧趋势序列不闪空。
 *
 * 图表策略（图表库移除后）：图表为 CSS/SVG 自绘组件，jsdom 直接渲染真实 DOM
 * （柱 div/--h 变量/SVG circle/tooltip div），无需 canvas 桩。
 * 动效确定性：mock '@/lib/motion' 强制 prefersReducedMotion=true，使 count-up
 * 数字滚动与柱生长动画直接呈现终态（组件内建无障碍降级路径），断言免于动画时序抖动。
 */
vi.mock('@/lib/motion', () => ({ prefersReducedMotion: () => true }))

/** 7 日反馈趋势 mock（count 为 Long 字符串，时间为 ISO-8601 无时区串） */
const TREND_7: FeedbackTrendItem[] = [
  { date: '2026-08-18', count: '3' },
  { date: '2026-08-19', count: '5' },
  { date: '2026-08-20', count: '0' },
  { date: '2026-08-21', count: '2' },
  { date: '2026-08-22', count: '4' },
  { date: '2026-08-23', count: '1' },
  { date: '2026-08-24', count: '6' },
]

/** 30 日趋势 mock：8 月逐日生成（范围切换档断言用） */
const TREND_30: FeedbackTrendItem[] = Array.from({ length: 30 }, (_, i) => ({
  date: `2026-08-${String(i + 1).padStart(2, '0')}`,
  count: String((i * 7) % 13),
}))

/** 意图统计 mock：知识问答 12 赞 3 踩 / 闲聊 5 赞 7 踩 / 未知意图 2 赞 1 踩（计数全字符串） */
const INTENTS: FeedbackIntentStat[] = [
  { intentType: 'knowledge_question', likedCount: '12', dislikedCount: '3' },
  { intentType: 'chat', likedCount: '5', dislikedCount: '7' },
  { intentType: 'unknown', likedCount: '2', dislikedCount: '1' },
]

/** 最近文档 mock：覆盖 ETL 多状态（INDEXED 终态 + PENDING/EMBEDDING 工作态 + FAILED 终态） */
const DOCS: DocumentVO[] = [
  {
    id: 'd-1',
    kbId: 'kb-1',
    title: 'Q3 大模型课程课件.pdf',
    fileType: 'PDF',
    fileSize: '2048',
    parseStatus: 'INDEXED',
    chunkCount: 42,
    errorMessage: '',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-24T09:30:00',
    updatedAt: '2026-08-24T09:30:00',
  },
  {
    id: 'd-2',
    kbId: 'kb-1',
    title: 'RAG 架构入门.md',
    fileType: 'MD',
    fileSize: '512',
    parseStatus: 'EMBEDDING',
    chunkCount: 0,
    errorMessage: '',
    metadataJson: '',
    courseId: '9',
    createdBy: '1001',
    createdAt: '2026-08-23T16:05:00',
    updatedAt: '2026-08-23T16:05:00',
  },
  {
    id: 'd-3',
    kbId: 'kb-2',
    title: '课程大纲.docx',
    fileType: 'DOCX',
    fileSize: '1024',
    parseStatus: 'PENDING',
    chunkCount: 0,
    errorMessage: '',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-22T11:20:00',
    updatedAt: '2026-08-22T11:20:00',
  },
  {
    id: 'd-4',
    kbId: 'kb-2',
    title: '损坏的旧讲义.pdf',
    fileType: 'PDF',
    fileSize: '300',
    parseStatus: 'FAILED',
    chunkCount: 0,
    errorMessage: '解析失败：文件损坏',
    metadataJson: '',
    courseId: null,
    createdBy: '1001',
    createdAt: '2026-08-21T14:00:00',
    updatedAt: '2026-08-21T14:00:00',
  },
]

/** 仪表盘 KPI mock（dashboard/stats 实际仅返回三字段；类型合并了 feedback/stats 故补齐占位） */
const KPIS: DashboardStats = {
  documentCount: '128',
  pendingChunkCount: '5',
  knowledgeBaseCount: '12',
  // 以下三字段实际来自 feedback/stats（类型合并占位，页面不消费）
  studentCount: '0',
  feedbackCount: '0',
  likeRate: 0,
}

const FEEDBACK: FeedbackStats = { studentCount: '46', feedbackCount: '180', likeRate: 0.86 }

/** 全部接口 spy 恢复为返回稳定 mock（五接口并行加载；趋势按天数分档） */
function mockResolvedData() {
  vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
  vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
  vi.spyOn(dashboardApi, 'feedbackTrend').mockImplementation(async (days = 7) =>
    days === 30 ? TREND_30 : TREND_7,
  )
  vi.spyOn(documentApi, 'list').mockResolvedValue({
    records: DOCS,
    total: '4',
    page: 1,
    size: 5,
  })
  vi.spyOn(feedbackApi, 'stats').mockResolvedValue(INTENTS)
}

/** 挂载仪表盘：登录态 + 独立路由器（快捷入口/eye/KPI 卡点击走真实路由守卫） */
async function mountDashboard() {
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
  const wrapper = mount(DashboardView, {
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
  await router.isReady()
  await flushPromises()
  return { wrapper, router, pinia }
}

describe('DashboardView：KPI 4 卡渲染（StatCard + count-up 终值）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染文档总数/待修正分片/学生总数/点赞率四卡，数值正确', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 四卡标签齐全
    expect(wrapper.text()).toContain('文档总数')
    expect(wrapper.text()).toContain('待修正分片')
    expect(wrapper.text()).toContain('学生总数')
    expect(wrapper.text()).toContain('点赞率')

    // Long 字符串计数转 number 滚动至终值：128 / 5 / 46 与 likeRate 0.86 → 86%
    expect(wrapper.find('[data-testid="kpi-documents"]').text()).toContain('128')
    expect(wrapper.find('[data-testid="kpi-pending"]').text()).toContain('5')
    expect(wrapper.find('[data-testid="kpi-students"]').text()).toContain('46')
    expect(wrapper.find('[data-testid="kpi-like"]').text()).toContain('86%')
    wrapper.unmount()
  })

  it('四卡为 StatCard 造型（lav 紫白底 + 图标圆），待修正卡 amber 图标警示 + 可点击', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // lav 底（bg-brand-light）StatCard 形态
    expect(wrapper.find('[data-testid="kpi-documents"]').classes()).toContain('bg-brand-light')
    // 待修正卡：button 可点击 + 图标圆 amber 警示（text-warning 语义层）+ 行动提示
    const pending = wrapper.find('[data-testid="kpi-pending"]')
    expect(pending.element.tagName).toBe('BUTTON')
    expect(pending.find('.stat-icon').classes()).toContain('text-warning')
    expect(pending.text()).toContain('点击进入修正工作台')
    wrapper.unmount()
  })

  it('无环比文案：不渲染「↗/↑/环比」类元素（后端无历史对比，禁止假数据）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    expect(wrapper.text()).not.toMatch(/↗|↑|环比/)
    wrapper.unmount()
  })
})

describe('DashboardView：反馈趋势柱状图（CSS 自绘）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染 7 柱 + MM-DD x 轴标签，柱高按满刻度换算（--h 变量）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    const bars = wrapper.findAll('[data-testid^="trend-bar-"]')
    expect(bars).toHaveLength(7)
    // x 轴标签：date 截取 MM-DD（30 天档才抽稀，7 天档全量）
    expect(wrapper.find('[data-testid="trend-chart"]').text()).toContain('08-18')
    expect(wrapper.find('[data-testid="trend-chart"]').text()).toContain('08-24')
    // 柱高换算：max=6 → 满刻度 8；首柱 count 3 → 3/8 = 37.5%
    expect(bars[0].find('.bar').attributes('style')).toContain('--h: 37.5%')
    // 峰值柱 count 6 → 6/8 = 75%
    expect(bars[6].find('.bar').attributes('style')).toContain('--h: 75%')
    wrapper.unmount()
  })

  it('柱 hover 弹出深底 tooltip（短日期 + 条数），移出隐藏', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 初始无 tooltip
    expect(wrapper.find('[data-testid="trend-tip"]').exists()).toBe(false)
    // hover 第 2 柱（08-19 · 5 条）
    await wrapper.find('[data-testid="trend-bar-1"]').trigger('mouseenter')
    const tip = wrapper.find('[data-testid="trend-tip"]')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toBe('08-19 · 5 条')
    // 移出绘制区隐藏
    await wrapper.find('[data-testid="trend-bar-1"]').trigger('mouseleave')
    expect(wrapper.find('[data-testid="trend-tip"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('时间范围切换 7 → 30 天：feedbackTrend 以 30 重拉并渲染 30 柱', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 打开范围下拉 → 选择「近 30 天」
    await wrapper.find('[data-testid="trend-range"]').trigger('click')
    await wrapper.find('[data-testid="range-opt-30"]').trigger('click')

    // queryKey 变化触发重拉：趋势接口收到 days=30，图表重渲染 30 柱（30 天档 x 轴标签抽稀）
    await vi.waitFor(() => {
      expect(dashboardApi.feedbackTrend).toHaveBeenCalledWith(30)
      expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(30)
    })
    wrapper.unmount()
  })

  it('趋势为空：不渲染柱状图，展示区块空态', async () => {
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue([])
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: DOCS, total: '4', page: 1, size: 5 })
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(INTENTS)
    const { wrapper } = await mountDashboard()

    expect(wrapper.find('[data-testid="trend-chart"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('近 7 日暂无反馈记录')
    wrapper.unmount()
  })
})

describe('DashboardView：反馈意图 donut（SVG 自绘）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染三意图扇区 + 图例占比 + 中心总条数（15+12+3=30）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 三扇区（意图按 知识问答 → 闲聊 → 未知意图 稳定排序）
    expect(wrapper.findAll('[data-testid^="donut-seg-"]')).toHaveLength(3)
    // 图例：标签 + 条数 + 占比（知识问答 15 条 50% / 闲聊 12 条 40% / 未知意图 3 条 10%）
    const legend = wrapper.find('[data-testid="donut-legend"]').text()
    expect(legend).toContain('知识问答')
    expect(legend).toContain('15 条 · 50%')
    expect(legend).toContain('闲聊')
    expect(legend).toContain('12 条 · 40%')
    expect(legend).toContain('未知意图')
    expect(legend).toContain('3 条 · 10%')
    // 中心总量（真实数据：全部意图赞踩总条数）
    expect(wrapper.find('[data-testid="intent-donut"]').text()).toContain('30')
    wrapper.unmount()
  })

  it('扇区 hover 高亮 + 深底 tooltip（标签 + 条数 + 占比），移出恢复', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 初始无 tooltip、无 hovering 态
    expect(wrapper.find('[data-testid="donut-tip"]').exists()).toBe(false)
    // hover 首扇区（知识问答 · 15 条 · 50%）
    await wrapper.find('[data-testid="donut-seg-0"]').trigger('mouseenter')
    const tip = wrapper.find('[data-testid="donut-tip"]')
    expect(tip.exists()).toBe(true)
    expect(tip.text()).toBe('知识问答 · 15 条 · 50%')
    // hover 态：svg 加 hovering 类、当前扇区加 on 类（其余扇区降透明由 CSS 承接）
    expect(wrapper.find('svg.hovering').exists()).toBe(true)
    expect(wrapper.find('[data-testid="donut-seg-0"]').classes()).toContain('on')
    // 移出恢复
    await wrapper.find('[data-testid="donut-seg-0"]').trigger('mouseleave')
    expect(wrapper.find('[data-testid="donut-tip"]').exists()).toBe(false)
    expect(wrapper.find('svg.hovering').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('DashboardView：意图×赞踩堆叠条卡', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('三意图行渲染赞踩计数，行内赞段宽度按占比换算', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 三行意图（knowledge_question 12 赞 3 踩 → 赞段 80%）
    const row = wrapper.find('[data-testid="intent-like-row-knowledge_question"]')
    expect(row.text()).toContain('知识问答')
    expect(row.text()).toContain('赞 12 · 踩 3')
    expect(row.find('.bg-success').attributes('style')).toContain('width: 80%')
    // 闲聊行（5 赞 7 踩 → 赞段 5/12 ≈ 41.67%，浮点尾数不逐位断言）
    const chatRow = wrapper.find('[data-testid="intent-like-row-chat"]')
    expect(chatRow.text()).toContain('赞 5 · 踩 7')
    expect(chatRow.find('.bg-success').attributes('style')).toMatch(/width: 41\.66\d+%/)
    wrapper.unmount()
  })

  it('意图统计为空：donut 与堆叠条双双降级空态', async () => {
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND_7)
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: DOCS, total: '4', page: 1, size: 5 })
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue([])
    const { wrapper } = await mountDashboard()

    expect(wrapper.find('[data-testid="intent-donut"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="intent-like-bar"]').exists()).toBe(false)
    // donut 卡与堆叠条卡双双降级为区块空态文案
    const emptyTexts = wrapper.findAll('p').filter((p) => p.text() === '暂无意图统计')
    expect(emptyTexts).toHaveLength(2)
    wrapper.unmount()
  })
})

describe('DashboardView：最近上传文档 5 行小表', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染文件名/状态 Badge/时间，状态按设计 §2.5 映射', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 文件名在场
    expect(wrapper.text()).toContain('Q3 大模型课程课件.pdf')
    expect(wrapper.text()).toContain('RAG 架构入门.md')

    // 状态 Badge：INDEXED 终态 emerald / FAILED 终态 danger / EMBEDDING 工作态 amber
    const badges = wrapper.findAll('[data-testid="recent-docs"] span')
    const badgeTexts = badges.map((b) => b.text())
    expect(badgeTexts).toContain('INDEXED')
    expect(badgeTexts).toContain('EMBEDDING')
    expect(badgeTexts).toContain('PENDING')
    expect(badgeTexts).toContain('FAILED')
    const indexed = badges.find((b) => b.text() === 'INDEXED')
    expect(indexed?.classes()).toContain('bg-emerald-50')
    const failed = badges.find((b) => b.text() === 'FAILED')
    expect(failed?.classes()).toContain('bg-red-50')

    // 时间短格式 MM-DD HH:mm
    expect(wrapper.text()).toContain('08-24 09:30')
    expect(wrapper.text()).toContain('08-21 14:00')
    wrapper.unmount()
  })

  it('eye 按钮跳转既有文档详情路由（/knowledge/documents/:id）', async () => {
    mockResolvedData()
    const { wrapper, router } = await mountDashboard()

    await wrapper.find('[data-testid="doc-eye-d-1"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/knowledge/documents/d-1'))
    expect(router.currentRoute.value.name).toBe('knowledge-document-detail')
    wrapper.unmount()
  })

  it('无文档：区块空态提示（含行动引导，禁止裸「暂无数据」）', async () => {
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND_7)
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: [], total: '0', page: 1, size: 5 })
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(INTENTS)
    const { wrapper } = await mountDashboard()

    expect(wrapper.text()).toContain('暂无上传文档')
    expect(wrapper.text()).toContain('上传文档')
    wrapper.unmount()
  })
})

describe('DashboardView：快捷入口 5 项与待修正卡跳转', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('5 个快捷入口各自跳转目标页', async () => {
    mockResolvedData()
    const { wrapper, router } = await mountDashboard()

    // 5 项入口文案齐全
    expect(wrapper.text()).toContain('上传文档')
    expect(wrapper.text()).toContain('待修正分片')
    expect(wrapper.text()).toContain('新建课程')
    expect(wrapper.text()).toContain('添加学生')
    expect(wrapper.text()).toContain('反馈报表')

    // 逐项点击断言路由跳转（真实守卫：TEACHER 两角色页放行）
    const cases: Array<[string, string]> = [
      ['quick-upload', '/knowledge/documents'],
      ['quick-course', '/courses/new'],
      ['quick-students', '/students'],
      ['quick-feedback', '/feedback'],
    ]
    for (const [testid, path] of cases) {
      await wrapper.find(`[data-testid="${testid}"]`).trigger('click')
      await vi.waitFor(() => expect(router.currentRoute.value.path).toBe(path))
    }
    wrapper.unmount()
  })

  it('待修正分片快捷入口与 amber KPI 卡均跳转分片修正页', async () => {
    mockResolvedData()
    const { wrapper, router } = await mountDashboard()

    await wrapper.find('[data-testid="quick-chunks"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/knowledge/chunks'))
    wrapper.unmount()
  })

  it('KPI 待修正卡点击跳转分片修正页（amber 警示直达工作台）', async () => {
    mockResolvedData()
    const { wrapper, router } = await mountDashboard()

    await wrapper.find('[data-testid="kpi-pending"]').trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('knowledge-chunks'))
    wrapper.unmount()
  })
})

describe('DashboardView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：骨架屏与最终布局同形（KPI 灰块 + 入口灰块 + 图表灰块），不出数值', async () => {
    // 全部接口挂起（永不 resolve），锁定加载态
    const never = <T>() => new Promise<T>(() => {})
    vi.spyOn(dashboardApi, 'stats').mockReturnValue(never())
    vi.spyOn(dashboardApi, 'feedbackStats').mockReturnValue(never())
    vi.spyOn(dashboardApi, 'feedbackTrend').mockReturnValue(never())
    vi.spyOn(documentApi, 'list').mockReturnValue(never())
    vi.spyOn(feedbackApi, 'stats').mockReturnValue(never())
    const { wrapper } = await mountDashboard()

    expect(wrapper.find('[data-testid="dashboard-skeleton"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('文档总数')
    expect(wrapper.text()).not.toContain('128')
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    // 首轮全部 503 失败，重试后全部成功
    vi.spyOn(dashboardApi, 'stats').mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
    vi.spyOn(dashboardApi, 'feedbackStats').mockRejectedValueOnce(
      new ApiError(503, '服务暂时不可用', 503),
    )
    vi.spyOn(dashboardApi, 'feedbackTrend').mockRejectedValueOnce(
      new ApiError(503, '服务暂时不可用', 503),
    )
    vi.spyOn(documentApi, 'list').mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
    vi.spyOn(feedbackApi, 'stats').mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 页内错误横幅 + 重试按钮（设计 §1.7：danger-soft 底）
    const banner = wrapper.find('[role="alert"]')
    expect(banner.text()).toContain('服务暂时不可用，请稍后重试')
    expect(banner.text()).toContain('重试')

    // 点击重试重新加载 → 正常态
    await wrapper.find('[data-testid="retry"]').trigger('click')
    await vi.waitFor(() =>
      expect(wrapper.find('[data-testid="kpi-documents"]').text()).toContain('128'),
    )
    wrapper.unmount()
  })

  it('error：非 ApiError 异常展示页面兜底文案', async () => {
    vi.spyOn(dashboardApi, 'stats').mockRejectedValueOnce(new Error('boom'))
    vi.spyOn(dashboardApi, 'feedbackStats').mockRejectedValueOnce(new Error('boom'))
    vi.spyOn(dashboardApi, 'feedbackTrend').mockRejectedValueOnce(new Error('boom'))
    vi.spyOn(documentApi, 'list').mockRejectedValueOnce(new Error('boom'))
    vi.spyOn(feedbackApi, 'stats').mockRejectedValueOnce(new Error('boom'))
    const { wrapper } = await mountDashboard()

    expect(wrapper.find('[role="alert"]').text()).toContain('仪表盘加载失败，请稍后重试')
    wrapper.unmount()
  })
})

describe('DashboardView：core/trend 查询拆键（PERF-12）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('切换 7 → 30 天仅重拉趋势接口，core 四接口零重拉', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    // 初始挂载：五接口各拉取一次
    expect(dashboardApi.stats).toHaveBeenCalledTimes(1)
    expect(dashboardApi.feedbackStats).toHaveBeenCalledTimes(1)
    expect(documentApi.list).toHaveBeenCalledTimes(1)
    expect(feedbackApi.stats).toHaveBeenCalledTimes(1)
    expect(dashboardApi.feedbackTrend).toHaveBeenCalledTimes(1)

    // 打开范围下拉 → 选择「近 30 天」
    await wrapper.find('[data-testid="trend-range"]').trigger('click')
    await wrapper.find('[data-testid="range-opt-30"]').trigger('click')

    // 拆键断言：仅趋势接口以 30 重拉一次，core 四接口调用次数保持不变（KPI/文档/意图不闪重载）
    await vi.waitFor(() => expect(dashboardApi.feedbackTrend).toHaveBeenCalledWith(30))
    await flushPromises()
    expect(dashboardApi.feedbackTrend).toHaveBeenCalledTimes(2)
    expect(dashboardApi.stats).toHaveBeenCalledTimes(1)
    expect(dashboardApi.feedbackStats).toHaveBeenCalledTimes(1)
    expect(documentApi.list).toHaveBeenCalledTimes(1)
    expect(feedbackApi.stats).toHaveBeenCalledTimes(1)
    // 30 天档序列渲染 30 柱
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(30))
    wrapper.unmount()
  })

  it('趋势失败仅趋势区报错：KPI/文档表正常渲染，无整页错误横幅；分区重试恢复', async () => {
    // 趋势首拉 503 失败（重试后成功），core 四接口正常
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(TREND_7)
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: DOCS, total: '4', page: 1, size: 5 })
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(INTENTS)
    const { wrapper } = await mountDashboard()

    // core 区不受趋势失败影响：KPI 与最近文档表正常渲染
    expect(wrapper.find('[data-testid="kpi-documents"]').text()).toContain('128')
    expect(wrapper.text()).toContain('Q3 大模型课程课件.pdf')
    // 趋势分区错误（503 降级文案）+ 分区重试钮在场
    const trendError = wrapper.find('[data-testid="trend-error"]')
    expect(trendError.exists()).toBe(true)
    expect(trendError.text()).toContain('服务暂时不可用，请稍后重试')
    expect(wrapper.find('[data-testid="trend-retry"]').exists()).toBe(true)
    // 无整页错误横幅（core 成功即不透出页面级 retry 契约）
    expect(wrapper.find('[data-testid="retry"]').exists()).toBe(false)

    // 分区重试：仅趋势恢复柱状图，无需整页刷新
    await wrapper.find('[data-testid="trend-retry"]').trigger('click')
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(7))
    wrapper.unmount()
  })

  it('keepPreviousData：切换期间保留旧趋势序列，30 天数据返回前不闪空', async () => {
    // 30 天档挂起（手动 resolve），锁定切换中间态
    let resolveTrend30: (v: FeedbackTrendItem[]) => void = () => {}
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockImplementation(async (days = 7) => {
      if (days === 30) {
        return new Promise<FeedbackTrendItem[]>((resolve) => {
          resolveTrend30 = resolve
        })
      }
      return TREND_7
    })
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: DOCS, total: '4', page: 1, size: 5 })
    vi.spyOn(feedbackApi, 'stats').mockResolvedValue(INTENTS)
    const { wrapper } = await mountDashboard()
    expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(7)

    // 切至 30 天：新序列未返回前仍渲染旧 7 柱（placeholderData 保留上次数据）
    await wrapper.find('[data-testid="trend-range"]').trigger('click')
    await wrapper.find('[data-testid="range-opt-30"]').trigger('click')
    await vi.waitFor(() => expect(dashboardApi.feedbackTrend).toHaveBeenCalledWith(30))
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(7)

    // 30 天序列返回后替换为 30 柱
    resolveTrend30(TREND_30)
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.findAll('[data-testid^="trend-bar-"]')).toHaveLength(30))
    wrapper.unmount()
  })
})
