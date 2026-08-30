import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { Select } from '@/components/ui/select'

/**
 * 基础下拉选择组件测试（契约 G.2.3 表单基线）
 *
 * 覆盖：选项渲染与 v-model / label 上置与必填指示 / 错误内联与 aria 关联 / 禁用态。
 */
const OPTIONS = [
  { value: '', label: '请选择' },
  { value: 'ACTIVE', label: 'ACTIVE（上架）' },
  { value: 'ARCHIVED', label: 'ARCHIVED（归档）' },
]

describe('Select 基础下拉选择', () => {
  function mountSelect(props: Record<string, unknown> = {}) {
    return mount(Select, {
      props: { modelValue: '', options: OPTIONS, label: '状态', ...props },
    })
  }

  it('选项集渲染 + label 上置 + required 红星', () => {
    const wrapper = mountSelect({ required: true })
    expect(wrapper.find('label').text()).toContain('状态')
    expect(wrapper.find('label').find('.text-danger').exists()).toBe(true)
    expect(wrapper.findAll('option')).toHaveLength(3)
    expect(wrapper.findAll('option')[1].text()).toBe('ACTIVE（上架）')
    wrapper.unmount()
  })

  it('v-model：change 回抛选中 value', async () => {
    const wrapper = mountSelect({ modelValue: 'ACTIVE' })
    const select = wrapper.find('select')
    expect((select.element as HTMLSelectElement).value).toBe('ACTIVE')
    await select.setValue('ARCHIVED')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['ARCHIVED'])
    wrapper.unmount()
  })

  it('错误态：描红 + aria-invalid + 字段下方红字关联', () => {
    const wrapper = mountSelect({ error: '请选择分类' })
    const select = wrapper.find('select')
    expect(select.attributes('aria-invalid')).toBe('true')
    const descId = select.attributes('aria-describedby')
    expect(wrapper.find(`#${descId}`).text()).toBe('请选择分类')
    wrapper.unmount()
  })

  it('禁用态：原生 disabled 属性', () => {
    const wrapper = mountSelect({ disabled: true })
    expect(wrapper.find('select').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('下拉指示箭头在场（Phosphor CaretDown 装饰，不拦截交互）', () => {
    const wrapper = mountSelect()
    expect(wrapper.find('.pointer-events-none').exists()).toBe(true)
    wrapper.unmount()
  })
})
