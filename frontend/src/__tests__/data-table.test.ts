import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import DataTable from '@/components/ui/data-table/DataTable.vue'

/**
 * 数据表壳测试（设计稿表格形态：lav 表头 / 行级联入场）
 *
 * 覆盖：插槽结构渲染 / aria-label 可访问名 / 行级联延迟编排 /
 * IO 命中点亮 / 偏好减少动效降级（不武装隐藏态）/ 无 IO 环境降级。
 * jsdom 无 IntersectionObserver：stub 假观察器捕获实例手动触发回调。
 */

/** 假 IntersectionObserver 实例池（触发回调用） */
const observers: Array<{
  callback: IntersectionObserverCallback
  observed: Element[]
  disconnected: boolean
}> = []

beforeEach(() => {
  observers.length = 0
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      callback: IntersectionObserverCallback
      observed: Element[] = []
      disconnected = false
      constructor(callback: IntersectionObserverCallback) {
        this.callback = callback
        observers.push(this)
      }
      observe(target: Element) {
        this.observed.push(target)
      }
      disconnect() {
        this.disconnected = true
      }
      unobserve() {
        /* 指令/组件路径均走 disconnect 整体断开 */
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

/** 组装标准两列表格挂载（header 插槽 + 默认插槽两行） */
function mountTable(props: Record<string, unknown> = {}) {
  return mount(DataTable, {
    props,
    slots: {
      header: '<th>文件名</th><th>状态</th>',
      default: `
        <tr><td>数据结构讲义.pdf</td><td>INDEXED</td></tr>
        <tr><td>算法导论.pdf</td><td>PENDING</td></tr>
      `,
    },
  })
}

/** 以指定 index 触发观察回调（isIntersecting 控制命中与否） */
function fireIntersect(index: number, isIntersecting: boolean) {
  const observer = observers[index]
  observer.callback([{ isIntersecting } as IntersectionObserverEntry], observer as never)
}

describe('DataTable 表格壳', () => {
  it('渲染 thead/tbody 插槽内容并透传 aria-label 可访问名', () => {
    const wrapper = mountTable({ label: '文档列表' })

    expect(wrapper.find('table').attributes('aria-label')).toBe('文档列表')
    expect(wrapper.findAll('thead th')).toHaveLength(2)
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
    expect(wrapper.text()).toContain('数据结构讲义.pdf')
    wrapper.unmount()
  })

  it('label 缺省：不输出 aria-label 属性', () => {
    const wrapper = mountTable()

    expect(wrapper.find('table').attributes('aria-label')).toBeUndefined()
    wrapper.unmount()
  })

  it('行级联入场：挂载即武装（dt-armed）+ 逐行写 --d（0.15s 步进封顶 0.9s），IO 命中后点亮', () => {
    const wrapper = mountTable()

    // 挂载即武装隐藏态并开始观察表格自身
    const table = wrapper.find('table')
    expect(table.classes()).toContain('dt-armed')
    expect(observers).toHaveLength(1)
    expect(observers[0].observed).toContain(table.element)

    // 级联延迟：第 1 行 0s、第 2 行 0.15s（步进 0.15s）
    const rows = wrapper.findAll('tbody tr')
    const rowDelay = (index: number) =>
      (rows[index].element as HTMLTableRowElement).style.getPropertyValue('--d')
    expect(rowDelay(0)).toBe('0s')
    expect(rowDelay(1)).toBe('0.15s')

    // 未命中视口：保持隐藏态
    fireIntersect(0, false)
    expect(table.classes()).not.toContain('dt-in')

    // 命中视口：点亮并停止观察（断开连接）
    fireIntersect(0, true)
    expect(table.classes()).toContain('dt-in')
    expect(observers[0].disconnected).toBe(true)
    wrapper.unmount()
  })

  it('长表级联封顶：第 7 行及以后延迟固定 0.9s（防尾部延迟过大）', () => {
    const wrapper = mount(DataTable, {
      slots: {
        header: '<th>列</th>',
        default: Array.from({ length: 8 }, (_, i) => `<tr><td>行${i + 1}</td></tr>`).join(''),
      },
    })

    const rows = wrapper.findAll('tbody tr')
    const rowDelay = (index: number) =>
      (rows[index].element as HTMLTableRowElement).style.getPropertyValue('--d')
    expect(rowDelay(6)).toBe('0.9s')
    expect(rowDelay(7)).toBe('0.9s')
    wrapper.unmount()
  })

  it('偏好减少动效：不武装隐藏态（行直接可见），不创建观察器', () => {
    // matchMedia 命中 prefers-reduced-motion: reduce
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: query.includes('prefers-reduced-motion'),
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

    const wrapper = mountTable()

    expect(wrapper.find('table').classes()).not.toContain('dt-armed')
    expect(observers).toHaveLength(0)
    wrapper.unmount()
  })

  it('环境无 IntersectionObserver：降级为不武装（行直接可见）', () => {
    vi.unstubAllGlobals()
    vi.stubGlobal('IntersectionObserver', undefined)

    const wrapper = mountTable()

    expect(wrapper.find('table').classes()).not.toContain('dt-armed')
    wrapper.unmount()
  })

  it('组件卸载：断开未完成的观察器（无僵尸观察）', () => {
    const wrapper = mountTable()
    fireIntersect(0, false) // 保持观察中

    wrapper.unmount()
    expect(observers[0].disconnected).toBe(true)
  })
})
