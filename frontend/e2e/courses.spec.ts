import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 课程管理 E2E（整合 spec §3.2 courses 组）
 * - 列表渲染；新建 POST body；编辑页 4 Tab 裸 JSON 保存
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
    tags: ['RAG', '入门'],
    enrollmentLink: '',
    status: 'ACTIVE',
    learningCount: 0,
    createdBy: 't-1',
    createdAt: '2026-08-01T09:00:00',
    updatedAt: '2026-08-01T09:00:00',
  }

  test('列表渲染与新建提交（POST body 断言）', async ({ page }) => {
    await page.route('**/api/admin/courses?page=1&size=20', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: [COURSE], total: '1', page: 1, size: 20 }) }),
    )
    let createBody: unknown = null
    await page.route('**/api/admin/courses', async (r) => {
      if (r.request().method() === 'POST') {
        createBody = r.request().postDataJSON()
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ ...COURSE, id: 'co2', title: '新课程' }) })
      } else {
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk({ records: [COURSE], total: '1', page: 1, size: 20 }) })
      }
    })
    await login(page, 'teacher')
    await page.goto('/courses')
    await expect(page.getByText('数据结构与算法精讲')).toBeVisible()
    await page.getByRole('button', { name: /新建课程/ }).click()
    await page.waitForURL('**/courses/new')
    await page.getByLabel('课程名称').fill('新课程')
    await page.getByLabel('讲师名').fill('李老师')
    await page.getByRole('button', { name: /保存|创建/ }).first().click()
    await expect.poll(() => createBody).toMatchObject({ title: '新课程', instructorName: '李老师' })
  })

  test('编辑页 4 Tab 内容独立保存（裸 JSON 字符串 body）', async ({ page }) => {
    await page.route('**/api/admin/courses/co1', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(COURSE) }),
    )
    await page.route('**/api/admin/courses/co1/contents', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk([
          { contentType: 'intro', content: '# 课程介绍', sortOrder: 1 },
          { contentType: 'syllabus', content: '## 教学大纲', sortOrder: 2 },
          { contentType: 'instructor', content: '## 讲师', sortOrder: 3 },
          { contentType: 'faq', content: '## FAQ', sortOrder: 4 },
        ]),
      }),
    )
    let savedBody: unknown = null
    await page.route('**/api/admin/courses/co1/contents/intro', async (r) => {
      if (r.request().method() === 'PUT') {
        savedBody = r.request().postData()
        await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
      } else {
        await r.fallback()
      }
    })
    await login(page, 'teacher')
    await page.goto('/courses/co1')
    // 切换内容 Tab（intro 默认）→ 编辑 → 保存
    const editor = page.locator('textarea').first()
    await editor.fill('更新后的课程介绍')
    await page.getByRole('button', { name: /保存此页|保存.*(介绍|内容)/ }).first().click()
    await expect.poll(() => savedBody).toBe('更新后的课程介绍')
  })
})