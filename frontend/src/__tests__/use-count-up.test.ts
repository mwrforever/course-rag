import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref } from 'vue'

import { COUNT_UP_DURATION_MS, useCountUp } from '@/composables/use-count-up'

/**
 * useCountUp 数字滚动测试（设计稿 A2：1700ms easeOutCubic，rAF 驱动）
 *
 * 覆盖：终值精确性 / easeOutCubic 单调递增 / 目标变化续滚 /
 * 减少动效与零时长降级（直接终值）/ 环境无 rAF 降级 / 作用域销毁取消帧。
 * jsdom rAF 不可控：stub 帧队列 + 虚拟时钟（performance.now 一并接管）手动泵帧。
 */

/** rAF 帧队列 */
let frames: Array<{ id: number; cb: FrameRequestCallback }> = []
let frameSeq = 0
/** cancelAnimationFrame 调用记录（验证销毁清理路径） */
let cancelledIds: number[] = []
/** 虚拟时钟（毫秒）：与被 stub 的 performance.now 同源，保证多次泵帧累计一致 */
let virtualNow = 0

/** 泵帧：推进虚拟时钟 ms 毫秒并执行当前排队回调（泵中追加的新帧进入下一轮队列） */
function pumpFrames(ms: number) {
  virtualNow += ms
  const queued = frames
  frames = []
  queued.forEach((frame) => frame.cb(virtualNow))
}

beforeEach(() => {
  frames = []
  frameSeq = 0
  cancelledIds = []
  virtualNow = 0
  vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
    frames.push({ id: ++frameSeq, cb })
    return frameSeq
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => {
    cancelledIds.push(id)
    frames = frames.filter((frame) => frame.id !== id)
  })
  // 接管 performance.now 为虚拟时钟：run() 捕获的 start 与泵帧时间同源可累计
  vi.spyOn(performance, 'now').mockImplementation(() => virtualNow)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** 在独立 effectScope 内创建（scope.stop 模拟组件卸载触发 onScopeDispose 清理） */
function setupCountUp(target: Parameters<typeof useCountUp>[0], duration?: number) {
  const scope = effectScope()
  let value: ReturnType<typeof useCountUp> | undefined
  scope.run(() => {
    value = useCountUp(target, duration === undefined ? undefined : { duration })
  })
  return { value: value!, stop: () => scope.stop() }
}

describe('useCountUp 数字滚动', () => {
  it('默认时长 1700ms：泵满时长后终值精确等于目标', () => {
    const { value, stop } = setupCountUp(ref(8595))

    // 起步：从 0 开始（首帧尚未泵）
    expect(value.value).toBe(0)

    // 半程：中间值（介于 0 与目标之间）
    pumpFrames(COUNT_UP_DURATION_MS / 2)
    expect(value.value).toBeGreaterThan(0)
    expect(value.value).toBeLessThan(8595)

    // 满程：精确终值
    pumpFrames(COUNT_UP_DURATION_MS)
    expect(value.value).toBe(8595)
    stop()
  })

  it('easeOutCubic：50% 时间完成 87.5%（前快后慢），75% 时间完成 98.4%', () => {
    const { value, stop } = setupCountUp(ref(1000))

    // 50% 时间点：eased = 1-(1-0.5)^3 = 0.875 → 875
    pumpFrames(COUNT_UP_DURATION_MS / 2)
    expect(value.value).toBe(875)

    // 再泵 25% 时间（累计 75%）：eased = 1-0.25^3 = 0.984375 → 984（增量收窄）
    pumpFrames(COUNT_UP_DURATION_MS / 4)
    expect(value.value).toBe(984)
    stop()
  })

  it('目标变化：从当前显示值续滚到新目标', async () => {
    const target = ref(100)
    const { value, stop } = setupCountUp(target)

    pumpFrames(COUNT_UP_DURATION_MS)
    expect(value.value).toBe(100)

    // 目标上调：watch 触发，从 100 续滚
    target.value = 200
    await nextTick()
    pumpFrames(COUNT_UP_DURATION_MS / 2)
    expect(value.value).toBe(188) // 100 + 100*0.875

    pumpFrames(COUNT_UP_DURATION_MS)
    expect(value.value).toBe(200)
    stop()
  })

  it('偏好减少动效：直接跳终值（不排动画帧）', () => {
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

    const { value, stop } = setupCountUp(ref(12345))

    expect(value.value).toBe(12345)
    expect(frames).toHaveLength(0)
    stop()
  })

  it('零时长：视为禁用动画直接跳终值', () => {
    const { value, stop } = setupCountUp(ref(42), 0)

    expect(value.value).toBe(42)
    expect(frames).toHaveLength(0)
    stop()
  })

  it('环境无 requestAnimationFrame：降级直接终值', () => {
    vi.stubGlobal('requestAnimationFrame', undefined)

    const { value, stop } = setupCountUp(ref(77))

    expect(value.value).toBe(77)
    stop()
  })

  it('作用域销毁：取消未完成的动画帧，销毁后泵帧不再推进', () => {
    const { value, stop } = setupCountUp(ref(500))

    // 动画进行中销毁：记录中间值并确认 cancel 被调用
    pumpFrames(100)
    const midValue = value.value
    expect(midValue).toBeGreaterThan(0)
    expect(midValue).toBeLessThan(500)

    stop()
    expect(cancelledIds.length).toBeGreaterThan(0)

    // 销毁后泵满剩余时长：值停留在销毁瞬间的中间值（清理生效）
    pumpFrames(COUNT_UP_DURATION_MS)
    expect(value.value).toBe(midValue)
  })
})
