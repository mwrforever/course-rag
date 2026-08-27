"use client";

/**
 * 上手指引横滑区（设计稿一 Knowledge Hub 横向轮播还原）
 *
 * 业务替换：教育资讯博客 → 六张「上手指引」卡，每张指向真实功能入口
 * （提问方法 / 附件用法 / 来源核对 / 会话管理 / 偏好记忆 / 课程浏览）。
 * 交互：按卡片步进横移（响应式可见数量 3/2/1），进度条 + 前后按钮；
 * 竖排日期栏替换为「第 N 步 / 预计耗时」语义，保持设计稿版式特征。
 */
import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Reveal } from "@/components/motion/reveal";

/** 指引卡数据（步骤序号 / 耗时 / 分类标签 / 标题 / 目标路由） */
const HUB_POSTS = [
  { minutes: 1, label: "提问", title: "如何提出一个能被精准回答的问题", href: "/chat" },
  { minutes: 2, label: "附件", title: "把讲义拍照上传：多模态资料怎么被读懂", href: "/chat" },
  { minutes: 1, label: "溯源", title: "来源卡片：每个答案的原文出处在哪里看", href: "/chat" },
  { minutes: 1, label: "会话", title: "历史会话与续聊：让复习跨天不断线", href: "/chat" },
  { minutes: 2, label: "记忆", title: "偏好记忆：它会慢慢记住你的学习习惯", href: "/profile" },
  { minutes: 1, label: "课堂", title: "浏览课程库并加入你正在修读的课", href: "/courses" },
] as const;

/**
 * 上手指引横滑区
 */
export function KnowledgeHub() {
  const trackRef = useRef<HTMLDivElement>(null);
  const [index, setIndex] = useState(0);
  /** 可见卡数（resize 时同步，SSR 初始按桌面 3 计） */
  const [visibleCount, setVisibleCount] = useState(3);

  useEffect(() => {
    const updateVisible = () => {
      setVisibleCount(window.innerWidth > 1080 ? 3 : window.innerWidth > 640 ? 2 : 1);
    };
    updateVisible();
    window.addEventListener("resize", updateVisible);
    return () => window.removeEventListener("resize", updateVisible);
  }, []);

  const maxIndex = Math.max(0, HUB_POSTS.length - visibleCount);

  /** 平移轨道至当前索引（含 gap 实测宽度） */
  const applyTransform = useCallback((next: number) => {
    const track = trackRef.current;
    if (!track) {
      return;
    }
    const firstCard = track.children[0] as HTMLElement | undefined;
    if (!firstCard) {
      return;
    }
    const gap =
      Number.parseFloat(getComputedStyle(track).columnGap || getComputedStyle(track).gap) || 44;
    const step = firstCard.getBoundingClientRect().width + gap;
    track.style.transform = `translateX(${-next * step}px)`;
  }, []);

  const goTo = useCallback(
    (next: number) => {
      const clamped = Math.min(Math.max(next, 0), maxIndex);
      setIndex(clamped);
      applyTransform(clamped);
    },
    [applyTransform, maxIndex],
  );

  return (
    <section id="knowledge-hub" className="overflow-hidden py-[140px] pb-[120px]">
      <div className="mx-auto px-6 text-center">
        <Reveal>
          <p className="text-accent-italic text-[clamp(22px,2vw,30px)]">上手指引</p>
        </Reveal>
        <Reveal delay={0.1}>
          <h2 className="font-serif-display mx-auto mt-4 max-w-[760px] text-[clamp(30px,3.4vw,48px)] leading-tight font-medium">
            五分钟掌握问渠学堂最常用的六个技巧
          </h2>
        </Reveal>
      </div>

      <Reveal delay={0.2}>
        {/* 横向轨道（overflow hidden 由外层承担；卡片定宽弹性位 clamp 对齐设计稿） */}
        <div className="mt-20">
          <div
            ref={trackRef}
            className="stagger flex gap-[clamp(24px,4.6vw,100px)] transition-transform duration-700 ease-out will-change-transform"
          >
            {HUB_POSTS.map((post) => (
              <Link
                key={post.title}
                href={post.href}
                data-testid="hub-post"
                className="group hover:bg-brand/70 flex min-h-[520px] flex-none basis-[86vw] bg-[#F9E8D8] transition-colors duration-500 ease-out sm:basis-[72vw] lg:basis-[calc(33%-22px)] lg:min-w-0 xl:basis-[33vw]"
              >
                {/* 竖排元信息栏 */}
                <div className="flex w-16 shrink-0 flex-col items-center justify-between border-r border-ink/25 py-6 max-lg:hidden">
                  <div className="text-center leading-none">
                    <b className="font-serif-display block text-[22px] font-medium">
                      {post.minutes}
                    </b>
                    <span className="mt-1.5 block text-[8.5px] tracking-[0.22em] text-muted uppercase">
                      Min
                    </span>
                  </div>
                  <span className="text-[9.5px] tracking-[0.18em] text-muted uppercase [writing-mode:vertical-rl]">
                    Step · 第 {post.minutes} 分钟
                  </span>
                </div>
                <div className="flex min-w-0 flex-1 flex-col px-8 pt-11 pb-10 md:px-12">
                  <span className="self-start rounded-full border border-ink/40 px-4 py-2 text-[9.5px] tracking-[0.16em] uppercase">
                    {post.label}
                  </span>
                  <h3 className="font-serif-display mt-[54px] text-[clamp(24px,2.15vw,38px)] leading-[1.22] font-medium text-[#2A231D]">
                    {post.title}
                  </h3>
                  <span className="read-link mt-auto self-end pt-[13px] text-right text-[10.5px] tracking-[0.16em] uppercase">
                    开始
                  </span>
                </div>
              </Link>
            ))}
          </div>
        </div>
      </Reveal>

      {/* 控制条：前退 / 进度条 / 前进 */}
      <Reveal delay={0.3}>
        <div className="mx-auto mt-16 flex items-center gap-8 px-[6vw] md:gap-11">
          <button
            type="button"
            aria-label="上一批指引"
            data-testid="hub-prev"
            onClick={() => goTo(index - 1)}
            disabled={index === 0}
            className="p-2.5 text-ink transition-all duration-300 hover:-translate-x-1.5 disabled:pointer-events-none disabled:opacity-25"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.3"
              aria-hidden
              className="size-[30px]"
            >
              <path d="M21 12H4M11 5l-7 7 7 7" />
            </svg>
          </button>
          <div className="relative mx-auto h-0.5 max-w-[560px] flex-1 rounded-full bg-ink/15">
            <i
              className="absolute top-1/2 left-0 h-1 -translate-y-1/2 rounded-full bg-ink transition-[width] duration-500 ease-out"
              style={{ width: `${maxIndex ? (index / maxIndex) * 100 : 100}%` }}
            />
          </div>
          <button
            type="button"
            aria-label="下一批指引"
            data-testid="hub-next"
            onClick={() => goTo(index + 1)}
            disabled={index === maxIndex}
            className="p-2.5 text-ink transition-all duration-300 hover:translate-x-1.5 disabled:pointer-events-none disabled:opacity-25"
          >
            <svg
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.3"
              aria-hidden
              className="size-[30px]"
            >
              <path d="M3 12h17M13 5l7 7-7 7" />
            </svg>
          </button>
        </div>
      </Reveal>

      <Reveal>
        <div className="mt-16 text-center">
          <Link href="/courses" className="btn-pill btn-dark text-[11px] uppercase">
            先去逛逛课堂
          </Link>
        </div>
      </Reveal>
    </section>
  );
}
