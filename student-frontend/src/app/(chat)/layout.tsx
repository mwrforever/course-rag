import { ChatSidebar } from "@/components/chat/chat-sidebar";
import { ChatStreamingProvider } from "@/components/chat/chat-streaming-context";
import { QueryProvider } from "@/lib/query-provider";

/**
 * 课程助手对话路由组壳（UI 重构 2026-08-25：kimi 式全局左侧栏）
 *
 * 仅包裹 /chat 与 /chat/[sessionId]：左侧栏（品牌/新建对话/会话历史/用户区）+ 右侧满高工作区，
 * 与 (main) 路由组（顶部导航首页/课堂/会话/个人中心）互斥——kimi 式布局只用于课程助手（用户拍板）。
 * QueryProvider 随 (main) 组剥离开后在此补挂，保证对话页服务端状态缓存可用；
 * ChatStreamingProvider 把工作区流式状态广播给侧栏做 Ctrl+K 守卫（流式中跳转会丢流视图）。
 * 本布局保持服务端组件：三个子壳均为客户端组件，无需整树 'use client'。
 */
export default function ChatLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <QueryProvider>
      <ChatStreamingProvider>
        <div className="flex h-dvh overflow-hidden">
          <ChatSidebar />
          <div className="flex min-w-0 flex-1 flex-col">{children}</div>
        </div>
      </ChatStreamingProvider>
    </QueryProvider>
  );
}
