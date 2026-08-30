import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { h } from 'vue'
import { PhArrowClockwise } from '@phosphor-icons/vue'

import { IconButton } from '@/components/ui/icon-button'

/**
 * 图标按钮组件测试（契约 G.2.7 刷新按钮形态）
 *
 * 覆盖：aria-label 与 tooltip 同源 / 点击分发 / loading 防重复 / 禁用态 / 尺寸变体。
 */
describe('IconButton 图标按钮', () => {
  function mountIconButton(props: Record<string, unknown> = {}) {
    return mount(IconButton, {
      props: { label: '刷新', ...props },
      slots: { default: () => h(PhArrowClockwise, { class: 'h-4 w-4' }) },
    })
  }

  it('aria-label 与 tooltip 同源渲染，图标插槽透传', () => {
    const wrapper = mountIconButton()
    const button = wrapper.find('button')
    expect(button.attributes('aria-label')).toBe('刷新')
    const tooltip = wrapper.find('[role="tooltip"]')
    expect(tooltip.text()).toBe('刷新')
    expect(button.findComponent(PhArrowClockwise).exists()).toBe(true)
    wrapper.unmount()
  })

  it('点击：回抛 click 事件', async () => {
    const wrapper = mountIconButton()
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('click')).toHaveLength(1)
    wrapper.unmount()
  })

  it('loading 态：spinner 替换图标 + 按钮禁用 + 点击不回抛', async () => {
    const wrapper = mountIconButton({ loading: true })
    const button = wrapper.find('button')
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.find('.animate-spin').exists()).toBe(true)
    expect(button.findComponent(PhArrowClockwise).exists()).toBe(false)
    await button.trigger('click')
    expect(wrapper.emitted('click')).toBeUndefined()
    wrapper.unmount()
  })

  it('disabled 态：原生禁用 + 点击不回抛', async () => {
    const wrapper = mountIconButton({ disabled: true })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('click')).toBeUndefined()
    wrapper.unmount()
  })

  it('尺寸与变体类：sm 32px / outline 描边 / brand 品牌实底', () => {
    const sm = mountIconButton({ size: 'sm' })
    expect(sm.find('button').classes()).toContain('h-8')
    sm.unmount()

    const outline = mountIconButton({ variant: 'outline' })
    expect(outline.find('button').classes()).toContain('border')
    outline.unmount()

    const brand = mountIconButton({ variant: 'brand' })
    expect(brand.find('button').classes()).toContain('bg-brand')
    brand.unmount()
  })
})
