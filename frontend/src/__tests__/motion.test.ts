import { afterEach, describe, expect, it, vi } from 'vitest'

import { prefersReducedMotion } from '@/lib/motion'

/**
 * 动效偏好探测测试（v-reveal / useCountUp / DataTable 共用的降级判定）
 *
 * 覆盖：正常命中 / 未命中 / matchMedia 缺失降级。
 */
describe('prefersReducedMotion 动效偏好探测', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('命中 prefers-reduced-motion: reduce 时返回 true', () => {
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

    expect(prefersReducedMotion()).toBe(true)
  })

  it('未命中偏好（常规用户）返回 false', () => {
    vi.stubGlobal('matchMedia', (query: string) => ({
      matches: false,
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

    expect(prefersReducedMotion()).toBe(false)
  })

  it('环境无 matchMedia（极端降级）：返回 false（保守放行，由 CSS 层总开关兜底）', () => {
    vi.stubGlobal('matchMedia', undefined)

    expect(prefersReducedMotion()).toBe(false)
  })
})
