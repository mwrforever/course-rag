import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiError, securityApi } from '@/lib/api'
import { createAppRouter } from '@/router'
import { useAuthStore } from '@/stores/auth'
import LoginRecordsView from '@/views/LoginRecordsView.vue'

import type { PageResponse, SysLoginRecordVO } from '@/lib/types'

/**
 * 登录记录页测试（Task 21 核心交付，超管专属：设计 §2.4.7 登录记录）
 *
 * 覆盖契约：
 * 1. 筛选 userId/deviceType/status（查询按钮统一提交 → 携带参数 + 回第 1 页）
 * 2. 列表：#id / 用户 / 设备 / IP / 到期 / 状态 Badge（ACTIVE emerald / REVOKED amber /
 *    EXPIRED 中性）/ 时间 / 操作 [踢出设备]
 * 3. 踢出设备：仅 ACTIVE 行入口 + 二次确认（danger）→ revokeLoginRecord → toast → 刷新
 * 4. 分页：共 N 条 + 翻页携带 page
 * 5. 四态：loading 骨架 / empty / error 横幅重试 / 正常
 *
 * 契约要点：id/total 为 Long 字符串铁律；时间 ISO-8601 本地时区解析。
 */
function pageOf<T>(records: T[], total: string, page = 1, size = 10): PageResponse<T> {
  return { records, total, page, size }
}

/** 登录记录工厂（默认 ACTIVE + WEB_DESKTOP） */
function record(id: string, over: Partial<SysLoginRecordVO> = {}): SysLoginRecordVO {
  return {
    id,
    userId: `3000${id}`,
    jtiAt: `jti-at-${id}`,
    jtiRt: `jti-rt-${id}`,
    deviceType: 'WEB_DESKTOP',
    deviceInfo: 'Chrome on Windows',
    ipAddress: '192.168.1.10',
    expiresAt: '2026-08-31T10:00:00',
    status: 'ACTIVE',
    createdAt: '2026-08-24T09:00:00',
    updatedAt: '2026-08-24T09:00:00',
    ...over,
  }
}

/** 挂载登录记录页（仅超管可进，mount 前固定 SUPER_ADMIN 登录态） */
async function mountRecords() {
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
  await router.push('/security/login-records')
  await router.isReady()
  const wrapper = mount(LoginRecordsView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { wrapper, router }
}

describe('LoginRecordsView：列表渲染', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('渲染 #id/用户/设备/IP/到期/状态/时间，三态 Badge 各自语义色', async () => {
    vi.spyOn(securityApi, 'loginRecords').mockResolvedValue(
      pageOf(
        [
          record('lr-1'),
          record('lr-2', { status: 'REVOKED' }),
          record('lr-3', { status: 'EXPIRED', deviceType: 'MOBILE_IOS' }),
        ],
        '3',
      ),
    )
    const { wrapper } = await mountRecords()

    // 行内容：id / 用户 / 设备 / IP / 到期 / 时间
    const row1 = wrapper.find('[data-testid="row-lr-1"]')
    expect(row1.text()).toContain('#lr-1')
    expect(row1.text()).toContain('3000lr-1')
    expect(row1.text()).toContain('WEB_DESKTOP')
    expect(row1.text()).toContain('192.168.1.10')
    expect(row1.text()).toContain('08-31 10:00')
    expect(row1.text()).toContain('08-24 09:00')

    // 状态 Badge 语义色（设计 §2.4.7 三态枚举）
    expect(wrapper.find('[data-testid="lr-status-lr-1"]').classes()).toContain('bg-emerald-50')
    expect(wrapper.find('[data-testid="lr-status-lr-2"]').classes()).toContain('bg-amber-50')
    expect(wrapper.find('[data-testid="lr-status-lr-3"]').classes()).toContain('bg-slate-100')

    // 时间列 tabular-nums；设备列文字
    expect(row1.findAll('.tabular-nums').length).toBeGreaterThan(0)

    // 踢出设备入口：仅 ACTIVE 行
    expect(wrapper.find('[data-testid="op-kick-lr-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="op-kick-lr-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="op-kick-lr-3"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('LoginRecordsView：筛选', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('设置 userId/deviceType/status 后点查询：loginRecords 携带全部参数且回第 1 页', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'loginRecords')
      .mockResolvedValue(pageOf([record('lr-1')], '1'))
    const { wrapper } = await mountRecords()

    await wrapper.find('[data-testid="filter-user"]').setValue('3000lr-1')
    await wrapper.find('[data-testid="filter-device"]').setValue('MOBILE_IOS')
    await wrapper.find('[data-testid="filter-status"]').setValue('EXPIRED')
    await wrapper.find('[data-testid="apply-filters"]').trigger('click')
    await flushPromises()

    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({
      userId: '3000lr-1',
      deviceType: 'MOBILE_IOS',
      status: 'EXPIRED',
      page: 1,
    })
    wrapper.unmount()
  })

  it('status 下拉即时生效：选择 REVOKED 直接携带参数重查', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'loginRecords')
      .mockResolvedValue(pageOf([record('lr-2', { status: 'REVOKED' })], '1'))
    const { wrapper } = await mountRecords()

    await wrapper.find('[data-testid="filter-status"]').setValue('REVOKED')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ status: 'REVOKED' })
    wrapper.unmount()
  })
})

describe('LoginRecordsView：踢出设备（二次确认）', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('取消：不调接口；确认：revokeLoginRecord(id) → toast → 刷新', async () => {
    const listSpy = vi
      .spyOn(securityApi, 'loginRecords')
      .mockResolvedValueOnce(pageOf([record('lr-1')], '1'))
      .mockResolvedValueOnce(pageOf([record('lr-1', { status: 'REVOKED' })], '1'))
    const revokeSpy = vi.spyOn(securityApi, 'revokeLoginRecord').mockResolvedValue()
    const { wrapper } = await mountRecords()

    // 取消路径
    await wrapper.find('[data-testid="op-kick-lr-1"]').trigger('click')
    expect(wrapper.find('[data-testid="kick-dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="kick-dialog"]').text()).toContain('踢出设备')
    await wrapper.find('[data-testid="cancel-kick"]').trigger('click')
    expect(revokeSpy).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="kick-dialog"]').exists()).toBe(false)

    // 确认路径（danger 实底 + 二次确认）
    await wrapper.find('[data-testid="op-kick-lr-1"]').trigger('click')
    const confirmBtn = wrapper.find('[data-testid="confirm-kick"]')
    expect(confirmBtn.classes()).toContain('bg-danger')
    await confirmBtn.trigger('click')
    await flushPromises()

    expect(revokeSpy).toHaveBeenCalledWith('lr-1')
    expect(document.body.textContent).toContain('已将该设备踢出')
    expect(listSpy.mock.calls.length).toBeGreaterThan(1)
    wrapper.unmount()
  })
})

describe('LoginRecordsView：四态与分页', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('loading：表格骨架屏在场', async () => {
    vi.spyOn(securityApi, 'loginRecords').mockReturnValue(new Promise(() => {}))
    const { wrapper } = await mountRecords()

    expect(wrapper.find('[data-testid="lr-skeleton"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('error：503 统一降级文案 + 重试恢复', async () => {
    vi.spyOn(securityApi, 'loginRecords')
      .mockRejectedValueOnce(new ApiError(503, '服务暂时不可用', 503))
      .mockResolvedValue(pageOf([record('lr-1')], '1'))
    const { wrapper } = await mountRecords()

    expect(wrapper.find('[role="alert"]').text()).toContain('服务暂时不可用，请稍后重试')
    await wrapper.find('[data-testid="retry-records"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="row-lr-1"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('empty：无登录记录空态文案', async () => {
    vi.spyOn(securityApi, 'loginRecords').mockResolvedValue(pageOf<SysLoginRecordVO>([], '0'))
    const { wrapper } = await mountRecords()

    expect(wrapper.text()).toContain('还没有登录记录')
    expect(wrapper.find('[data-testid="lr-table"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('分页：共 N 条 + 翻页携带 page 参数', async () => {
    const listSpy = vi.spyOn(securityApi, 'loginRecords').mockImplementation(async (params) => {
      const p = params?.page ?? 1
      return pageOf([record(`lr-${p}`)], '33', p)
    })
    const { wrapper } = await mountRecords()

    expect(wrapper.text()).toContain('共 33 条')
    await wrapper.find('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listSpy.mock.calls.at(-1)?.[0]).toMatchObject({ page: 2 })
    expect(wrapper.find('[data-testid="row-lr-2"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
