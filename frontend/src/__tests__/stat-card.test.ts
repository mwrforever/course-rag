import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import StatCard from '@/components/ui/stat-card/StatCard.vue'

/**
 * 统计卡测试（设计稿 stat-card 形态：lav 底 + 图标圆 + count-up 数值）
 *
 * 覆盖：标签与数值渲染 / 字符串数值直出 / count-up 滚动到终值 /
 * 色系映射 / 自定义格式化 / meta 插槽。
 * jsdom 无 rAF：统一 stub 帧队列手动泵帧驱动动画。
 */

/** rAF 帧队列（{id, cb} 手动泵帧） */
let frames: Array<{ id: number; cb: FrameRequestCallback }> = []
let frameSeq = 0

/** 泵帧：推进虚拟时钟 ms 毫秒并执行当前排队的全部回调（泵中追加的新帧进入下一轮队列） */
function pumpFrames(ms: number) {
  const queued = frames
  frames = []
  queued.forEach((frame) => frame.cb(performance.now() + ms))
}

beforeEach(() => {
  frames = []
  frameSeq = 0
  // jsdom 无 requestAnimationFrame：stub 为可控帧队列（含 cancel 清理路径）
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    frames.push({ id: ++frameSeq, cb })
    return frameSeq
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => {
    frames = frames.filter((frame) => frame.id !== id)
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('StatCard 统计卡', () => {
  it('渲染标签与数值：lav 底卡片造型 + 图标圆', () => {
    const wrapper = mount(StatCard, {
      props: { label: '文档总数', value: 156 },
    })

    expect(wrapper.find('h3').text()).toBe('文档总数')
    expect(wrapper.text()).toContain('156')
    // lav 底（brand-light 令牌）+ 圆形图标容器
    expect(wrapper.find('.stat-card').classes()).toContain('bg-brand-light')
    expect(wrapper.find('.stat-icon').exists()).toBe(true)
    wrapper.unmount()
  })

  it('字符串数值原样呈现（如百分比），不走数字滚动', () => {
    const wrapper = mount(StatCard, {
      props: { label: '点赞率', value: '98%' },
    })

    expect(wrapper.text()).toContain('98%')
    wrapper.unmount()
  })

  it('count-up 开启：数值从 0 滚动到目标终值（en-US 千分位）', async () => {
    const wrapper = mount(StatCard, {
      props: { label: '学生总数', value: 8595, countUp: true },
    })

    // 首帧未泵：仍处于滚动起点（未到终值）
    expect(wrapper.text()).not.toContain('8,595')

    // 泵满 1700ms（设计稿 A2 时长）：终值精确等于目标（千分位格式）
    pumpFrames(1800)
    await nextTick()
    expect(wrapper.text()).toContain('8,595')
    wrapper.unmount()
  })

  it('count-up 关闭（缺省）：数值直接以千分位呈现', () => {
    const wrapper = mount(StatCard, {
      props: { label: '文档总数', value: 12345 },
    })

    expect(wrapper.text()).toContain('12,345')
    wrapper.unmount()
  })

  it('色系映射：tone 落到图标圆文字色（brand/success/warning/danger）', () => {
    const brand = mount(StatCard, { props: { label: '文档', value: 1, tone: 'brand' } })
    expect(brand.find('.stat-icon').classes()).toContain('text-brand')
    brand.unmount()

    const success = mount(StatCard, { props: { label: '学生', value: 1, tone: 'success' } })
    expect(success.find('.stat-icon').classes()).toContain('text-success')
    success.unmount()

    const warning = mount(StatCard, { props: { label: '待修正', value: 1, tone: 'warning' } })
    expect(warning.find('.stat-icon').classes()).toContain('text-warning')
    warning.unmount()

    const danger = mount(StatCard, { props: { label: '失败', value: 1, tone: 'danger' } })
    expect(danger.find('.stat-icon').classes()).toContain('text-danger')
    danger.unmount()
  })

  it('自定义格式化：数值经 format 输出（如固定两位小数）', () => {
    const wrapper = mount(StatCard, {
      props: {
        label: '点赞率',
        value: 98,
        format: (n: number) => `${(n / 100).toFixed(2)}%`,
      },
    })

    expect(wrapper.text()).toContain('0.98%')
    wrapper.unmount()
  })

  it('meta 插槽：附加行内容透出', () => {
    const wrapper = mount(StatCard, {
      props: { label: '待修正分片', value: 12 },
      slots: { meta: '<p data-testid="stat-meta">点击查看分片列表</p>' },
    })

    expect(wrapper.find('[data-testid="stat-meta"]').text()).toBe('点击查看分片列表')
    wrapper.unmount()
  })
})
