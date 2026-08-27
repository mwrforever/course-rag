/**
 * 登录页小怪物驱动 composable（DOM 绑定 + rAF 帧循环 + 眨眼调度）
 *
 * 职责：把 lib/monsters.ts 的纯计算接到真实 DOM 上——
 * 1. 挂载后按 data-monster / data-lean / data-face / data-pupil-track / data-pupil
 *    属性收集四怪绑定（结构与 LoginView 模板约定，属性名即契约）；
 * 2. rAF 每帧：读取几何快照 → 纯函数算目标 → lerp 逼近 → 写 lean/face/pupil 的
 *    transform（全部 will-change:transform，不触发重排）；
 * 3. 状态入口 setTyping / setPeeking 由表单事件驱动（用户名聚焦/密码眼睛切换），
 *    glancing 0.8s 窗口在内部自动管理；
 * 4. 仅紫/黑怪随机眨眼（3~7s 间隔、150ms 闭合，加/移除 blinking 类由 CSS 完成形变）。
 *
 * 降级策略（无障碍底线）：
 * - prefers-reduced-motion 命中或环境无 requestAnimationFrame（旧 jsdom）→
 *   不启动帧循环/不挂 pointermove/不眨眼，怪物保持模板静态姿态；
 * - 怪物不可见（width < 2）该帧跳过写 transform，循环本身继续（等待重新可见）。
 *
 * 生命周期：onScopeDispose 取消 rAF、摘除监听、清理全部定时器，
 * 不存在卸载后回调写已脱管 DOM 的问题。
 *
 * 线程安全注意：单组件实例内使用，rAF 单队列串行回调，无跨实例共享可变状态。
 */
import { onMounted, onScopeDispose, ref } from 'vue'

import { prefersReducedMotion } from '@/lib/motion'
import {
  MONSTER_CONFIGS,
  approachPose,
  approachPupil,
  computePose,
  computePupilOffset,
} from '@/lib/monsters'

import type { MonsterConfig, MonsterGeometry, MonsterPose } from '@/lib/monsters'

/** 单只瞳孔的运行时绑定（锚点取自模板 cx/cy 属性，x/y 为当前 lerp 值） */
interface PupilBinding {
  /** 瞳孔位移容器（设计稿 .pupil-track，transform 写在该元素上） */
  el: SVGGraphicsElement
  /** 瞳孔圆心锚点 X（SVG 用户单位，来自 data-pupil 的 cx 属性） */
  cx: number
  /** 瞳孔圆心锚点 Y（SVG 用户单位） */
  cy: number
  /** 当前瞳孔 X 偏移（lerp 中间态） */
  x: number
  /** 当前瞳孔 Y 偏移（lerp 中间态） */
  y: number
}

/** 单只怪物的运行时绑定（DOM 引用 + 姿态 lerp 中间态） */
interface MonsterBinding {
  /** 怪物静态配置（含姿态参数） */
  cfg: MonsterConfig
  /** 怪物 SVG 根（getBoundingClientRect 的几何来源） */
  svg: SVGSVGElement
  /** 身体容器（skewX + translateX 写入点） */
  lean: SVGGraphicsElement
  /** 五官容器（translate 写入点 + blinking 类挂载点） */
  face: SVGGraphicsElement
  /** 两枚瞳孔绑定 */
  pupils: PupilBinding[]
  /** 当前姿态（lerp 中间态） */
  pose: MonsterPose
}

/** 聚焦瞬间紫黑对视窗口时长（毫秒，设计稿 B5 typing glance 0.8s） */
export const GLANCE_WINDOW_MS = 800
/** 随机眨眼最小间隔（毫秒） */
export const BLINK_MIN_MS = 3000
/** 随机眨眼间隔随机跨度（毫秒）：实际间隔 = BLINK_MIN_MS + random * BLINK_SPAN_MS */
export const BLINK_SPAN_MS = 4000
/** 眨眼闭合持续时长（毫秒） */
export const BLINK_HOLD_MS = 150

/**
 * 小怪物引擎入口（LoginView setup 调用）
 *
 * @returns stageRef 绑到怪物舞台容器（含四只 SVG 怪物）；
 *   setTyping 切 typing 态（用户名输入框 focus/blur）；
 *   setPeeking 切 peek 态（密码眼睛按钮，明文 true / 密文 false）
 */
export function useMonsters() {
  /** 怪物舞台容器（.monsters 一层，模板 ref 绑定） */
  const stageRef = ref<HTMLElement | null>(null)
  /** 交互信号（引擎内部可变，每帧作为只读快照传给纯函数） */
  const signals = { mouseX: 0, mouseY: 0, typing: false, peeking: false, glancing: false }
  /** 四怪绑定（onMounted 时收集，卸载后不再使用） */
  let bindings: MonsterBinding[] = []
  /** 帧循环句柄（0 = 空闲） */
  let rafId = 0
  /** glancing 窗口定时器 */
  let glanceTimer: ReturnType<typeof setTimeout> | null = null
  /** 眨眼定时器池（含外层间隔与内层闭合两档，统一清理） */
  const blinkTimers: ReturnType<typeof setTimeout>[] = []

  /**
   * 切换 typing 态（用户名输入框 focus/blur 驱动）
   *
   * @param on true = 聚焦（紫怪探头 + 触发 0.8s 紫黑对视窗口）；false = 失焦复原
   */
  function setTyping(on: boolean) {
    signals.typing = on
    if (!on || signals.peeking) {
      // 失焦或偷看优先：不开对视窗口
      return
    }
    // 聚焦瞬间：紫黑先对视一眼（0.8s 后自动失效；重复聚焦重置窗口）
    signals.glancing = true
    if (glanceTimer) {
      clearTimeout(glanceTimer)
    }
    glanceTimer = setTimeout(() => {
      signals.glancing = false
    }, GLANCE_WINDOW_MS)
  }

  /**
   * 切换 peek 态（密码眼睛按钮驱动）
   *
   * @param on true = 密码明文（四怪右倾偷看，瞳孔锁定）；false = 密文（全员复原）
   */
  function setPeeking(on: boolean) {
    signals.peeking = on
    if (!on) {
      return
    }
    // 偷看优先级最高：立即取消对视窗口
    signals.glancing = false
    if (glanceTimer) {
      clearTimeout(glanceTimer)
      glanceTimer = null
    }
  }

  /**
   * 收集四怪 DOM 绑定（data-* 属性契约与 LoginView 模板一一对应；
   * 单只怪物结构缺失时跳过该怪，不阻塞其余三只演出）
   *
   * @param root 怪物舞台容器
   */
  function bind(root: HTMLElement) {
    bindings = MONSTER_CONFIGS.flatMap((cfg) => {
      const svg = root.querySelector(`[data-monster="${cfg.id}"]`)
      const lean = svg?.querySelector('[data-lean]')
      const face = svg?.querySelector('[data-face]')
      if (!(svg instanceof SVGSVGElement) || !lean || !face) {
        return []
      }
      const pupils: PupilBinding[] = [...svg.querySelectorAll('[data-pupil-track]')].map(
        (track) => {
          const pupil = track.querySelector('[data-pupil]')
          return {
            el: track as SVGGraphicsElement,
            cx: Number(pupil?.getAttribute('cx') ?? 0),
            cy: Number(pupil?.getAttribute('cy') ?? 0),
            x: 0,
            y: 0,
          }
        },
      )
      return [
        {
          cfg,
          svg,
          lean: lean as SVGGraphicsElement,
          face: face as SVGGraphicsElement,
          pupils,
          pose: { skew: 0, tx: 0, ex: 0, ey: 0 },
        },
      ]
    })
  }

  /**
   * 帧循环：逐怪读几何 → 纯函数算目标 → lerp 逼近 → 写 transform
   * （数值取整写入，减少字符串抖动；不可见怪物跳过写样式）
   */
  function frame() {
    for (const m of bindings) {
      const rect = m.svg.getBoundingClientRect()
      if (rect.width < 2) {
        continue
      }
      const geo: MonsterGeometry = {
        left: rect.left,
        top: rect.top,
        width: rect.width,
        height: rect.height,
      }
      // 身体姿态：skewX 倾斜 + 水平位移（探头/偷看的主要演出通道）
      m.pose = approachPose(m.pose, computePose(m.cfg, geo, signals))
      m.lean.style.transform = `skewX(${m.pose.skew.toFixed(2)}deg) translateX(${m.pose.tx.toFixed(1)}px)`
      // 五官容器：随鼠标轻移 / 对视转头（瞳孔出发点随五官整体平移）
      m.face.style.transform = `translate(${m.pose.ex.toFixed(1)}px, ${m.pose.ey.toFixed(1)}px)`
      // 瞳孔：peek 锁定 / glance 对视 / idle 跟随鼠标
      for (const p of m.pupils) {
        const target = computePupilOffset(
          m.cfg,
          { cx: p.cx, cy: p.cy },
          m.pose.ex,
          m.pose.ey,
          geo,
          signals,
        )
        const next = approachPupil({ x: p.x, y: p.y }, target)
        p.x = next.x
        p.y = next.y
        p.el.style.transform = `translate(${p.x.toFixed(2)}px, ${p.y.toFixed(2)}px)`
      }
    }
    rafId = requestAnimationFrame(frame)
  }

  /** 视口指针移动 → 更新信号坐标（idle 跟随鼠标的数据源） */
  function onPointerMove(e: PointerEvent) {
    signals.mouseX = e.clientX
    signals.mouseY = e.clientY
  }

  /**
   * 随机眨眼调度（仅 blink 怪物）：间隔 3~7s 闭眼 150ms 后重排期
   *
   * @param m 怪物绑定（blinking 类挂在五官容器上，CSS scaleY 完成形变）
   */
  function scheduleBlink(m: MonsterBinding) {
    blinkTimers.push(
      setTimeout(
        () => {
          m.face.classList.add('blinking')
          blinkTimers.push(
            setTimeout(() => {
              m.face.classList.remove('blinking')
              scheduleBlink(m)
            }, BLINK_HOLD_MS),
          )
        },
        BLINK_MIN_MS + Math.random() * BLINK_SPAN_MS,
      ),
    )
  }

  onMounted(() => {
    // 初始鼠标取视口右侧偏上（与设计稿一致：怪物开局即有轻微朝向感）
    signals.mouseX = window.innerWidth * 0.7
    signals.mouseY = window.innerHeight * 0.4
    const root = stageRef.value
    if (!root) {
      return
    }
    bind(root)
    // 降级：偏好减少动效 / 无 rAF 环境 → 保持静态姿态，不启动任何循环
    if (prefersReducedMotion() || typeof requestAnimationFrame !== 'function') {
      return
    }
    window.addEventListener('pointermove', onPointerMove)
    rafId = requestAnimationFrame(frame)
    for (const m of bindings) {
      if (m.cfg.blink) {
        scheduleBlink(m)
      }
    }
  })

  onScopeDispose(() => {
    window.removeEventListener('pointermove', onPointerMove)
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    if (glanceTimer) {
      clearTimeout(glanceTimer)
      glanceTimer = null
    }
    for (const t of blinkTimers) {
      clearTimeout(t)
    }
    blinkTimers.length = 0
    bindings = []
  })

  return { stageRef, setTyping, setPeeking }
}
