import type { Metadata } from "next";
import { Geist_Mono, Noto_Sans_SC, Outfit } from "next/font/google";
import { AuthProvider } from "@/lib/auth-context";
import "../styles/globals.css";

// 三字体体系（设计文档 §1.3）：Outfit 承担拉丁与数字显示、Noto Sans SC 承担中文、Geist Mono 等宽
const outfit = Outfit({
  variable: "--font-outfit",
  subsets: ["latin"],
  display: "swap",
});

const notoSansSC = Noto_Sans_SC({
  variable: "--font-noto",
  weight: ["400", "500", "700"],
  subsets: ["latin"],
  display: "swap",
  // CJK 字符集庞大，按 unicode-range 分片按需加载即可，不做首屏 preload
  preload: false,
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
  display: "swap",
});

export const metadata: Metadata = {
  title: {
    default: "课程助手",
    template: "%s | 课程助手",
  },
  description: "封闭私域学习空间：课程橱窗、学习资料与 AI 助教",
};

/**
 * 根布局：挂载三字体 CSS 变量与 AuthProvider（登录态全局可用）
 *
 * 根布局保持中性壳（不带顶导）：顶导 64px 归属 (main) 路由组布局，
 * (auth) 路由组（登录页）独立无顶导壳（设计文档 §1.5.7）。
 * 字体变量注入后由 globals.css 的 @theme 消费为 font-display/font-mono 工具类。
 */
export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body
        className={`${outfit.variable} ${notoSansSC.variable} ${geistMono.variable} font-display antialiased`}
      >
        <AuthProvider>
          <div className="flex min-h-screen flex-col bg-bg text-text">{children}</div>
        </AuthProvider>
      </body>
    </html>
  );
}
