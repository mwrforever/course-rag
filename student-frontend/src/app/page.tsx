// 首页占位：Hero 问候、我的课程 Bento 橱窗、最近会话等区块由后续任务落地（设计文档 §1.5.1）
export default function Home() {
  return (
    <section className="mx-auto w-full max-w-6xl px-6 py-20">
      <p className="mb-3 inline-block rounded-full bg-brand-soft px-3 py-1 text-xs font-medium text-brand-strong">
        课程助手
      </p>
      <h1 className="font-display text-[44px] font-bold leading-[1.15] text-text">
        你好，欢迎回到学习空间
      </h1>
      <p className="mt-4 max-w-xl text-[15px] leading-relaxed text-muted">
        课程橱窗、学习资料与 AI 助教对话将在这里逐步就位，当前为工程初始化占位页。
      </p>
    </section>
  );
}
