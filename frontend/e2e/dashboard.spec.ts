import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 仪表盘 E2E（整合 spec §3.2 dashboard 组）
 * - KPI 4 卡数值渲染（mock stats）＋ 趋势柱状图挂载 ＋ 快捷入口跳转
 * - 2026-08-27 紫系换肤适配：图表改 CSS 自绘（原图表库移除）；仪表盘新增
 *   feedbacks/stats（意图 donut / 意图×赞踩）消费，补对应路由 mock
 */

test.describe('仪表盘', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  async function mockDashboard(page: import('@playwright/test').Page) {
    await page.route('**/api/v1/admin/dashboard/stats', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ documentCount: '156', pendingChunkCount: '23', knowledgeBaseCount: '5' }),
      }),
    )
    await page.route('**/api/v1/admin/feedback/stats*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ studentCount: '89', feedbackCount: '120', likeRate: 0.86 }),
      }),
    )
    await page.route('**/api/v1/admin/feedback/trend*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk([
          { date: '2026-08-18', count: '12' },
          { date: '2026-08-19', count: '8' },
          { date: '2026-08-20', count: '15' },
        ]),
      }),
    )
    await page.route('**/api/v1/admin/feedbacks/stats*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk([
          { intentType: 'knowledge_question', likedCount: '9', dislikedCount: '1' },
          { intentType: 'chat', likedCount: '4', dislikedCount: '2' },
          { intentType: 'unknown', likedCount: '1', dislikedCount: '0' },
        ]),
      }),
    )
    await page.route('**/api/v1/admin/documents*', (r) =>
      r.fulfill({
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
      }),
    )
  }

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
})
