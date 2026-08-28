import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { nextTick } from 'vue'

import DropdownMenu from '@/components/ui/dropdown-menu/DropdownMenu.vue'
import DropdownMenuItem from '@/components/ui/dropdown-menu/DropdownMenuItem.vue'

/**
 * 下拉菜单测试（设计稿 tb-menu 形态：弹簧入场 / Esc 关闭 / 外点关闭）
 *
 * 覆盖：开合切换 / role=menu 语义 / Esc 与外点关闭 / 菜单项点击与禁用 /
 * danger 色系 / 对齐方向 / open-change 事件 / 作用域插槽下发 toggle/close。
 */
describe('DropdownMenu 下拉菜单', () => {
  /** 标准挂载：触发器按钮绑定 toggle + 两个菜单项 */
  function mountMenu(props: Record<string, unknown> = {}) {
    return mount(DropdownMenu, {
      props,
      slots: {
        trigger: `<button type="button" data-testid="dd-trigger" @click="toggle">操作</button>`,
        default: `
          <DropdownMenuItem label="重新解析" @click="close()" />
          <DropdownMenuItem label="删除" tone="danger" data-testid="dd-delete" />
        `,
      },
      global: {
        components: { DropdownMenuItem },
      },
    })
  }

  it('初始关闭：菜单不渲染，触发器在场', () => {
    const wrapper = mountMenu()

    expect(wrapper.find('[data-testid="dd-trigger"]').exists()).toBe(true)
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('点击触发器开合：菜单以 role=menu 渲染并携带菜单项', async () => {
    const wrapper = mountMenu()

    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')
    const menu = wrapper.find('[role="menu"]')
    expect(menu.exists()).toBe(true)
    expect(wrapper.findAll('button[role="menuitem"]')).toHaveLength(2)
    expect(menu.text()).toContain('重新解析')

    // 再次点击触发器：关闭（v-if 卸载）
    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Esc 关闭：open 态下 window keydown Escape 收起菜单', async () => {
    const wrapper = mountMenu()
    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('外点关闭：pointerdown 落点在组件外收起，落点在组件内不收起', async () => {
    const wrapper = mountMenu()
    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')

    // 组件内落点（冒泡到 window）：不关闭
    wrapper
      .find('[data-testid="dd-trigger"]')
      .element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true }))
    await nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(true)

    // 组件外落点（window 自身）：关闭
    window.dispatchEvent(new MouseEvent('pointerdown'))
    await nextTick()
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('open-change 事件：开/关各回抛对应状态', async () => {
    const wrapper = mountMenu()

    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')
    await wrapper.find('[data-testid="dd-trigger"]').trigger('click')

    const events = wrapper.emitted('open-change')
    expect(events).toEqual([[true], [false]])
    wrapper.unmount()
  })

  it('对齐方向：right（默认）右对齐 / left 左对齐；minWidth 透传行内样式', async () => {
    const right = mountMenu()
    await right.find('[data-testid="dd-trigger"]').trigger('click')
    expect(right.find('[role="menu"]').classes()).toContain('right-0')
    expect(right.find('[role="menu"]').classes()).toContain('origin-top-right')
    right.unmount()

    const left = mountMenu({ align: 'left', minWidth: 240 })
    await left.find('[data-testid="dd-trigger"]').trigger('click')
    const menu = left.find('[role="menu"]')
    expect(menu.classes()).toContain('left-0')
    expect(menu.attributes('style')).toContain('min-width: 240px')
    left.unmount()
  })
})

describe('DropdownMenuItem 菜单选项', () => {
  it('常规色系：紫系 hover 类（brand-soft 底 + brand 字色）+ 点击回抛文案', async () => {
    const wrapper = mount(DropdownMenuItem, { props: { label: '重新解析' } })

    const button = wrapper.find('button[role="menuitem"]')
    expect(button.text()).toBe('重新解析')
    expect(button.classes()).toContain('hover:bg-brand-soft')
    expect(button.classes()).toContain('hover:text-brand')

    await button.trigger('click')
    expect(wrapper.emitted('click')).toEqual([['重新解析']])
    wrapper.unmount()
  })

  it('danger 色系：删除类操作走 danger 语义色', () => {
    const wrapper = mount(DropdownMenuItem, { props: { label: '删除', tone: 'danger' } })

    expect(wrapper.find('button').classes()).toContain('text-danger')
    expect(wrapper.find('button').classes()).toContain('hover:bg-red-50')
    wrapper.unmount()
  })

  it('禁用态：按钮 disabled、点击不回抛事件', async () => {
    const wrapper = mount(DropdownMenuItem, { props: { label: '删除', disabled: true } })

    const button = wrapper.find('button')
    expect(button.attributes('disabled')).toBeDefined()

    await button.trigger('click')
    expect(wrapper.emitted('click')).toBeUndefined()
    wrapper.unmount()
  })
})
