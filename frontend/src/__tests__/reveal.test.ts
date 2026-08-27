import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { vReveal } from '@/directives/reveal'

/**
 * v-reveal 滚动入场指令测试（设计稿 A1：IO threshold .15 命中后 .in 点亮）
 *
 * 覆盖：隐藏态武装 / 级联延迟 --d 写入 / IO 命中点亮并停止观察 /
 * 未命中保持隐藏 / 偏好减少动效降级（直接可见）/ 无 IO 环境降级 /
 * 元素卸载断开观察（无僵尸观察）。
 * jsdom 无 IntersectionObserver：stub 假观察器捕获实例手动触发回调。
 */

/** 假 IntersectionObserver 实例池 */
const observers: Array<{
  callback: IntersectionObserverCallback
  observed: Element[]
  disconnected: boolean
  unobserved: Element[]
}> = []

/** 以指定 index 触发观察回调 */
function fireIntersect(index: number, isIntersecting: boolean) {
  const observer = observers[index]
  observer.callback([{ isIntersecting } as IntersectionObserverEntry], observer as never)
}

/** 挂载一个带 v-reveal 的宿主组件（delay 可选注入数字修饰） */
function mountReveal(delay?: number) {
  return mount(
    {
      template: `<div v-reveal="${delay ?? ''}">内容</div>`,
    },
    { global: { directives: { reveal: vReveal } } },
  )
}

/** 覆盖 matchMedia 返回（reduced 控制 prefers-reduced-motion 命中） */
function stubMatchMedia(reduced: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: reduced && query.includes('prefers-reduced-motion'),
    media: query,
    onchange: null,
    addEventListener() {},
    removeEventListener() {},
    addListener() {},
    removeListener() {},
    dispatchEvent() {
      return false
    },
  }))
}

beforeEach(() => {
  observers.length = 0
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      callback: IntersectionObserverCallback
      observed: Element[] = []
      unobserved: Element[] = []
      disconnected = false
      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback
        observers.push(this)
      }
      observe(target: Element) {
        this.observed.push(target)
      }
      unobserve(target: Element) {
        this.unobserved.push(target)
      }
      disconnect() {
        this.disconnected = true
      }
      takeRecords(): IntersectionObserverEntry[] {
        return []
      }
      root = null
      rootMargin = ''
      thresholds = []
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('v-reveal 滚动入场指令', () => {
  it('挂载即武装隐藏态并观察元素；IO 命中后加 .in 并停止观察', () => {
    const wrapper = mountReveal()
    const el = wrapper.find('div').element

    // 武装：隐藏态类 + 开始观察
    expect(el.classList.contains('reveal')).toBe(true)
    expect(el.classList.contains('in')).toBe(false)
    expect(observers).toHaveLength(1)
    expect(observers[0].observed).toContain(el)

    // 命中视口：点亮（reveal + in）
    fireIntersect(0, true)
    expect(el.classList.contains('in')).toBe(true)
    // 停止观察（disconnect 整体断开，元素从 WeakMap 摘除）
    expect(observers[0].disconnected).toBe(true)
    wrapper.unmount()
  })

  it('未命中视口：保持隐藏态（不加 .in）', () => {
    const wrapper = mountReveal()
    const el = wrapper.find('div').element

    fireIntersect(0, false)
    expect(el.classList.contains('reveal')).toBe(true)
    expect(el.classList.contains('in')).toBe(false)
    wrapper.unmount()
  })

  it('数字修饰：级联延迟毫秒写入 --d CSS 变量（供 transition 消费）', () => {
    const wrapper = mountReveal(120)
    const el = wrapper.find('div').element

    expect(el.style.getPropertyValue('--d')).toBe('0.12s')
    wrapper.unmount()
  })

  it('无修饰：不写 --d（走 CSS 缺省 0s）', () => {
    const wrapper = mountReveal()
    const el = wrapper.find('div').element

    expect(el.style.getPropertyValue('--d')).toBe('')
    wrapper.unmount()
  })

  it('偏好减少动效：不武装隐藏态、不观察（元素直接可见）', () => {
    stubMatchMedia(true)

    const wrapper = mountReveal()
    const el = wrapper.find('div').element

    expect(el.classList.contains('reveal')).toBe(false)
    expect(observers).toHaveLength(0)
    wrapper.unmount()
  })

  it('环境无 IntersectionObserver：降级为直接可见', () => {
    vi.stubGlobal('IntersectionObserver', undefined)

    const wrapper = mountReveal()
    const el = wrapper.find('div').element

    expect(el.classList.contains('reveal')).toBe(false)
    wrapper.unmount()
  })

  it('元素卸载：断开观察器（无僵尸观察阻止 GC）', () => {
    const wrapper = mountReveal()

    wrapper.unmount()
    expect(observers[0].disconnected).toBe(true)
  })
})
