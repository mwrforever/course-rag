import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick, ref } from 'vue'

import { Dialog } from '@/components/ui/dialog'

/**
 * 通用弹窗壳组件测试（契约 T2.1 统一遮罩形态）
 *
 * 覆盖：受控开合 / dialog 语义 / Esc 与遮罩点击关闭 / canClose 守卫 /
 * 主体与 footer 插槽 / 描述行可选。
 */
describe('Dialog 通用弹窗壳', () => {
  function mountDialog(props: Record<string, unknown> = {}) {
    const open = ref(true)
    const wrapper = mount(Dialog, {
      props: {
        title: '添加学生',
        description: '搜索学生后批量添加',
        ...props,
        open: open.value,
        'onUpdate:open': (v: boolean) => (open.value = v),
      },
      slots: {
        default: '<p data-testid="dialog-body">主体内容</p>',
        footer: '<button data-testid="dialog-footer-btn">确认</button>',
      },
      attachTo: document.body,
    })
    return { wrapper, open }
  }

  it('open 渲染：dialog 语义 + 标题描述 + 主体/footer 插槽', () => {
    const { wrapper } = mountDialog()
    const dialog = wrapper.find('[role="dialog"]')
    expect(dialog.exists()).toBe(true)
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(wrapper.text()).toContain('添加学生')
    expect(wrapper.text()).toContain('搜索学生后批量添加')
    expect(wrapper.find('[data-testid="dialog-body"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="dialog-footer-btn"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('open=false：不渲染任何 DOM', () => {
    const wrapper = mount(Dialog, { props: { open: false, title: '添加学生' } })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('遮罩自点：回抛 update:open=false 与 close', async () => {
    const { wrapper, open } = mountDialog()
    await wrapper.find('[role="dialog"]').trigger('click')
    expect(open.value).toBe(false)
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })

  it('全局 Esc：关闭（keydown 挂 window）', async () => {
    const { wrapper, open } = mountDialog()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(open.value).toBe(false)
    wrapper.unmount()
  })

  it('canClose=false（提交中）：Esc 与遮罩点击均拦截', async () => {
    const { wrapper, open } = mountDialog({ canClose: false })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await wrapper.find('[role="dialog"]').trigger('click')
    await nextTick()
    expect(open.value).toBe(true)
    expect(wrapper.emitted('update:open')).toBeUndefined()
    wrapper.unmount()
  })

  it('描述缺省：不渲染描述行；无 footer 插槽时不渲染动作区', () => {
    const wrapper = mount(Dialog, {
      props: { open: true, title: '提示' },
      slots: { default: '<p>主体</p>' },
    })
    expect(wrapper.findAll('p').length).toBe(1)
    // 无 footer 插槽：动作区容器不渲染（组件根内仅标题 + 主体）
    expect(wrapper.find('[role="dialog"] div > div.mt-5').exists()).toBe(false)
    wrapper.unmount()
  })

  it('宽度类透传（maxWidthClass）', () => {
    const { wrapper } = mountDialog({ maxWidthClass: 'max-w-[560px]' })
    expect(wrapper.find('[role="dialog"] > div').classes()).toContain('max-w-[560px]')
    wrapper.unmount()
  })
})
