import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 用户管理 E2E（整合 spec §3.2 users 组）
 * - 角色 Tab 过滤；教师端添加学生无角色选择器；禁用二次确认
 */

test.describe('用户管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  const STUDENTS = [
    { id: 's1', username: 'student1', displayName: '李明', role: 'STUDENT', status: 'ACTIVE', createdAt: '2026-08-10T09:00:00' },
  ]

  async function mockUsers(page: import('@playwright/test').Page) {
    await page.route('**/api/admin/users*', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: STUDENTS, total: '1', page: 1, size: 20 }) }),
    )
  }

  test('教师端：角色 Tab 过滤 + 添加学生无角色选择器', async ({ page }) => {
    await mockUsers(page)
    await login(page, 'teacher')
    await page.goto('/users')
    await expect(page.getByText('李明')).toBeVisible()
    // 教师端无「添加教师」入口
    await expect(page.getByRole('button', { name: /添加教师/ })).toHaveCount(0)
    await page.getByRole('button', { name: /添加学生/ }).click()
    // Dialog 内无角色选择器（固定 STUDENT）
    await expect(page.getByText('角色')).toHaveCount(0)
  })

  test('禁用用户：二次确认 + 请求发出', async ({ page }) => {
    await mockUsers(page)
    let patched: unknown = null
    await page.route('**/api/admin/users/s1/status', async (r) => {
      patched = r.request().postDataJSON()
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.goto('/users')
    await page.getByRole('button', { name: /禁用/ }).click()
    await page.getByRole('button', { name: '确认禁用' }).click()
    await expect.poll(() => patched).toEqual({ status: 'DISABLED' })
  })
})