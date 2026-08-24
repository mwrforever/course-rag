import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { showToast } from '@/lib/toast'

/**
 * toast 轻提示测试（设计 §2.6：右上角、三色左边条、3s 自动消失）
 *
 * 失败登出全局流（api 401 刷新失败 → toast「登录已失效，请重新登录」）
 * 与本组件复用同一实现，保证全局提示路径可用。
 */
describe('toast 轻提示', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  /** 容器内最后一条 toast 元素 */
  function lastToast(): HTMLElement | null {
    const container = document.querySelector('[data-toast-container]')
    const toasts = container?.querySelectorAll('[data-toast]')
    return toasts ? (toasts[toasts.length - 1] as HTMLElement) : null
  }

  it('默认 info 类型：展示消息并挂载右上角容器', () => {
    showToast('登录已失效，请重新登录')

    expect(document.body.textContent).toContain('登录已失效，请重新登录')
    const toast = lastToast()
    expect(toast?.textContent).toBe('登录已失效，请重新登录')
    // 左上色条：info 语义色
    expect(toast?.className).toContain('border-info')
    const container = document.querySelector('[data-toast-container]')
    expect(container?.className).toContain('fixed')
    expect(container?.className).toContain('right-4')
    expect(container?.className).toContain('top-4')
  })

  it('success/danger 类型：使用对应语义色左边条', () => {
    showToast('保存成功', 'success')
    showToast('操作失败', 'danger')

    const toasts = document.querySelectorAll('[data-toast]')
    expect(toasts[0].className).toContain('border-success')
    expect(toasts[1].className).toContain('border-danger')
    expect(document.querySelectorAll('[data-toast]')).toHaveLength(2)
  })

  it('3 秒后自动消失', () => {
    showToast('临时消息')

    expect(document.body.textContent).toContain('临时消息')
    vi.advanceTimersByTime(2900)
    expect(document.body.textContent).toContain('临时消息')

    vi.advanceTimersByTime(200)
    expect(document.body.textContent).not.toContain('临时消息')
    // 全部消失后容器一并移除
    expect(document.querySelector('[data-toast-container]')).toBeNull()
  })
})
