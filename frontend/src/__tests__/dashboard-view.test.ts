import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, dashboardApi, documentApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import DashboardView from '@/views/DashboardView.vue'

import type { DashboardStats, DocumentVO, FeedbackStats, FeedbackTrendItem } from '@/lib/types'

/**
 * 仪表盘页测试（Task 17 核心交付）
 *
 * 覆盖契约（设计 §2.4.1 + 任务 brief）：
 * 1. KPI 4 卡渲染（文档总数/待修正分片[amber 警示]/学生总数/点赞率，值全 string 渲染）
 * 2. 单折线挂载：vue-echarts 实例收到 Line option（x 轴日期/序列数值/主题色）
 * 3. 快捷入口 5 项跳转 + 待修正 KPI 卡点击跳分片页
 * 4. 四态：loading skeleton / empty 分区块空态 / error 横幅重试 / 正常
 * 5. 无环比：断言「↗/↑/环比」类元素不存在（后端无历史对比，禁止假数据）
 *
 * 图表策略：jsdom 无 canvas 实现，`vi.mock('vue-echarts')` 替换为渲染占位 div 的
 * 桩组件（render 函数形态，runtime-only Vue 无模板编译器），断言挂载与 option 入参。
 * echarts/core 按需注册（use）为纯 JS 注册表操作，jsdom 下安全执行。
 */

/** 桩组件：接收 option 透传即可，避免 echarts 真实 canvas 初始化 */
vi.mock('vue-echarts', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    default: defineComponent({
      name: 'VChart',
      props: { option: { type: Object, default: null } },
      render() {
        return h('div', { class: 'v-chart-stub' })
      },
    }),
  }
})

/** 7 日反馈趋势 mock（count 为 Long 字符串，时间为 ISO-8601 无时区串） */
const TREND: FeedbackTrendItem[] = [
  { date: '2026-08-18', count: '3' },
  { date: '2026-08-19', count: '5' },
  { date: '2026-08-20', count: '0' },
  { date: '2026-08-21', count: '2' },
  { date: '2026-08-22', count: '4' },
  { date: '2026-08-23', count: '1' },
  { date: '2026-08-24', count: '6' },
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

/** 全部接口 spy 恢复为返回稳定 mock（四接口并行加载） */
function mockResolvedData() {
  vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
  vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
  vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
  vi.spyOn(documentApi, 'list').mockResolvedValue({
    records: DOCS,
    total: '4',
    page: 1,
    size: 5,
  })
}

/** 挂载仪表盘：登录态 + 独立路由器（快捷入口点击走真实路由守卫） */
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
  const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
  await router.isReady()
  await flushPromises()
  return { wrapper, router, pinia }
}

describe('DashboardView：KPI 4 卡渲染（值全 string）', () => {
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

    // string 计数值直接渲染：128 / 5 / 46 与 likeRate 0.86 → 86%
    expect(wrapper.find('[data-testid="kpi-documents"]').text()).toContain('128')
    expect(wrapper.find('[data-testid="kpi-pending"]').text()).toContain('5')
    expect(wrapper.find('[data-testid="kpi-students"]').text()).toContain('46')
    expect(wrapper.find('[data-testid="kpi-like"]').text()).toContain('86%')
    wrapper.unmount()
  })

  it('待修正分片卡带 amber 警示样式（状态语义，非装饰）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    const pending = wrapper.find('[data-testid="kpi-pending"]')
    expect(pending.classes()).toContain('bg-amber-50')
    // 警示色文字落在卡内标签/数值行（text-warning 语义层）
    expect(pending.find('p').classes()).toContain('text-warning')
    wrapper.unmount()
  })

  it('无环比文案：不渲染「↗/↑/环比」类元素（后端无历史对比，禁止假数据）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    expect(wrapper.text()).not.toMatch(/↗|↑|环比/)
    wrapper.unmount()
  })
})

describe('DashboardView：单折线图表挂载', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('vue-echarts 实例挂载且收到 Line option（日期轴/数值序列/主题色）', async () => {
    mockResolvedData()
    const { wrapper } = await mountDashboard()

    const chart = wrapper.findComponent({ name: 'VChart' })
    expect(chart.exists()).toBe(true)

    // option 断言：x 轴为近 7 日 MM-DD，序列数据为 count 字符串转 number
    const option = chart.props('option') as {
      tooltip: { trigger: string }
      xAxis: { data: string[] }
      series: Array<{ type: string; data: number[]; lineStyle: { color: string } }>
    }
    expect(option.tooltip.trigger).toBe('axis')
    expect(option.xAxis.data).toEqual([
      '08-18',
      '08-19',
      '08-20',
      '08-21',
      '08-22',
      '08-23',
      '08-24',
    ])
    expect(option.series[0].type).toBe('line')
    expect(option.series[0].data).toEqual([3, 5, 0, 2, 4, 1, 6])
    // 主题色来自 design tokens（jsdom 取不到 CSS 变量时回退蓝 600）
    expect(option.series[0].lineStyle.color).toBe('#2563EB')
    wrapper.unmount()
  })

  it('趋势为空：不挂载图表，展示区块空态', async () => {
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue([])
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: DOCS, total: '4', page: 1, size: 5 })
    const { wrapper } = await mountDashboard()

    expect(wrapper.findComponent({ name: 'VChart' }).exists()).toBe(false)
    expect(wrapper.text()).toContain('近 7 日暂无反馈记录')
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

  it('无文档：区块空态提示（含行动引导，禁止裸「暂无数据」）', async () => {
    vi.spyOn(dashboardApi, 'stats').mockResolvedValue(KPIS)
    vi.spyOn(dashboardApi, 'feedbackStats').mockResolvedValue(FEEDBACK)
    vi.spyOn(dashboardApi, 'feedbackTrend').mockResolvedValue(TREND)
    vi.spyOn(documentApi, 'list').mockResolvedValue({ records: [], total: '0', page: 1, size: 5 })
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
      ['quick-users', '/students'],
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

  it('loading：骨架屏与最终布局同形（KPI 灰块 + 入口灰块 + 表格灰行），不出数值', async () => {
    // 全部接口挂起（永不 resolve），锁定加载态
    const never = <T>() => new Promise<T>(() => {})
    vi.spyOn(dashboardApi, 'stats').mockReturnValue(never())
    vi.spyOn(dashboardApi, 'feedbackStats').mockReturnValue(never())
    vi.spyOn(dashboardApi, 'feedbackTrend').mockReturnValue(never())
    vi.spyOn(documentApi, 'list').mockReturnValue(never())
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
    const { wrapper } = await mountDashboard()

    expect(wrapper.find('[role="alert"]').text()).toContain('仪表盘加载失败，请稍后重试')
    wrapper.unmount()
  })
})
