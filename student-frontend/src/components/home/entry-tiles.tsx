"use client";

/**
 * 快捷入口宫格（设计稿一 Tiles 五联画还原）
 *
 * 业务替换：私教/咨询业务入口 → 平台四大真实目的地 +
 * 中央箭头区（直达课程助手对话）。交互：hover 弹性变宽（flex-grow 过渡）
 * 与黑白影像复色，纯 CSS 动画；移动端折行为两列网格。
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
      className="flex min-h-0 w-full max-lg:flex-wrap lg:h-[min(86vh,920px)]"
    >
      {TILES.map((tile) =>
        tile.mid ? (
          /* 中央箭头区：灰白影像 + 圆环箭头推进 */
          <Link
            key="tile-main"
            href={tile.href}
            className="reveal group relative grid min-w-0 flex-[1.7] place-items-center overflow-hidden transition-[flex] duration-700 ease-out hover:flex-[3] max-lg:h-[210px] max-lg:flex-basis-full lg:flex-1"
            data-testid="entry-tile"
          >
            <img
              src={tile.src}
              alt=""
              loading="lazy"
              className="absolute inset-0 h-full w-full object-cover transition-transform duration-[1200ms] ease-out group-hover:scale-[1.04]"
            />
            <span
              aria-hidden
              className="absolute inset-0 bg-ink/30 transition-colors duration-500 group-hover:bg-ink/15"
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
          </Link>
        ) : (
          <Link
            key={tile.href + tile.label}
            href={tile.href}
            data-testid="entry-tile"
            className="reveal tile-bw group relative flex min-w-0 flex-1 overflow-hidden transition-[flex] duration-700 ease-out hover:flex-[2.6] max-lg:h-[250px] max-lg:flex-basis-1/2"
          >
            <img
              src={tile.src}
              alt=""
              loading="lazy"
              className="h-full w-full object-cover grayscale transition-all duration-[1200ms] ease-out group-hover:scale-[1.04] group-hover:grayscale-0"
            />
            <span
              aria-hidden
              className="absolute inset-0 bg-ink/35 transition-colors duration-500 group-hover:bg-ink/10"
            />
            <span className="font-serif-display absolute bottom-[26px] left-7 z-[2] text-[clamp(20px,1.8vw,27px)] font-medium text-bg [text-shadow:0_1px_14px_rgb(0_0_0/40%)]">
              {tile.label}
            </span>
          </Link>
        ),
      )}
    </section>
  );
}
