import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 文档管理 E2E（整合 spec §3.2 documents 组）
 * - 列表 PENDING→INDEXED 渲染；FAILED 行展开 errorMessage + 重新解析；
 *   上传 Dialog 前置校验（kbId 必选）
 * 注：ETL 5s 轮询启停由 use-etl-polling 单测覆盖（100%），E2E 不等待轮询周期
 */

test.describe('文档管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  /** 列表接口 mock：支持按调用次数切换 PENDING→INDEXED（轮询二段） */
  async function mockDocs(page: import('@playwright/test').Page, failed = false) {
    let call = 0
    await page.route('**/api/admin/documents*', async (r) => {
      if (r.request().method() !== 'GET') return r.fulfill({ status: 405, contentType: 'application/json', body: apiOk(null) })
      call += 1
      const status = failed ? 'FAILED' : call === 1 ? 'PENDING' : 'INDEXED'
      await r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          records: [
            {
              id: 'd1',
              kbId: 'k1',
              title: '数据结构讲义',
              fileType: 'pdf',
              parseStatus: status,
              chunkCount: status === 'INDEXED' ? 45 : null,
              fileSize: '2048000',
              errorMessage: failed ? '解析超时：分片失败' : null,
              createdAt: '2026-08-24T10:00:00',
              updatedAt: '2026-08-24T10:00:00',
            },
          ],
          total: '1',
          page: 1,
          size: 20,
        }),
      })
    })
  }

  test('列表渲染与解析状态徽章（PENDING→INDEXED 轮询）', async ({ page }) => {
    await mockDocs(page)
    await login(page, 'teacher')
    await page.goto('/knowledge/documents')
    await expect(page.getByText('数据结构讲义')).toBeVisible()
    // 首次 PENDING 徽章（分析/排队态）
    await expect(page.getByText(/PENDING|解析中|排队/).first()).toBeVisible()
    // 轮询后翻 INDEXED（等一个轮询周期 5s + 缓冲）
    await expect(page.getByText(/INDEXED|已索引/).first()).toBeVisible({ timeout: 10_000 })
  })

  test('FAILED 行展开 errorMessage 与重新解析', async ({ page }) => {
    await mockDocs(page, true)
    let reparseCalled = 0
    await page.route('**/api/admin/documents/d1/reparse', async (r) => {
      reparseCalled += 1
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/knowledge/documents')
    await expect(page.getByText(/FAILED|解析失败/).first()).toBeVisible()
    await expect(page.getByText('解析超时：分片失败')).toBeVisible()
    await page.getByRole('button', { name: '重新解析' }).click()
    await expect.poll(() => reparseCalled).toBe(1)
  })

  test('上传 Dialog：kbId 必选前置校验（不发请求）', async ({ page }) => {
    await mockDocs(page)
    await page.route('**/api/admin/knowledge-bases?page=1&size=20', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: [], total: '0', page: 1, size: 20 }) }),
    )
    let uploadCalled = 0
    await page.route('**/api/admin/documents', async (r) => {
      if (r.request().method() === 'POST') uploadCalled += 1
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/knowledge/documents')
    await page.getByRole('button', { name: '上传文档' }).click()
    // 选择文件（无 kbId）→ 校验拦截不发请求
    await page.locator('input[type="file"]').setInputFiles({
      name: 'lecture.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4 test'),
    })
    await page.getByRole('button', { name: /开始上传/ }).click()
    expect(uploadCalled).toBe(0)
    await expect(page.getByText(/知识库|必选/).first()).toBeVisible()
  })
})