"use client";

/**
 * 右下浮动操作组（设计稿一 RAG FAB 还原）
 *
 * 业务替换：设计稿的假对话面板 → 跳转真实课程助手对话页（不重复实现
 * 会话界面，符合宪法 C 端对话界面变更前置沟通约束）；悬浮胶囊位由
 * Hero 区 CTA 承担，此处仅保留圆形 FAB（进场弹入动画 + tip 提示）。
 */
import Link from "next/link";

/**
 * 浮动 AI 助教按钮（右下角）
 */
export function FloatingAssistantFab() {
  return (
    <Link
      href="/chat"
      aria-label="打开 AI 课程助教"
      data-testid="assistant-fab"
      className="group fixed right-7 bottom-7 z-[140] grid size-16 place-items-center rounded-full bg-ink text-bg shadow-xl transition-transform duration-400 ease-out hover:scale-105 hover:-translate-y-1 max-md:right-4 max-md:bottom-4"
      style={{ animation: "fab-in .9s 1.1s cubic-bezier(.22,.61,.36,1) both" }}
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        aria-hidden
        className="size-6"
      >
        <path d="M21 11.5a8.4 8.4 0 0 1-8.5 8.4c-1.5 0-2.9-.4-4.1-1L3 20l1.2-4.1a8.3 8.3 0 0 1-1.2-4.4A8.4 8.4 0 0 1 11.5 3 8.4 8.4 0 0 1 21 11.5Z" />
        <path d="M8 10h8M8 13.5h5" />
      </svg>
      {/* tip 气泡：父级 group hover 淡入（BUG-23：Link 缺 group 类致 group-hover 永不命中）；
          pointer-events-none 保留——防 tip 悬浮在按钮热点区外时拦截/抢走 hover */}
      <span className="pointer-events-none absolute top-1/2 right-[74px] -translate-y-1/2 rounded-full bg-white px-4 py-2.5 text-[10.5px] font-medium tracking-[0.12em] whitespace-nowrap text-ink uppercase opacity-0 shadow-md transition-all duration-300 group-hover:opacity-100">
        问 AI 助教
      </span>
    </Link>
  );
}
