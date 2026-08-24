import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 分片修正工作台 E2E（整合 spec §3.2 chunks 组）
 * - pending 列表渲染；批量修正（勾选→Dialog→「确认修正」）提交体断言；
 *   标记已修正（可变列表：确认后行移出）；编辑 Drawer 保存 PUT
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

  async function enterChunks(page: import('@playwright/test').Page, records: unknown[]) {
    await page.route('**/api/v1/admin/chunks/pending*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records, total: String(records.length), page: 1, size: 20 }),
      }),
    )
    await login(page, 'teacher')
    await page.getByRole('link', { name: '分片' }).click()
    await expect(page).toHaveURL(/\/knowledge\/chunks$/)
  }

  test('批量修正：勾选→Dialog→确认修正提交体断言', async ({ page }) => {
    await enterChunks(page, CHUNKS)
    let batchBody: unknown = null
    await page.route('**/api/v1/admin/chunks/batch-update', async (r) => {
      batchBody = r.request().postDataJSON()
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await expect(page.getByText('数据结构是计算机科学的核心基础课程')).toBeVisible()
    await page.locator('input[type="checkbox"]').first().check()
    await page.getByRole('button', { name: /批量修正/ }).click()
    // 必须选择 collectionType（全「不改」时表单校验禁用提交）
    await page.getByTestId('batch-collection-type').selectOption('TECHNICAL_QA')
    await page.getByTestId('submit-batch').click({ force: true })
    await expect
      .poll(() => batchBody)
      .toMatchObject({ ids: ['c1'], collectionType: 'TECHNICAL_QA' })
  })

  test('标记已修正：二次确认后行移出待修正视图', async ({ page }) => {
    let records = [...CHUNKS]
    await page.route('**/api/v1/admin/chunks/pending*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records, total: String(records.length), page: 1, size: 20 }),
      }),
    )
    let corrected = 0
    await page.route('**/api/v1/admin/chunks/batch-corrected', async (r) => {
      corrected += 1
      records = []
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.getByRole('link', { name: '分片' }).click()
    await page.locator('input[type="checkbox"]').first().check()
    await page.getByRole('button', { name: /标记已修正/ }).click()
    await expect(page.getByText(/不可撤销/)).toBeVisible()
    await page.getByTestId('confirm-corrected').click()
    await expect.poll(() => corrected).toBe(1)
    await expect(page.getByText('数据结构是计算机科学的核心基础课程')).toBeHidden({
      timeout: 10_000,
    })
  })

  test('编辑 Drawer：保存 PUT 内容触发重向量化提示', async ({ page }) => {
    await enterChunks(page, CHUNKS)
    let putBody: unknown = null
    await page.route('**/api/v1/admin/chunks/c1', async (r) => {
      if (r.request().method() === 'PUT') {
        putBody = r.request().postDataJSON()
      }
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await page.getByRole('button', { name: '编辑' }).first().click()
    const editor = page.locator('textarea')
    await editor.fill('修正后的分片内容')
    await page.getByRole('button', { name: /^保存$/ }).click()
    await expect.poll(() => putBody).toMatchObject({ content: '修正后的分片内容' })
    // CI 时序下保存 toast 与页面提示同现「重新向量化」文案（本地仅 1 处），取首个
    await expect(page.getByText(/重新向量化/).first()).toBeVisible()
  })
})
