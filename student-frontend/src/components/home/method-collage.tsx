"use client";

/**
 * 「问渠的方法」拼贴叙事区（设计稿一 Lionheart School 拼贴还原）
 *
 * 业务替换：西伦敦私塾故事 → RAG 检索增强工作原理的三步讲述
 * （入库 → 检索 → 有据可答），右侧文字块 + 左侧漂浮宝丽来照片墙
 * （三张错落旋转浮动动画 + 区块级视差位移）。
 */
import Link from "next/link";
import { useParallax } from "@/components/motion/parallax";
import { Reveal } from "@/components/motion/reveal";

/** 三张宝丽来素材与位置/姿态（本地化图片资产） */
const SNAPS = [
  {
    src: "/images/collage-desk.jpg",
    className: "top-[4%] left-[16%] w-[60%]",
    rotate: "-rotate-[2.5deg]",
    delay: "0s",
  },
  {
    src: "/images/collage-campus.jpg",
    className: "right-[2%] bottom-[6%] w-[46%]",
    rotate: "rotate-[2.4deg]",
    delay: "1.4s",
  },
  {
    src: "/images/collage-books.jpg",
    className: "top-[38%] left-[2%] w-[34%]",
    rotate: "-rotate-[4deg]",
    delay: "2.6s",
  },
] as const;

/**
 * 方法拼贴区
 */
export function MethodCollage() {
  const collageRef = useParallax();

  return (
    <section id="about-method" className="bg-bg py-[140px]">
      <div className="mx-auto grid w-full max-w-[1360px] grid-cols-1 items-center gap-[70px] px-6 lg:grid-cols-[1.05fr_1fr] lg:gap-[9vw]">
        {/* 宝丽来拼贴（径向柔光底 + 浮动动画 + 视差容器） */}
        <Reveal variant="scale">
          <div
            ref={collageRef}
            data-parallax={26}
            className="relative min-h-[440px] will-change-transform lg:min-h-[600px]"
          >
            <div
              aria-hidden
              className="absolute inset-[2%] blur-[4px]"
              style={{
                background:
                  "radial-gradient(closest-side at 28% 28%, #EFDCc4, transparent 70%), radial-gradient(closest-side at 72% 58%, #E7CDB4, transparent 70%), radial-gradient(closest-side at 45% 88%, #DEE4D3, transparent 72%)",
              }}
            />
            {SNAPS.map((snap) => (
              <figure
                key={snap.src}
                className={`absolute bg-white p-3 pb-4 shadow-xl ${snap.className} ${snap.rotate}`}
                style={{ animation: `floaty 8s ease-in-out ${snap.delay} infinite` }}
              >
                <img
                  src={snap.src}
                  alt=""
                  loading="lazy"
                  className="aspect-4/3.1 w-full object-cover"
                />
              </figure>
            ))}
          </div>
        </Reveal>

        <Reveal variant="right" delay={0.15}>
          <p className="text-accent-italic text-[clamp(22px,2vw,30px)]">问渠的方法</p>
          <h2 className="font-serif-display mt-5 mb-8 text-[clamp(32px,3.6vw,52px)] leading-[1.14] font-medium">
            不是凭空作答，而是先翻遍你的课堂资料
          </h2>
          <p className="mb-[22px] max-w-[560px] text-[15.5px] leading-[1.9] text-[#4A4038]">
            传统问答模型最让人不安的是「一本正经地编」。问渠学堂把检索放在生成之前：
            你的每一门课、每份讲义都经过解析、分块、语义索引进入知识库；提问发生时，
            助教先在资料里定位相关段落，再组织语言回答——答案下方的「来源卡片」可以逐条点开原文核对。
          </p>
          <p className="max-w-[560px] text-[15.5px] leading-[1.9] text-[#4A4038]">
            我们相信：学习工具的价值不在于替你完成作业，而在于让你更快抵达「真正理解」的那一刻。
          </p>
          <Link href="/chat" className="rule-link mt-4 uppercase">
            去问第一个问题
          </Link>
        </Reveal>
      </div>
    </section>
  );
}
