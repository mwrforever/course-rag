"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { subscribeAuthCacheReset } from "./auth-cache-events";

/**
 * react-query Provider 挂载组件（(main)/(chat) 布局统一挂载，设计 §1.8 服务端状态）
 *
 * 职责：提供 QueryClient 上下文（缓存/重试/失效统一配置）；
 * 组件内 useState 惰性初始化客户端，避免模块级单例在测试与热更新间共享缓存。
 * 默认策略：失败重试 1 次、数据 30s 内视为新鲜、窗口聚焦不自动刷新。
 * 账号切换清理（BUG-06）：订阅 auth-context 的账号切换事件（401 全局登出 / 登录成功），
 * 到达即 queryClient.clear()——与三处显式登出对齐，防换登后新账号读到旧账号缓存。
 */
export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: 1,
            staleTime: 30_000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  // 订阅账号切换事件清空缓存；卸载退订防悬空回调泄漏（无监听广播为空操作）
  useEffect(() => {
    return subscribeAuthCacheReset(() => client.clear());
  }, [client]);

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
