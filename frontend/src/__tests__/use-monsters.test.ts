import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import {
  BLINK_HOLD_MS,
  BLINK_MIN_MS,
  GLANCE_WINDOW_MS,
  useMonsters,
} from '@/composables/use-monsters'

/**
 * 小怪物引擎 composable 测试（rAF 层：绑定 / 帧循环 / 眨眼 / 降级 / 生命周期）
 *
 * jsdom 无真实渲染几何与 rAF 节奏：stub requestAnimationFrame 捕获帧回调手动驱动，
 * stub getBoundingClientRect 注入可见矩形；纯计算分支（姿态/瞳孔公式）见 monsters.test。
 * 注意：vi.useFakeTimers 须限定 toFake=setTimeout/clearTimeout——默认会连 rAF 一起 fake，
 * 覆盖本文件的 rAF 桩导致帧回调丢失；jsdom 序列化 transform 会补 px 单位。
 * 覆盖：可见帧写 transform / 不可见跳过 / reduced-motion 与无 rAF 双降级 /
 * 随机眨眼时序 / typing→glancing 窗口（紫黑对视演出）/ peek 覆盖 glance / 卸载清理。
 */

/** 构造 DOMRect 快照（引擎只读 left/top/width/height） */
function makeRect(left: number, top: number, width: number, height: number): DOMRect {
  return {
    left,
    top,
    width,
    height,
    right: left + width,
    bottom: top + height,
    x: left,
    y: top,
  } as DOMRect
}

/** 四怪宿主模板（data-* 属性契约与 LoginView 模板一致的最小骨架） */
const HOST = defineComponent({
  setup() {
    return useMonsters()
  },
  template: `
    <div>
      <div ref="stageRef">
        <svg data-monster="purple" viewBox="0 0 200 300">
          <g data-lean><g data-face>
            <g data-pupil-track><circle data-pupil cx="72" cy="88" r="9" /></g>
          </g></g>
        </svg>
        <svg data-monster="black" viewBox="0 0 170 252">
          <g data-lean><g data-face>
            <g data-pupil-track><circle data-pupil cx="61" cy="84" r="8" /></g>
          </g></g>
        </svg>
        <svg data-monster="yellow" viewBox="0 0 180 240">
          <g data-lean><g data-face>
            <g data-pupil-track><circle data-pupil cx="66" cy="104" r="6.5" /></g>
          </g></g>
        </svg>
        <svg data-monster="orange" viewBox="0 0 260 200">
          <g data-lean><g data-face>
            <g data-pupil-track><circle data-pupil cx="96" cy="102" r="6" /></g>
          </g></g>
        </svg>
      </div>
    </div>
  `,
})

/** rAF 桩：捕获最新帧回调（帧尾会重新注册，回调滚动更新） */
let frameCb: FrameRequestCallback | null = null
const rafSpy = vi.fn<(cb: FrameRequestCallback) => number>((cb) => {
  frameCb = cb
  return 1
})
const cancelSpy = vi.fn<(handle: number) => void>()

/** 手动驱动 n 帧（几何就绪后调用） */
function runFrames(n: number) {
  for (let i = 0; i < n; i += 1) {
    frameCb?.(performance.now())
  }
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

/** 挂载宿主并把四怪几何 stub 为可见矩形（left100/top50/200x300） */
function mountVisible() {
  const wrapper = mount(HOST)
  for (const svg of wrapper.element.querySelectorAll('[data-monster]')) {
    vi.spyOn(svg as SVGSVGElement, 'getBoundingClientRect').mockReturnValue(
      makeRect(100, 50, 200, 300),
    )
  }
  return wrapper
}

/** 在 window 上派发 pointermove（MouseEvent 承载 clientX/clientY） */
function moveMouseTo(x: number, y: number) {
  window.dispatchEvent(new MouseEvent('pointermove', { clientX: x, clientY: y }))
}

beforeEach(() => {
  frameCb = null
  rafSpy.mockClear()
  cancelSpy.mockClear()
  vi.stubGlobal('requestAnimationFrame', rafSpy)
  vi.stubGlobal('cancelAnimationFrame', cancelSpy)
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('useMonsters：帧循环与降级', () => {
  it('挂载即启动 rAF 循环；鼠标移动后逐帧逼近写 lean/face/pupil transform', () => {
    stubMatchMedia(false)
    const wrapper = mountVisible()
    expect(rafSpy).toHaveBeenCalled()

    // 鼠标 (272,138)：紫怪瞳孔右移封顶 9、五官 ex→3.6、身体 skew→-0.6（手算见 monsters.test）
    moveMouseTo(272, 138)
    runFrames(120)
    const purple = wrapper.element.querySelector('[data-monster="purple"]')!
    expect(purple.querySelector('[data-lean]')!.style.transform).toContain('skewX(-0.60deg)')
    expect(purple.querySelector('[data-face]')!.style.transform).toContain('translate(3.6px,')
    expect(purple.querySelector('[data-pupil-track]')!.style.transform).toContain(
      'translate(9.00px',
    )
    wrapper.unmount()
  })

  it('怪物不可见（零尺寸矩形）：该帧不写任何 transform', () => {
    stubMatchMedia(false)
    const wrapper = mount(HOST)
    // jsdom 原生 getBoundingClientRect 即全零，无需 stub
    moveMouseTo(272, 138)
    runFrames(10)
    const purple = wrapper.element.querySelector('[data-monster="purple"]')!
    expect(purple.querySelector('[data-lean]')!.style.transform).toBe('')
    expect(purple.querySelector('[data-pupil-track]')!.style.transform).toBe('')
    wrapper.unmount()
  })

  it('偏好减少动效：不启动 rAF、不眨眼（纯静态降级）', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubMatchMedia(true)
    const wrapper = mountVisible()
    expect(rafSpy).not.toHaveBeenCalled()

    vi.advanceTimersByTime(BLINK_MIN_MS + BLINK_HOLD_MS + 1000)
    const purple = wrapper.element.querySelector('[data-monster="purple"]')!
    expect(purple.querySelector('[data-face]')!.classList.contains('blinking')).toBe(false)
    wrapper.unmount()
  })

  it('环境无 requestAnimationFrame（旧 jsdom）：挂载不崩溃且保持静态', () => {
    stubMatchMedia(false)
    vi.stubGlobal('requestAnimationFrame', undefined)
    const wrapper = mountVisible()
    expect(wrapper.element.querySelector('[data-monster="purple"]')).toBeTruthy()
    wrapper.unmount()
  })
})

describe('useMonsters：眨眼时序（仅紫/黑）', () => {
  it('随机间隔（mock 0 → 恰 3s）后闭眼 150ms 再睁开并重排期', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    stubMatchMedia(false)
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const wrapper = mountVisible()

    const purpleFace = wrapper.element.querySelector('[data-monster="purple"] [data-face]')!
    const yellowFace = wrapper.element.querySelector('[data-monster="yellow"] [data-face]')!

    vi.advanceTimersByTime(BLINK_MIN_MS - 1)
    expect(purpleFace.classList.contains('blinking')).toBe(false)

    vi.advanceTimersByTime(1)
    expect(purpleFace.classList.contains('blinking')).toBe(true)
    expect(yellowFace.classList.contains('blinking')).toBe(false) // 黄怪不参与眨眼

    vi.advanceTimersByTime(BLINK_HOLD_MS)
    expect(purpleFace.classList.contains('blinking')).toBe(false)

    // 重排期：再过 3s 又闭眼
    vi.advanceTimersByTime(BLINK_MIN_MS)
    expect(purpleFace.classList.contains('blinking')).toBe(true)
    wrapper.unmount()
  })
})

describe('useMonsters：typing / peek 状态联动', () => {
  it('typing + glancing：黑怪五官对视 (6,-20)；800ms 窗口结束后回归跟随目标', async () => {
    stubMatchMedia(false)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const wrapper = mountVisible()
    const vm = wrapper.vm as unknown as ReturnType<typeof useMonsters>

    // 聚焦用户名框 → typing + 0.8s 对视窗口（鼠标对准黑怪眼部中心：cy=50+300*0.33=149）
    vm.setTyping(true)
    moveMouseTo(200, 149)
    runFrames(120)
    const blackFace = wrapper.element.querySelector('[data-monster="black"] [data-face]')!
    expect(blackFace.style.transform).toBe('translate(6.0px, -20.0px)')

    // 窗口结束：目标回归 idle 跟随（鼠标居中 → ex/ey 收敛回 0；
    // ey 自 -20 向 0 逼近的 lerp 残差恒为负，toFixed(1) 呈确定性的 -0.0）
    vi.advanceTimersByTime(GLANCE_WINDOW_MS + 1)
    runFrames(150)
    expect(blackFace.style.transform).toBe('translate(0.0px, -0.0px)')
    wrapper.unmount()
  })

  it('peek 优先：眼睛切明文后紫怪立刻偷看姿态（skew-12/tx26），glancing 被取消', async () => {
    stubMatchMedia(false)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const wrapper = mountVisible()
    const vm = wrapper.vm as unknown as ReturnType<typeof useMonsters>

    vm.setTyping(true)
    vm.setPeeking(true)
    moveMouseTo(9999, 9999) // 极端鼠标位也会被 peek 目标覆盖
    runFrames(150)
    const purpleLean = wrapper.element.querySelector('[data-monster="purple"] [data-lean]')!
    const purpleFace = wrapper.element.querySelector('[data-monster="purple"] [data-face]')!
    expect(purpleLean.style.transform).toBe('skewX(-12.00deg) translateX(26.0px)')
    expect(purpleFace.style.transform).toBe('translate(0.0px, 0.0px)')

    // glancing 窗口已被取消：越过 800ms 也不产生对视目标（仍保持 peek 姿态）
    vi.advanceTimersByTime(GLANCE_WINDOW_MS + 500)
    runFrames(50)
    expect(purpleLean.style.transform).toBe('skewX(-12.00deg) translateX(26.0px)')

    // 隐藏密码 + 用户名框失焦 → 复原（鼠标移回紫怪身体中心 → 近似立正）
    vm.setPeeking(false)
    vm.setTyping(false)
    moveMouseTo(200, 137)
    runFrames(200)
    expect(purpleLean.style.transform).toBe('skewX(-0.00deg) translateX(0.0px)')
    wrapper.unmount()
  })
})

describe('useMonsters：生命周期清理', () => {
  it('卸载：取消 rAF、移除 pointermove 监听、清空眨眼定时器', () => {
    stubMatchMedia(false)
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const removeSpy = vi.spyOn(window, 'removeEventListener')
    const wrapper = mountVisible()

    wrapper.unmount()
    expect(cancelSpy).toHaveBeenCalledWith(1)
    expect(removeSpy).toHaveBeenCalledWith('pointermove', expect.any(Function))

    // 卸载后推进时间：眨眼不再触发（定时器已清）
    const purpleFace = wrapper.element.querySelector('[data-monster="purple"] [data-face]')!
    vi.advanceTimersByTime(BLINK_MIN_MS * 3)
    expect(purpleFace.classList.contains('blinking')).toBe(false)
  })
})
