import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import { Input } from '@/components/ui/input'

/**
 * 基础文本输入组件测试（契约 G.2.3 表单基线）
 *
 * 覆盖：v-model 双向 / label 上置与必填指示 / 错误内联与 aria 关联 /
 * helper 展示与优先级 / 禁用只读态。
 */
describe('Input 基础文本输入', () => {
  function mountInput(props: Record<string, unknown> = {}) {
    return mount(Input, {
      props: { modelValue: '', label: '课程标题', ...props },
    })
  }

  it('label 上置渲染 + required 追加红星', () => {
    const wrapper = mountInput({ required: true })
    const label = wrapper.find('label')
    expect(label.exists()).toBe(true)
    expect(label.text()).toContain('课程标题')
    expect(label.find('.text-danger').exists()).toBe(true)
    wrapper.unmount()
  })

  it('v-model：输入回抛 update:modelValue', async () => {
    const wrapper = mountInput()
    await wrapper.find('input').setValue('RAG 实战')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['RAG 实战'])
    wrapper.unmount()
  })

  it('错误态：输入框 aria-invalid + 字段下方红字 + aria-describedby 关联', async () => {
    const wrapper = mountInput({ error: '请输入课程标题' })
    const input = wrapper.find('input')
    expect(input.attributes('aria-invalid')).toBe('true')
    const descId = input.attributes('aria-describedby')
    expect(descId).toBeTruthy()
    expect(wrapper.find(`#${descId}`).text()).toBe('请输入课程标题')
    wrapper.unmount()
  })

  it('helper：无错误时以 muted 色展示（视觉区分由类名承载）', () => {
    const wrapper = mountInput({ helper: '展示在课程卡片封面' })
    expect(wrapper.find('p').text()).toBe('展示在课程卡片封面')
    expect(wrapper.find('p').classes()).toContain('text-text-subtle')
    wrapper.unmount()
  })

  it('错误优先于 helper 展示', () => {
    const wrapper = mountInput({ helper: '辅助说明', error: '错误文案' })
    expect(wrapper.find('p').text()).toBe('错误文案')
    expect(wrapper.find('p').classes()).toContain('text-danger')
    wrapper.unmount()
  })

  it('label 传空串时不渲染 label 行', () => {
    const wrapper = mountInput({ label: '' })
    expect(wrapper.find('label').exists()).toBe(false)
    wrapper.unmount()
  })

  it('placeholder 透传 + disabled/readonly 原生属性', () => {
    const wrapper = mountInput({
      placeholder: '请输入标题',
      disabled: true,
      readonly: true,
    })
    const input = wrapper.find('input')
    expect(input.attributes('placeholder')).toBe('请输入标题')
    expect(input.attributes('disabled')).toBeDefined()
    expect(input.attributes('readonly')).toBeDefined()
    wrapper.unmount()
  })

  it('type 透传（number 域）与初始值回显', () => {
    const wrapper = mountInput({ type: 'number', modelValue: '199' })
    const input = wrapper.find('input')
    expect(input.attributes('type')).toBe('number')
    expect((input.element as HTMLInputElement).value).toBe('199')
    wrapper.unmount()
  })
})
