"use client";

/**
 * 首页（UI 全面重构 2026-08-27：问渠学堂学院风，设计稿一完整还原）
 *
 * 区块编排（自上而下，动画体系与设计稿逐帧对应）：
 * ScrollProgress 阅读进度条 → HomeHero（Ken Burns + 巨字视差）→ IntroStatement
 * → ServicesAccordion（能力手风琴）→ FeaturedCourses（公开课程三列大卡）
 * → MethodCollage（问渠的方法·宝丽来拼贴视差）→ Testimonials（学员声音轮播）
 * → VoicesSection（暗景语录切换）→ KnowledgeHub（上手指引横滑）
 * → EntryTiles（快捷入口宫格）；底栏由布局 SiteFooter 承担。
 *
 * 公开浏览契约：首页全程可匿名访问；?login=1 参数仍走全局登录弹窗
 * （middleware 带回的受保护路由拦截流）。所有动效对 prefers-reduced-motion 静态降级。
 */
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { EntryTiles } from "@/components/home/entry-tiles";
import { FeaturedCourses } from "@/components/home/featured-courses";
import { FloatingAssistantFab } from "@/components/home/floating-assistant-fab";
import { HomeHero } from "@/components/home/hero";
import { IntroStatement } from "@/components/home/intro-statement";
import { KnowledgeHub } from "@/components/home/knowledge-hub";
import { MethodCollage } from "@/components/home/method-collage";
import { ServicesAccordion } from "@/components/home/services-accordion";
import { Testimonials } from "@/components/home/testimonials";
import { VoicesSection } from "@/components/home/voices-section";
import { useAuth } from "@/lib/auth-context";

/**
 * 首页
 */
export default function HomePage() {
  const { openLoginDialog } = useAuth();
  const router = useRouter();

  // 未登录访问受保护路由被 middleware 带回 /?login=1：自动打开登录弹窗并清参（防重触发）
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get("login") === "1") {
      openLoginDialog();
      router.replace("/", { scroll: false });
    }
  }, [openLoginDialog, router]);

  return (
    <div>
      <HomeHero />
      <IntroStatement />
      <ServicesAccordion />
      <FeaturedCourses />
      <MethodCollage />
      <Testimonials />
      <VoicesSection />
      <KnowledgeHub />
      {/* 宫格前引导句（设计稿 tiles 无标题，保留纯区块；此处加 sr-only 语义锚点） */}
      <h2 className="sr-only">快捷入口</h2>
      <EntryTiles />
      {/* 返回顶部语义 CTA：页脚前的收束横条（承接设计稿 hub-cta 之外的空档节奏） */}
      <section className="bg-bg py-20 text-center">
        <Link href="/chat" className="btn-pill btn-solid text-[11px] uppercase">
          现在就向 AI 助教提问
        </Link>
      </section>
      <FloatingAssistantFab />
    </div>
  );
}
