import { describe, expect, it, vi } from 'vitest'

import { formatDateTime, formatFileSize, formatRelativeTime } from '@/lib/utils'

/**
 * 工具函数测试（Task 18：相对时间 + 文件大小）
 *
 * 覆盖契约（设计 §2.4.2 上传时间「相对 + 绝对 tooltip」/ 详情页文件大小）：
 * 1. formatRelativeTime 五个档位：刚刚 / N 分钟前 / N 小时前 / N 天前 / 超过 30 天回退短格式，非法输入 --
 * 2. formatFileSize：B / KB / MB 档位（Long string 或 number），非法输入 --
 */

/** 冻结系统时间：2026-08-24 12:00:00（与相对时间断言对齐） */
const NOW = new Date('2026-08-24T12:00:00')

describe('formatRelativeTime：相对时间档位（设计 §2.4.2）', () => {
  it('不足 1 分钟 → 刚刚', () => {
    vi.setSystemTime(NOW)
    expect(formatRelativeTime('2026-08-24T11:59:30')).toBe('刚刚')
  })

  it('1 小时内 → N 分钟前', () => {
    vi.setSystemTime(NOW)
    expect(formatRelativeTime('2026-08-24T11:55:00')).toBe('5 分钟前')
  })

  it('24 小时内 → N 小时前', () => {
    vi.setSystemTime(NOW)
    expect(formatRelativeTime('2026-08-24T09:00:00')).toBe('3 小时前')
  })

  it('30 天内 → N 天前', () => {
    vi.setSystemTime(NOW)
    expect(formatRelativeTime('2026-08-24T00:00:00')).toBe('12 小时前')
    expect(formatRelativeTime('2026-08-20T12:00:00')).toBe('4 天前')
  })

  it('超过 30 天回退 MM-DD HH:mm 短格式；非法输入 → --', () => {
    vi.setSystemTime(NOW)
    expect(formatRelativeTime('2026-07-01T08:00:00')).toBe('07-01 08:00')
    expect(formatRelativeTime('not-a-date')).toBe('--')
  })
})

describe('formatFileSize：文件大小可读化（B/KB/MB）', () => {
  it('B / KB / MB 三档位（Long string 与 number 均支持）', () => {
    expect(formatFileSize('512')).toBe('512 B')
    expect(formatFileSize(512)).toBe('512 B')
    expect(formatFileSize('2048')).toBe('2.0 KB')
    expect(formatFileSize('10485760')).toBe('10.0 MB')
  })

  it('非法输入（NaN/负数）→ --', () => {
    expect(formatFileSize('abc')).toBe('--')
    expect(formatFileSize('-1')).toBe('--')
  })
})

describe('formatDateTime：短格式（既有函数回归）', () => {
  it('ISO-8601 无时区串 → MM-DD HH:mm；非法输入 → --', () => {
    expect(formatDateTime('2026-08-24T10:15:30')).toBe('08-24 10:15')
    expect(formatDateTime('bad')).toBe('--')
  })
})
