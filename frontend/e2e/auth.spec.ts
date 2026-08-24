import { test, expect } from '@playwright/test'
import { mockAuth, apiFail } from './helpers/api-mock'

/**
 * B 端认证流 E2E（整合 spec §3.2 auth 组）
 * - teacher/admin 登录各自跳仪表盘；401 Alert；未登录重定向；STUDENT 拒绝
 */

test.describe('B 端认证流', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('教师登录成功跳仪表盘', async ({ page }) => {
    await page.goto('/login')
    await page.fill('#username', 'teacher')
    await page.fill('#password', '123456')
    await page.click('button[type="submit"]')
    await page.waitForURL('**/dashboard')
    // 布局壳顶栏品牌与侧导航出现
    await expect(page.getByText('知识库管理后台')).toBeVisible()
  })

  test('超管登录成功跳仪表盘（侧导航含超管分组）', async ({ page }) => {
    await page.goto('/login')
    await page.fill('#username', 'admin')
    await page.fill('#password', '123456')
    await page.click('button[type="submit"]')
    await page.waitForURL('**/dashboard')
    await expect(page.getByText('会话审计')).toBeVisible()
    await expect(page.getByText('安全审计')).toBeVisible()
  })

  test('错误凭据 401 显示 Alert 且停留登录页', async ({ page }) => {
    await mockAuth(page)
    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({ status: 401, contentType: 'application/json', body: apiFail(401, '用户名或密码错误') })
    })
    await page.goto('/login')
    await page.fill('#username', 'teacher')
    await page.fill('#password', 'wrong')
    await page.click('button[type="submit"]')
    await expect(page.getByText('用户名或密码错误')).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
  })

  test('未登录访问仪表盘重定向登录页', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login\?redirect=/)
  })

  test('STUDENT 登录被拒：无权限提示且不跳转', async ({ page }) => {
    await page.route('**/api/auth/login', async (route) => {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: apiFail(403, '当前账号无管理后台访问权限'),
      })
    })
    await page.goto('/login')
    await page.fill('#username', 'student')
    await page.fill('#password', '123456')
    await page.click('button[type="submit"]')
    await expect(page.getByText('当前账号无管理后台访问权限')).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
  })
})