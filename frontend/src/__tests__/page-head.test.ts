import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import PageHead from '@/components/ui/page-head/PageHead.vue'

/**
 * 页头组件测试（设计稿 page-head 形态：h1 标题 + 副标题 + 右侧动作区）
 *
 * 覆盖：标题渲染 / 副标题可选渲染 / 动作插槽透出。
 */
describe('PageHead 页头', () => {
  it('渲染主标题（h1 语义）', () => {
    const wrapper = mount(PageHead, { props: { title: '仪表盘' } })

    expect(wrapper.find('h1').text()).toBe('仪表盘')
    wrapper.unmount()
  })

  it('传入副标题时渲染次级说明行，未传时不渲染', () => {
    const withSub = mount(PageHead, {
      props: { title: '文档管理', subtitle: '管理课程知识库的全部文档' },
    })
    expect(withSub.find('p').text()).toBe('管理课程知识库的全部文档')
    withSub.unmount()

    const withoutSub = mount(PageHead, { props: { title: '文档管理' } })
    // 副标题缺省：不渲染 p 节点（避免空占位）
    expect(withoutSub.find('p').exists()).toBe(false)
    withoutSub.unmount()
  })

  it('动作插槽内容渲染到页头右侧动作区', () => {
    const wrapper = mount(PageHead, {
      props: { title: '课程' },
      slots: { actions: '<button type="button">新建课程</button>' },
    })

    const actionButton = wrapper.find('header button')
    expect(actionButton.exists()).toBe(true)
    expect(actionButton.text()).toBe('新建课程')
    wrapper.unmount()
  })
})
