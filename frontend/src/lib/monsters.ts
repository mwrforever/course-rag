/**
 * 登录页小怪物引擎 · 纯计算模块（设计稿 code(14).html B5 状态机 1:1 移植）
 *
 * 职责：只做姿态（身体倾斜/位移）、五官偏移、瞳孔目标值的**纯计算**与 lerp 逼近，
 * 不碰 DOM、不定时器、无响应式状态——由 composables/use-monsters.ts 绑定真实
 * DOM 后在 rAF 帧内逐帧调用；所有函数均为无副作用纯函数，可直接单测。
 *
 * 状态机语义（设计稿原注释）：
 * - idle：身体朝鼠标方向微倾（≤6°），五官与瞳孔整体跟随鼠标
 * - typing：用户名输入框聚焦 → 紫怪向表单探头（tx 40 / skew 基础上 -12）；
 *   黑怪在聚焦后的 glancing 窗口（0.8s）内转身与紫怪对视
 * - peek：密码明文展示 → 四怪齐刷刷右倾偷看（各配 skew/tx），瞳孔锁定右下
 * - peek 优先级最高：偷看时全员覆盖为偷看姿态；隐藏密码即复原
 */

/** 怪物身份（对应设计稿四只站位：紫高个/黑中个/黄圆顶/橙拱形） */
export type MonsterId = 'purple' | 'black' | 'yellow' | 'orange'

/** 单只怪物的静态配置（数值逐项取自设计稿 MONSTERS 表） */
export interface MonsterConfig {
  /** 身份标识，同时是 DOM 挂载点 data-monster 属性值 */
  id: MonsterId
  /** SVG viewBox 宽度：用于把屏幕像素换算回 SVG 用户单位（姿态数值均在用户单位系） */
  vbW: number
  /** 眼部纵向锚点（0~1，占怪物高度比例）：五官跟随计算的参考中心 */
  refY: number
  /** 瞳孔最大位移（SVG 用户单位） */
  pMax: number
  /** peek 态身体倾斜角（度，负值 = 向右倾） */
  peekSkew: number
  /** peek 态身体水平位移（SVG 用户单位） */
  peekTx: number
  /** 是否参与随机眨眼（仅紫/黑为白眼球怪，眨眼才可见） */
  blink: boolean
}

/** 四怪物配置（顺序即舞台渲染顺序约定，与设计稿一致） */
export const MONSTER_CONFIGS: readonly MonsterConfig[] = [
  { id: 'purple', vbW: 200, refY: 0.29, pMax: 9, peekSkew: -12, peekTx: 26, blink: true },
  { id: 'black', vbW: 170, refY: 0.33, pMax: 8, peekSkew: -9, peekTx: 12, blink: true },
  { id: 'yellow', vbW: 180, refY: 0.43, pMax: 4.5, peekSkew: -8, peekTx: 10, blink: false },
  { id: 'orange', vbW: 260, refY: 0.51, pMax: 4, peekSkew: -6.5, peekTx: 6, blink: false },
]

/** 引擎交互信号（由 use-monsters 维护，每帧快照传入） */
export interface MonsterSignals {
  /** 鼠标视口 X（px），初始为视口右侧偏上（设计稿 innerWidth*.7） */
  mouseX: number
  /** 鼠标视口 Y（px） */
  mouseY: number
  /** 用户名输入框聚焦/键入中（typing 态） */
  typing: boolean
  /** 密码明文展示中（peek 态，优先级最高） */
  peeking: boolean
  /** 聚焦瞬间的对视窗口内（紫黑互看，随 typing 触发 0.8s 后自动失效） */
  glancing: boolean
}

/** 怪物在视口中的几何信息（getBoundingClientRect 快照） */
export interface MonsterGeometry {
  /** 视口左边界（px） */
  left: number
  /** 视口上边界（px） */
  top: number
  /** 渲染宽度（px），< 2 视为不可见（移动端隐藏/jsdom 零尺寸） */
  width: number
  /** 渲染高度（px） */
  height: number
}

/** 身体姿态目标/当前值（skew/tx 为身体，ex/ey 为五官容器偏移） */
export interface MonsterPose {
  /** 身体 skewX 角度（度） */
  skew: number
  /** 身体水平位移（SVG 用户单位） */
  tx: number
  /** 五官容器 X 偏移（SVG 用户单位） */
  ex: number
  /** 五官容器 Y 偏移（SVG 用户单位） */
  ey: number
}

/** 瞳孔偏移（SVG 用户单位） */
export interface PupilOffset {
  /** 瞳孔 X 位移 */
  x: number
  /** 瞳孔 Y 位移 */
  y: number
}

/** 瞳孔锚点（瞳孔圆心的 cx/cy 属性，SVG 用户单位） */
export interface PupilAnchor {
  /** 瞳孔圆心 X */
  cx: number
  /** 瞳孔圆心 Y */
  cy: number
}

/** 姿态 lerp 系数：skew/tx 通道（设计稿 k=.11） */
export const POSE_LERP_K = 0.11
/** 姿态 lerp 系数：五官通道（设计稿 k=.14） */
export const FACE_LERP_K = 0.14
/** 瞳孔 lerp 系数（设计稿 k=.2） */
export const PUPIL_LERP_K = 0.2

/**
 * 数值夹取
 *
 * @param v 待夹取值
 * @param lo 下界（含）
 * @param hi 上界（含）
 * @returns 落在 [lo, hi] 内的值；lo > hi 时行为同 Math.max/Math.min 叠加（不做交换保证）
 */
export function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v))
}

/**
 * 线性插值：从 a 向 b 以比例 k 逼近（帧间平滑，k∈(0,1]）
 *
 * @param a 当前值
 * @param b 目标值
 * @param k 逼近比例（每帧固定，视觉上呈指数收敛）
 */
export function lerp(a: number, b: number, k: number): number {
  return a + (b - a) * k
}

/**
 * 计算单只怪物的姿态目标值（B5 状态机核心：idle/typing/peek/glance 四分支）
 *
 * @param cfg 怪物静态配置
 * @param geo 当前帧几何快照（width < 2 时直接返回零姿态，避免除零）
 * @param signals 交互信号快照
 * @returns 姿态目标（skew/tx 身体 + ex/ey 五官），供 approachPose 逐帧逼近
 */
export function computePose(
  cfg: MonsterConfig,
  geo: MonsterGeometry,
  signals: MonsterSignals,
): MonsterPose {
  // 不可见（移动端隐藏/jsdom 零尺寸）：零姿态，引擎侧也不会写 transform
  if (geo.width < 2) {
    return { skew: 0, tx: 0, ex: 0, ey: 0 }
  }
  const scale = geo.width / cfg.vbW
  const cx = geo.left + geo.width / 2
  const cy = geo.top + geo.height * cfg.refY

  // ---- idle 基线：身体朝鼠标微倾（≤6°），五官随鼠标轻移（各通道独立限幅后除回用户单位） ----
  let skew = clamp(-(signals.mouseX - cx) / 120, -6, 6)
  let tx = 0
  let ex = clamp((signals.mouseX - cx) / 20, -15, 15) / scale
  let ey = clamp((signals.mouseY - cy) / 30, -10, 10) / scale

  if (signals.peeking) {
    // ---- peek：密码明文，全员右倾偷看，五官回中（瞳孔另行锁定） ----
    skew = cfg.peekSkew
    tx = cfg.peekTx
    ex = 0
    ey = 0
  } else if (signals.typing) {
    // ---- typing：紫怪探头，黑怪 glancing 窗口内转身对视 ----
    if (cfg.id === 'purple') {
      skew = clamp(skew - 12, -18, 6)
      tx = 40
    } else if (cfg.id === 'black') {
      skew = clamp(skew * 1.5, -9, 9)
      if (signals.glancing) {
        skew += 10
        tx = 20
        ex = 6
        ey = -20
      }
    }
  }

  // 紫怪在 glancing 窗口内瞟向黑怪（peek 时不参与）
  if (signals.glancing && cfg.id === 'purple' && !signals.peeking) {
    ex = 10
    ey = 25
  }

  return { skew, tx, ex, ey }
}

/**
 * 计算单只瞳孔的目标偏移
 *
 * @param cfg 怪物静态配置
 * @param pupil 瞳孔锚点（cx/cy，SVG 用户单位）
 * @param faceEx 当前五官容器 X 偏移（瞳孔出发点随五官整体平移）
 * @param faceEy 当前五官容器 Y 偏移
 * @param geo 当前帧几何快照（width < 2 返回零偏移）
 * @param signals 交互信号快照
 * @returns 瞳孔偏移目标：peek 锁定右下 / glance 对视 / idle 沿 atan2 方向且距离封顶 pMax
 */
export function computePupilOffset(
  cfg: MonsterConfig,
  pupil: PupilAnchor,
  faceEx: number,
  faceEy: number,
  geo: MonsterGeometry,
  signals: MonsterSignals,
): PupilOffset {
  if (geo.width < 2) {
    return { x: 0, y: 0 }
  }
  const scale = geo.width / cfg.vbW

  // peek：瞳孔锁定右下（看向密码框方向）
  if (signals.peeking) {
    return { x: cfg.pMax, y: cfg.pMax * 0.25 }
  }
  // glance：紫黑对视（互为对方的锚点方向）
  if (signals.glancing && cfg.id === 'purple') {
    return { x: 3, y: 4 }
  }
  if (signals.glancing && cfg.id === 'black') {
    return { x: 0, y: -3.5 }
  }

  // idle：瞳孔从「五官平移后的实际位置」朝鼠标方向偏移，距离封顶 pMax（换算回用户单位）
  const bx = geo.left + (pupil.cx + faceEx) * scale
  const by = geo.top + (pupil.cy + faceEy) * scale
  const dx = signals.mouseX - bx
  const dy = signals.mouseY - by
  const d = Math.min(Math.hypot(dx, dy), cfg.pMax * scale) || 0
  const a = Math.atan2(dy, dx)
  return { x: (Math.cos(a) * d) / scale, y: (Math.sin(a) * d) / scale }
}

/**
 * 姿态逐帧逼近（skew/tx 用 POSE_LERP_K，五官用 FACE_LERP_K，与设计稿一致）
 *
 * @param current 当前姿态
 * @param target 目标姿态
 * @returns 新姿态（不修改入参）
 */
export function approachPose(current: MonsterPose, target: MonsterPose): MonsterPose {
  return {
    skew: lerp(current.skew, target.skew, POSE_LERP_K),
    tx: lerp(current.tx, target.tx, POSE_LERP_K),
    ex: lerp(current.ex, target.ex, FACE_LERP_K),
    ey: lerp(current.ey, target.ey, FACE_LERP_K),
  }
}

/**
 * 瞳孔逐帧逼近（PUPIL_LERP_K，与设计稿一致）
 *
 * @param current 当前偏移
 * @param target 目标偏移
 * @returns 新偏移（不修改入参）
 */
export function approachPupil(current: PupilOffset, target: PupilOffset): PupilOffset {
  return {
    x: lerp(current.x, target.x, PUPIL_LERP_K),
    y: lerp(current.y, target.y, PUPIL_LERP_K),
  }
}
