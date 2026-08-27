/**
 * 全站底栏（UI 全面重构 2026-08-27：问渠学堂学院风深墨面板，设计稿一还原）
 *
 * 结构：联系横条（奶油浅面：一句话 + 客服 mailto 胶囊）→ 深墨面板：
 * 手写体引语 + 衬线宣言 + 功能胶囊行 + 四列导航（品牌徽记/探索/支持/平台）
 * + 技术栈署名行。链接契约：站内真实路由与 mailto，无占位死链。
 *
 * 实现约束：本组件为服务端组件（纯静态零 JS），**禁止引入 @phosphor-icons/react**
 * ——该库 context 模块顶层调用 createContext，RSC 层 react-server 精简导出无此 API
 * （2026-08-26 实测：layout 引用后首页全量 500，改用内联 SVG）。外链箭头同为内联 SVG。
 */
import Link from "next/link";

/** 客服联系邮箱 */
const SUPPORT_EMAIL = "18229923842@163.com";

/** 探索导航列（与顶导同源语义） */
const FOOTER_EXPLORE = [
  { href: "/", label: "首页" },
  { href: "/chat", label: "课程助手" },
  { href: "/courses", label: "课堂" },
  { href: "/profile", label: "个人中心" },
] as const;

/** 支持导航列 */
const FOOTER_SUPPORT = [
  { href: `mailto:${SUPPORT_EMAIL}`, label: "联系我们", external: true },
  { href: "/#services", label: "平台能力" },
  { href: "/#knowledge-hub", label: "上手指引" },
] as const;

/** 外链小箭头（服务端组件内联 SVG 替代图标库） */
function ExternalArrow() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden
      className="size-2.5 opacity-70"
    >
      <path d="M9 5h10v10M19 5L7 17" />
    </svg>
  );
}

/**
 * 全站底栏
 */
export function SiteFooter() {
  return (
    <footer data-testid="site-footer">
      {/* ===== 联系横条 ===== */}
      <div className="bg-surface-2">
        <div className="mx-auto flex w-full max-w-[1360px] flex-wrap items-center justify-between gap-8 px-[6vw] py-[60px]">
          <h2 className="font-serif-display max-w-xl text-[clamp(22px,2.3vw,30px)] leading-snug font-medium text-text">
            学以为己，问渠以源——有任何问题，欢迎直接写信给我们。
          </h2>
          <a
            href={`mailto:${SUPPORT_EMAIL}`}
            className="btn-pill btn-solid shrink-0 text-[11px] uppercase"
          >
            写信给问渠学堂
          </a>
        </div>
      </div>

      {/* ===== 深墨面板 ===== */}
      <div className="bg-ink-800 px-[6vw] pt-[90px] pb-[80px] text-bg">
        <p className="text-script text-center text-[#E8DFCE]">The Wenqu Way</p>
        <p className="font-serif-display mx-auto mt-9 max-w-4xl text-center text-[clamp(32px,4.4vw,58px)] leading-[1.18] font-medium text-[#F1EADC]">
          为有源头活水来。
        </p>

        {/* 功能胶囊行 */}
        <div className="mt-[86px] grid grid-cols-1 gap-6 sm:grid-cols-3">
          <Link
            href="/chat"
            className="rounded-full border border-bg/30 py-[19px] text-center text-[12px] tracking-[0.16em] uppercase transition-colors hover:bg-bg hover:text-ink"
          >
            与 AI 助教对话
          </Link>
          <Link
            href="/courses"
            className="rounded-full border border-bg/30 py-[19px] text-center text-[12px] tracking-[0.16em] uppercase transition-colors hover:bg-bg hover:text-ink"
          >
            浏览全部课程
          </Link>
          <a
            href={`mailto:${SUPPORT_EMAIL}?subject=建议反馈`}
            className="flex items-center justify-center gap-2 rounded-full border border-bg/30 py-[19px] text-center text-[12px] tracking-[0.16em] uppercase transition-colors hover:bg-bg hover:text-ink"
          >
            建议反馈
            <ExternalArrow />
          </a>
        </div>

        {/* 四列导航 */}
        <div className="mt-[100px] grid grid-cols-1 gap-12 md:grid-cols-[230px_1fr_1fr_1fr]">
          {/* 品牌徽记 */}
          <div className="text-[#E9E2D3] max-md:text-center">
            <svg
              viewBox="0 0 140 160"
              fill="none"
              stroke="currentColor"
              aria-hidden
              className="mx-auto w-[110px] md:mx-0"
            >
              <path
                d="M70 6 L130 24 V84 c0 40 -28 62 -60 74 C38 146 10 124 10 84 V24 Z"
                strokeWidth="2.4"
              />
              <path
                d="M70 18 L118 32 V82 c0 32 -22 50 -48 60 C44 132 22 114 22 82 V32 Z"
                strokeWidth="1"
                opacity=".5"
              />
              <text
                x="70"
                y="92"
                textAnchor="middle"
                fontSize="54"
                fill="currentColor"
                stroke="none"
                style={{ fontFamily: "var(--font-display)" }}
              >
                问
              </text>
            </svg>
            <div className="mt-4 text-[8.5px] tracking-[0.22em] uppercase opacity-80">
              Wenqu Academy · Est. 2026
            </div>
          </div>

          <nav aria-label="探索导航">
            <h4 className="font-serif-display mb-7 text-2xl font-medium">探索</h4>
            <ul>
              {FOOTER_EXPLORE.map((item) => (
                <li key={item.href} className="my-3.5">
                  <Link
                    href={item.href}
                    className="text-sm text-bg/60 transition-all hover:translate-x-1 hover:text-bg"
                  >
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>

          <nav aria-label="支持导航">
            <h4 className="font-serif-display mb-7 text-2xl font-medium">支持</h4>
            <ul>
              {FOOTER_SUPPORT.map((item) => (
                <li key={item.label} className="my-3.5">
                  {"external" in item && item.external ? (
                    <a
                      href={item.href}
                      className="flex items-center gap-1.5 text-sm text-bg/60 transition-all hover:translate-x-1 hover:text-bg"
                    >
                      {item.label}
                      <ExternalArrow />
                    </a>
                  ) : (
                    <Link
                      href={item.href}
                      className="text-sm text-bg/60 transition-all hover:translate-x-1 hover:text-bg"
                    >
                      {item.label}
                    </Link>
                  )}
                </li>
              ))}
            </ul>
          </nav>

          <nav aria-label="平台说明">
            <h4 className="font-serif-display mb-7 text-2xl font-medium">平台</h4>
            <p className="max-w-xs text-sm leading-relaxed text-bg/60">
              问渠学堂是面向学生的 RAG
              课程学习助手：课堂知识库检索增强问答、多模态资料理解、个性化偏好与情景记忆，
              让每一次提问都有出处。
            </p>
          </nav>
        </div>

        {/* 技术栈署名行（「合作伙伴」位替换为真实技术底座署名） */}
        <div className="mt-20 flex flex-wrap items-center justify-center gap-x-14 gap-y-8 text-bg/75">
          <div className="flex flex-col leading-tight">
            <b className="-tracking-[0.02em] text-[22px] font-semibold">Spring AI Alibaba</b>
            <small className="mt-1 text-[8.5px] tracking-[0.24em] uppercase opacity-70">
              Agent Framework
            </small>
          </div>
          <div className="font-serif-display text-2xl tracking-wide">问渠学堂</div>
          <div className="flex flex-col leading-tight">
            <b className="text-[22px] font-semibold">Milvus × PGVector</b>
            <small className="mt-1 text-[8.5px] tracking-[0.24em] uppercase opacity-70">
              Retrieval Backbone
            </small>
          </div>
        </div>

        <div className="mt-16 border-t border-bg/15 pt-7 text-center text-xs text-bg/50">
          © 2026 问渠学堂 Wenqu Academy · 为有源头活水来
        </div>
      </div>
    </footer>
  );
}
