import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * 类名合并工具（shadcn-vue 约定）：clsx 条件组合 + tailwind-merge 去冲突
 *
 * @param inputs 条件类名列表（支持字符串/对象/数组）
 * @returns 合并后的最终类名字符串
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * ISO-8601 无时区时间串（如 2026-08-24T10:15:30）→ 本地时区「MM-DD HH:mm」短格式
 *
 * 适用场景：B 端数据密集表格/小表的紧凑时间展示（设计 §2.2 密度约束），
 * 不携带时区后缀由 new Date 按本地时区解析（全局规范 六.5）。
 *
 * @param iso 后端 LocalDateTime ISO 串（无时区，视为本地时间）
 * @returns 「08-24 10:15」形态短时间；非法输入返回占位「--」
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '--'
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/**
 * ISO-8601 时间串 → 相对时间（设计 §2.4.2 上传时间列：相对展示 + 绝对时间 tooltip）
 *
 * 档位：不足 1 分钟「刚刚」/ 1 小时内「N 分钟前」/ 24 小时内「N 小时前」/
 * 30 天内「N 天前」/ 超过 30 天回退 MM-DD HH:mm 短格式（越界时间无相对意义）。
 *
 * @param iso 后端 LocalDateTime ISO 串（无时区，视为本地时间）
 * @returns 相对时间文案；非法输入返回占位「--」
 */
export function formatRelativeTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '--'
  }
  const diff = Date.now() - date.getTime()
  const minute = 60_000
  const hour = 60 * minute
  const day = 24 * hour
  if (diff < minute) {
    return '刚刚'
  }
  if (diff < hour) {
    return `${Math.floor(diff / minute)} 分钟前`
  }
  if (diff < day) {
    return `${Math.floor(diff / hour)} 小时前`
  }
  if (diff < 30 * day) {
    return `${Math.floor(diff / day)} 天前`
  }
  return formatDateTime(iso)
}

/**
 * 文件大小字节数 → 人类可读（B/KB/MB，KB/MB 保留 1 位小数）
 *
 * 文档详情的 fileSize 为 Long 序列化字符串（铁律按 string 接收），
 * 此处兼容 number 入参以便测试与可能的历史调用。
 *
 * @param bytes 字节数（string 或 number）
 * @returns 如「512 B / 2.0 KB / 10.0 MB」；非法输入（NaN/负数）返回占位「--」
 */
export function formatFileSize(bytes: string | number): string {
  const n = Number(bytes)
  if (!Number.isFinite(n) || n < 0) {
    return '--'
  }
  if (n < 1024) {
    return `${n} B`
  }
  if (n < 1024 * 1024) {
    return `${(n / 1024).toFixed(1)} KB`
  }
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}
