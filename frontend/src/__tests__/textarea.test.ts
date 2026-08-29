import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { Textarea } from '@/components/ui/textarea'

/**
 * 基础多行文本组件测试（契约 G.2.3 表单基线）
 *
 * 覆盖：v-model / label 上置 / rows 透传 / 错误内联与 aria / 禁用态。
 */
describe('Textarea 基础多行文本', () => {
  function mountTextarea(props: Record<string, unknown> = {}) {
    return mount(Textarea, {
      props: { modelValue: '', label: '简述', ...props },
    })
  }

  it('label 上置 + rows 缺省 3 + 禁止纵向拖拽（布局稳定）', () => {
    const wrapper = mountTextarea()
    const area = wrapper.find('textarea')
    expect(wrapper.find('label').text()).toContain('简述')
    expect(area.attributes('rows')).toBe('3')
    expect(area.classes()).toContain('resize-none')
    wrapper.unmount()
  })

  it('v-model：输入回抛 update:modelValue', async () => {
    const wrapper = mountTextarea()
    await wrapper.find('textarea').setValue('一句话介绍课程')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['一句话介绍课程'])
    wrapper.unmount()
  })

  it('错误态：aria-invalid + 字段下方红字 + aria-describedby 关联', () => {
    const wrapper = mountTextarea({ error: '简述不能超过 200 字' })
    const area = wrapper.find('textarea')
    expect(area.attributes('aria-invalid')).toBe('true')
    const descId = area.attributes('aria-describedby')
    expect(wrapper.find(`#${descId}`).text()).toBe('简述不能超过 200 字')
    wrapper.unmount()
  })

  it('helper 辅助说明展示（无错误时）', () => {
    const wrapper = mountTextarea({ helper: '展示在课程详情页顶部' })
    expect(wrapper.find('p').text()).toBe('展示在课程详情页顶部')
    wrapper.unmount()
  })

  it('rows 透传 + 禁用态', () => {
    const wrapper = mountTextarea({ rows: 5, disabled: true })
    const area = wrapper.find('textarea')
    expect(area.attributes('rows')).toBe('5')
    expect(area.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
