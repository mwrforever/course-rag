import { test, expect } from '@playwright/test'
import { mockAuth, login, apiOk } from './helpers/api-mock'

/**
 * 文档管理 E2E（整合 spec §3.2 documents 组）
 * - 列表最终态渲染（轮询启停由 use-etl-polling 单测覆盖）；FAILED 行展开+重新解析；
 *   上传 Dialog kbId 必选前置校验（不发请求）
 */

test.describe('文档管理', () => {
  test.beforeEach(async ({ page }) => {
    await mockAuth(page)
  })

  /** 列表接口 mock：按调用次数切换 PENDING→INDEXED（轮询二段）；failed=true 恒定 FAILED */
  async function mockDocs(page: import('@playwright/test').Page, failed = false) {
    let call = 0
    await page.route('**/api/v1/admin/documents*', async (r) => {
      if (r.request().method() !== 'GET')
        return r.fulfill({ status: 405, contentType: 'application/json', body: apiOk(null) })
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

  test('列表渲染并轮询至最终态（INDEXED）', async ({ page }) => {
    await mockDocs(page)
    await login(page, 'teacher')
    await page.getByRole('link', { name: '文档' }).click()
    await expect(page).toHaveURL(/\/knowledge\/documents$/)
    await expect(page.getByText('数据结构讲义')).toBeVisible()
    // 轮询最终态（5s 间隔 + 渲染缓冲）
    await expect(page.getByText(/INDEXED|已索引/).first()).toBeVisible({ timeout: 15_000 })
  })

  test('FAILED 行展开 errorMessage 与重新解析', async ({ page }) => {
    await mockDocs(page, true)
    let reparseCalled = 0
    await page.route('**/api/v1/admin/documents/d1/reparse', async (r) => {
      reparseCalled += 1
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.getByRole('link', { name: '文档' }).click()
    // FAILED 错误详情需点击徽章展开（EtlStatusBadge errorExpanded toggle）
    await page.getByTestId('etl-badge-toggle').click()
    await expect(page.getByTestId('etl-error-message')).toHaveText('解析超时：分片失败')
    // 重新解析入口在行操作 ⋮ 菜单内（弹层：菜单项 data-testid=menu-reparse；
    // 菜单项经 DropdownMenuItem 渲染为 role=menuitem，显式角色覆盖隐式 button）
    await page.getByTestId('doc-menu-d1').click()
    await page.getByRole('menuitem', { name: '重新解析' }).click()
    await expect.poll(() => reparseCalled).toBe(1)
  })

  test('上传 Dialog：kbId 必选前置校验（不发请求）', async ({ page }) => {
    await mockDocs(page)
    await page.route('**/api/v1/admin/knowledge-bases*', (r) =>
      r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({ records: [], total: '0', page: 1, size: 20 }),
      }),
    )
    let uploadCalled = 0
    await page.route('**/api/v1/admin/documents', async (r) => {
      if (r.request().method() === 'POST') uploadCalled += 1
      await r.fulfill({ status: 200, contentType: 'application/json', body: apiOk(null) })
    })
    await login(page, 'teacher')
    await page.getByRole('link', { name: '文档' }).click()
    await page.getByRole('button', { name: '上传文档' }).click()
    // 选择文件（无 kbId）→ 校验拦截不发请求
    await page.locator('input[type="file"]').setInputFiles({
      name: 'lecture.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4 test'),
    })
    await page.getByTestId('submit-upload').click()
    expect(uploadCalled).toBe(0)
    // 前端校验文案：知识库必选
    await expect(page.getByTestId('upload-error')).toBeVisible()
  })

  test('末行 ⋮ 菜单完整可见（不被表格容器裁切）', async ({ page }) => {
    // 三条记录：d3 为末行（末行菜单向下展开会越过表格容器下缘，回归覆盖裁切修复）
    await page.route('**/api/v1/admin/documents*', async (r) => {
      if (r.request().method() !== 'GET')
        return r.fulfill({ status: 405, contentType: 'application/json', body: apiOk(null) })
      const record = (id: string, title: string) => ({
        id,
        kbId: 'k1',
        title,
        fileType: 'pdf',
        parseStatus: 'INDEXED',
        chunkCount: 5,
        fileSize: '1000',
        errorMessage: null,
        createdAt: '2026-08-24T10:00:00',
        updatedAt: '2026-08-24T10:00:00',
      })
      await r.fulfill({
        status: 200,
        contentType: 'application/json',
        body: apiOk({
          records: [record('d1', '文档一'), record('d2', '文档二'), record('d3', '文档三')],
          total: '3',
          page: 1,
          size: 20,
        }),
      })
    })
    await login(page, 'teacher')
    await page.getByRole('link', { name: '文档' }).click()
    await page.getByTestId('doc-menu-d3').click()
    const menu = page.getByTestId('doc-menu')
    await expect(menu).toBeVisible()
    // 修复断言（裁切前失败）：菜单整体位于视口内
    const menuBox = (await menu.boundingBox())!
    const viewport = page.viewportSize()!
    expect(menuBox.x).toBeGreaterThanOrEqual(0)
    expect(menuBox.y).toBeGreaterThanOrEqual(0)
    expect(menuBox.x + menuBox.width).toBeLessThanOrEqual(viewport.width)
    expect(menuBox.y + menuBox.height).toBeLessThanOrEqual(viewport.height)
    // 不被裁切实证：菜单底边中心点必须落到菜单自身
    // （底边中心避开圆角弧外的透明角；若被表格容器 overflow 裁切，
    //   该坐标命中的是容器下方元素而非菜单）
    const hitMenu = await page.evaluate(
      ([x, y]) => {
        const el = document.elementFromPoint(x, y)
        return el?.closest('[data-testid="doc-menu"]') !== null
      },
      [menuBox.x + menuBox.width / 2, menuBox.y + menuBox.height - 2],
    )
    expect(hitMenu).toBe(true)
    // 末行菜单全部项可达（含最底部「删除」；菜单项 role=menuitem）
    await expect(page.getByRole('menuitem', { name: '删除' })).toBeVisible()
  })
})
