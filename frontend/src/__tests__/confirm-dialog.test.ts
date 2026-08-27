import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'

import ConfirmDialog from '@/components/ui/confirm-dialog/ConfirmDialog.vue'

/**
 * 确认弹窗测试（紫黑遮罩 + 取消/确认动作契约）
 *
 * 覆盖：受控开合 / dialog 语义 / Esc 与遮罩点击取消 / 确认不自动关闭 /
 * loading 防重复提交 / 色系切换 / $attrs 转发（testid 契约）。
 */
describe('ConfirmDialog 确认弹窗', () => {
  /** 受控挂载：open 绑定 ref（v-model:open） */
  function mountDialog(props: Record<string, unknown> = {}, attrs: Record<string, unknown> = {}) {
    const open = ref(true)
    const wrapper = mount(ConfirmDialog, {
      props: {
        title: '删除知识库',
        description: '删除后不可恢复',
        ...props,
        open: open.value,
        'onUpdate:open': (v: boolean) => (open.value = v),
      },
      attrs,
    })
    return { wrapper, open }
  }

  it('open 渲染：dialog 语义 + 标题描述 + 确认/取消按钮', () => {
    const { wrapper } = mountDialog()

    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(wrapper.text()).toContain('删除知识库')
    expect(wrapper.text()).toContain('删除后不可恢复')
    expect(wrapper.find('[data-testid="confirm-action"]').text()).toBe('确认')
    expect(wrapper.find('[data-testid="cancel-action"]').text()).toBe('取消')
    wrapper.unmount()
  })

  it('open=false：不渲染任何 DOM', () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: false, title: '删除知识库' },
    })

    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('取消按钮：回抛 cancel 并经 v-model:open 关闭', async () => {
    const { wrapper, open } = mountDialog()

    await wrapper.find('[data-testid="cancel-action"]').trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(open.value).toBe(false)
    wrapper.unmount()
  })

  it('Esc 与遮罩自点：均触发取消关闭', async () => {
    const { wrapper, open } = mountDialog()

    await wrapper.find('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(open.value).toBe(false)
    wrapper.unmount()

    // 遮罩自点（click.self：target 为遮罩层本身）
    const second = mountDialog()
    await second.wrapper.find('[role="dialog"]').trigger('click')
    expect(second.open.value).toBe(false)
    second.wrapper.unmount()
  })

  it('确认：回抛 confirm 且不自动关闭（关闭由调用方随异步结果控制）', async () => {
    const { wrapper, open } = mountDialog()

    await wrapper.find('[data-testid="confirm-action"]').trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(open.value).toBe(true)
    wrapper.unmount()
  })

  it('loading 态：确认按钮禁用 + spinner 在场 + 点击不再回抛 confirm', async () => {
    const { wrapper } = mountDialog({ loading: true })

    const confirmButton = wrapper.find('[data-testid="confirm-action"]')
    expect(confirmButton.attributes('disabled')).toBeDefined()
    expect(confirmButton.find('.animate-spin').exists()).toBe(true)

    await confirmButton.trigger('click')
    expect(wrapper.emitted('confirm')).toBeUndefined()
    wrapper.unmount()
  })

  it('色系：danger（默认）确认按钮玫红实底 / brand 走主紫实底', () => {
    const danger = mountDialog()
    expect(danger.wrapper.find('[data-testid="confirm-action"]').classes()).toContain('bg-danger')
    danger.wrapper.unmount()

    const brand = mountDialog({ tone: 'brand' })
    expect(brand.wrapper.find('[data-testid="confirm-action"]').classes()).toContain('bg-brand')
    brand.wrapper.unmount()
  })

  it('$attrs 转发：data-testid 覆盖确认按钮默认值（既有视图选择器契约）', () => {
    const { wrapper } = mountDialog({}, { 'data-testid': 'confirm-delete' })

    expect(wrapper.find('[data-testid="confirm-delete"]').exists()).toBe(true)
    // 默认 confirm-action 被覆盖（同一元素不再持有默认 testid）
    expect(wrapper.find('[data-testid="confirm-action"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('描述缺省：不渲染描述行', async () => {
    const wrapper = mount(ConfirmDialog, {
      props: { open: true, title: '确认操作' },
    })

    expect(wrapper.findAll('p')).toHaveLength(0)
    await nextTick()
    wrapper.unmount()
  })
})
