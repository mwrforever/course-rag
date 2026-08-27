import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import EmptyState from '@/components/ui/empty-state/EmptyState.vue'

/**
 * 空态组件测试（图标 + 标题 + 描述 + 可选动作）
 *
 * 覆盖：标题/描述渲染 / 描述可选 / 默认图标 / 自定义图标插槽 / 动作插槽。
 */
describe('EmptyState 空态', () => {
  it('渲染标题与描述（引导性说明）', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无文档', description: '上传课程讲义后自动进入解析管道' },
    })

    expect(wrapper.text()).toContain('暂无文档')
    expect(wrapper.text()).toContain('上传课程讲义后自动进入解析管道')
    wrapper.unmount()
  })

  it('描述缺省：不渲染描述行（避免空占位）', () => {
    const wrapper = mount(EmptyState, { props: { title: '暂无反馈' } })

    expect(wrapper.findAll('p')).toHaveLength(1) // 仅标题一个 p
    wrapper.unmount()
  })

  it('默认图标：brand-soft 圆内渲染 Phosphor 包裹图标（svg）', () => {
    const wrapper = mount(EmptyState, { props: { title: '暂无数据' } })

    expect(wrapper.find('.bg-brand-soft').exists()).toBe(true)
    expect(wrapper.find('.bg-brand-soft svg').exists()).toBe(true)
    wrapper.unmount()
  })

  it('自定义图标插槽：替换默认图标', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无文档' },
      slots: { icon: '<svg data-testid="custom-icon"></svg>' },
    })

    expect(wrapper.find('[data-testid="custom-icon"]').exists()).toBe(true)
    // 默认图标不再渲染（被插槽替换）
    const icons = wrapper.find('.bg-brand-soft').element.querySelectorAll('svg')
    expect(icons).toHaveLength(1)
    wrapper.unmount()
  })

  it('动作插槽：主引导按钮透出', () => {
    const wrapper = mount(EmptyState, {
      props: { title: '暂无文档', description: '先上传一份讲义试试' },
      slots: { action: '<button type="button" data-testid="empty-upload">上传文档</button>' },
    })

    expect(wrapper.find('[data-testid="empty-upload"]').text()).toBe('上传文档')
    wrapper.unmount()
  })
})
