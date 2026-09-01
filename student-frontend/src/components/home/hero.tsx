"use client";

/**
 * 首页英雄区（设计稿一还原：Ken Burns 缓推背景 + 巨型衬线品牌字 + 双列副题差速视差）
 *
 * 业务替换：Lionheart 英伦学院叙事 → 问渠学堂 RAG 课程助教定位；
 * 动画契约与设计稿逐帧一致——hero-word/bg/foot 三层以 y*0.30/0.16/0.08 差速位移，
 * 大字随滚动渐隐；reduced-motion 下全部静态呈现（hydration 安全：SSR 输出无随机量，
 * 入场动画为纯 CSS keyframes，挂载后才启动 rAF 视差，2026-08-26 教训对齐）。
 * PERF-06：LCP 背景改走 next/image（priority 预载 + AVIF/WebP 运行时转换 +
 * 响应式 srcset），Ken Burns 动效保留在图元素 style 上。
 * BUG-29+PERF-23：rAF 循环改 useRafLoop——hero 滚出视口/页面切后台即暂停，
 * 重新入视口先同步补一帧再续排（滚动视差帧率不回退）。
 */
import Image from "next/image";
import Link from "next/link";
import { useRef } from "react";
import { useRafLoop } from "@/components/motion/raf-loop";

/** 英雄区巨型字 */
const HERO_WORD = "问渠学堂";

/**
 * 首页英雄区
 */
export function HomeHero() {
  const sectionRef = useRef<HTMLElement>(null);
  const wordRef = useRef<HTMLHeadingElement>(null);
  const bgRef = useRef<HTMLDivElement>(null);
  const footRef = useRef<HTMLDivElement>(null);

  // 三层差速视差（rAF 单循环驱动三元素，近顶 1.2 屏内生效；循环启停归 useRafLoop）
  useRafLoop(() => {
    const y = window.scrollY;
    const vh = window.innerHeight;
    if (y < vh * 1.2) {
      if (wordRef.current) {
        wordRef.current.style.transform = `translate3d(0, ${(y * 0.3).toFixed(1)}px, 0)`;
        wordRef.current.style.opacity = String(Math.max(0, 1 - y / (vh * 0.75)));
      }
      bgRef.current?.style.setProperty(
        "transform",
        `translate3d(0, ${(y * 0.16).toFixed(1)}px, 0)`,
      );
      footRef.current?.style.setProperty(
        "transform",
        `translate3d(0, ${(y * 0.08).toFixed(1)}px, 0)`,
      );
    }
  }, sectionRef);

  return (
    <section
      ref={sectionRef}
      className="relative flex min-h-[calc(100vh-68px)] flex-col overflow-hidden bg-surface-deep text-bg md:min-h-[calc(100vh-78px)]"
      id="top"
    >
      {/* 背景：本地化素材 Ken Burns 缓推近（PERF-06：next/image priority 预载 LCP） */}
      <div ref={bgRef} aria-hidden className="absolute inset-0 will-change-transform">
        <Image
          src="/images/hero-study.jpg"
          alt=""
          fill
          priority
          sizes="100vw"
          className="object-cover"
          style={{ animation: "kenburns 7s cubic-bezier(.22,.61,.36,1) both" }}
        />
        <div
          className="absolute inset-0"
          style={{
            background:
              "linear-gradient(rgb(18 12 8 / 50%), rgb(18 12 8 / 28%) 45%, rgb(18 12 8 / 55%))",
          }}
        />
      </div>

      {/* 巨型品牌字（进场后 rAF 接管滚动视差） */}
      <h1
        ref={wordRef}
        className="font-serif-display relative z-[2] mt-[9vh] text-center leading-none font-medium tracking-[0.06em] uppercase"
        style={{
          fontSize: "clamp(56px, 13vw, 200px)",
          color: "#F6F1E7",
          animation: "hero-word-up 1.2s .15s cubic-bezier(.22,.61,.36,1) both",
        }}
      >
        {HERO_WORD}
      </h1>

      {/* 底部双列副题（右对齐旧学 / 左对齐新知，差速下沉视差） */}
      <div
        ref={footRef}
        className="relative z-[2] mt-auto flex items-end justify-between px-[11vw] pb-[9vh] max-md:flex-col max-md:items-start max-md:gap-6 max-md:pb-[12vh]"
      >
        <div
          className="col-left max-md:text-left md:text-right"
          style={{ animation: "hero-up 1.2s .45s cubic-bezier(.22,.61,.36,1) both" }}
        >
          <h2 className="font-serif-display text-[clamp(34px,4.4vw,68px)] leading-tight font-medium [text-shadow:0_2px_30px_rgb(0_0_0/35%)]">
            旧学之蕴
            <br />
            治学如故
          </h2>
        </div>
        <div
          className="col-right text-left"
          style={{ animation: "hero-up 1.2s .65s cubic-bezier(.22,.61,.36,1) both" }}
        >
          <h2 className="font-serif-display text-[clamp(34px,4.4vw,68px)] leading-tight font-medium [text-shadow:0_2px_30px_rgb(0_0_0/35%)]">
            新知之源
            <br />
            有问必答
          </h2>
        </div>
      </div>

      {/* 右下快捷入口胶囊（设计稿 WhatsApp 浮条位替换为站内 CTA） */}
      <Link
        href="/chat"
        className="absolute bottom-7 left-1/2 z-[3] hidden -translate-x-1/2 items-center gap-2.5 rounded-full bg-surface px-[30px] py-[15px] text-xs font-medium tracking-[0.14em] text-ink shadow-xl transition-transform duration-300 hover:-translate-y-1 hover:-translate-x-1/2 lg:inline-flex"
        style={{ animation: "wa-in .9s .8s cubic-bezier(.22,.61,.36,1) both" }}
      >
        直接向 AI 助教提问
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          aria-hidden
          className="size-4"
        >
          <path d="M4 12h15M13 5l7 7-7 7" />
        </svg>
      </Link>
    </section>
  );
}
