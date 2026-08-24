"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";

/**
 * react-query Provider 挂载组件（(main) 布局统一挂载，设计 §1.8 服务端状态）
 *
 * 职责：提供 QueryClient 上下文（缓存/重试/失效统一配置）；
 * 组件内 useState 惰性初始化客户端，避免模块级单例在测试与热更新间共享缓存。
 * 默认策略：失败重试 1 次、数据 30s 内视为新鲜、窗口聚焦不自动刷新。
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

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
