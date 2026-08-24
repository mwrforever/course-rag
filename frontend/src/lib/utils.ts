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
