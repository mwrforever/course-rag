import { describe, expect, it } from 'vitest'

import {
  MONSTER_CONFIGS,
  PUPIL_LERP_K,
  approachPose,
  approachPupil,
  clamp,
  computePose,
  computePupilOffset,
  lerp,
} from '@/lib/monsters'

import type { MonsterGeometry, MonsterSignals } from '@/lib/monsters'

/**
 * 小怪物引擎纯函数测试（设计稿 code(14).html B5 状态机逐分支覆盖）
 *
 * 覆盖：clamp/lerp 基元；computePose 四态（idle 限幅 / typing 探头与对视 /
 * peek 全员右倾 / 不可见零姿态）；computePupilOffset 四态（跟随方向与距离封顶 /
 * peek 锁定 / 紫黑对视 / 不可见零偏移）；approach 帧间逼近系数。
 * 全部为纯函数：无 DOM、无定时器，几何用矩形快照注入。
 */

/** 紫怪配置（MONSTER_CONFIGS 首项，参数取设计稿原值） */
const PURPLE = MONSTER_CONFIGS[0]!
const BLACK = MONSTER_CONFIGS.find((m) => m.id === 'black')!
const YELLOW = MONSTER_CONFIGS.find((m) => m.id === 'yellow')!

/** 视口几何快照：left=100/top=50/width=200/height=300（紫怪 scale=1，便于手算期望） */
const GEO: MonsterGeometry = { left: 100, top: 50, width: 200, height: 300 }

/** 全False 信号（idle 基线） */
const IDLE: MonsterSignals = {
  mouseX: 0,
  mouseY: 0,
  typing: false,
  peeking: false,
  glancing: false,
}

/** 构造信号（覆盖 IDLE 基线） */
function signals(over: Partial<MonsterSignals>): MonsterSignals {
  return { ...IDLE, ...over }
}

describe('clamp / lerp 基元', () => {
  it('clamp：区间内原样返回，越界夹取到边界', () => {
    expect(clamp(5, 0, 10)).toBe(5)
    expect(clamp(-3, 0, 10)).toBe(0)
    expect(clamp(99, 0, 10)).toBe(10)
  })

  it('lerp：按比例从当前值向目标逼近', () => {
    expect(lerp(0, 10, 0.5)).toBe(5)
    expect(lerp(10, 0, 0.11)).toBeCloseTo(8.9, 10)
    expect(lerp(2, 2, 0.3)).toBe(2)
  })
})

describe('computePose 姿态目标', () => {
  it('idle：鼠标在右侧 → 身体右倾（负 skew），五官右移；数值符合公式', () => {
    // cx=200, cy=50+300*0.29=137；鼠标 (320,137)：skew=-(120)/120=-1，ex=120/20=6，ey=0
    const pose = computePose(PURPLE, GEO, signals({ mouseX: 320, mouseY: 137 }))
    expect(pose.skew).toBeCloseTo(-1, 10)
    expect(pose.tx).toBe(0)
    expect(pose.ex).toBeCloseTo(6, 10)
    expect(pose.ey).toBeCloseTo(0, 10)
  })

  it('idle：鼠标远距 → 各通道限幅（skew ±6 / ex ±15 / ey ±10，scale=1）', () => {
    const right = computePose(PURPLE, GEO, signals({ mouseX: 9999, mouseY: 9999 }))
    expect(right.skew).toBe(-6)
    expect(right.ex).toBe(15)
    expect(right.ey).toBe(10)

    const left = computePose(PURPLE, GEO, signals({ mouseX: -9999, mouseY: -9999 }))
    expect(left.skew).toBe(6)
    expect(left.ex).toBe(-15)
    expect(left.ey).toBe(-10)
  })

  it('idle：scale≠1 时五官偏移换算回 SVG 用户单位（黑怪 vbW=170）', () => {
    // 黑怪 geo 宽 340 → scale=2；cx=100+170=270；鼠标 (510,150)：ex=clamp(240/20,±15)/2=12/2
    const geo: MonsterGeometry = { left: 100, top: 50, width: 340, height: 400 }
    const pose = computePose(BLACK, geo, signals({ mouseX: 510, mouseY: 150 }))
    expect(pose.ex).toBeCloseTo(6, 10)
  })

  it('typing：紫怪探头（skew 基础上 -12 且夹取 [-18,6]，tx=40）', () => {
    // 鼠标正中（cx=200）→ 基线 skew=0 → 探头 skew=clamp(-12,-18,6)=-12
    const centered = computePose(PURPLE, GEO, signals({ mouseX: 200, mouseY: 137, typing: true }))
    expect(centered.skew).toBe(-12)
    expect(centered.tx).toBe(40)

    // 鼠标极右 → 基线 skew=-6 → clamp(-18,-18,6)=-18（下限兜住）
    const farRight = computePose(PURPLE, GEO, signals({ mouseX: 9999, mouseY: 137, typing: true }))
    expect(farRight.skew).toBe(-18)
  })

  it('typing：黑怪 skew 放大 1.5 倍并夹取；glancing 窗口内转身对视（+10/tx20/五官上移）', () => {
    // 黑怪专用几何（宽 170 = vbW，scale=1）；鼠标极左 → 基线 skew=+6 → *1.5=9（恰在上限）
    const geo: MonsterGeometry = { left: 100, top: 50, width: 170, height: 252 }
    const onlyTyping = computePose(
      BLACK,
      geo,
      signals({ mouseX: -9999, mouseY: 133, typing: true }),
    )
    expect(onlyTyping.skew).toBe(9)
    expect(onlyTyping.tx).toBe(0)

    // glancing：skew 再 +10、tx=20、ex=6、ey=-20（转头看紫怪）
    const glancing = computePose(
      BLACK,
      geo,
      signals({ mouseX: -9999, mouseY: 133, typing: true, glancing: true }),
    )
    expect(glancing.skew).toBe(19)
    expect(glancing.tx).toBe(20)
    expect(glancing.ex).toBe(6)
    expect(glancing.ey).toBe(-20)
  })

  it('typing：黄/橙怪不参与探头（与 idle 同值）', () => {
    const idle = computePose(YELLOW, GEO, signals({ mouseX: 320, mouseY: 137 }))
    const typing = computePose(YELLOW, GEO, signals({ mouseX: 320, mouseY: 137, typing: true }))
    expect(typing).toEqual(idle)
  })

  it('glancing：紫怪瞟向黑怪（ex=10 / ey=25），peeking 时不参与', () => {
    const glance = computePose(
      PURPLE,
      GEO,
      signals({ mouseX: 320, mouseY: 137, typing: true, glancing: true }),
    )
    expect(glance.ex).toBe(10)
    expect(glance.ey).toBe(25)

    const peek = computePose(
      PURPLE,
      GEO,
      signals({ mouseX: 320, mouseY: 137, typing: true, glancing: true, peeking: true }),
    )
    expect(peek.ex).toBe(0)
    expect(peek.ey).toBe(0)
  })

  it('peek：四怪按各自配置右倾（紫 -12/26、黑 -9/12、黄 -8/10、橙 -6.5/6）', () => {
    for (const cfg of MONSTER_CONFIGS) {
      const pose = computePose(cfg, GEO, signals({ mouseX: 320, mouseY: 137, peeking: true }))
      expect(pose.skew).toBe(cfg.peekSkew)
      expect(pose.tx).toBe(cfg.peekTx)
      expect(pose.ex).toBe(0)
      expect(pose.ey).toBe(0)
    }
    // 抽查具体配置值防回归（数值来自设计稿 MONSTERS 表）
    expect(MONSTER_CONFIGS.map((c) => [c.peekSkew, c.peekTx])).toEqual([
      [-12, 26],
      [-9, 12],
      [-8, 10],
      [-6.5, 6],
    ])
  })

  it('不可见（width<2）：零姿态，避免除零', () => {
    const pose = computePose(
      PURPLE,
      { left: 0, top: 0, width: 0, height: 0 },
      signals({ mouseX: 320, mouseY: 137, peeking: true }),
    )
    expect(pose).toEqual({ skew: 0, tx: 0, ex: 0, ey: 0 })
  })
})

describe('computePupilOffset 瞳孔目标', () => {
  it('idle：瞳孔朝鼠标方向偏移（右侧 → x 正、y 0）', () => {
    // 瞳孔锚点 (72,88)，faceEx/Ey=0 → 瞳孔屏幕位 (172,138)；鼠标 (272,138) 正右方
    const offset = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 272, mouseY: 138 }),
    )
    expect(offset.x).toBeCloseTo(9, 10)
    expect(offset.y).toBeCloseTo(0, 10)
  })

  it('idle：距离封顶 pMax（鼠标极远 → 位移幅度恰为 pMax）', () => {
    const offset = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 99999, mouseY: 0 }),
    )
    expect(Math.hypot(offset.x, offset.y)).toBeCloseTo(9, 10)
  })

  it('idle：鼠标在上方 → y 为负；45° 方向分量正确', () => {
    // 瞳孔屏幕位 (172,138)；鼠标 (208,102)：dx=36, dy=-36 → 45° 向上偏右，距离封顶 9
    const offset = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 208, mouseY: 102 }),
    )
    expect(offset.x).toBeCloseTo(9 / Math.SQRT2, 10)
    expect(offset.y).toBeCloseTo(-9 / Math.SQRT2, 10)
  })

  it('idle：瞳孔出发点随五官偏移平移（faceEx 计入基准位）', () => {
    // faceEx=6 → 瞳孔屏幕位 x=172+6=178；鼠标 (272,138)：dx=94 仍向右
    const offset = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      6,
      0,
      GEO,
      signals({ mouseX: 272, mouseY: 138 }),
    )
    expect(offset.x).toBeGreaterThan(0)
    // 鼠标落在出发点左侧时方向翻转（鼠标 x=100 < 178）
    const flipped = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      6,
      0,
      GEO,
      signals({ mouseX: 100, mouseY: 138 }),
    )
    expect(flipped.x).toBeLessThan(0)
  })

  it('peek：瞳孔锁定右下（pMax, pMax*0.25），黄怪用自身 pMax', () => {
    const purplePupil = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 0, mouseY: 0, peeking: true }),
    )
    expect(purplePupil).toEqual({ x: 9, y: 2.25 })

    const yellowPupil = computePupilOffset(
      YELLOW,
      { cx: 66, cy: 104 },
      0,
      0,
      GEO,
      signals({ mouseX: 0, mouseY: 0, peeking: true }),
    )
    expect(yellowPupil).toEqual({ x: 4.5, y: 1.125 })
  })

  it('glancing：紫看右下 (3,4)、黑看上方 (0,-3.5)；peeking 优先于对视', () => {
    const glancePurple = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 0, mouseY: 0, glancing: true }),
    )
    expect(glancePurple).toEqual({ x: 3, y: 4 })

    const glanceBlack = computePupilOffset(
      BLACK,
      { cx: 61, cy: 84 },
      0,
      0,
      GEO,
      signals({ mouseX: 0, mouseY: 0, glancing: true }),
    )
    expect(glanceBlack).toEqual({ x: 0, y: -3.5 })

    const peekWins = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      GEO,
      signals({ mouseX: 0, mouseY: 0, glancing: true, peeking: true }),
    )
    expect(peekWins).toEqual({ x: 9, y: 2.25 })
  })

  it('不可见（width<2）：零偏移', () => {
    const offset = computePupilOffset(
      PURPLE,
      { cx: 72, cy: 88 },
      0,
      0,
      { left: 0, top: 0, width: 0, height: 0 },
      signals({ mouseX: 272, mouseY: 138 }),
    )
    expect(offset).toEqual({ x: 0, y: 0 })
  })
})

describe('approach 帧间逼近', () => {
  it('approachPose：skew/tx 用 k=.11，五官用 k=.14（与设计稿一致）', () => {
    const next = approachPose({ skew: 0, tx: 0, ex: 0, ey: 0 }, { skew: 6, tx: 40, ex: 15, ey: 10 })
    expect(next.skew).toBeCloseTo(6 * 0.11, 10)
    expect(next.tx).toBeCloseTo(40 * 0.11, 10)
    expect(next.ex).toBeCloseTo(15 * 0.14, 10)
    expect(next.ey).toBeCloseTo(10 * 0.14, 10)
  })

  it('approachPupil：k=.2；入参不被修改', () => {
    const cur = { x: 0, y: 0 }
    const next = approachPupil(cur, { x: 9, y: 2.25 })
    expect(PUPIL_LERP_K).toBe(0.2)
    expect(next).toEqual({ x: 1.8, y: 0.45 })
    expect(cur).toEqual({ x: 0, y: 0 })
  })
})

describe('MONSTER_CONFIGS 配置表', () => {
  it('四怪齐备，仅紫/黑参与眨眼（白眼球怪才可见）', () => {
    expect(MONSTER_CONFIGS.map((c) => c.id)).toEqual(['purple', 'black', 'yellow', 'orange'])
    expect(MONSTER_CONFIGS.filter((c) => c.blink).map((c) => c.id)).toEqual(['purple', 'black'])
  })
})
