"use client";

/**
 * 快捷入口宫格（设计稿一 Tiles 五联画还原；2026-08-27 根修空白块事故）
 *
 * 事故根因：五联画 Link 裸写 `reveal` 类但无 IntersectionObserver 接管 →
 * `.reveal{opacity:0}` 永不入场，桌面端最高 920px 纯空白带。
 * 根修：tile 外层改用 <Reveal>（组件自带 reveal 类 + 观察器；once 定格防
 * hover 弹性变宽与离场复位动画互搏），Link 降级为整面点击层。
 *
 * 业务替换：私教/咨询业务入口 → 平台四大真实目的地 +
 * 中央箭头区（直达课程助手对话）。交互（2026-08-28 层叠化改版）：hover 时目标画
 * 展开抬升，左右邻画负 margin 滑入其下方形成照片层叠、远画收缩退暗，
 * flex/margin/filter/transform 统一 expo-out 同曲线消除错拍（样式见
 * globals.css「首页五联画宫格：层叠式悬浮」块，仅 lg+ 悬停设备生效）；
 * 移动端折行为两列网格。纯 CSS 动画。
 */
import Link from "next/link";
import { Reveal } from "@/components/motion/reveal";

/** 宫格条目：黑白影像 + 底部衬线标签；mid=true 为中央箭头区 */
const TILES = [
  { src: "/images/tile-library.jpg", label: "浏览课程库", href: "/courses", mid: false },
  { src: "/images/tile-group.jpg", label: "与 AI 助教对话", href: "/chat", mid: false },
  { src: "/images/tile-main.jpg", label: "", href: "/chat", mid: true },
  { src: "/images/tile-mentor.jpg", label: "个人中心", href: "/profile", mid: false },
  { src: "/images/tile-chat.jpg", label: "上手指引", href: "/#knowledge-hub", mid: false },
] as const;

/**
 * 快捷入口宫格
 */
export function EntryTiles() {
  return (
    <section
      aria-label="快捷入口"
      className="entry-tiles flex min-h-0 w-full max-lg:flex-wrap lg:h-[min(86vh,920px)]"
    >
      {TILES.map((tile) =>
        tile.mid ? (
          /* 中央箭头区：灰白影像 + 圆环箭头推进（层叠悬浮几何由 globals.css 承担） */
          <Reveal
            key="tile-main"
            once
            data-testid="entry-tile"
            className="entry-tile entry-tile--mid group relative grid min-w-0 flex-1 place-items-center overflow-hidden max-lg:h-[210px] max-lg:flex-basis-full"
          >
            <Link href={tile.href} aria-label="进入课程助手" className="absolute inset-0 z-[3]" />
            <img
              src={tile.src}
              alt=""
              loading="lazy"
              className="absolute inset-0 h-full w-full object-cover"
            />
            <span
              aria-hidden
              className="tile-shade absolute inset-0 bg-ink/30 transition-colors duration-[900ms] ease-[cubic-bezier(0.16,1,0.3,1)] group-hover:bg-ink/15"
            />
            <span className="relative z-[2] grid size-[66px] place-items-center rounded-full border border-bg/70 text-bg transition-all duration-500 group-hover:translate-x-2.5 group-hover:bg-bg/10">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.4"
                aria-hidden
                className="size-[22px]"
              >
                <path d="M4 12h15M13 5l7 7-7 7" />
              </svg>
            </span>
          </Reveal>
        ) : (
          <Reveal
            key={tile.href + tile.label}
            once
            data-testid="entry-tile"
            className="entry-tile group relative flex min-w-0 flex-1 overflow-hidden max-lg:h-[250px] max-lg:flex-basis-1/2"
          >
            <Link href={tile.href} aria-label={tile.label} className="absolute inset-0 z-[3]" />
            <img
              src={tile.src}
              alt=""
              loading="lazy"
              className="h-full w-full object-cover grayscale"
            />
            <span
              aria-hidden
              className="tile-shade absolute inset-0 bg-ink/35 transition-colors duration-[900ms] ease-[cubic-bezier(0.16,1,0.3,1)] group-hover:bg-ink/10"
            />
            <span className="tile-label font-serif-display absolute bottom-[26px] left-7 z-[2] text-[clamp(20px,1.8vw,27px)] font-medium text-bg [text-shadow:0_1px_14px_rgb(0_0_0/40%)]">
              {tile.label}
            </span>
          </Reveal>
        ),
      )}
    </section>
  );
}
