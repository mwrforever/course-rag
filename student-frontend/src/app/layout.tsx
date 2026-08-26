import type { Metadata } from "next";
import { LoginDialog } from "@/components/auth/login-dialog";
import { AuthProvider } from "@/lib/auth-context";
import "../styles/globals.css";

// 三字体体系（设计文档 §1.3）：Outfit 拉丁与数字、Noto Sans SC 中文、Geist Mono 等宽
// 字体经 @fontsource 自托管引入（globals.css @import，本地资源，无 Google Fonts 远程依赖）

export const metadata: Metadata = {
  title: {
    default: "课程助手",
    template: "%s | 课程助手",
  },
  description: "封闭私域学习空间：课程橱窗、学习资料与 AI 助教",
};

/**
 * 根布局：挂载 AuthProvider（登录态全局可用）+ 全局登录弹窗
 *
 * 根布局保持中性壳（不带顶导）：顶导 64px 归属 (main) 路由组布局，
 * 课程助手对话页为独立 kimi 侧栏壳（(chat) 路由组）。
 * 登录弹窗挂根级：全部路由组（含公开页登录墙场景）均可触发。
 * 字体族由 globals.css 的 @fontsource 自托管，@theme 声明 --font-display/--font-mono 工具类。
 */
export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="font-display antialiased">
        <AuthProvider>
          <div className="flex min-h-screen flex-col bg-bg text-text">{children}</div>
          <LoginDialog />
        </AuthProvider>
      </body>
    </html>
  );
}
