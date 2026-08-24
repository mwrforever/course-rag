import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 知识库 CRUD E2E（整合 spec §3.2 knowledge-bases 组）
 * - 列表渲染／新建 Dialog 表单校验与提交／删除二次确认
 */

test.describe('知识库管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  const KB = [
    { id: 'k1', name: '数据结构课程库', description: '课程讲义与资料', status: 'ACTIVE', createdBy: 't-1', createdAt: '2026-08-01T09:00:00', updatedAt: '2026-08-01T09:00:00' },
  ]

  test('列表渲染与新建提交', async ({ page }) => {
    await page.route('**/api/admin/knowledge-bases?page=1&size=20', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: KB, total: '1', page: 1, size: 20 }) }),
    )
    let createBody: unknown = null
    await page.route('**/api/admin/knowledge-bases', async (r) => {
      if (r.request().method() === 'POST') {
        createBody = r.request().postDataJSON()
        await r.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiOk({ id: 'k2', name: '新知识库', description: '新建', status: 'ACTIVE', createdBy: 't-1', createdAt: '2026-08-24T10:00:00', updatedAt: '2026-08-24T10:00:00' }),
        })
      } else {
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: KB, total: '1', page: 1, size: 20 }) })
      }
    })
    await login(page, 'teacher')
    await page.goto('/knowledge-bases')
    await expect(page.getByText('数据结构课程库')).toBeVisible()

    await page.getByRole('button', { name: /新建知识库|新增知识库/ }).click()
    // name 必填：直接提交不发请求
    await page.click('button[type="submit"]')
    expect(createBody).toBeNull()
    await page.getByLabel('名称').fill('新知识库')
    await page.click('button[type="submit"]')
    await expect.poll(() => createBody).toMatchObject({ name: '新知识库' })
    await expect(page.getByText('新知识库')).toBeVisible()
  })

  test('删除二次确认（级联警告）', async ({ page }) => {
    await page.route('**/api/admin/knowledge-bases?page=1&size=20', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: KB, total: '1', page: 1, size: 20 }) }),
    )
    let deleted = 0
    await page.route('**/api/admin/knowledge-bases/k1', async (r) => {
      if (r.request().method() === 'DELETE') {
        deleted += 1
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
      } else {
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(KB[0]) })
      }
    })
    await login(page, 'teacher')
    await page.goto('/knowledge-bases')
    await page.getByRole('button', { name: /删除知识库|删除/ }).first().click()
    await expect(page.getByText(/级联删除|不可恢复/)).toBeVisible()
    await page.getByRole('button', { name: '确认删除' }).click()
    await expect.poll(() => deleted).toBe(1)
  })
})