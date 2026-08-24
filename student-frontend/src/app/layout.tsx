import type { Metadata } from "next";
import { Geist_Mono, Noto_Sans_SC, Outfit } from "next/font/google";
import { SiteHeader } from "@/components/site-header";
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
 * 根布局：挂载三字体 CSS 变量与全站壳
 *
 * 结构为「顶导 64px + 全宽内容区、无侧边栏」（设计文档 §1.1），
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
        <div className="flex min-h-screen flex-col bg-bg text-text">
          <SiteHeader />
          <main className="flex-1">{children}</main>
        </div>
      </body>
    </html>
  );
}
