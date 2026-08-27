"use client";

/**
 * 首页引言区（设计稿一 Intro 还原：居中衬线宣言 + 副句 + 主 CTA）
 *
 * 业务替换：三十年私教机构叙事 → 问渠学堂定位宣言；
 * 按钮语义为「进入课程助手」（站内真实路由）。
 */
import Link from "next/link";
import { Reveal } from "@/components/motion/reveal";

/**
 * 首页引言区
 */
export function IntroStatement() {
  return (
    <section className="pt-[140px] pb-[130px] text-center">
      <div className="mx-auto px-6">
        <Reveal>
          <p className="font-serif-display mx-auto mb-[52px] max-w-[980px] text-[clamp(22px,2.5vw,34px)] leading-[1.55] font-normal text-text">
            把整间课堂装进一个会检索的知识库—— 问渠学堂以 RAG 检索增强生成回答你的每一个问题，
            让「答案从哪来」永远可查、可信。
          </p>
        </Reveal>
        <Reveal variant="scale" delay={0.15}>
          <Link href="/chat" className="btn-pill btn-dark text-[11px] uppercase">
            开始向 AI 助教提问
          </Link>
        </Reveal>
      </div>
    </section>
  );
}
