import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 分片修正工作台 E2E（整合 spec §3.2 chunks 组）
 * - pending 列表渲染；批量修正提交体断言；标记已修正确认流；编辑 Drawer 保存 PUT
 */

test.describe('分片修正工作台', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  const CHUNKS = [
    {
      id: 'c1',
      docId: 'd1',
      kbId: 'k1',
      chunkIndex: 3,
      content: '数据结构是计算机科学的核心基础课程，本章介绍线性表。',
      headingPath: '第1章',
      parentTitle: null,
      collectionType: 'TECHNICAL_QA',
      courseId: 'DEFAULT',
      correctionStatus: 'PENDING',
      createdAt: '2026-08-24T09:00:00',
      updatedAt: '2026-08-24T09:00:00',
    },
  ]

  async function mockPending(page: import('@playwright/test').Page, records = CHUNKS) {
    await page.route('**/api/admin/chunks/pending*', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records, total: String(records.length), page: 1, size: 20 }) }),
    )
  }

  test('批量修正：勾选→Dialog→提交体断言（含 DEFAULT 语义）', async ({ page }) => {
    await mockPending(page)
    let batchBody: unknown = null
    await page.route('**/api/admin/chunks/batch-update', async (r) => {
      batchBody = r.request().postDataJSON()
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/knowledge/chunks')
    await expect(page.getByText('数据结构是计算机科学的核心基础课程')).toBeVisible()
    await page.locator('input[type="checkbox"]').first().check()
    await page.getByRole('button', { name: /批量修正/ }).click()
    // 通用(DEFAULT)选项显式提交 'DEFAULT'（审查修复语义）
    await page.getByRole('button', { name: /确认|提交/ }).click()
    await expect.poll(() => batchBody).toMatchObject({ ids: ['c1'], courseId: 'DEFAULT' })
  })

  test('标记已修正：二次确认后行消失', async ({ page }) => {
    await mockPending(page)
    let corrected = 0
    await page.route('**/api/admin/chunks/batch-corrected', async (r) => {
      corrected += 1
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/knowledge/chunks')
    await page.locator('input[type="checkbox"]').first().check()
    await page.getByRole('button', { name: /标记已修正/ }).click()
    await expect(page.getByText(/不可撤销/)).toBeVisible()
    await page.getByRole('button', { name: '确认' }).click()
    await expect.poll(() => corrected).toBe(1)
    // 行移出待修正视图（二次 mock 返回空列表）
    await expect(page.getByText('数据结构是计算机科学的核心基础课程')).toBeHidden({ timeout: 10_000 })
  })

  test('编辑 Drawer：保存 PUT 内容触发重向量化提示', async ({ page }) => {
    await mockPending(page)
    let putBody: unknown = null
    await page.route('**/api/admin/chunks/c1', async (r) => {
      if (r.request().method() === 'PUT') {
        putBody = r.request().postDataJSON()
      }
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/knowledge/chunks')
    await page.getByRole('button', { name: /编辑/ }).first().click()
    const editor = page.locator('textarea')
    await editor.fill('修正后的分片内容')
    await page.getByRole('button', { name: /保存/ }).click()
    await expect.poll(() => putBody).toMatchObject({ content: '修正后的分片内容' })
    await expect(page.getByText(/重新向量化/)).toBeVisible()
  })
})