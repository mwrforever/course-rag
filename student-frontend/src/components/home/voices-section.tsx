"use client";

/**
 * 学生心声暗景区（设计稿一 In Their Words 还原）
 *
 * 结构：左侧手写引语 + 大衬线语录（3 条轮换，fade 切换）｜右侧影像卡 +
 * 缩略图行 + 前后箭头。背景图区块级视差（data-parallax），文字区 reveal 进场；
 * 语录内容为学习方法向的品牌叙事（非虚构人物）。切换动画 380ms 半程换文，
 * 与设计稿时序一致。
 */
import { useCallback, useEffect, useState } from "react";
import { useParallax } from "@/components/motion/parallax";
import { Reveal } from "@/components/motion/reveal";

/** 语录条目（引语 / 署名场景 / 影像素材） */
const VOICES = [
  {
    quote: "先把问题问清楚，答案已经在资料里等你——这是我在问渠学会的第一课。",
    by: "提问的方法论",
    img: "/images/avatar-3.jpg",
  },
  {
    quote: "来源卡片是安全感：每个结论都能点回原文，越查证越有把握。",
    by: "溯源的自信",
    img: "/images/avatar-1.jpg",
  },
  {
    quote: "它不替我写作业，但总能把「看不懂」变成「原来如此」。",
    by: "理解的瞬间",
    img: "/images/avatar-2.jpg",
  },
] as const;

/** 引语切换半程延时（毫秒，文本淡出后再换内容） */
const VOICE_SWAP_DELAY_MS = 380;

/**
 * 学生心声区
 */
export function VoicesSection() {
  const [voiceIndex, setVoiceIndex] = useState(0);
  // fading=true 的 380ms 内旧引语淡出、图片降透明，随后替换内容并复位
  const [fading, setFading] = useState(false);

  /** 切换到指定索引（自动/手动共用，半程换文动画编排） */
  const goVoice = useCallback(
    (index: number) => {
      if (fading) {
        return;
      }
      setFading(true);
      setTimeout(() => {
        setVoiceIndex(((index % VOICES.length) + VOICES.length) % VOICES.length);
        setFading(false);
      }, VOICE_SWAP_DELAY_MS);
    },
    [fading],
  );

  // 定时轮换（30s，节奏克制不打断阅读）
  useEffect(() => {
    const timer = setInterval(() => goVoice(voiceIndex + 1), 30000);
    return () => clearInterval(timer);
  }, [voiceIndex, goVoice]);

  const voice = VOICES[voiceIndex];
  const bgRef = useParallax();

  return (
    <section className="relative overflow-hidden py-[150px] text-bg">
      {/* 深色影像背景（上溢出裁切视差） */}
      <div
        ref={bgRef}
        aria-hidden
        className="absolute inset-0 will-change-transform"
        data-parallax={46}
      >
        <img
          src="/images/words-bg.jpg"
          alt=""
          loading="lazy"
          className="relative top-[-11%] h-[122%] w-full object-cover brightness-50"
          style={{ filter: "grayscale(.35) brightness(.5)" }}
        />
        <div
          className="absolute inset-0"
          style={{
            background: "linear-gradient(90deg, rgb(18 13 10 / 94%) 20%, rgb(18 13 10 / 45%))",
          }}
        />
      </div>

      <div className="relative mx-auto grid w-full max-w-[1360px] grid-cols-1 items-center gap-16 px-6 lg:grid-cols-[1.15fr_.85fr] lg:gap-[6vw]">
        <Reveal variant="left">
          <p className="text-script mb-7 text-bg">学习的心声</p>
          <div
            data-testid="voice-quote"
            className="transition-all duration-400 ease-out"
            style={{ opacity: fading ? 0 : 1, transform: fading ? "translateY(14px)" : undefined }}
          >
            <p className="font-serif-display max-w-[640px] text-[clamp(26px,3vw,44px)] leading-[1.35] font-medium text-bg">
              「{voice.quote}」
            </p>
            <div className="mt-11">
              <span className="font-serif-display block text-[22px]">{voice.by}</span>
              <span className="mt-2 block text-[10.5px] tracking-[0.16em] text-bg/60 uppercase">
                问渠学堂学习笔记 · {voiceIndex + 1}/{VOICES.length}
              </span>
            </div>
          </div>
        </Reveal>

        <Reveal variant="right" delay={0.15}>
          <div className="aspect-16/10.5 overflow-hidden border border-bg/20">
            <img
              src={voice.img}
              alt=""
              loading="lazy"
              className="h-full w-full object-cover transition-opacity duration-400"
              style={{ opacity: fading ? 0 : 1 }}
            />
          </div>
          <div className="mt-[22px] flex items-center justify-between">
            {/* 缩略图行 */}
            <div className="flex gap-2.5">
              {VOICES.map((item, index) => (
                <button
                  key={item.img}
                  type="button"
                  aria-label={`查看第 ${index + 1} 条心声`}
                  onClick={() => goVoice(index)}
                  className={`size-[56px] h-10 overflow-hidden border p-0 transition-[opacity,border-color] duration-300 ${
                    index === voiceIndex && !fading
                      ? "border-bg/80 opacity-100"
                      : "border-transparent opacity-55 hover:opacity-80"
                  }`}
                >
                  <img
                    src={item.img}
                    alt=""
                    loading="lazy"
                    className="h-full w-full object-cover"
                  />
                </button>
              ))}
            </div>
            {/* 前后箭头 */}
            <div className="flex gap-3">
              <button
                type="button"
                aria-label="上一条心声"
                onClick={() => goVoice(voiceIndex - 1)}
                className="grid size-12 place-items-center rounded-full border border-bg/40 transition-colors duration-300 hover:bg-bg hover:text-ink"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  aria-hidden
                  className="size-[19px]"
                >
                  <path d="M20 12H5M11 5l-7 7 7 7" />
                </svg>
              </button>
              <button
                type="button"
                aria-label="下一条心声"
                onClick={() => goVoice(voiceIndex + 1)}
                className="grid size-12 place-items-center rounded-full border border-bg/40 transition-colors duration-300 hover:bg-bg hover:text-ink"
              >
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.5"
                  aria-hidden
                  className="size-[19px]"
                >
                  <path d="M4 12h15M13 5l7 7-7 7" />
                </svg>
              </button>
            </div>
          </div>
        </Reveal>
      </div>
    </section>
  );
}
