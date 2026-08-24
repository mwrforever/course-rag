import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 知识库 CRUD E2E（整合 spec §3.2 knowledge-bases 组）
 * - 经文档页「管理知识库」入口进入（侧导航无独立项，设计 §2.3）
 * - 列表渲染／新建 Dialog 表单校验与提交（可变列表：POST 后新记录可见）／删除二次确认
 */

test.describe('知识库管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  const KB = [
    {
      id: 'k1',
      name: '数据结构课程库',
      description: '课程讲义与资料',
      status: 'ACTIVE',
      createdBy: 't-1',
      createdAt: '2026-08-01T09:00:00',
      updatedAt: '2026-08-01T09:00:00',
    },
  ]

  async function enterKbPage(page: import('@playwright/test').Page) {
    await login(page, 'teacher')
    await page.getByRole('link', { name: '文档' }).click()
    await expect(page).toHaveURL(/\/knowledge\/documents$/)
    await page.getByRole('link', { name: '管理知识库' }).click()
    await expect(page).toHaveURL(/\/knowledge-bases$/)
  }

  test('列表渲染与新建提交（POST body 断言 + 列表刷新）', async ({ page }) => {
    const records = [...KB]
    await page.route('**/api/v1/admin/knowledge-bases*', async (r) => {
      if (r.request().method() === 'GET') {
        await r.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiOk({ records, total: String(records.length), page: 1, size: 20 }),
        })
        return
      }
      if (r.request().method() === 'POST') {
        const body = r.request().postDataJSON()
        const created = {
          id: 'k2',
          name: body.name,
          description: body.description ?? '',
          status: 'ACTIVE',
          createdBy: 't-1',
          createdAt: '2026-08-24T10:00:00',
          updatedAt: '2026-08-24T10:00:00',
        }
        records.push(created)
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(created) })
        return
      }
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await enterKbPage(page)
    await expect(page.getByText('数据结构课程库')).toBeVisible()

    await page.getByRole('button', { name: /新建知识库|新增知识库/ }).click()
    // name 必填：直接提交不发请求
    await page.click('button[type="submit"]')
    expect(records).toHaveLength(1)
    await page.getByLabel('名称').fill('新知识库')
    await page.click('button[type="submit"]')
    await expect(page.getByText('新知识库')).toBeVisible({ timeout: 10_000 })
    expect(records).toHaveLength(2)
  })

  test('删除二次确认（级联警告）', async ({ page }) => {
    await page.route('**/api/v1/admin/knowledge-bases*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records: KB, total: '1', page: 1, size: 20 }),
      }),
    )
    let deleted = 0
    await page.route('**/api/v1/admin/knowledge-bases/k1', async (r) => {
      if (r.request().method() === 'DELETE') {
        deleted += 1
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
      } else {
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(KB[0]) })
      }
    })
    await enterKbPage(page)
    await page
      .getByRole('button', { name: /删除知识库|删除/ })
      .first()
      .click()
    await expect(page.getByText(/级联删除|不可恢复/)).toBeVisible()
    await page.getByTestId('confirm-delete').click()
    await expect.poll(() => deleted).toBe(1)
  })
})
