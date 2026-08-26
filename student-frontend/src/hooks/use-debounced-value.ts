"use client";

/**
 * 防抖值 Hook —— 输入高频变更（搜索框每键）时延迟回传最终值
 *
 * 用途：消除搜索输入每次按键触发同步 URL/查询的抖动（courses 页关键词筛选）。
 * 注意返回的是延迟后的值；筛选计算仍用即时值，仅把"写 URL/发起请求"收敛到防抖后。
 */
import { useEffect, useState } from "react";

/**
 * @param value   原始值（每次渲染的新值）
 * @param delayMs 防抖窗口（毫秒，静默期结束后回传）
 * @returns 防抖后的值（窗口内的中间变更被丢弃）
 */
export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);

  return debounced;
}
