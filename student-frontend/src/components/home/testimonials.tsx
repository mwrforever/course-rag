"use client";

/**
 * 学员声音轮播区（设计稿一 Testimonials 还原：6 秒自动轮换 + 圆点导航）
 *
 * 业务替换：私教学员家长评语 → 问渠学堂学生视角的学习体验泛化反馈。
 * 轮播实现与设计稿一致：grid-area 叠层 + active 类透明度/位移过渡；
 * 定时器在手动切换后重置；reduced-motion 仍可点按切换（过渡时长由全局总开关压缩）。
 */
import { useEffect, useState } from "react";
import { Reveal } from "@/components/motion/reveal";

/** 学员反馈（产品向泛化文案） */
const TESTIMONIALS = [
  {
    quote: "答案下面直接给原文出处，复习时哪里不确定就点开哪段，比来回翻 PDF 快太多了。",
    name: "大三 · 计算机系",
    role: "课程助手周用户",
  },
  {
    quote: "上传的扫描讲义居然也能被问到，图表位置都能定位到页码，检索是真的在做。",
    name: "研一 · 信号处理",
    role: "多模态资料重度使用者",
  },
  {
    quote: "它记得我习惯用短句解释和伪代码举例，第三次开始回答风格已经完全是「我的助教」了。",
    name: "大二 · 软件工程",
    role: "个性化记忆内测学员",
  },
  {
    quote: "期末周把三份课件同时挂进对话里交叉提问，知识点串起来的感觉第一次这么清晰。",
    name: "大四 · 金融学",
    role: "情景记忆功能用户",
  },
] as const;

/** 自动轮换间隔（毫秒，设计稿 6000ms 对齐） */
const ROTATE_INTERVAL_MS = 6000;

/**
 * 学员声音区
 */
export function Testimonials() {
  const [activeIndex, setActiveIndex] = useState(0);

  // 自动轮换：每次切换（含手动）后重新计时
  useEffect(() => {
    const timer = setInterval(() => {
      setActiveIndex((index) => (index + 1) % TESTIMONIALS.length);
    }, ROTATE_INTERVAL_MS);
    return () => clearInterval(timer);
  });

  const active = TESTIMONIALS[activeIndex];

  return (
    <section className="relative bg-[#F9F5EC] py-[140px]">
      <div className="mx-auto px-6">
        <Reveal>
          <div className="mx-auto grid max-w-[920px] text-center">
            {/* 叠层轨道：所有条目占同一格，激活项淡入上移 */}
            <div
              key={activeIndex}
              data-testid="testimonial-slide"
              style={{ animation: "pane-in .7s cubic-bezier(.22,.61,.36,1)" }}
            >
              <blockquote className="font-serif-display text-[clamp(24px,3.1vw,42px)] leading-[1.4] font-medium text-[#2A231D]">
                「{active.quote}」
              </blockquote>
              <div className="mt-11 text-sm text-muted">
                <strong className="block font-semibold text-ink">{active.name}</strong>
                {active.role}
              </div>
            </div>
          </div>
        </Reveal>

        {/* 圆点导航（桌面右侧竖排 / 移动底部横排） */}
        <div
          className="mt-14 flex justify-center gap-[18px] lg:absolute lg:right-[4.5vw] lg:top-1/2 lg:-translate-y-1/2"
          role="tablist"
          aria-label="切换学员声音"
        >
          {TESTIMONIALS.map((item, index) => (
            <button
              key={item.name}
              type="button"
              role="tab"
              aria-selected={index === activeIndex}
              aria-label={`第 ${index + 1} 条`}
              onClick={() => setActiveIndex(index)}
              className={`size-3 rounded-full border-[1.4px] transition-colors duration-300 ${
                index === activeIndex ? "border-ink bg-ink" : "border-[#B4A794] bg-transparent"
              }`}
            />
          ))}
        </div>
      </div>
    </section>
  );
}
