import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, knowledgeBaseApi } from '@/lib/api'
import KnowledgeBasesView from '@/views/KnowledgeBasesView.vue'

import type { KnowledgeBaseVO, PageResponse } from '@/lib/types'

/**
 * 知识库管理页测试（Task 17 核心交付）
 *
 * 覆盖契约（设计 §2.4 知识库 CRUD 行 + §2.6 表格/表单/弹窗规范 + 任务 brief）：
 * 1. 列表渲染：名称/描述/状态 Badge/创建时间 + 分页器「共 N 条」
 * 2. 新建 Dialog：name 必填校验（zod 前置，不发请求）、提交成功 toast + 刷新列表
 * 3. 编辑回填：行内编辑打开 Dialog 预填 name/description，保存走 update
 * 4. 删除二次确认：级联告警文案 + 取消不调接口 + 确认后 remove + toast
 * 5. 分页：上一页/下一页禁用态与翻页请求参数
 * 6. 四态：loading skeleton / empty 空态（含行动入口）/ error 横幅重试 / 正常
 *
 * 接口层以 vi.spyOn 替换 api 模块函数（视图直接调 api 函数，内存 mock 无网络）。
 */

/** 分页响应构造（Long total 为 string，page/size 为 number） */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 知识库记录工厂（后端仅返回 ACTIVE 列表） */
function kb(
  id: string,
  name: string,
  description = '',
  createdAt = '2026-08-20T10:00:00',
): KnowledgeBaseVO {
  return {
    id,
    name,
    description,
    status: 'ACTIVE',
    createdBy: '1001',
    createdAt,
    updatedAt: createdAt,
  }
}

/** 挂载知识库页（独立 pinia；视图不依赖路由，无需 router 插件） */
async function mountKb() {
  const pinia = createPinia()
  setActivePinia(pinia)
  const wrapper = mount(KnowledgeBasesView, { global: { plugins: [pinia] } })
  await flushPromises()
  return { wrapper, pinia }
}

/** 打开新建 Dialog 并返回 */
async function openCreateDialog(wrapper: ReturnType<typeof mount>) {
  await wrapper.find('[data-testid="create-kb"]').trigger('click')
  return wrapper.find('[data-testid="kb-dialog"]')
}

describe('KnowledgeBasesView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染名称/描述/状态 Badge/创建时间与总数', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(
      pageOf(
        [
          kb('kb-1', '前端知识库', '前端框架与组件资料'),
          kb('kb-2', '后端知识库', 'Spring Boot 与数据库讲义'),
        ],
        '2',
      ),
    )
    const { wrapper } = await mountKb()

    expect(wrapper.find('[data-testid="kb-skeleton"]').exists()).toBe(false)
    const table = wrapper.find('[data-testid="kb-table"]')
    expect(table.text()).toContain('前端知识库')
    expect(table.text()).toContain('前端框架与组件资料')
    expect(table.text()).toContain('后端知识库')
    // 状态 Badge：ACTIVE → emerald（设计 §2.5 课程 ACTIVE）
    const badge = table.findAll('span').find((b) => b.text() === 'ACTIVE')
    expect(badge?.classes()).toContain('bg-emerald-50')
    expect(badge?.classes()).toContain('text-emerald-600')
    // 创建时间短格式 MM-DD HH:mm
    expect(table.text()).toContain('08-20 10:00')
    // 分页器左侧总数
    expect(wrapper.text()).toContain('共 2 条')
    // 页头操作按钮在场
    expect(wrapper.find('[data-testid="create-kb"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('KnowledgeBasesView：新建 Dialog 校验与提交', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('name 必填：空名提交就地报错且不调创建接口', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([], '0'))
    const createSpy = vi.spyOn(knowledgeBaseApi, 'create').mockResolvedValue(kb('kb-new', '新品库'))
    const { wrapper } = await mountKb()

    const dialog = await openCreateDialog(wrapper)
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('新建知识库')

    // 空表单提交：zod 前置校验拦截
    await dialog.find('form[data-testid="kb-form"]').trigger('submit')
    await flushPromises()
    expect(dialog.text()).toContain('请输入知识库名称')
    expect(createSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('新建成功：调用 create 携带表单、toast 成功、关闭 Dialog 并刷新列表', async () => {
    const listSpy = vi
      .spyOn(knowledgeBaseApi, 'list')
      .mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const createSpy = vi
      .spyOn(knowledgeBaseApi, 'create')
      .mockResolvedValue(kb('kb-new', '新品知识库'))
    const { wrapper } = await mountKb()
    const listCallsBefore = listSpy.mock.calls.length

    const dialog = await openCreateDialog(wrapper)
    await dialog.find('input[aria-label="知识库名称"]').setValue('新品知识库')
    await dialog.find('textarea[aria-label="知识库描述"]').setValue('二期课程资料')
    await dialog.find('form[data-testid="kb-form"]').trigger('submit')
    await flushPromises()

    expect(createSpy).toHaveBeenCalledWith({ name: '新品知识库', description: '二期课程资料' })
    expect(document.body.textContent).toContain('知识库创建成功')
    // Dialog 关闭 + 列表刷新（重新拉取）
    expect(wrapper.find('[data-testid="kb-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(listCallsBefore)
    wrapper.unmount()
  })

  it('创建失败：danger toast 展示后端文案，Dialog 停留可重试', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([], '0'))
    vi.spyOn(knowledgeBaseApi, 'create').mockRejectedValue(
      new ApiError(400, '知识库名称已存在', 400),
    )
    const { wrapper } = await mountKb()

    const dialog = await openCreateDialog(wrapper)
    await dialog.find('input[aria-label="知识库名称"]').setValue('重名库')
    await dialog.find('form[data-testid="kb-form"]').trigger('submit')
    await flushPromises()

    expect(document.body.textContent).toContain('知识库名称已存在')
    expect(wrapper.find('[data-testid="kb-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('KnowledgeBasesView：编辑回填与保存', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('行内编辑：Dialog 预填 name/description，保存调 update 并 toast', async () => {
    const target = kb('kb-1', '前端知识库', '原描述', '2026-08-20T10:00:00')
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([target], '1'))
    const updateSpy = vi.spyOn(knowledgeBaseApi, 'update').mockResolvedValue()
    const { wrapper } = await mountKb()

    await wrapper.find('[data-testid="edit-kb-1"]').trigger('click')
    const dialog = wrapper.find('[data-testid="kb-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('编辑知识库')
    // 回填断言：input 值等于行数据
    expect((dialog.find('input[aria-label="知识库名称"]').element as HTMLInputElement).value).toBe(
      '前端知识库',
    )
    expect(
      (dialog.find('textarea[aria-label="知识库描述"]').element as HTMLTextAreaElement).value,
    ).toBe('原描述')

    // 改名保存：update 携带 id 与表单
    await dialog.find('input[aria-label="知识库名称"]').setValue('前端知识库（改名）')
    await dialog.find('form[data-testid="kb-form"]').trigger('submit')
    await flushPromises()

    expect(updateSpy).toHaveBeenCalledWith('kb-1', {
      name: '前端知识库（改名）',
      description: '原描述',
    })
    expect(document.body.textContent).toContain('知识库已更新')
    expect(wrapper.find('[data-testid="kb-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('KnowledgeBasesView：删除二次确认', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('删除前二次确认：级联告警文案，取消不调接口', async () => {
    const target = kb('kb-1', '前端知识库')
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([target], '1'))
    const removeSpy = vi.spyOn(knowledgeBaseApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountKb()

    await wrapper.find('[data-testid="delete-kb-1"]').trigger('click')
    const dialog = wrapper.find('[data-testid="delete-dialog"]')
    expect(dialog.exists()).toBe(true)
    // 级联告警文案（设计 §2.4 知识库行：删除级联文档与分片）
    expect(dialog.text()).toContain('级联删除')
    expect(dialog.text()).toContain('不可恢复')
    expect(dialog.text()).toContain('前端知识库')

    await wrapper.find('[data-testid="cancel-delete"]').trigger('click')
    expect(wrapper.find('[data-testid="delete-dialog"]').exists()).toBe(false)
    expect(removeSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('确认删除：调用 remove、toast 成功并刷新列表', async () => {
    const listSpy = vi
      .spyOn(knowledgeBaseApi, 'list')
      .mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const removeSpy = vi.spyOn(knowledgeBaseApi, 'remove').mockResolvedValue()
    const { wrapper } = await mountKb()
    const listCallsBefore = listSpy.mock.calls.length

    await wrapper.find('[data-testid="delete-kb-1"]').trigger('click')
    await wrapper.find('[data-testid="confirm-delete"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('kb-1')
    expect(document.body.textContent).toContain('知识库已删除')
    expect(wrapper.find('[data-testid="delete-dialog"]').exists()).toBe(false)
    expect(listSpy.mock.calls.length).toBeGreaterThan(listCallsBefore)
    wrapper.unmount()
  })
})

describe('KnowledgeBasesView：分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('分页器：首页上一页禁用，翻页携带 page 参数，总数/页码正确', async () => {
    // 25 条三分页：page1 两条、page2 一条（按请求参数回包）
    const listSpy = vi.spyOn(knowledgeBaseApi, 'list').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      const records =
        p === 1
          ? [kb('kb-1', '第一页第一条'), kb('kb-2', '第一页第二条')]
          : [kb('kb-3', '第二页第三条')]
      return pageOf(records, '25', p)
    })
    const { wrapper } = await mountKb()

    // 首页：上一页禁用、下一页可用；「共 25 条」「第 1 / 3 页」
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      true,
    )
    expect((wrapper.find('[data-testid="next-page"]').element as HTMLButtonElement).disabled).toBe(
      false,
    )
    expect(wrapper.text()).toContain('共 25 条')
    expect(wrapper.text()).toContain('第 1 / 3 页')
    expect(wrapper.text()).toContain('第一页第一条')

    // 下一页：以 page=2 重新拉取并渲染
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.text()).toContain('第 2 / 3 页')
    expect(wrapper.text()).toContain('第二页第三条')
    expect((wrapper.find('[data-testid="prev-page"]').element as HTMLButtonElement).disabled).toBe(
      false,
    )

    // 回上一页：page=1
    await wrapper.find('[data-testid="prev-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 1 })
    expect(wrapper.text()).toContain('第 1 / 3 页')
    wrapper.unmount()
  })
})

describe('KnowledgeBasesView：四态', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场（与最终布局同形）', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountKb()

    expect(wrapper.find('[data-testid="kb-skeleton"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="kb-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockRejectedValueOnce(
      new ApiError(503, '服务暂时不可用', 503),
    )
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([kb('kb-1', '前端知识库')], '1'))
    const { wrapper } = await mountKb()

    const banner = wrapper.find('[role="alert"]')
    expect(banner.text()).toContain('服务暂时不可用，请稍后重试')

    await wrapper.find('[data-testid="retry-kb"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="kb-table"]').text()).toContain('前端知识库')
    wrapper.unmount()
  })

  it('empty：空态含行动入口（新建按钮），禁止裸「暂无数据」', async () => {
    vi.spyOn(knowledgeBaseApi, 'list').mockResolvedValue(pageOf([], '0'))
    const { wrapper } = await mountKb()

    expect(wrapper.text()).toContain('还没有知识库')
    // 空态行动入口可直接打开新建 Dialog
    await wrapper.find('[data-testid="create-kb-empty"]').trigger('click')
    expect(wrapper.find('[data-testid="kb-dialog"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
