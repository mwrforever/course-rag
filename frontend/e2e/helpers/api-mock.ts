import type { Page } from '@playwright/test'

/**
 * B 端 E2E route-mock 基建（整合 spec §3.2：全 route-mock，不依赖真实后端）
 *
 * - mockApi(page, role)：统一 mock 认证与公共接口；登录响应带 Set-Cookie
 *   （commerce_token，与后端 AuthController 一致，供 middleware 存在性检查——
 *   注：B 端为 SPA 无 middleware，cookie 无守卫角色，仅按真实登录流复现）
 * - login(page, role)：按角色走完整登录流程（mock），跳仪表盘
 * - apiOk / apiFail：ApiResponse 体构造
 */

export const API = '**/api/**'

/** ApiResponse 成功体（code=0 为成功契约，非 200） */
export function apiOk(data: unknown): string {
  return JSON.stringify({ code: 0, message: 'success', data })
}

/** ApiResponse 错误体（code 与 HTTP 同值，契约定稿 §1） */
export function apiFail(code: number, message: string): string {
  return JSON.stringify({ code, message, data: null })
}

const USERS: Record<string, { userId: string; displayName: string }> = {
  TEACHER: { userId: 't-1', displayName: '张老师' },
  SUPER_ADMIN: { userId: 'a-1', displayName: '超管' },
  STUDENT: { userId: 's-1', displayName: '学生甲' },
}

function authBody(role: keyof typeof USERS) {
  const u = USERS[role]
  return apiOk({
    accessToken: `at-${role}`,
    refreshToken: `rt-${role}`,
    userId: u.userId,
    role,
    displayName: u.displayName,
  })
}

/** 统一 mock 认证三端点；其余接口按用例自行覆盖（fallback 404 显式暴露未 mock 的访问） */
export async function mockAuth(page: Page) {
  await page.route(API, async (route) => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    if (req.method() === 'POST' && path.endsWith('/auth/login')) {
      const body = req.postDataJSON?.() ?? {}
      const role = body.username === 'admin' ? 'SUPER_ADMIN' : 'TEACHER'
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        headers: { 'set-cookie': `commerce_token=at-${role}; Path=/; Max-Age=900; HttpOnly` },
        body: authBody(role),
      })
      return
    }
    if (req.method() === 'POST' && path.endsWith('/auth/refresh')) {
      const role = req.headers()['x-role'] ?? 'TEACHER'
      await route.fulfill({ status: 200, contentType: 'application/json', body: authBody(role as keyof typeof USERS) })
      return
    }
    if (req.method() === 'POST' && path.endsWith('/auth/logout')) {
      await route.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
      return
    }
    await route.fulfill({ status: 404, contentType: 'application/json', body: apiFail(404, '接口未 mock（用例未覆盖）') })
  })
}

/** 走完整登录流程（teacher / admin），成功跳 /dashboard */
export async function login(page: Page, username: 'teacher' | 'admin' = 'teacher') {
  await page.goto('/login')
  await page.fill('#username', username)
  await page.fill('#password', '123456')
  await page.click('button[type="submit"]')
  await page.waitForURL('**/dashboard')
}