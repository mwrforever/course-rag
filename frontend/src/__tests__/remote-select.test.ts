import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DefineComponent } from 'vue'

import { RemoteSelect } from '@/components/ui/remote-select'

/**
 * 泛型组件挂载形态收窄：script setup generic 组件经 vue-tsc 推导为开放泛型构造器，
 * mount 的 props 推断退化为 unknown——以 Record<string, unknown> props 形态挂载，
 * 运行时行为不受影响（测试断言全部落在 DOM 交互结果上）。
 */
const RemoteSelectForTest = RemoteSelect as unknown as DefineComponent<Record<string, unknown>>

/**
 * 远程搜索选择组件测试（契约 E 行为清单）
 *
 * 覆盖：打开即空关键字拉取 / 防抖 300ms / AbortController 取消旧请求 /
 * 过期响应禁止回写（竞态防护）/ 三态（加载/空/错误重试）/ 单选与多选交互 /
 * chip 移除 / 键盘导航 / initialOptions 回显 / 禁用态。
 */

/** 测试选项载体（模拟教师/学生等业务对象） */
interface Opt {
  id: string
  name: string
}

const POOL: Opt[] = [
  { id: 't1', name: '张老师' },
  { id: 't2', name: '李老师' },
  { id: 't3', name: '王老师' },
]

/** 受控延迟的搜索记录（关键字 + 信号 + 手动放行） */
interface SearchCall {
  keyword: string
  signal: AbortSignal
  resolve: (list: Opt[]) => void
  reject: (err: unknown) => void
}

describe('RemoteSelect 远程搜索选择', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  /** 挂载：fetcher 记录每次调用（含 signal），默认按池过滤返回 */
  function mountSelect(props: Record<string, unknown> = {}) {
    const calls: SearchCall[] = []
    const fetcher = vi.fn(
      (keyword: string, signal: AbortSignal) =>
        new Promise<Opt[]>((resolve, reject) => {
          calls.push({ keyword, signal, resolve, reject })
        }),
    )
    const wrapper = mount(RemoteSelectForTest, {
      props: {
        modelValue: null,
        getValue: (o: Opt) => o.id,
        getLabel: (o: Opt) => o.name,
        fetcher,
        placeholder: '搜索教师',
        ...props,
      },
    })
    return { wrapper, calls, fetcher }
  }

  /** 触发输入（写关键字 + 防抖调度） */
  async function type(wrapper: ReturnType<typeof mountSelect>['wrapper'], text: string) {
    await wrapper.find('[data-testid="remote-input"]').setValue(text)
  }

  /** 推进防抖窗口并放行微任务队列 */
  async function flush() {
    await vi.advanceTimersByTimeAsync(350)
  }

  it('打开（focus）即以空关键字拉一次：listbox 渲染 + 加载态', async () => {
    const { wrapper, calls } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)

    const input = wrapper.find('[data-testid="remote-input"]')
    expect(input.attributes('aria-expanded')).toBe('true')
    expect(input.attributes('aria-haspopup')).toBe('listbox')
    expect(calls).toHaveLength(1)
    expect(calls[0].keyword).toBe('')
    expect(wrapper.find('[data-testid="remote-listbox"]').attributes('role')).toBe('listbox')
    expect(wrapper.find('[data-testid="remote-loading"]').exists()).toBe(true)

    calls[0].resolve(POOL)
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[data-testid="remote-loading"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('防抖 300ms：输入停顿后才发请求，连续输入只发最后一次', async () => {
    const { wrapper, calls } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    calls[0].resolve([])

    await type(wrapper, '张')
    await vi.advanceTimersByTimeAsync(299)
    expect(calls).toHaveLength(1) // 防抖窗口内未发请求
    await vi.advanceTimersByTimeAsync(1)
    expect(calls).toHaveLength(2)
    expect(calls[1].keyword).toBe('张')

    // 连续输入重置防抖：只发最后一次
    await type(wrapper, '张老')
    await type(wrapper, '张老师')
    await flush()
    expect(calls).toHaveLength(3)
    expect(calls[2].keyword).toBe('张老师')
    wrapper.unmount()
  })

  it('新输入取消旧请求（AbortController）：旧 signal 置 aborted', async () => {
    const { wrapper, calls } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)

    await type(wrapper, '李')
    await flush()
    expect(calls[1].signal.aborted).toBe(false)

    // 再输入：前两次在途请求的 signal 均被 abort
    await type(wrapper, '王')
    expect(calls[0].signal.aborted).toBe(true)
    expect(calls[1].signal.aborted).toBe(true)
    wrapper.unmount()
  })

  it('竞态防护：过期响应（控制器已被替换）禁止回写 options', async () => {
    const { wrapper, calls } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)

    await type(wrapper, '李')
    await flush() // calls[1] 在途未放行
    await type(wrapper, '王')
    await flush() // calls[2] 在途

    // 先放行第二次搜索（新鲜），再放行第一次（过期）：过期结果不得覆盖
    calls[2].resolve([{ id: 't3', name: '王老师' }])
    await vi.advanceTimersByTimeAsync(0)
    calls[1].resolve([{ id: 't2', name: '李老师' }])
    await vi.advanceTimersByTimeAsync(0)

    expect(wrapper.find('[data-testid="remote-option-t3"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="remote-option-t2"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('空态与错误态：emptyText 引导 / errorText 点击重试并透出 error 事件', async () => {
    const { wrapper, calls } = mountSelect({ emptyText: '没有匹配的教师' })
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    calls[0].resolve([])
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[data-testid="remote-empty"]').text()).toBe('没有匹配的教师')

    // 错误路径：重新输入触发失败
    await type(wrapper, '张')
    await flush()
    calls[1].reject(new Error('网络错误'))
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[data-testid="remote-error"]').exists()).toBe(true)
    expect(wrapper.emitted('error')).toHaveLength(1)

    // 点击重试：以当前关键字立即重发（无防抖）
    await wrapper.find('[data-testid="remote-error"] button').trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(3)
    expect(calls[2].keyword).toBe('张')
    wrapper.unmount()
  })

  it('单选：点击选项回抛对象并关闭下拉，输入框回显选中文案；X 清空回抛 null', async () => {
    const { wrapper, calls } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    calls[0].resolve(POOL)
    await vi.advanceTimersByTimeAsync(0)

    await wrapper.find('[data-testid="remote-option-t2"]').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted?.[0]?.[0]).toEqual(POOL[1])
    // 模拟父级 v-model 回写后：关闭下拉 + 输入框回显选中项文案
    await wrapper.setProps({ modelValue: POOL[1] })
    expect(wrapper.find('[data-testid="remote-listbox"]').exists()).toBe(false)
    expect((wrapper.find('[data-testid="remote-input"]').element as HTMLInputElement).value).toBe(
      '李老师',
    )

    // 清空钮：回抛 null
    await wrapper.find('[data-testid="remote-clear"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[1]?.[0]).toBeNull()
    wrapper.unmount()
  })

  it('多选：选项切换包含关系 + chips 展示与 X 平级移除', async () => {
    const { wrapper, calls } = mountSelect({ multiple: true, modelValue: [] })
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    calls[0].resolve(POOL)
    await vi.advanceTimersByTimeAsync(0)

    // 选中 t1 → [t1]
    await wrapper.find('[data-testid="remote-option-t1"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual([POOL[0]])
    expect(wrapper.find('[data-testid="remote-listbox"]').exists()).toBe(true) // 多选保持打开
    await wrapper.setProps({ modelValue: [POOL[0]] })
    expect(wrapper.find('[data-testid="remote-chip-t1"]').text()).toContain('张老师')

    // 再选 t2 → [t1, t2]
    await wrapper.find('[data-testid="remote-option-t2"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[1]?.[0]).toEqual([POOL[0], POOL[1]])
    await wrapper.setProps({ modelValue: [POOL[0], POOL[1]] })
    expect(wrapper.find('[data-testid="remote-option-t2"]').attributes('aria-selected')).toBe(
      'true',
    )

    // chip X 移除 t1 → [t2]
    await wrapper.find('[data-testid="remote-chip-remove-t1"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[2]?.[0]).toEqual([POOL[1]])
    wrapper.unmount()
  })

  it('initialOptions 回显：不经 fetcher 也能渲染 chip（编辑态预填）', async () => {
    const { wrapper } = mountSelect({ multiple: true, modelValue: [POOL[2]], initialOptions: POOL })
    expect(wrapper.find('[data-testid="remote-chip-t3"]').text()).toContain('王老师')
    expect(wrapper.find('[data-testid="remote-chip-remove-t3"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('键盘导航：ArrowDown/Up 移动高亮 + Enter 确认高亮项 + Esc 关闭', async () => {
    const { wrapper, calls } = mountSelect()
    const input = wrapper.find('[data-testid="remote-input"]')
    await input.trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    calls[0].resolve(POOL)
    await vi.advanceTimersByTimeAsync(0)

    // ArrowDown 一次：高亮 t1 → t2，Enter 确认高亮项
    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBeTruthy()
    await input.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toEqual(POOL[1])
    await wrapper.setProps({ modelValue: POOL[1] })

    // 重新打开后 Esc 关闭（不清已选）
    await input.trigger('focus')
    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="remote-listbox"]').exists()).toBe(false)
    expect((input.element as HTMLInputElement).value).toBe('李老师')
    wrapper.unmount()
  })

  it('点击外部关闭：document mousedown 目标不在组件内即收起', async () => {
    const { wrapper } = mountSelect()
    await wrapper.find('[data-testid="remote-input"]').trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[data-testid="remote-listbox"]').exists()).toBe(true)

    document.dispatchEvent(new MouseEvent('mousedown'))
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.find('[data-testid="remote-listbox"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('禁用态：输入框 disabled，focus 不触发拉取', async () => {
    const { wrapper, calls } = mountSelect({ disabled: true })
    const input = wrapper.find('[data-testid="remote-input"]')
    expect(input.attributes('disabled')).toBeDefined()
    await input.trigger('focus')
    await vi.advanceTimersByTimeAsync(0)
    expect(calls).toHaveLength(0)
    wrapper.unmount()
  })
})
