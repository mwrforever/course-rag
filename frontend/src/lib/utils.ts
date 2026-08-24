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
