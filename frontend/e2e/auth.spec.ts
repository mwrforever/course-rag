import { test, expect } from '@playwright/test'
import { apiFail, apiOk, login, mockAuth } from './helpers/api-mock'

/**
 * B 端认证流 E2E（整合 spec §3.2 auth 组）
 * - teacher/admin 登录各自跳仪表盘；401 Alert；未登录重定向；STUDENT 拒绝
 * - M10 刷新登录态恢复：登录 → reload → 启动 fetchMe 恢复顶栏用户名与角色导航分组
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
    await expect(page.getByText('课程助手管理后台')).toBeVisible()
  })

  test('超管登录成功跳仪表盘（侧导航含超管分组）', async ({ page }) => {
    await page.goto('/login')
    await page.fill('#username', 'admin')
    await page.fill('#password', '123456')
    await page.click('button[type="submit"]')
    await page.waitForURL('**/dashboard')
    // 审计分组默认折叠：先展开再断言子项（白色侧栏手风琴分组交互）
    await page.getByRole('button', { name: /审计/ }).click()
    await expect(page.getByText('会话审计')).toBeVisible()
    await expect(page.getByText('Token 黑名单')).toBeVisible()
  })

  test('错误凭据 401 显示 Alert 且停留登录页', async ({ page }) => {
    await mockAuth(page)
    await page.route('**/api/v1/auth/login', async (route) => {
      await route.fulfill({
        status: 401,
        contentType: 'application/json',
        body: apiFail(401, '用户名或密码错误'),
      })
    })
    await page.goto('/login')
    await page.fill('#username', 'teacher')
    await page.fill('#password', 'wrong1')
    await page.click('button[type="submit"]')
    await expect(page.getByText('用户名或密码错误')).toBeVisible()
    await expect(page).toHaveURL(/\/login$/)
  })

  test('未登录访问仪表盘重定向登录页', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login\?redirect=/)
  })

  test('STUDENT 登录被拒：无权限提示且不跳转', async ({ page }) => {
    await page.route('**/api/v1/auth/login', async (route) => {
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

  test('刷新后登录态恢复：顶栏显示用户名 + 角色导航分组正常（M10）', async ({ page }) => {
    // me 端点 mock（mockAuth 未覆盖 GET /auth/me；后注册的 route 优先匹配）：
    // 返回与 admin 登录一致的 SUPER_ADMIN 身份，模拟刷新后 AT cookie 兜底有效的恢复源
    await page.route('**/api/v1/auth/me', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ userId: 'a-1', role: 'SUPER_ADMIN', displayName: '超管' }),
      })
    })
    await login(page, 'admin')

    // 刷新前顶栏用户名在场（登录响应直接写入）
    await expect(page.getByRole('button', { name: '用户菜单' })).toContainText('超管')

    // 刷新：AT 内存态丢失、RT 在 sessionStorage、身份字段清空 →
    // 路由守卫首个导航前 fetchMe（RT 在且 displayName 空时发请求）
    await page.reload()
    await page.waitForURL('**/dashboard')

    // 顶栏用户名恢复（修复前恒显示「未登录」）
    await expect(page.getByRole('button', { name: '用户菜单' })).toContainText('超管')

    // 下拉菜单内身份信息（displayName + 角色文案）
    await page.getByRole('button', { name: '用户菜单' }).click()
    await expect(page.getByTestId('user-menu')).toContainText('超管')
    await expect(page.getByTestId('user-menu')).toContainText('超级管理员')

    // 角色恢复后导航分组正常：超管专属审计分组回归（修复前 role=null 按最低权限渲染不可见）
    await expect(page.getByRole('button', { name: /审计/ })).toBeVisible()
  })
})
