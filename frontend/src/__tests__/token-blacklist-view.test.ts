import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, securityApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import TokenBlacklistView from '@/views/TokenBlacklistView.vue'

import type { PageResponse, SysTokenBlacklistVO } from '@/lib/types'

/**
 * Token 黑名单页测试（Task 21 核心交付，超管专属：设计 §2.4.7 黑名单）
 *
 * 覆盖契约：
 * 1. 筛选 userId/jti/tokenType（查询按钮统一提交 → 携带参数）
 * 2. 列表：#id / jti / tokenType Badge / userId / reason / expiresAt / createdAt / 移除
 * 3. 手动加入（查询参数传参表单）：addBlacklist 收到 {jti, tokenType, userId,
 *    reason:'MANUAL_REVOKE'}；expiresAt 可选（缺省不携带）
 * 4. 移除：二次确认 → removeBlacklist(id)
 * 5. [清理过期]：cleanupBlacklist → toast 展示 cleaned 数 → 刷新
 * 6. 四态：loading 骨架 / empty / error 横幅重试 / 正常 + 分页
 *
 * 契约要点：id/total 为 Long 字符串铁律；cleaned 为 Integer 保持 number；
 * 后端 K5 全参数走 @RequestParam（查询参数，非请求体）。
 */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 黑名单项工厂 */
function item(id: string, over: Partial<SysTokenBlacklistVO> = {}): SysTokenBlacklistVO {
  return {
    id,
    jti: `jti-${id}`,
    tokenType: 'ACCESS',
    userId: '4000' + id,
    blacklistedBy: '1001',
    reason: 'MANUAL_REVOKE',
    expiresAt: '2026-08-31T10:00:00',
    createdAt: '2026-08-24T09:00:00',
    ...over,
  }
}

/** 挂载黑名单页（仅超管可进，mount 前固定 SUPER_ADMIN 登录态） */
async function mountBlacklist() {
  const pinia = createPinia()
  setActivePinia(pinia)
  useAuthStore().setAuth({
    accessToken: 'at-1',
    refreshToken: 'rt-1',
    userId: '1001',
    role: 'SUPER_ADMIN',
    displayName: '超管',
  })
  const router = createAppRouter()
  await router.push('/security/token-blacklist')
  await router.isReady()
  const wrapper = mount(TokenBlacklistView, {
    global: {
      plugins: [
        [
          VueQueryPlugin,
          { queryClient: new QueryClient({ defaultOptions: { queries: { retry: false } } }) },
        ],
        pinia,
        router,
      ],
    },
  })
  await flushPromises()
  return { wrapper, router }
}

describe('TokenBlacklistView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染 #id/jti/tokenType/userId/reason/到期/时间，tokenType Badge 区分 ACCESS/REFRESH', async () => {
    vi.spyOn(securityApi, 'blacklist').mockResolvedValue(
      pageOf([item('tb-1'), item('tb-2', { tokenType: 'REFRESH' })], '2'),
    )
    const { wrapper } = await mountBlacklist()

    // 行内容齐全
    const row1 = wrapper.find('[data-testid="row-tb-1"]')
    expect(row1.text()).toContain('#tb-1')
    expect(row1.text()).toContain('jti-tb-1')
    expect(row1.text()).toContain('4000tb-1')
    expect(row1.text()).toContain('MANUAL_REVOKE')
    expect(row1.text()).toContain('08-31 10:00')
    expect(row1.text()).toContain('08-24 09:00')

    // tokenType Badge：ACCESS 强调 / REFRESH 中性（双型区分）
    expect(wrapper.find('[data-testid="tb-type-tb-1"]').classes()).toContain('bg-brand-soft')
    expect(wrapper.find('[data-testid="tb-type-tb-2"]').classes()).toContain('bg-slate-100')

    // 时间列 tabular-nums + 移除入口
    expect(row1.findAll('.tabular-nums').length).toBeGreaterThan(0)
    expect(wrapper.find('[data-testid="op-remove-tb-1"]').exists()).toBe(true)
    wrapper.unmount()
  })
})

describe('TokenBlacklistView：筛选', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('设置 userId/jti/tokenType 后点查询：blacklist 携带全部参数且回第 1 页', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'blacklist')
      .mockResolvedValue(pageOf([item('tb-1')], '1'))
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="filter-user"]').setValue('4000tb-1')
    await wrapper.find('[data-testid="filter-jti"]').setValue('jti-tb-1')
    await wrapper.find('[data-testid="filter-type"]').setValue('ACCESS')
    await wrapper.find('[data-testid="apply-filters"]').trigger('click')
    await flushPromises()

    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({
      userId: '4000tb-1',
      jti: 'jti-tb-1',
      tokenType: 'ACCESS',
    })
    wrapper.unmount()
  })
})

describe('TokenBlacklistView：手动加入（查询参数传参）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('表单提交：addBlacklist 收到 jti/tokenType/userId + reason=MANUAL_REVOKE；expiresAt 缺省不携带', async () => {
    vi.spyOn(securityApi, 'blacklist').mockResolvedValue(pageOf([item('tb-1')], '1'))
    const addSpy = vi.spyOn(securityApi, 'addBlacklist').mockResolvedValue()
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="open-add"]').trigger('click')
    const dialog = wrapper.find('[data-testid="blacklist-add-dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.text()).toContain('手动加入黑名单')

    await wrapper.find('[data-testid="add-jti"]').setValue('jti-new-1')
    await wrapper.find('[data-testid="add-type"]').setValue('REFRESH')
    await wrapper.find('[data-testid="add-user"]').setValue('4000tb-9')
    await wrapper.find('[data-testid="blacklist-add-form"]').trigger('submit')
    await flushPromises()

    // 查询参数传参：reason 固定 MANUAL_REVOKE，expiresAt 未填不携带
    expect(addSpy).toHaveBeenCalledWith({
      jti: 'jti-new-1',
      tokenType: 'REFRESH',
      userId: '4000tb-9',
      reason: 'MANUAL_REVOKE',
    })
    expect(document.body.textContent).toContain('已加入黑名单')
    expect(wrapper.find('[data-testid="blacklist-add-dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('表单 zod 校验：jti/userId 必填拦截，不发请求', async () => {
    vi.spyOn(securityApi, 'blacklist').mockResolvedValue(pageOf([item('tb-1')], '1'))
    const addSpy = vi.spyOn(securityApi, 'addBlacklist').mockResolvedValue()
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="open-add"]').trigger('click')
    await wrapper.find('[data-testid="blacklist-add-form"]').trigger('submit')
    await flushPromises()

    expect(addSpy).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请输入 JTI')
    expect(wrapper.text()).toContain('请输入用户 ID')
    wrapper.unmount()
  })

  it('expiresAt 可选：填写后随查询参数携带（datetime-local → LocalDateTime 串）', async () => {
    vi.spyOn(securityApi, 'blacklist').mockResolvedValue(pageOf([item('tb-1')], '1'))
    const addSpy = vi.spyOn(securityApi, 'addBlacklist').mockResolvedValue()
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="open-add"]').trigger('click')
    await wrapper.find('[data-testid="add-jti"]').setValue('jti-new-2')
    await wrapper.find('[data-testid="add-user"]').setValue('4000tb-9')
    await wrapper.find('[data-testid="add-expires"]').setValue('2026-09-01T12:00')
    await wrapper.find('[data-testid="blacklist-add-form"]').trigger('submit')
    await flushPromises()

    // 缺省 tokenType → ACCESS（default），expiresAt 补秒位变成 ISO 无时区串
    const payload = addSpy.mock.calls.at(-1)?.[0]
    expect(payload).toMatchObject({ jti: 'jti-new-2', userId: '4000tb-9', tokenType: 'ACCESS' })
    expect(payload?.expiresAt).toBe('2026-09-01T12:00:00')
    wrapper.unmount()
  })
})

describe('TokenBlacklistView：移除与清理过期', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('移除：二次确认 → removeBlacklist(id) → toast → 刷新', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'blacklist')
      .mockResolvedValueOnce(pageOf([item('tb-1'), item('tb-2')], '2'))
      .mockResolvedValueOnce(pageOf([item('tb-2')], '1'))
    const removeSpy = vi.spyOn(securityApi, 'removeBlacklist').mockResolvedValue()
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="op-remove-tb-1"]').trigger('click')
    expect(wrapper.find('[data-testid="blacklist-del-dialog"]').exists()).toBe(true)
    await wrapper.find('[data-testid="confirm-blacklist-del"]').trigger('click')
    await flushPromises()

    expect(removeSpy).toHaveBeenCalledWith('tb-1')
    expect(document.body.textContent).toContain('已从黑名单移除')
    // 失效重拉为异步链：行消失与重拉次数以 waitFor 收敛
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="row-tb-1"]').exists()).toBe(false)
      expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    })
    wrapper.unmount()
  })

  it('移除末页最后一条：回退上一页防空页（页码变化自动重拉）', async () => {
    // 第 1 页 1 条共 11（2 页）→ 翻第 2 页 1 条 → 移除后回退第 1 页
    const listSpy = vi
      .spyOn(securityApi, 'blacklist')
      .mockResolvedValueOnce(pageOf([item('tb-1')], '11'))
      .mockResolvedValueOnce(pageOf([item('tb-9')], '11'))
      .mockResolvedValueOnce(pageOf([item('tb-1')], '10'))
    const removeSpy = vi.spyOn(securityApi, 'removeBlacklist').mockResolvedValue()
    const { wrapper } = await mountBlacklist()

    // 翻到第 2 页（末页仅剩 1 条）
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('第 2 / 2 页')
    expect(wrapper.find('[data-testid="row-tb-9"]').exists()).toBe(true)

    // 移除唯一行：回退到第 1 页（不展示空页）
    await wrapper.find('[data-testid="op-remove-tb-9"]').trigger('click')
    await wrapper.find('[data-testid="confirm-blacklist-del"]').trigger('click')
    await flushPromises()
    expect(removeSpy).toHaveBeenCalledWith('tb-9')
    await vi.waitFor(() => {
      expect(wrapper.text()).toContain('第 1 / 1 页')
      expect(wrapper.find('[data-testid="row-tb-1"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="row-tb-9"]').exists()).toBe(false)
      expect(listSpy.mock.calls.length).toBeGreaterThan(2)
    })
    wrapper.unmount()
  })

  it('清理过期：cleanupBlacklist → toast 展示 cleaned 数 → 刷新', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'blacklist')
      .mockResolvedValue(pageOf([item('tb-1')], '1'))
    const cleanupSpy = vi.spyOn(securityApi, 'cleanupBlacklist').mockResolvedValue({ cleaned: 5 })
    const { wrapper } = await mountBlacklist()

    await wrapper.find('[data-testid="cleanup"]').trigger('click')
    await flushPromises()

    expect(cleanupSpy).toHaveBeenCalledTimes(1)
    expect(document.body.textContent).toContain('已清理 5 条过期记录')
    // 失效重拉为异步链：列表重拉次数以 waitFor 收敛
    await vi.waitFor(() => expect(listSpy.mock.calls.length).toBeGreaterThan(1))
    wrapper.unmount()
  })
})

describe('TokenBlacklistView：四态与分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(securityApi, 'blacklist').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountBlacklist()

    expect(wrapper.find('[data-testid="tb-skeleton"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(securityApi, 'blacklist')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([item('tb-1')], '1'))
    const { wrapper } = await mountBlacklist()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
    await wrapper.find('[data-testid="retry-blacklist"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="row-tb-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('empty：无黑名单项空态文案（含手动加入入口）', async () => {
    vi.spyOn(securityApi, 'blacklist').mockResolvedValue(pageOf<SysTokenBlacklistVO>([], '0'))
    const { wrapper } = await mountBlacklist()

    expect(wrapper.text()).toContain('黑名单为空')
    expect(wrapper.find('[data-testid="tb-table"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="open-add"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('分页：共 N 条 + 翻页携带 page 参数', async () => {
    const listSpy = vi.spyOn(securityApi, 'blacklist').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([item(`tb-${p}`)], '41', p)
    })
    const { wrapper } = await mountBlacklist()

    expect(wrapper.text()).toContain('共 41 条')
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.find('[data-testid="row-tb-2"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
