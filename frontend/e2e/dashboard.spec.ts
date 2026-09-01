import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk, apiFail } from './helpers/api-mock'

/**
 * 仪表盘 E2E（整合 spec §3.2 dashboard 组）
 * - KPI 4 卡数值渲染（mock stats）＋ 趋势柱状图挂载 ＋ 快捷入口跳转
 * - 2026-08-27 紫系换肤适配：图表改 CSS 自绘（原图表库移除）；仪表盘新增
 *   feedbacks/stats（意图 donut / 意图×赞踩）消费，补对应路由 mock
 * - 2026-09-01 PERF-12 拆键：core（四接口）/ trend（days 入键）双查询；
 *   断言范围切换仅重拉趋势（core 请求数不变）与趋势分区错误语义
 */

/** 仪表盘五接口请求计数（PERF-12 拆键断言：trendDays 逐次记录趋势请求的天数参数） */
interface DashboardCounts {
  stats: number
  feedbackStats: number
  feedbacksStats: number
  documents: number
  trendDays: number[]
}

/** 7 天档趋势 mock（3 柱，count 为 Long 字符串） */
const TREND_7 = [
  { date: '2026-08-18', count: '12' },
  { date: '2026-08-19', count: '8' },
  { date: '2026-08-20', count: '15' },
]

/** 30 天档趋势 mock（5 柱，与 7 天档柱数不同，供换档后断言新序列已到达） */
const TREND_30 = [
  { date: '2026-08-01', count: '5' },
  { date: '2026-08-05', count: '9' },
  { date: '2026-08-12', count: '3' },
  { date: '2026-08-20', count: '11' },
  { date: '2026-08-28', count: '7' },
]

/**
 * mock 仪表盘五接口路由
 *
 * 传入 counts 时按接口计数并记录趋势 days 参数（拆键分区断言用）；
 * 趋势响应按 days 分档（7 天 3 柱 / 30 天 5 柱）。
 *
 * @param page Playwright 页面
 * @param counts 可选请求计数器（不传则不计数，既有用例零改动）
 */
async function mockDashboard(page: import('@playwright/test').Page, counts?: DashboardCounts) {
  await page.route('**/api/v1/admin/dashboard/stats', (r) => {
    if (counts) counts.stats++
    return r.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiOk({ documentCount: '156', pendingChunkCount: '23', knowledgeBaseCount: '5' }),
    })
  })
  await page.route('**/api/v1/admin/feedback/stats*', (r) => {
    if (counts) counts.feedbackStats++
    return r.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiOk({ studentCount: '89', feedbackCount: '120', likeRate: 0.86 }),
    })
  })
  await page.route('**/api/v1/admin/feedback/trend*', (r) => {
    // 从查询串取 days 分档响应（缺省 7，与 api 层默认一致）
    const days = Number(new URL(r.request().url()).searchParams.get('days') ?? 7)
    if (counts) counts.trendDays.push(days)
    return r.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiOk(days === 30 ? TREND_30 : TREND_7),
    })
  })
  await page.route('**/api/v1/admin/feedbacks/stats*', (r) => {
    if (counts) counts.feedbacksStats++
    return r.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiOk([
        { intentType: 'knowledge_question', likedCount: '9', dislikedCount: '1' },
        { intentType: 'chat', likedCount: '4', dislikedCount: '2' },
        { intentType: 'unknown', likedCount: '1', dislikedCount: '0' },
      ]),
    })
  })
  await page.route('**/api/v1/admin/documents*', (r) => {
    if (counts) counts.documents++
    return r.fulfill({
      status: 200,
      contentType: 'application/json',
      body: apiOk({
        records: [
          {
            id: 'd1',
            kbId: 'k1',
            title: '数据结构讲义',
            fileType: 'pdf',
            parseStatus: 'INDEXED',
            chunkCount: 45,
            fileSize: '2048000',
            createdAt: '2026-08-24T10:00:00',
          },
        ],
        total: '1',
        page: 1,
        size: 5,
      }),
    })
  })
}

test.describe('仪表盘', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('KPI 卡数值渲染且无环比箭头', async ({ page }) => {
    await mockDashboard(page)
    await login(page, 'teacher')
    await expect(page.getByText('156', { exact: true })).toBeVisible()
    await expect(page.getByText('23', { exact: true })).toBeVisible()
    await expect(page.getByText('89', { exact: true })).toBeVisible()
    await expect(page.getByText('86%', { exact: true })).toBeVisible()
    // D11：无环比箭头（禁假数据）
    await expect(page.getByText(/↗|↑|环比/)).toHaveCount(0)
  })

  test('最近文档渲染与快捷入口跳转', async ({ page }) => {
    await mockDashboard(page)
    await login(page, 'teacher')
    await expect(page.getByText('数据结构讲义')).toBeVisible()
    await page.getByTestId('quick-upload').click()
    await expect(page).toHaveURL(/\/knowledge\/documents$/)
  })

  test('切换 7 → 30 天仅重拉趋势接口，core 四接口零重拉（PERF-12 拆键）', async ({ page }) => {
    const counts: DashboardCounts = {
      stats: 0,
      feedbackStats: 0,
      feedbacksStats: 0,
      documents: 0,
      trendDays: [],
    }
    await mockDashboard(page, counts)
    await login(page, 'teacher')

    // 首屏：五接口各一次，趋势带 days=7 渲染 3 柱
    await expect(page.getByText('156', { exact: true })).toBeVisible()
    await expect(page.locator('[data-testid^="trend-bar-"]')).toHaveCount(3)
    await expect.poll(() => counts.trendDays).toEqual([7])

    // 打开范围下拉 → 选择「近 30 天」：仅趋势换键重拉（30 天档 5 柱到达）
    await page.getByTestId('trend-range').click()
    await page.getByTestId('range-opt-30').click()
    await expect(page.locator('[data-testid^="trend-bar-"]')).toHaveCount(5)
    await expect.poll(() => counts.trendDays).toEqual([7, 30])

    // core 四接口请求数保持首轮各 1 次（KPI/文档/意图不随范围切换重拉）
    await expect.poll(() => counts.stats).toBe(1)
    await expect.poll(() => counts.feedbackStats).toBe(1)
    await expect.poll(() => counts.feedbacksStats).toBe(1)
    await expect.poll(() => counts.documents).toBe(1)
  })

  test('趋势接口失败仅趋势区报错，core 区正常且分区重试恢复（PERF-12 分区错误）', async ({
    page,
  }) => {
    await mockDashboard(page)
    // 后注册路由优先级更高：趋势降级 500，core 四接口保持成功
    await page.route('**/api/v1/admin/feedback/trend*', (r) =>
      r.fulfill({
        status: 500,
        contentType: 'application/json',
        body: apiFail(500, '服务器内部错误'),
      }),
    )
    await login(page, 'teacher')

    // core 区正常渲染：KPI 数值与最近文档在场
    await expect(page.getByText('156', { exact: true })).toBeVisible()
    await expect(page.getByText('数据结构讲义')).toBeVisible()
    // 趋势分区错误 + 分区重试钮在场；页面级错误横幅（retry 契约）不透出
    await expect(page.getByTestId('trend-error')).toBeVisible()
    await expect(page.getByTestId('trend-retry')).toBeVisible()
    await expect(page.getByTestId('retry')).toHaveCount(0)

    // 注册成功路由后分区重试：仅趋势区恢复柱状图，KPI 不受牵动
    await page.route('**/api/v1/admin/feedback/trend*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk(TREND_7),
      }),
    )
    await page.getByTestId('trend-retry').click()
    await expect(page.locator('[data-testid^="trend-bar-"]')).toHaveCount(3)
    await expect(page.getByText('156', { exact: true })).toBeVisible()
  })
})
