"use client";

/**
 * 平台核心能力区（设计稿一 Services 手风琴还原）
 *
 * 业务替换：私教/ homeschooling 等营业务 → 问渠学堂四项真实能力：
 * AI 课程问答（RAG 检索增强）、多模态资料理解、个性化偏好记忆、情景记忆与会话管理。
 * 视觉：深棕墨底 + 漂移模糊背景图 + 四格手风琴（单项展开，plus 号旋转收合），
 * max-height 过渡展开动画与设计稿一致；Reveal blur 进场。
 */
import { useRef, useState } from "react";
import { Reveal } from "@/components/motion/reveal";

/** 手风琴条目（平台真实能力的对外表述） */
const SERVICE_ITEMS = [
  {
    title: "AI 课程问答",
    body: "基于课堂知识库的检索增强生成（RAG）：先从课程资料中检索出处，再生成回答——每句话都有原文依据，可信、可溯源。",
  },
  {
    title: "多模态资料理解",
    body: "文档、表格、扫描件与图片一并入库：解析、分块、图文标注后进入知识库，提问时跨模态召回，讲义里的图表也能被问到。",
  },
  {
    title: "个性化偏好记忆",
    body: "系统会渐进记住你的称呼、学习目标与表达偏好：常用术语深度、举例风格、答题习惯……对话越久，越懂你。",
  },
  {
    title: "情景记忆与会话管理",
    body: "每一次问答都沉淀为可回溯的学习经历：历史会话随时续聊，关键结论自动提取成记忆片段，跨天复习不再断线。",
  },
] as const;

/**
 * 平台核心能力手风琴区
 */
export function ServicesAccordion() {
  const [openIndex, setOpenIndex] = useState<number | null>(null);
  // 各面板实际高度引用（展开时写入 max-height 完成过渡动画）
  const bodyRefs = useRef<Array<HTMLDivElement | null>>([]);

  /** 切换手风琴项：再次点击已展开项收合 */
  function toggle(index: number) {
    setOpenIndex((current) => (current === index ? null : index));
  }

  return (
    <section id="services" className="relative overflow-hidden pt-[120px] text-bg">
      {/* 漂移模糊背景（装饰层，reduced-motion 时静止） */}
      <div
        aria-hidden
        className="absolute -inset-[12%] opacity-50"
        style={{
          background: "url(/images/services-bg.jpg) center/cover",
          filter: "blur(70px) brightness(.85) saturate(1.05)",
          animation: "drift 26s ease-in-out infinite alternate",
        }}
      />
      <div
        className="absolute inset-0"
        style={{ background: "linear-gradient(rgb(30 20 13 / 50%), rgb(28 18 12 / 72%))" }}
      />

      <div className="relative mx-auto w-full max-w-[1360px] px-6 pb-[110px]">
        <Reveal variant="blur">
          <p
            className="text-accent-italic text-[clamp(22px,2vw,30px)]"
            style={{ color: "var(--color-cream-300)" }}
          >
            平台能力
          </p>
        </Reveal>
        <Reveal variant="blur" delay={0.1}>
          <h2 className="font-serif-display mt-6 max-w-[720px] text-[clamp(36px,4.4vw,58px)] leading-[1.12] font-medium">
            从课堂资料到你的问题之间，
            <em className="font-normal italic">只隔一次检索的距离</em>
          </h2>
        </Reveal>
        <Reveal variant="blur" delay={0.2}>
          <p className="mt-7 max-w-[520px] text-[15px] leading-[1.9] text-bg/80">
            问渠学堂把整间课堂装进知识库：你负责提出好问题，我们负责在浩瀚资料里找到那一段正确答案。
          </p>
        </Reveal>

        <Reveal delay={0.25}>
          <div className="mt-20 grid grid-cols-1 border-t border-bg/25 md:grid-cols-2 xl:grid-cols-4">
            {SERVICE_ITEMS.map((item, index) => {
              const open = openIndex === index;
              return (
                <div
                  key={item.title}
                  data-testid="service-item"
                  aria-expanded={open}
                  className={`border-r border-b border-bg/25 p-0 transition-colors duration-400 first:border-l xl:[&:nth-child(-n+2)]:border-l [&:nth-child(even)]:border-l xl:[&:nth-child(n)]:border-l ${
                    open ? "bg-bg/[.06]" : ""
                  }`}
                >
                  <button
                    type="button"
                    onClick={() => toggle(index)}
                    data-testid="service-head"
                    aria-expanded={open}
                    className="flex w-full items-center justify-between gap-3.5 px-[30px] py-9 text-left font-serif-display text-[clamp(19px,1.8vw,25px)] font-medium text-bg"
                  >
                    {item.title}
                    <i
                      aria-hidden
                      className={`relative inline-block size-[15px] shrink-0 transition-transform ${open ? "rotate-90" : ""}`}
                    >
                      <span className="absolute top-1/2 left-0 h-[1.5px] w-full -translate-y-1/2 bg-bg" />
                      <span
                        className="absolute left-1/2 top-0 h-full w-[1.5px] -translate-x-1/2 bg-bg transition-transform duration-500"
                        style={{ transform: open ? "scaleY(0)" : undefined }}
                      />
                    </i>
                  </button>
                  <div
                    ref={(el) => {
                      bodyRefs.current[index] = el;
                    }}
                    className="overflow-hidden transition-[max-height] duration-500 ease-out"
                    style={{ maxHeight: open ? (bodyRefs.current[index]?.scrollHeight ?? 200) : 0 }}
                    aria-hidden={!open}
                  >
                    <p className="px-[30px] pb-9 text-sm leading-[1.85] text-bg/75">{item.body}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </Reveal>
      </div>
    </section>
  );
}
