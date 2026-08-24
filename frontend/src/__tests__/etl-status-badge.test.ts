import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import EtlStatusBadge from '@/components/EtlStatusBadge.vue'

import type { DocumentParseStatus } from '@/lib/types'

/**
 * ETL 状态徽章测试（Task 18 核心交付 + carry：8 态映射全分支样本）
 *
 * 覆盖契约（设计 §2.5 状态可视化体系）：
 * 1. 八态变体映射：PENDING 中性 / PARSING·PARSED 蓝(brand) / CHUNKING·CHUNKED 紫(violet) /
 *    EMBEDDING amber(warning) / INDEXED emerald(success) / FAILED red(danger)
 * 2. 工作态 spinner：PARSING/CHUNKING/EMBEDDING 内置 12px 旋转图标；PARSED/CHUNKED 无 spinner
 * 3. INDEXED ✓ / FAILED ✗ 图标；FAILED errorMessage 行内可展开（mono 13px）
 */

/** 八态样本表：状态 → 期望变体类名对（bg/text）、是否带 spinner、是否带图标 */
const CASES: Array<{
  status: DocumentParseStatus
  bg: string
  text: string
  spinner: boolean
  icon: boolean
}> = [
  { status: 'PENDING', bg: 'bg-slate-100', text: 'text-slate-500', spinner: false, icon: false },
  { status: 'PARSING', bg: 'bg-brand-soft', text: 'text-brand-strong', spinner: true, icon: true },
  { status: 'PARSED', bg: 'bg-brand-soft', text: 'text-brand-strong', spinner: false, icon: false },
  { status: 'CHUNKING', bg: 'bg-violet-50', text: 'text-violet-600', spinner: true, icon: true },
  { status: 'CHUNKED', bg: 'bg-violet-50', text: 'text-violet-600', spinner: false, icon: false },
  { status: 'EMBEDDING', bg: 'bg-amber-50', text: 'text-amber-600', spinner: true, icon: true },
  { status: 'INDEXED', bg: 'bg-emerald-50', text: 'text-emerald-600', spinner: false, icon: true },
  { status: 'FAILED', bg: 'bg-red-50', text: 'text-red-600', spinner: false, icon: true },
]

describe('EtlStatusBadge：八态变体映射（设计 §2.5）', () => {
  it.each(CASES)('$status 渲染 $bg 变体', ({ status, bg, text, spinner, icon }) => {
    const wrapper = mount(EtlStatusBadge, { props: { status } })
    const badge = wrapper.find('[data-testid="etl-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.classes()).toContain(bg)
    expect(badge.classes()).toContain(text)
    expect(badge.text()).toContain(status)

    // 工作态 spinner：仅 PARSING/CHUNKING/EMBEDDING 三态（设计 §2.5 非终态 spinner）
    expect(badge.find('.animate-spin').exists()).toBe(spinner)
    // 图标：工作态 spinner 与终态 ✓/✗ 在场；PENDING/PARSED/CHUNKED 纯文本无图标
    expect(badge.find('svg').exists()).toBe(icon)
    wrapper.unmount()
  })

  it('INDEXED 终态带 ✓、FAILED 终态带 ✗ 图标（终态标识）', () => {
    // INDEXED → PhCheck（svg 在场）
    const indexed = mount(EtlStatusBadge, { props: { status: 'INDEXED' } })
    expect(indexed.find('[data-testid="etl-badge"] svg').exists()).toBe(true)
    indexed.unmount()

    // FAILED（无 errorMessage）→ PhX（svg 在场，无展开按钮）
    const failed = mount(EtlStatusBadge, { props: { status: 'FAILED' } })
    expect(failed.find('[data-testid="etl-badge"] svg').exists()).toBe(true)
    expect(failed.find('[data-testid="etl-badge-toggle"]').exists()).toBe(false)
    failed.unmount()
  })
})

describe('EtlStatusBadge：FAILED errorMessage 行内展开（设计 §2.5）', () => {
  it('有 errorMessage：点击徽章展开 mono 13px 错误详情，再点收起', async () => {
    const wrapper = mount(EtlStatusBadge, {
      props: { status: 'FAILED', errorMessage: '解析失败：文件损坏' },
    })

    // 初始收起：错误文本不可见，切换按钮在场
    expect(wrapper.find('[data-testid="etl-badge-toggle"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="etl-error-message"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="etl-badge-toggle"]').attributes('aria-expanded')).toBe(
      'false',
    )

    // 点击展开：mono 13px 错误区块 + aria-expanded=true
    await wrapper.find('[data-testid="etl-badge-toggle"]').trigger('click')
    const errorBox = wrapper.find('[data-testid="etl-error-message"]')
    expect(errorBox.exists()).toBe(true)
    expect(errorBox.text()).toContain('解析失败：文件损坏')
    expect(errorBox.classes()).toContain('font-mono')
    expect(errorBox.classes()).toContain('text-[13px]')
    expect(wrapper.find('[data-testid="etl-badge-toggle"]').attributes('aria-expanded')).toBe(
      'true',
    )

    // 再次点击收起
    await wrapper.find('[data-testid="etl-badge-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="etl-error-message"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('无 errorMessage：不渲染展开按钮，纯徽章展示', () => {
    const wrapper = mount(EtlStatusBadge, { props: { status: 'FAILED' } })
    expect(wrapper.find('[data-testid="etl-badge-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="etl-error-message"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
