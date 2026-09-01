import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 课程表单与列表 E2E（2026-08-29 T2 交付：封面上传 / 远程搜索防抖 / 局部刷新）
 *
 * 覆盖：
 * - 新建课程含封面上传：POST /admin/courses/cover 回传相对 URL → 预览 → 随创建提交 coverImage
 * - 远程搜索防抖：PERF-09 池缓存（['user-pool','TEACHER'] ensureQueryData）打开首拉整池 1 次，
 *   30s staleTime 窗口内关键字搜索纯本地过滤 0 请求（无逐键/防抖后网络请求）
 * - 课程列表页头刷新按钮：点击按查询重拉（请求数收敛为 2）
 */

/** 列表空态响应体（新建页前置列表 mock 不需要，但导航经过列表页时兜底） */
const EMPTY_LIST = apiOk({ records: [], total: '0', page: 1, size: 20 })

/** 封面上传端点回传（契约 D.2.2：objectKey + 相对 url） */
const COVER_URL = '/api/v1/public/covers/0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png'

test.describe('课程表单（封面上传 / 远程搜索防抖 / 刷新）', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  test('新建课程含封面上传：上传回传 URL 预览并随创建提交', async ({ page }) => {
    // 列表兜底（登录后经仪表盘跳转不触达，mockAuth 之外显式兜住）
    await page.route('**/api/v1/admin/courses*', (r) =>
      r.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_LIST }),
    )
    let coverCalled = 0
    await page.route('**/api/v1/admin/courses/cover', (r) => {
      coverCalled += 1
      return r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          objectKey: '0/3f2b8c6d4e5f6a7b8c9d0e1f2a3b4c9d.png',
          url: COVER_URL,
        }),
      })
    })
    let createBody: Record<string, unknown> | null = null
    await page.route('**/api/v1/admin/courses', async (r) => {
      if (r.request().method() === 'POST') {
        createBody = r.request().postDataJSON()
        await r.fulfill({
          status: 200,
          contentType: 'application/json',
          body: apiOk({
            id: 'co9',
            title: '新课程',
            status: 'ACTIVE',
            coverImage: COVER_URL,
            enrollmentLink: 'http://localhost:3000/courses/co9',
          }),
        })
      } else {
        await r.fulfill({ status: 200, contentType: 'application/json', body: EMPTY_LIST })
      }
    })

    await login(page, 'teacher')
    await page.goto('/courses/new')
    // 隐藏文件输入：jsdom 之外的真浏览器直选文件（走点击/拖拽共用的上传链路）
    await page.getByTestId('upload-input').setInputFiles({
      name: 'cover.png',
      mimeType: 'image/png',
      buffer: Buffer.from('png-bytes'),
    })
    // 上传成功：预览 img 直渲染相对 URL（dev 经 vite 代理同源）
    await expect(page.getByTestId('upload-preview')).toHaveAttribute('src', COVER_URL)
    await expect.poll(() => coverCalled).toBe(1)

    await page.getByTestId('field-title').fill('新课程')
    await page.getByTestId('save-basic').click()
    // 创建体携带上传回传的 coverImage（契约 D.2.2：url 整串写入）
    await expect.poll(() => createBody).toMatchObject({ title: '新课程', coverImage: COVER_URL })
    // 新建成功跳转详情
    await expect(page).toHaveURL(/\/courses\/co9$/, { timeout: 10_000 })
  })

  test('远程搜索防抖：池缓存首拉一次 + 关键字本地过滤 0 请求', async ({ page }) => {
    const keywords: string[] = []
    await page.route('**/api/v1/admin/users*', (r) => {
      keywords.push(new URL(r.request().url()).searchParams.get('keyword') ?? '')
      return r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          records: [
            {
              id: 't1',
              username: 'teacher01',
              displayName: '张老师',
              role: 'TEACHER',
              status: 'ACTIVE',
              createdAt: '2026-08-01T09:00:00',
            },
          ],
          total: '1',
          page: 1,
          size: 100,
        }),
      })
    })

    await login(page, 'teacher')
    await page.goto('/courses/new')
    // 打开教师 remote-select：focus 即空关键字首拉一次（视图 testid field-teachers 落在组件根）
    const input = page.locator('[data-testid="field-teachers"] [data-testid="remote-input"]')
    await input.click()
    await expect(page.getByTestId('remote-option-t1')).toBeVisible()
    // 首拉完成：整池恰一次真实请求（PERF-09：fetcher 经 ensureQueryData 未命中缓存时整池拉取）
    await expect.poll(() => keywords.length).toBe(1)
    // 连续输入（40ms/键 < 300ms 防抖窗口）：防抖窗口内不逐键、不防抖发请求（本地过滤 0 网络）
    await input.pressSequentially('张老师', { delay: 40 })
    expect(keywords.length).toBe(1)
    // 负向断言的稳定窗口：防抖窗口两倍时长后仍无第 2 次请求
    // （PERF-09：30s staleTime 窗口内关键字搜索命中池缓存纯本地过滤 0 网络请求的回归保护）
    await page.waitForTimeout(600)
    expect(keywords.length).toBe(1)
    // 本地过滤命中 displayName 含「张老师」的选项，可选中入 chip（fetcher 过滤字段 displayName/username）
    await page.getByTestId('remote-option-t1').click()
    await expect(page.getByTestId('remote-chip-t1')).toBeVisible()
  })

  test('课程列表刷新按钮：点击触发列表重拉', async ({ page }) => {
    let listCalls = 0
    await page.route('**/api/v1/admin/courses*', (r) => {
      listCalls += 1
      return r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          records: [
            {
              id: 'co1',
              title: '数据结构与算法精讲',
              category: '编程',
              instructorName: '张老师',
              price: 299,
              duration: '12',
              status: 'ACTIVE',
              learningCount: '0',
              coverImage: null,
            },
          ],
          total: '1',
          page: 1,
          size: 20,
        }),
      })
    })

    await login(page, 'teacher')
    await page.getByRole('link', { name: '课程' }).click()
    await expect(page.getByText('数据结构与算法精讲')).toBeVisible()
    expect(listCalls).toBe(1)

    // 页头刷新按钮（refetch 期间禁用防重复）：点击后列表接口重拉一次
    await page.getByTestId('refresh-courses').click()
    await expect.poll(() => listCalls).toBe(2)
    await expect(page.getByText('数据结构与算法精讲')).toBeVisible()
  })
})
