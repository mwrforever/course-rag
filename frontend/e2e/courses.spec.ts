import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 课程管理 E2E（整合 spec §3.2 courses 组）
 * - 列表渲染；新建（POST body 断言）；编辑页基础保存与 4 Tab 内容回显
 * 注：裸 JSON 字符串保存契约由 api.test/view 单测锁定，E2E 聚焦端到端交互
 */

test.describe('课程管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  const COURSE = {
    id: 'co1',
    title: '数据结构与算法精讲',
    description: '系统学习数据结构',
    coverImage: null,
    category: '编程',
    instructorName: '张老师',
    price: 299,
    duration: 12,
    tags: ['RAG'],
    enrollmentLink: '',
    status: 'ACTIVE',
    learningCount: 0,
    createdBy: 't-1',
    createdAt: '2026-08-01T09:00:00',
    updatedAt: '2026-08-01T09:00:00',
  }

  test('列表渲染与新建提交（POST body 断言）', async ({ page }) => {
    await page.route('**/api/v1/admin/courses*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records: [COURSE], total: '1', page: 1, size: 20 }),
      }),
    )
    let createBody: unknown = null
    await page.route('**/api/v1/admin/courses', async (r) => {
      if (r.request().method() === 'POST') {
        createBody = r.request().postDataJSON()
        await r.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiOk({ ...COURSE, id: 'co2', title: '新课程' }),
        })
      } else {
        await r.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiOk({ records: [COURSE], total: '1', page: 1, size: 20 }),
        })
      }
    })
    await login(page, 'teacher')
    await page.getByRole('link', { name: '课程' }).click()
    await expect(page.getByText('数据结构与算法精讲')).toBeVisible()
    await page.getByRole('button', { name: /新建课程/ }).click()
    await expect(page).toHaveURL(/\/courses\/new$/)
    await page.locator('#course-title').fill('新课程')
    await page.getByTestId('save-basic').click()
    await expect.poll(() => createBody).toMatchObject({ title: '新课程' })
    // 新建成功后跳转编辑页
    await expect(page).toHaveURL(/\/courses\/co2$/, { timeout: 10_000 })
  })

  test('编辑页：基础信息回显与 4 Tab 内容回显', async ({ page }) => {
    // 进入编辑页前先渲染列表（列表接口未 mock 会显著 404 错误态）
    await page.route('**/api/v1/admin/courses*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records: [COURSE], total: '1', page: 1, size: 20 }),
      }),
    )
    await page.route('**/api/v1/admin/courses/co1', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(COURSE) }),
    )
    await page.route('**/api/v1/admin/users*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records: [], total: '0', page: 1, size: 100 }),
      }),
    )
    await page.route('**/api/v1/admin/courses/co1/schedules', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk([]) }),
    )
    await page.route('**/api/v1/admin/courses/co1/students', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk([]) }),
    )
    await page.route('**/api/v1/admin/courses/co1/contents', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk([
          { contentType: 'intro', content: '# 课程介绍', sortOrder: 1 },
          { contentType: 'syllabus', content: '## 教学大纲', sortOrder: 2 },
        ]),
      }),
    )
    await login(page, 'teacher')
    await page.getByRole('link', { name: '课程' }).click()
    await page.getByRole('button', { name: '编辑' }).first().click()
    await expect(page).toHaveURL(/\/courses\/co1$/)
    // 基础信息回显
    await expect(page.locator('#course-title')).toHaveValue('数据结构与算法精讲')
    // 4 Tab 内容回显（intro 默认）
    await expect(page.getByRole('button', { name: '课程介绍' })).toBeVisible()
    await expect(page.getByText('# 课程介绍', { exact: true })).toBeVisible()
    // 切 Tab 回显另一内容
    await page.getByRole('button', { name: '教学大纲' }).click()
    await expect(page.getByText('## 教学大纲', { exact: true })).toBeVisible()
  })
})
