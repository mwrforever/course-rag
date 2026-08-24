import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 反馈报表 + 超管专属 E2E（整合 spec §3.2 feedback/admin-only 组）
 * - 反馈 KPI/图表/列表渲染；超管回放入口（教师不可见）；
 *   TEACHER 直访超管 URL 被守卫拦截；TEACHER 侧导航无超管分组
 */

test.describe('反馈与权限矩阵', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  async function mockFeedback(page: import('@playwright/test').Page) {
    await page.route('**/api/admin/feedbacks/stats', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk([
          { intentType: 'TECHNICAL_QA', likedCount: '10', dislikedCount: '6' },
          { intentType: 'COURSE_INFO', likedCount: '3', dislikedCount: '1' },
        ]),
      }),
    )
    await page.route('**/api/admin/feedback/trend?days=7', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk([{ date: '2026-08-18', count: '12' }]) }),
    )
    await page.route('**/api/admin/feedbacks?page=1&size=20', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          records: [
            {
              id: 'f1',
              sessionId: 'sess-9',
              messageId: 'm-9',
              userId: 's-1',
              isLiked: true,
              intentType: 'TECHNICAL_QA',
              createdAt: '2026-08-24T09:00:00',
            },
          ],
          total: '1',
          page: 1,
          size: 20,
        }),
      }),
    )
  }

  test('反馈报表：KPI 与低分列表渲染（教师无回放入口）', async ({ page }) => {
    await mockFeedback(page)
    await login(page, 'teacher')
    await page.goto('/feedback')
    // KPI：点赞率 = liked/(liked+disliked) 口径渲染 + 意图统计
    await expect(page.getByText('#s-1')).toBeVisible()
    // 教师：无「查看会话回放」入口
    await expect(page.getByRole('button', { name: /查看会话回放/ })).toHaveCount(0)
  })

  test('超管：反馈回放入口可见并打开只读消息 Drawer', async ({ page }) => {
    await mockFeedback(page)
    await page.route('**/api/admin/sessions/sess-9', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          id: 'sess-9',
          userId: 's-1',
          title: '课程咨询',
          status: 'ACTIVE',
          lastMessageAt: '2026-08-24T09:00:00',
          createdAt: '2026-08-24T08:00:00',
          messages: [
            { id: 'm8', role: 'USER', content: '这门课难吗', messageType: null, intentType: null, runId: 'r1', seq: 0, createdAt: '2026-08-24T09:00:00' },
            { id: 'm9', role: 'ASSISTANT', content: '课程难度适中，建议先学基础。', messageType: null, intentType: 'knowledge_question', runId: 'r1', seq: 1, createdAt: '2026-08-24T09:00:01' },
          ],
        }),
      }),
    )
    await login(page, 'admin')
    await page.goto('/feedback')
    await page.getByRole('button', { name: /查看会话回放/ }).click()
    await expect(page.getByText('课程难度适中，建议先学基础。')).toBeVisible()
  })

  test('TEACHER 直访超管路由被守卫拦截（ForbiddenView）', async ({ page }) => {
    await login(page, 'teacher')
    await page.goto('/sessions')
    await expect(page).toHaveURL('**/forbidden*')
    await expect(page.getByText(/无权限|无权访问/)).toBeVisible()
  })

  test('TEACHER 侧导航无超管分组', async ({ page }) => {
    await login(page, 'teacher')
    await expect(page.getByText('会话审计')).toHaveCount(0)
    await expect(page.getByText('安全审计')).toHaveCount(0)
  })
})